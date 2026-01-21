package com.hunet.common.excel

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 차트 프로세서 - SXSSF 스트리밍 모드에서 차트 보존
 *
 * SXSSF로 새 워크북을 생성하면 원본 템플릿의 차트가 손실됩니다.
 * 이 프로세서는 차트와 관련 드로잉을 추출하고 처리 후 복원합니다.
 *
 * Phase 3: 드로잉 파일 병합 지원
 * - 기존 드로잉(이미지 등)에 차트 앵커를 병합
 * - 드로잉 관계 파일도 병합하여 차트 참조 추가
 */
internal class ChartProcessor {

    companion object {
        private val LOG = LoggerFactory.getLogger(ChartProcessor::class.java)

        private const val CHART_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
        private const val CHART_STYLE_CONTENT_TYPE = "application/vnd.ms-office.chartstyle+xml"
        private const val CHART_COLORS_CONTENT_TYPE = "application/vnd.ms-office.chartcolorstyle+xml"
        private const val DRAWING_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.drawing+xml"

        // 지연 초기화: 차트가 없는 템플릿에서는 Regex 컴파일 비용 절약
        private val CHART_PATH_PATTERN by lazy { Regex("/xl/charts/[^/]+\\.(xml|rels)") }
        private val CHART_RELS_PATH_PATTERN by lazy { Regex("/xl/charts/_rels/.*") }
        private val DRAWING_PATH_PATTERN by lazy { Regex("/xl/drawings/[^/]+\\.xml") }
        private val DRAWING_RELS_PATH_PATTERN by lazy { Regex("/xl/drawings/_rels/.*") }

        // 차트 앵커 패턴 (graphicFrame)
        private val GRAPHIC_FRAME_PATTERN by lazy {
            Regex(
                "<xdr:twoCellAnchor[^>]*>.*?<xdr:graphicFrame.*?</xdr:graphicFrame>.*?</xdr:twoCellAnchor>",
                RegexOption.DOT_MATCHES_ALL
            )
        }

        // twoCellAnchor 전체 패턴 (개별 앵커 단위로 추출)
        private val TWO_CELL_ANCHOR_PATTERN by lazy {
            Regex(
                "<xdr:twoCellAnchor[^>]*>(?:(?!</xdr:twoCellAnchor>).)*</xdr:twoCellAnchor>",
                RegexOption.DOT_MATCHES_ALL
            )
        }

        // oneCellAnchor 패턴 (절대 위치 도형 등)
        private val ONE_CELL_ANCHOR_PATTERN by lazy {
            Regex(
                "<xdr:oneCellAnchor[^>]*>(?:(?!</xdr:oneCellAnchor>).)*</xdr:oneCellAnchor>",
                RegexOption.DOT_MATCHES_ALL
            )
        }

        // rId 패턴
        private val RID_PATTERN by lazy { Regex("r:id=\"(rId\\d+)\"") }
        private val RELS_RID_PATTERN by lazy { Regex("Id=\"(rId\\d+)\"") }

        // 차트 관계 및 콘텐츠 타입 패턴
        private val CHART_REL_ID_TARGET_PATTERN by lazy { Regex("<Relationship[^>]*Id=\"(rId\\d+)\"[^>]*Target=\"([^\"]*)\"[^>]*/?>") }
        private val CHART_REL_TARGET_PATTERN by lazy { Regex("<Relationship[^>]*Target=\"[^\"]*charts/[^\"]*\"[^>]*/?>") }
        private val CHART_TARGET_ATTR_PATTERN by lazy { Regex("Target=\"[^\"]*charts/[^\"]*\"") }
        private val OVERRIDE_PATTERN by lazy { Regex("<Override[^>]*PartName=\"([^\"]*)\"[^>]*ContentType=\"([^\"]*)\"[^>]*>") }
        private val CHART_OVERRIDE_PATTERN by lazy { Regex("<Override[^>]*PartName=\"[^\"]*/charts/[^\"]*\"[^>]*>") }
        private val MULTIPLE_NEWLINES_PATTERN by lazy { Regex("\n\\s*\n") }
        private val PART_NAME_ATTR_PATTERN by lazy { Regex("PartName=\"([^\"]*)\"") }
    }

    data class ChartInfo(
        val chartFiles: Map<String, ByteArray>,
        val chartRelsFiles: Map<String, ByteArray>,
        val drawingFiles: Map<String, ByteArray>,
        val drawingRelsFiles: Map<String, ByteArray>,
        val contentTypeEntries: List<String>,
        /** 도형 보존을 위한 모든 드로잉 파일 (차트 관련 드로잉 포함) */
        val allDrawingFiles: Map<String, ByteArray> = emptyMap(),
        val allDrawingRelsFiles: Map<String, ByteArray> = emptyMap()
    )

    fun extractAndRemove(inputBytes: ByteArray): Pair<ChartInfo?, ByteArray> {
        val chartFiles = mutableMapOf<String, ByteArray>()
        val chartRelsFiles = mutableMapOf<String, ByteArray>()
        val drawingFiles = mutableMapOf<String, ByteArray>()
        val drawingRelsFiles = mutableMapOf<String, ByteArray>()
        val contentTypeEntries = mutableListOf<String>()
        var contentTypesXml: String? = null

        var hasChart = false

        // 단일 ZIP 순회로 모든 필요한 데이터 수집
        ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val path = "/" + entry.name

                when {
                    entry.name == "[Content_Types].xml" -> {
                        contentTypesXml = String(zis.readBytes(), Charsets.UTF_8)
                    }
                    CHART_PATH_PATTERN.matches(path) && !path.contains("/_rels/") -> {
                        chartFiles[path] = zis.readBytes()
                        hasChart = true
                    }
                    CHART_RELS_PATH_PATTERN.matches(path) -> {
                        chartRelsFiles[path] = zis.readBytes()
                    }
                    DRAWING_PATH_PATTERN.matches(path) && !path.contains("/_rels/") -> {
                        drawingFiles[path] = zis.readBytes()
                    }
                    DRAWING_RELS_PATH_PATTERN.matches(path) -> {
                        drawingRelsFiles[path] = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }

        if (!hasChart) {
            LOG.debug("차트가 없는 템플릿입니다.")
            return null to inputBytes
        }

        // 차트 관련 드로잉 + 모든 드로잉 (도형 보존용)
        val chartRelatedDrawings = filterChartRelatedDrawings(drawingFiles, drawingRelsFiles)
        // 도형을 포함한 모든 드로잉 파일을 저장 (차트가 없어도 도형이 있을 수 있음)
        val allDrawingsForShapes = AllDrawings(drawingFiles, drawingRelsFiles)

        LOG.debug("차트 추출: charts=${chartFiles.keys}, drawings=${chartRelatedDrawings.drawingFiles.keys}")

        // 저장된 [Content_Types].xml에서 차트/드로잉 콘텐츠 타입 추출
        contentTypesXml?.let { xml ->
            contentTypeEntries.addAll(extractChartAndDrawingContentTypes(xml, chartRelatedDrawings))
        }

        val chartInfo = ChartInfo(
            chartFiles = chartFiles,
            chartRelsFiles = chartRelsFiles,
            drawingFiles = chartRelatedDrawings.drawingFiles,
            drawingRelsFiles = chartRelatedDrawings.drawingRelsFiles,
            contentTypeEntries = contentTypeEntries,
            allDrawingFiles = allDrawingsForShapes.drawingFiles,
            allDrawingRelsFiles = allDrawingsForShapes.drawingRelsFiles
        )

        // Phase 3: 차트 파일만 제거, 드로잉은 유지 (나중에 병합)
        val cleanedBytes = removeChartFilesOnly(inputBytes)

        return chartInfo to cleanedBytes
    }

    /**
     * 차트 복원
     * @param variableResolver 변수 치환 함수 (차트 타이틀 등의 변수 치환에 사용)
     */
    fun restore(
        inputBytes: ByteArray,
        chartInfo: ChartInfo?,
        variableResolver: ((String) -> String)? = null
    ): ByteArray {
        if (chartInfo == null) return inputBytes

        LOG.debug("차트 복원 시작: charts=${chartInfo.chartFiles.keys}, drawings=${chartInfo.drawingFiles.keys}")

        val output = ByteArrayOutputStream()

        // 단일 ZIP 순회로 드로잉 관계 파일 수집과 복원 작업 통합
        ZipOutputStream(output).use { zos ->
            val writtenEntries = mutableSetOf<String>()
            val currentDrawingRels = mutableMapOf<String, String>()
            val pendingDrawingFiles = mutableListOf<Triple<String, String, ByteArray>>() // path, entryName, content
            val pendingDrawingRelsFiles = mutableListOf<Triple<String, String, ByteArray>>()

            ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val path = "/" + entry.name
                    val entryName = entry.name

                    when {
                        entryName == "[Content_Types].xml" -> {
                            val content = String(zis.readBytes(), Charsets.UTF_8)
                            val updatedContent = addChartAndDrawingContentTypes(content, chartInfo)
                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(updatedContent.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                            writtenEntries.add(entryName)
                        }
                        // 드로잉 파일 - 나중에 처리하기 위해 보관
                        DRAWING_PATH_PATTERN.matches(path) && !path.contains("/_rels/") -> {
                            pendingDrawingFiles.add(Triple(path, entryName, zis.readBytes()))
                        }
                        // 드로잉 관계 파일 - rId 매핑 계산용으로 보관
                        DRAWING_RELS_PATH_PATTERN.matches(path) -> {
                            val content = zis.readBytes()
                            currentDrawingRels[path] = String(content, Charsets.UTF_8)
                            pendingDrawingRelsFiles.add(Triple(path, entryName, content))
                        }
                        else -> {
                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(zis.readBytes())
                            zos.closeEntry()
                            writtenEntries.add(entryName)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // rId 매핑 계산 (드로잉 관계 파일 수집 후)
            val ridMappings = mutableMapOf<String, Map<String, String>>()
            chartInfo.drawingRelsFiles.forEach { (relsPath, originalRelsBytes) ->
                val currentRelsXml = currentDrawingRels[relsPath]
                if (currentRelsXml != null) {
                    val originalRelsXml = String(originalRelsBytes, Charsets.UTF_8)
                    ridMappings[relsPath] = calculateRidMapping(currentRelsXml, originalRelsXml)
                }
            }

            // 보관된 드로잉 파일 처리
            pendingDrawingFiles.forEach { (path, entryName, currentContent) ->
                val originalDrawing = chartInfo.drawingFiles[path]
                    ?: chartInfo.allDrawingFiles[path]

                val drawingName = path.substringAfterLast("/")
                val relsPath = "/xl/drawings/_rels/$drawingName.rels"
                val ridMapping = ridMappings[relsPath] ?: emptyMap()

                var mergedContent = if (originalDrawing != null) {
                    mergeDrawingXml(
                        String(currentContent, Charsets.UTF_8),
                        String(originalDrawing, Charsets.UTF_8),
                        ridMapping
                    )
                } else {
                    String(currentContent, Charsets.UTF_8)
                }

                if (variableResolver != null) {
                    mergedContent = variableResolver(mergedContent)
                }

                zos.putNextEntry(ZipEntry(entryName))
                zos.write(mergedContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                writtenEntries.add(entryName)
            }

            // 보관된 드로잉 관계 파일 처리
            pendingDrawingRelsFiles.forEach { (path, entryName, currentContent) ->
                val originalRels = chartInfo.drawingRelsFiles[path]
                val ridMapping = ridMappings[path] ?: emptyMap()

                val mergedContent = if (originalRels != null) {
                    mergeDrawingRelsXml(
                        String(currentContent, Charsets.UTF_8),
                        String(originalRels, Charsets.UTF_8),
                        ridMapping
                    )
                } else {
                    String(currentContent, Charsets.UTF_8)
                }

                zos.putNextEntry(ZipEntry(entryName))
                zos.write(mergedContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                writtenEntries.add(entryName)
            }

            // 차트 파일 추가 (변수 치환 적용)
            chartInfo.chartFiles.forEach { (path, bytes) ->
                val processedBytes = if (variableResolver != null && path.endsWith(".xml")) {
                    val content = String(bytes, Charsets.UTF_8)
                    val processed = variableResolver(content)
                    processed.toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
                addZipEntry(zos, path, processedBytes, writtenEntries)
            }

            // 차트 관계 파일 추가
            chartInfo.chartRelsFiles.forEach { (path, bytes) ->
                addZipEntry(zos, path, bytes, writtenEntries)
            }

            // 현재 파일에 없는 드로잉 파일 추가
            chartInfo.drawingFiles.forEach { (path, bytes) ->
                addZipEntry(zos, path, bytes, writtenEntries)
            }

            // 현재 파일에 없는 드로잉 관계 파일 추가
            chartInfo.drawingRelsFiles.forEach { (path, bytes) ->
                addZipEntry(zos, path, bytes, writtenEntries)
            }
        }

        LOG.debug("차트 복원 완료")
        return output.toByteArray()
    }

    /**
     * 원본 드로잉 rels의 차트 관계 rId를 새 rId로 매핑 계산
     */
    private fun calculateRidMapping(currentRelsXml: String, originalRelsXml: String): Map<String, String> {
        // 현재 rels의 최대 rId
        val currentMaxRid = RELS_RID_PATTERN.findAll(currentRelsXml)
            .mapNotNull { match ->
                val ridValue = match.groupValues[1]
                ridValue.removePrefix("rId").toIntOrNull().also { rid ->
                    if (rid == null) {
                        LOG.warn("잘못된 rId 형식: $ridValue")
                    }
                }
            }
            .maxOrNull() ?: 0

        // 원본에서 차트 관계 추출
        val chartRels = CHART_REL_ID_TARGET_PATTERN.findAll(originalRelsXml)
            .filter { it.groupValues[2].contains("charts/") }
            .toList()

        val mapping = mutableMapOf<String, String>()
        var newRidCounter = currentMaxRid + 1

        chartRels.forEach { match ->
            val originalRid = match.groupValues[1]
            val newRid = "rId$newRidCounter"
            mapping[originalRid] = newRid
            newRidCounter++
        }

        return mapping
    }

    /**
     * 드로잉 XML 병합 - 기존 드로잉에 원본의 모든 드로잉 객체 추가
     * 차트 앵커, 도형 앵커(텍스트 상자, WordArt 포함), 연결선, oneCellAnchor 등 모두 포함
     * @param ridMapping 원본 rId -> 새 rId 매핑
     */
    private fun mergeDrawingXml(currentXml: String, originalXml: String, ridMapping: Map<String, String>): String {
        // 원본에서 차트 앵커(graphicFrame) 추출
        val chartAnchors = GRAPHIC_FRAME_PATTERN.findAll(originalXml).map { it.value }.toList()

        // 현재 드로잉에서 SXSSF가 생성한 차트 앵커 제거 (원본 차트 앵커로 대체하기 위해)
        // SXSSF는 차트 앵커를 자체적으로 생성하지만, 우리는 원본 템플릿의 차트 앵커를 사용
        var workingXml = currentXml
        if (chartAnchors.isNotEmpty()) {
            workingXml = GRAPHIC_FRAME_PATTERN.replace(workingXml, "")
        }

        // 현재(작업 중인) 드로잉의 twoCellAnchor 추출
        val currentTwoCellAnchors = TWO_CELL_ANCHOR_PATTERN.findAll(workingXml).map { it.value }.toSet()
        val currentOneCellAnchors = ONE_CELL_ANCHOR_PATTERN.findAll(workingXml).map { it.value }.toSet()

        // 원본에서 twoCellAnchor 추출하고 타입별로 분류
        val originalTwoCellAnchors = TWO_CELL_ANCHOR_PATTERN.findAll(originalXml).map { it.value }.toList()

        // 도형 앵커 (sp 포함, graphicFrame 미포함)
        val shapeAnchors = originalTwoCellAnchors
            .filter { it.contains("<xdr:sp") && !it.contains("<xdr:graphicFrame") }
            .filter { it !in currentTwoCellAnchors }

        // 연결선 앵커 (cxnSp 포함, graphicFrame 미포함)
        val connectorAnchors = originalTwoCellAnchors
            .filter { it.contains("<xdr:cxnSp") && !it.contains("<xdr:graphicFrame") }
            .filter { it !in currentTwoCellAnchors }

        // 원본에서 oneCellAnchor 추출 - 현재 드로잉에 없는 것만
        val oneCellAnchors = ONE_CELL_ANCHOR_PATTERN.findAll(originalXml)
            .map { it.value }
            .filter { it !in currentOneCellAnchors }
            .toList()

        if (chartAnchors.isEmpty() && shapeAnchors.isEmpty() &&
            connectorAnchors.isEmpty() && oneCellAnchors.isEmpty()) {
            return workingXml
        }

        // 차트 앵커의 rId를 매핑된 새 값으로 변경
        val updatedChartAnchors = chartAnchors.map { anchor ->
            var updated = anchor
            ridMapping.forEach { (oldRid, newRid) ->
                updated = updated.replace("r:id=\"$oldRid\"", "r:id=\"$newRid\"")
            }
            updated
        }

        // </xdr:wsDr> 앞에 앵커들 삽입
        val insertPosition = workingXml.lastIndexOf("</xdr:wsDr>")
        if (insertPosition == -1) {
            return workingXml
        }

        val allAnchors = (shapeAnchors + connectorAnchors + oneCellAnchors + updatedChartAnchors).joinToString("")

        return workingXml.substring(0, insertPosition) +
            allAnchors +
            workingXml.substring(insertPosition)
    }

    /**
     * 드로잉 관계 XML 병합 - 기존 관계에 차트 참조 추가
     * @param ridMapping 원본 rId -> 새 rId 매핑
     */
    private fun mergeDrawingRelsXml(currentXml: String, originalXml: String, ridMapping: Map<String, String>): String {
        // 원본에서 차트 참조 추출
        val chartRels = CHART_REL_TARGET_PATTERN.findAll(originalXml).map { it.value }.toList()

        if (chartRels.isEmpty()) {
            return currentXml
        }

        // 차트 관계의 rId를 매핑된 새 값으로 변경
        val updatedRels = chartRels.map { rel ->
            var updated = rel
            ridMapping.forEach { (oldRid, newRid) ->
                updated = updated.replace("Id=\"$oldRid\"", "Id=\"$newRid\"")
            }
            updated
        }

        // </Relationships> 앞에 차트 관계 삽입
        val insertPosition = currentXml.lastIndexOf("</Relationships>")
        if (insertPosition == -1) {
            return currentXml
        }

        return currentXml.substring(0, insertPosition) +
            updatedRels.joinToString("") +
            currentXml.substring(insertPosition)
    }

    private data class ChartRelatedDrawings(
        val drawingFiles: Map<String, ByteArray>,
        val drawingRelsFiles: Map<String, ByteArray>
    )

    private data class AllDrawings(
        val drawingFiles: Map<String, ByteArray>,
        val drawingRelsFiles: Map<String, ByteArray>
    )

    private fun filterChartRelatedDrawings(
        allDrawings: Map<String, ByteArray>,
        allDrawingRels: Map<String, ByteArray>
    ): ChartRelatedDrawings {
        val chartRelatedDrawingRels = allDrawingRels.filter { (_, content) ->
            CHART_TARGET_ATTR_PATTERN.containsMatchIn(String(content, Charsets.UTF_8))
        }

        val relatedDrawingNames = chartRelatedDrawingRels.keys.map { relsPath ->
            relsPath.substringAfterLast("/_rels/").removeSuffix(".rels")
        }

        val chartRelatedDrawings = allDrawings.filter { (path, _) ->
            relatedDrawingNames.any { name -> path.endsWith(name) }
        }

        return ChartRelatedDrawings(chartRelatedDrawings, chartRelatedDrawingRels)
    }

    private fun extractChartAndDrawingContentTypes(
        contentTypesXml: String,
        chartRelatedDrawings: ChartRelatedDrawings
    ): List<String> {
        val entries = mutableListOf<String>()

        val drawingPaths = chartRelatedDrawings.drawingFiles.keys.map { it.removePrefix("/") }

        OVERRIDE_PATTERN.findAll(contentTypesXml).forEach { match ->
            val partName = match.groupValues[1]
            val contentType = match.groupValues[2]

            if (partName.contains("/charts/") ||
                contentType == CHART_CONTENT_TYPE ||
                contentType == CHART_STYLE_CONTENT_TYPE ||
                contentType == CHART_COLORS_CONTENT_TYPE
            ) {
                entries.add(match.value)
            }

            // 드로잉 콘텐츠 타입은 더 이상 추출하지 않음 (기존 것 유지)
        }

        return entries
    }

    /**
     * 차트 파일만 제거 (드로잉은 유지)
     */
    private fun removeChartFilesOnly(inputBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zos ->
            ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val path = "/" + entry.name

                    val shouldSkip = CHART_PATH_PATTERN.matches(path) ||
                        CHART_RELS_PATH_PATTERN.matches(path)

                    if (!shouldSkip) {
                        when (entry.name) {
                            "[Content_Types].xml" -> {
                                val content = String(zis.readBytes(), Charsets.UTF_8)
                                val cleaned = removeChartContentTypes(content)
                                zos.putNextEntry(ZipEntry(entry.name))
                                zos.write(cleaned.toByteArray(Charsets.UTF_8))
                                zos.closeEntry()
                            }
                            else -> {
                                zos.putNextEntry(ZipEntry(entry.name))
                                zos.write(zis.readBytes())
                                zos.closeEntry()
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        return output.toByteArray()
    }

    private fun removeChartContentTypes(contentTypesXml: String): String =
        contentTypesXml
            .replace(CHART_OVERRIDE_PATTERN, "")
            .replace(MULTIPLE_NEWLINES_PATTERN, "\n")

    private fun addChartAndDrawingContentTypes(contentTypesXml: String, chartInfo: ChartInfo): String {
        if (chartInfo.contentTypeEntries.isEmpty()) return contentTypesXml

        val insertPosition = contentTypesXml.lastIndexOf("</Types>")
        if (insertPosition == -1) return contentTypesXml

        val entriesToAdd = chartInfo.contentTypeEntries
            .filter { entry ->
                val partNameMatch = PART_NAME_ATTR_PATTERN.find(entry)
                val partName = partNameMatch?.groupValues?.get(1) ?: ""
                !contentTypesXml.contains("PartName=\"$partName\"")
            }
            .joinToString("\n")

        if (entriesToAdd.isEmpty()) return contentTypesXml

        return contentTypesXml.substring(0, insertPosition) +
            entriesToAdd + "\n" +
            contentTypesXml.substring(insertPosition)
    }

    private fun addZipEntry(
        zos: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        writtenEntries: MutableSet<String>
    ) {
        val entryPath = path.removePrefix("/")
        if (entryPath !in writtenEntries) {
            zos.putNextEntry(ZipEntry(entryPath))
            zos.write(bytes)
            zos.closeEntry()
            writtenEntries.add(entryPath)
        }
    }
}
