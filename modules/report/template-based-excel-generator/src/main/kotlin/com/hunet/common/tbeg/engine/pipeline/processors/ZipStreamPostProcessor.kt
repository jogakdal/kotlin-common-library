package com.hunet.common.tbeg.engine.pipeline.processors

import com.hunet.common.tbeg.isNullOrEmpty
import com.hunet.common.tbeg.engine.core.XmlVariableProcessor
import com.hunet.common.tbeg.engine.pipeline.ExcelProcessor
import com.hunet.common.tbeg.engine.pipeline.ProcessingContext
import com.hunet.common.tbeg.engine.pipeline.processors.zippost.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayOutputStream

/**
 * ZIP 스트리밍 기반 통합 후처리 프로세서.
 *
 * `XSSFWorkbook` / `OPCPackage` 전체 로드 없이 ZIP 엔트리 단위로 후처리하여
 * 대용량 파일 처리 한계를 해소한다.
 *
 * ## 처리 방식 (raw copy 최적화)
 * SXSSF 산출물을 `ZipFile`(central directory 기반)로 읽어, 후처리가 필요한 엔트리만
 * 압축 해제→변형→재압축하고, 나머지(특히 대용량 sheet)는 `addRawArchiveEntry`로
 * 재압축 없이 그대로 복사한다.
 *
 * `ZipFile`로 읽으므로 raw copy 엔트리도 central directory의 정확한 size가 로컬 헤더에
 * 확정된다. 즉 SXSSF의 data-descriptor(size=0)가 이 과정에서 정규화되어 `java.util.zip`
 * 호환 ZIP이 되고, `absPath` 제거도 그대로 보장된다.
 *
 * 처리 순서:
 * 1. Phase 1 (Pre-scan): styles.xml만 DOM 파싱, 스타일 변형 추가 및 매핑 구축
 * 2. Phase 2 (Main pass): 전체 ZIP 순회, 후처리 필요 엔트리만 재압축, 나머지는 raw copy
 */
internal class ZipStreamPostProcessor(
    private val xmlVariableProcessor: XmlVariableProcessor
) : ExcelProcessor {

    companion object {
        private const val STYLES_XML = "xl/styles.xml"
        private const val WORKBOOK_XML = "xl/workbook.xml"
        private const val CORE_XML = "docProps/core.xml"
        private const val APP_XML = "docProps/app.xml"
        private const val SHEET_XML_PREFIX = "xl/worksheets/sheet"
    }

    override val name = "ZipStreamPost"

    override fun process(context: ProcessingContext): ProcessingContext {
        // 변수 치환 resolver 생성 (ChartRestoreProcessor에서도 사용)
        val variableNames = context.requiredNames?.variables
        val hasVariables = variableNames?.any { context.dataProvider.getValue(it) != null } ?: false

        val variableResolver = if (hasVariables) {
            xmlVariableProcessor.createVariableResolver(context.dataProvider, variableNames)
        } else null

        context.variableResolver = variableResolver

        val needsMetadata = !context.metadata.isNullOrEmpty()

        ZipFile.builder()
            .setSeekableByteChannel(SeekableInMemoryByteChannel(context.resultBytes))
            .get()
            .use { zf ->
                // Phase 1: styles.xml pre-scan
                // 피벗이 있을 때만 후처리로 숫자 서식 변형을 만든다. 피벗이 없으면 렌더링 시점에 이미
                // 자동 숫자 서식이 적용되므로 styleMapping을 비워 sheet를 raw copy 대상으로 만든다.
                val stylesEntry = zf.getEntry(STYLES_XML)
                val (processedStylesBytes, styleMapping) = if (stylesEntry != null && context.pivotTableInfos.isNotEmpty()) {
                    StylesXmlHandler.process(zf.getInputStream(stylesEntry).readBytes(), context.config)
                } else {
                    null to emptyMap()
                }

                // Phase 2: ZIP 전체 순회 (후처리 필요 엔트리만 재압축, 나머지는 raw copy)
                context.resultBytes = rewriteZip(
                    zf, context, processedStylesBytes, styleMapping, variableResolver, needsMetadata
                )
            }

        return context
    }

    private fun rewriteZip(
        zf: ZipFile,
        context: ProcessingContext,
        processedStylesBytes: ByteArray?,
        styleMapping: Map<Int, StyleVariants>,
        variableResolver: ((String) -> String)?,
        needsMetadata: Boolean
    ): ByteArray {
        val output = ByteArrayOutputStream(context.resultBytes.size)

        ZipArchiveOutputStream(output).use { zos ->
            val entries = zf.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (needsTransform(entry.name, styleMapping, variableResolver, needsMetadata, context)) {
                    // 후처리 대상: 압축 해제 → 변형 → 재압축
                    val original = zf.getInputStream(entry).readBytes()
                    val processed = processEntry(
                        entry.name, original,
                        processedStylesBytes, styleMapping,
                        variableResolver, needsMetadata, context
                    )
                    zos.putArchiveEntry(ZipArchiveEntry(entry.name).apply { time = entry.time })
                    zos.write(processed)
                    zos.closeArchiveEntry()
                } else {
                    // 후처리 불필요: 재압축 없이 그대로 복사 (data-descriptor size가 여기서 정규화됨)
                    zos.addRawArchiveEntry(entry, zf.getRawInputStream(entry))
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * 해당 엔트리가 후처리(압축 해제→변형→재압축) 대상인지 판정한다.
     *
     * false면 압축 해제 없이 raw copy한다. 특히 sheet는 styleMapping이 없으면(피벗 숫자서식
     * 미사용) 대상이 아니므로, 대용량 sheet를 열지 않고 그대로 복사하여 이중 압축을 피한다.
     *
     * 각 분기는 processEntry가 실제로 내용을 변형하는 조건과 일치한다. 대상이 아닌 엔트리를
     * processEntry에 태우면 원본을 그대로 반환하므로, raw copy와 결과 내용이 동일하다.
     */
    private fun needsTransform(
        entryName: String,
        styleMapping: Map<Int, StyleVariants>,
        variableResolver: ((String) -> String)?,
        needsMetadata: Boolean,
        context: ProcessingContext
    ): Boolean = when {
        entryName == STYLES_XML -> true
        entryName.startsWith(SHEET_XML_PREFIX) && entryName.endsWith(".xml") -> styleMapping.isNotEmpty()
        entryName == WORKBOOK_XML -> true
        entryName == CORE_XML -> needsMetadata
        entryName == APP_XML ->
            needsMetadata && (context.metadata?.company != null || context.metadata?.manager != null)
        else -> variableResolver != null && VariableXmlHandler.shouldProcess(entryName)
    }

    private fun processEntry(
        entryName: String,
        entryBytes: ByteArray,
        processedStylesBytes: ByteArray?,
        styleMapping: Map<Int, StyleVariants>,
        variableResolver: ((String) -> String)?,
        needsMetadata: Boolean,
        context: ProcessingContext
    ): ByteArray = when {
        // styles.xml: Phase 1에서 이미 처리된 결과 사용
        entryName == STYLES_XML ->
            processedStylesBytes ?: entryBytes

        // sheet*.xml: StAX 스트리밍으로 셀 스타일 교체
        entryName.startsWith(SHEET_XML_PREFIX) && entryName.endsWith(".xml") ->
            SheetXmlHandler.process(entryBytes, styleMapping)

        // workbook.xml: AlternateContent 제거 (absPath)
        entryName == WORKBOOK_XML ->
            WorkbookXmlHandler.process(entryBytes)

        // core.xml: 메타데이터 설정
        entryName == CORE_XML && needsMetadata ->
            MetadataXmlHandler.processCoreXml(entryBytes, context.metadata!!)

        // app.xml: 메타데이터 설정
        entryName == APP_XML && needsMetadata &&
            (context.metadata?.company != null || context.metadata?.manager != null) ->
            MetadataXmlHandler.processAppXml(entryBytes, context.metadata)

        // 기타 XML: 변수 치환
        VariableXmlHandler.shouldProcess(entryName) ->
            VariableXmlHandler.process(entryBytes, variableResolver)

        // 나머지: 그대로 통과
        else -> entryBytes
    }
}
