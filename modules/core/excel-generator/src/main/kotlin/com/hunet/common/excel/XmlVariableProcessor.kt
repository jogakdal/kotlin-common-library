package com.hunet.common.excel

import com.hunet.common.logging.commonLogger
import org.apache.poi.openxml4j.opc.OPCPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Excel 패키지 내 모든 XML 요소의 변수 치환을 담당하는 프로세서.
 *
 * JXLS는 워크시트 셀만 처리하므로 그 외 요소들(차트, 도형, 머리글/바닥글, 텍스트 상자 등)의
 * 변수 치환은 이 프로세서에서 처리합니다.
 *
 * Excel 파일(.xlsx)은 ZIP 형식의 XML 파일 모음이므로,
 * 모든 XML 파일에서 변수 패턴을 검색하여 치환합니다.
 *
 * 처리 대상 예시:
 * - 차트 타이틀 및 레이블 (xl/charts 폴더의 XML 파일들)
 * - 도형 텍스트 (xl/drawings 폴더의 XML 파일들)
 * - 머리글/바닥글
 * - 텍스트 상자, SmartArt 등 기타 모든 XML 요소
 *
 * 제외 대상:
 * - 관계 파일 (.rels 확장자)
 * - 콘텐츠 타입 정의 파일
 * - 워크시트 셀 데이터 (JXLS가 처리)
 */
internal class XmlVariableProcessor {
    companion object {
        val LOG by commonLogger()
        private val VARIABLE_PATTERN = Regex("""\$\{(\w+)}""")
        private val EXCLUDE_PATTERNS = listOf(".rels", "[Content_Types].xml", "/docProps/")
    }

    fun processVariables(inputBytes: ByteArray, dataProvider: ExcelDataProvider): ByteArray {
        val variableValues = dataProvider.getAvailableNames()
            .associateWith { dataProvider.getValue(it)?.toString() }
            .filterValues { it != null }
            .mapValues { it.value!! }
            .takeIf { it.isNotEmpty() }
            ?: run {
                LOG.debug("치환할 변수 값이 없음")
                return inputBytes
            }

        return OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
            val modifiedParts = pkg.parts
                .filter { it.partName.name.endsWith(".xml") && !shouldExclude(it.partName.name) }
                .mapNotNull { part ->
                    val originalXml = part.inputStream.bufferedReader().readText()
                    replaceVariables(originalXml, variableValues)
                        .takeIf { it != originalXml }
                        ?.also { processedXml ->
                            part.outputStream.use { it.write(processedXml.toByteArray(Charsets.UTF_8)) }
                        }
                        ?.let { part.partName.name }
                }

            if (modifiedParts.isNotEmpty()) {
                LOG.debug(
                    "변수 치환 완료: ${modifiedParts.size}개 파일 - ${modifiedParts.joinToString(", ")}"
                )
                ByteArrayOutputStream().also { pkg.save(it) }.toByteArray()
            } else {
                inputBytes
            }
        }
    }

    private fun shouldExclude(partName: String) =
        EXCLUDE_PATTERNS.any { it in partName }

    private fun replaceVariables(xml: String, values: Map<String, String>) =
        VARIABLE_PATTERN.replace(xml) { match ->
            values[match.groupValues[1]]?.escapeXml() ?: match.value
        }

    private fun String.escapeXml() = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
