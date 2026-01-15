package com.hunet.common.excel

import com.hunet.common.logging.commonLogger
import com.hunet.common.util.unquote
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellAddress
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 템플릿 전처리기.
 *
 * 사용자 친화적인 `${repeat(...)}` 마커를 JXLS가 이해하는 jx:each 코멘트로 변환합니다.
 *
 * ## 지원 문법
 *
 * ### repeat 마커
 * ```
 * ${repeat(collection=employees, range=B5:D5, var=emp, direction=DOWN)}
 * ${repeat(employees, B5:D5, emp, DOWN)}   // 파라미터명 생략
 * ${repeat(employees, B5:D5, emp)}         // direction 생략 (기본: DOWN)
 * ${repeat(employees, B5:D5)}              // var 생략 (collection을 var로 사용)
 * ${repeat(employees, 'Sheet1'!B5:D5)}     // 다른 시트의 범위 지정
 * ```
 *
 * ## 오류 처리
 *
 * - **예외 발생**: 문법 오류, 필수 파라미터 누락, 잘못된 범위, 존재하지 않는 시트
 * - **경고 로깅**: 알 수 없는 direction 값 (기본값 DOWN 사용)
 */
internal class TemplatePreprocessor {
    companion object {
        val LOG by commonLogger()
        private val REPEAT_MARKER_PATTERN = Regex("""\$\{repeat\(([^)]+)\)\}""")
        private val COMPLETE_MARKER_PATTERN = Regex("""\$\{repeat\([^)]+\)\}""")
        private const val REPEAT_MARKER_PREFIX = "\${repeat("
    }

    /**
     * repeat 마커 정보
     */
    data class RepeatMarker(
        val collection: String,
        val range: CellRangeAddress,
        val variable: String,
        val direction: Direction,
        val markerCell: CellAddress,
        val markerSheetName: String,
        val targetSheetName: String,
        val originalMarker: String  // 원본 마커 문자열 (오류 메시지용)
    )

    /**
     * 반복 방향
     */
    enum class Direction {
        DOWN, RIGHT
    }

    /**
     * 파싱된 범위 정보
     */
    private data class ParsedRange(
        val sheetName: String?,
        val range: CellRangeAddress
    )

    /**
     * 템플릿을 전처리하여 JXLS 형식으로 변환합니다.
     *
     * @param template 원본 템플릿 입력 스트림
     * @return 변환된 템플릿 입력 스트림
     * @throws TemplateProcessingException 템플릿 처리 중 오류 발생 시
     */
    fun preprocess(template: InputStream): InputStream = XSSFWorkbook(template).use { workbook ->
        val availableSheets = workbook.sheetNames()

        checkIncompleteMarkers(workbook)

        val markers = findRepeatMarkers(workbook)

        markers.firstOrNull { workbook.getSheet(it.targetSheetName) == null }?.let {
            throw TemplateProcessingException.sheetNotFound(it.targetSheetName, availableSheets)
        }

        markers.forEach { convertMarkerToJxlsComment(workbook, it) }

        ByteArrayOutputStream().also { workbook.write(it) }.let { ByteArrayInputStream(it.toByteArray()) }
    }

    private fun XSSFWorkbook.sheetNames() = (0 until numberOfSheets).map { getSheetAt(it).sheetName }

    /**
     * 불완전한 repeat 마커를 검사합니다.
     * `${repeat(` 로 시작하지만 `)}` 로 끝나지 않는 마커를 찾습니다.
     */
    private fun checkIncompleteMarkers(workbook: XSSFWorkbook) {
        workbook.sheetSequence()
            .flatMap { sheet -> sheet.cellSequence().map { cell -> sheet to cell } }
            .filter { (_, cell) -> cell.cellType == CellType.STRING }
            .mapNotNull { (sheet, cell) ->
                cell.stringCellValue?.takeIf { it.contains(REPEAT_MARKER_PREFIX) }
                    ?.let { Triple(sheet, cell, it) }
            }
            .filterNot { (_, _, value) -> COMPLETE_MARKER_PATTERN.containsMatchIn(value) }
            .firstOrNull()
            ?.let { (sheet, cell, cellValue) ->
                val reason = when {
                    ')' !in cellValue -> "닫는 괄호 ')'가 누락되었습니다."
                    '}' !in cellValue -> "닫는 중괄호 '}'가 누락되었습니다."
                    else -> "문법이 올바르지 않습니다. " +
                        "올바른 형식: \${repeat(collection, range, var, direction)}"
                }
                val markerStart = cellValue.indexOf(REPEAT_MARKER_PREFIX)
                val markerPreview = cellValue.substring(markerStart).take(50) +
                    if (cellValue.length - markerStart > 50) "..." else ""

                throw TemplateProcessingException.invalidRepeatSyntax(
                    marker = markerPreview,
                    reason = "$reason (시트: '${sheet.sheetName}', " +
                        "셀: ${CellAddress(cell.rowIndex, cell.columnIndex)})"
                )
            }
    }

    private fun XSSFWorkbook.sheetSequence() =
        (0 until numberOfSheets).asSequence().map { getSheetAt(it) }

    /**
     * 워크북에서 모든 repeat 마커를 찾습니다.
     */
    private fun findRepeatMarkers(workbook: XSSFWorkbook) =
        (0 until workbook.numberOfSheets)
            .flatMap { workbook.getSheetAt(it).let { sheet -> sheet.findMarkersInSheet() } }

    private fun Sheet.findMarkersInSheet() = cellSequence()
        .filter { it.cellType == CellType.STRING }
        .mapNotNull { parseRepeatMarker(it.stringCellValue, it, sheetName) }
        .toList()

    /**
     * 셀 값에서 repeat 마커를 파싱합니다.
     */
    private fun parseRepeatMarker(cellValue: String, cell: Cell, sheetName: String): RepeatMarker? =
        REPEAT_MARKER_PATTERN.find(cellValue)?.let { match ->
            parseRepeatParams(match.groupValues[1], cell, sheetName, match.value)
        }

    /**
     * repeat 파라미터를 파싱합니다.
     */
    private fun parseRepeatParams(
        params: String,
        cell: Cell,
        sheetName: String,
        originalMarker: String
    ): RepeatMarker = params.split(",").map { it.trim().unquote() }.let { parts ->
        if (parts.any { "=" in it }) parseNamedParams(parts, cell, sheetName, originalMarker)
        else parsePositionalParams(parts, cell, sheetName, originalMarker)
    }

    /**
     * 키=값 형태의 파라미터를 파싱합니다.
     */
    private fun parseNamedParams(
        parts: List<String>,
        cell: Cell,
        sheetName: String,
        originalMarker: String
    ): RepeatMarker {
        val paramMap = parts.filter { "=" in it }
            .associate { part ->
                part.split("=", limit = 2).let { (key, value) ->
                    key.trim() to value.trim().unquote()
                }
            }

        val collection = paramMap["collection"]
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "collection")
        val rangeStr = paramMap["range"]
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "range")
        val parsedRange = parseRangeWithSheet(rangeStr, originalMarker)

        return RepeatMarker(
            collection = collection,
            range = parsedRange.range,
            variable = paramMap["var"] ?: collection,
            direction = paramMap["direction"]?.let { parseDirection(it, originalMarker) }
                ?: Direction.DOWN,
            markerCell = CellAddress(cell.rowIndex, cell.columnIndex),
            markerSheetName = sheetName,
            targetSheetName = parsedRange.sheetName ?: sheetName,
            originalMarker = originalMarker
        )
    }

    /**
     * 위치 기반 파라미터를 파싱합니다.
     */
    private fun parsePositionalParams(
        parts: List<String>,
        cell: Cell,
        sheetName: String,
        originalMarker: String
    ): RepeatMarker {
        val collection = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "collection")
        val rangeStr = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "range")
        val parsedRange = parseRangeWithSheet(rangeStr, originalMarker)

        return RepeatMarker(
            collection = collection,
            range = parsedRange.range,
            variable = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: collection,
            direction = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                ?.let { parseDirection(it, originalMarker) } ?: Direction.DOWN,
            markerCell = CellAddress(cell.rowIndex, cell.columnIndex),
            markerSheetName = sheetName,
            targetSheetName = parsedRange.sheetName ?: sheetName,
            originalMarker = originalMarker
        )
    }

    /**
     * direction 문자열을 파싱합니다.
     */
    private fun parseDirection(dirStr: String, originalMarker: String) =
        runCatching { Direction.valueOf(dirStr.uppercase()) }
            .getOrElse {
                LOG.warn(
                    "repeat 마커 '$originalMarker'의 direction 값 '$dirStr'이(가) 올바르지 않습니다. " +
                        "사용 가능한 값: DOWN, RIGHT. 기본값 DOWN을 사용합니다."
                )
                Direction.DOWN
            }

    /**
     * 시트명이 포함될 수 있는 범위 문자열을 파싱합니다.
     */
    private fun parseRangeWithSheet(rangeStr: String, originalMarker: String): ParsedRange {
        val trimmed = rangeStr.trim()
        val exclamationIndex = findExclamationIndex(trimmed)

        fun parseRange(rangePart: String) = runCatching { CellRangeAddress.valueOf(rangePart) }
            .getOrElse { throw TemplateProcessingException.invalidRange(originalMarker, rangeStr) }

        return if (exclamationIndex >= 0) {
            ParsedRange(
                sheetName = trimmed.substring(0, exclamationIndex).unquote(),
                range = parseRange(trimmed.substring(exclamationIndex + 1))
            )
        } else {
            ParsedRange(sheetName = null, range = parseRange(trimmed))
        }
    }

    /**
     * 범위 문자열에서 '!' 위치를 찾습니다.
     */
    private fun findExclamationIndex(str: String): Int {
        var inQuote = false
        var quoteChar: Char? = null

        for (i in str.indices) {
            val c = str[i]
            when {
                !inQuote && (c == '\'' || c == '"') -> {
                    inQuote = true
                    quoteChar = c
                }
                inQuote && c == quoteChar -> {
                    inQuote = false
                    quoteChar = null
                }
                !inQuote && c == '!' -> return i
            }
        }

        return -1
    }

    /**
     * repeat 마커를 JXLS jx:each 코멘트로 변환합니다.
     */
    private fun convertMarkerToJxlsComment(workbook: XSSFWorkbook, marker: RepeatMarker) {
        val targetSheet = workbook.getSheet(marker.targetSheetName) ?: return

        // range의 시작 셀에 jx:each 코멘트 추가
        val targetRow = targetSheet.getRow(marker.range.firstRow)
            ?: targetSheet.createRow(marker.range.firstRow)
        val targetCell = targetRow.getCell(marker.range.firstColumn)
            ?: targetRow.createCell(marker.range.firstColumn)

        val lastCellRef = CellAddress(marker.range.lastRow, marker.range.lastColumn).formatAsString()

        val jxlsComment = buildJxlsComment(marker, lastCellRef)
        addCellComment(workbook, targetSheet, targetCell, jxlsComment)

        // 마커 셀 내용 삭제
        val markerSheet = workbook.getSheet(marker.markerSheetName) ?: return
        val markerRow = markerSheet.getRow(marker.markerCell.row)
        val markerCell = markerRow?.getCell(marker.markerCell.column)
        markerCell?.setCellValue("")
    }

    /**
     * JXLS jx:each 코멘트 문자열을 생성합니다.
     */
    private fun buildJxlsComment(marker: RepeatMarker, lastCellRef: String): String {
        val directionParam = marker.direction.takeIf { it == Direction.RIGHT }
            ?.let { " direction=\"RIGHT\"" } ?: ""
        return "jx:each(items=\"${marker.collection}\" var=\"${marker.variable}\" " +
            "lastCell=\"${lastCellRef}\"${directionParam})"
    }

    /**
     * 셀에 코멘트를 추가합니다.
     */
    private fun addCellComment(
        workbook: XSSFWorkbook,
        sheet: Sheet,
        cell: Cell,
        commentText: String
    ) {
        cell.removeCellComment()
        val anchor = XSSFClientAnchor(
            0, 0, 0, 0,
            cell.columnIndex, cell.rowIndex,
            cell.columnIndex + 3, cell.rowIndex + 2
        )
        cell.cellComment = sheet.createDrawingPatriarch().createCellComment(anchor).apply {
            string = workbook.creationHelper.createRichTextString(commentText)
        }
    }
}
