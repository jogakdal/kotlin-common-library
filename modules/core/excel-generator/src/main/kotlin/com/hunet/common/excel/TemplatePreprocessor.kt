package com.hunet.common.excel

import com.hunet.common.util.unquote
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellAddress
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(TemplatePreprocessor::class.java)

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
    fun preprocess(template: InputStream): InputStream {
        val workbook = XSSFWorkbook(template)

        try {
            // 사용 가능한 시트 목록 (오류 메시지용)
            val availableSheets = (0 until workbook.numberOfSheets).map { workbook.getSheetAt(it).sheetName }

            // 1. 불완전한 repeat 마커 검사
            checkIncompleteMarkers(workbook)

            // 2. 모든 시트에서 repeat 마커 찾기
            val markers = findRepeatMarkers(workbook)

            // 3. 시트 존재 여부 검증
            for (marker in markers) {
                if (workbook.getSheet(marker.targetSheetName) == null) {
                    throw TemplateProcessingException.sheetNotFound(marker.targetSheetName, availableSheets)
                }
            }

            // 4. 각 마커를 jx:each 코멘트로 변환
            for (marker in markers) {
                convertMarkerToJxlsComment(workbook, marker)
            }

            // 변환된 워크북을 스트림으로 반환
            val output = ByteArrayOutputStream()
            workbook.write(output)
            return ByteArrayInputStream(output.toByteArray())
        } finally {
            workbook.close()
        }
    }

    /**
     * 불완전한 repeat 마커를 검사합니다.
     * `${repeat(` 로 시작하지만 `)}` 로 끝나지 않는 마커를 찾습니다.
     */
    private fun checkIncompleteMarkers(workbook: XSSFWorkbook) {
        // 불완전한 마커 패턴: ${repeat( 로 시작하지만 )}로 끝나지 않는 것
        val incompletePattern = Regex("""\$\{repeat\([^}]*$""")
        // 괄호가 맞지 않는 패턴: ${repeat(...) 만 있고 } 가 없는 것
        val missingBracePattern = Regex("""\$\{repeat\([^)]*\)(?!})""")

        for (sheetIndex in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(sheetIndex)

            for (row in sheet) {
                for (cell in row) {
                    if (cell.cellType == CellType.STRING) {
                        val cellValue = cell.stringCellValue ?: continue

                        // ${repeat( 가 있는지 확인
                        if (cellValue.contains("\${repeat(")) {
                            // 완전한 마커인지 확인
                            val completePattern = Regex("""\$\{repeat\([^)]+\)\}""")
                            if (!completePattern.containsMatchIn(cellValue)) {
                                // 어떤 유형의 오류인지 판단
                                val reason = when {
                                    !cellValue.contains(")") -> "닫는 괄호 ')'가 누락되었습니다."
                                    !cellValue.contains("}") -> "닫는 중괄호 '}'가 누락되었습니다."
                                    else -> "문법이 올바르지 않습니다. 올바른 형식: \${repeat(collection, range, var, direction)}"
                                }

                                val markerStart = cellValue.indexOf("\${repeat(")
                                val markerPreview = cellValue.substring(markerStart).take(50) +
                                    if (cellValue.length - markerStart > 50) "..." else ""

                                throw TemplateProcessingException.invalidRepeatSyntax(
                                    marker = markerPreview,
                                    reason = "$reason (시트: '${sheet.sheetName}', 셀: ${CellAddress(cell.rowIndex, cell.columnIndex)})"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 워크북에서 모든 repeat 마커를 찾습니다.
     */
    private fun findRepeatMarkers(workbook: XSSFWorkbook): List<RepeatMarker> {
        val markers = mutableListOf<RepeatMarker>()

        for (sheetIndex in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(sheetIndex)

            for (row in sheet) {
                for (cell in row) {
                    if (cell.cellType == CellType.STRING) {
                        val cellValue = cell.stringCellValue
                        val marker = parseRepeatMarker(cellValue, cell, sheet.sheetName)
                        if (marker != null) {
                            markers.add(marker)
                        }
                    }
                }
            }
        }

        return markers
    }

    /**
     * 셀 값에서 repeat 마커를 파싱합니다.
     */
    private fun parseRepeatMarker(cellValue: String, cell: Cell, sheetName: String): RepeatMarker? {
        val pattern = Regex("""\$\{repeat\(([^)]+)\)\}""")
        val match = pattern.find(cellValue) ?: return null

        val originalMarker = match.value
        val params = match.groupValues[1]
        return parseRepeatParams(params, cell, sheetName, originalMarker)
    }

    /**
     * repeat 파라미터를 파싱합니다.
     */
    private fun parseRepeatParams(params: String, cell: Cell, sheetName: String, originalMarker: String): RepeatMarker {
        val parts = params.split(",").map { it.trim().unquote() }

        return if (parts.any { it.contains("=") }) {
            parseNamedParams(parts, cell, sheetName, originalMarker)
        } else {
            parsePositionalParams(parts, cell, sheetName, originalMarker)
        }
    }

    /**
     * 키=값 형태의 파라미터를 파싱합니다.
     */
    private fun parseNamedParams(parts: List<String>, cell: Cell, sheetName: String, originalMarker: String): RepeatMarker {
        val paramMap = mutableMapOf<String, String>()

        for (part in parts) {
            if (part.contains("=")) {
                val (key, value) = part.split("=", limit = 2).map { it.trim() }
                paramMap[key] = value.unquote()
            }
        }

        val collection = paramMap["collection"]
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "collection")

        val rangeStr = paramMap["range"]
            ?: throw TemplateProcessingException.missingParameter(originalMarker, "range")

        val variable = paramMap["var"] ?: collection

        val direction = paramMap["direction"]?.let { dirStr ->
            parseDirection(dirStr, originalMarker)
        } ?: Direction.DOWN

        val parsedRange = parseRangeWithSheet(rangeStr, originalMarker)

        return RepeatMarker(
            collection = collection,
            range = parsedRange.range,
            variable = variable,
            direction = direction,
            markerCell = CellAddress(cell.rowIndex, cell.columnIndex),
            markerSheetName = sheetName,
            targetSheetName = parsedRange.sheetName ?: sheetName,
            originalMarker = originalMarker
        )
    }

    /**
     * 위치 기반 파라미터를 파싱합니다.
     */
    private fun parsePositionalParams(parts: List<String>, cell: Cell, sheetName: String, originalMarker: String): RepeatMarker {
        if (parts.isEmpty()) {
            throw TemplateProcessingException.missingParameter(originalMarker, "collection")
        }

        val collection = parts[0]
        if (collection.isBlank()) {
            throw TemplateProcessingException.missingParameter(originalMarker, "collection")
        }

        if (parts.size < 2 || parts[1].isBlank()) {
            throw TemplateProcessingException.missingParameter(originalMarker, "range")
        }

        val rangeStr = parts[1]
        val variable = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else collection
        val direction = if (parts.size >= 4 && parts[3].isNotBlank()) {
            parseDirection(parts[3], originalMarker)
        } else {
            Direction.DOWN
        }

        val parsedRange = parseRangeWithSheet(rangeStr, originalMarker)

        return RepeatMarker(
            collection = collection,
            range = parsedRange.range,
            variable = variable,
            direction = direction,
            markerCell = CellAddress(cell.rowIndex, cell.columnIndex),
            markerSheetName = sheetName,
            targetSheetName = parsedRange.sheetName ?: sheetName,
            originalMarker = originalMarker
        )
    }

    /**
     * direction 문자열을 파싱합니다.
     */
    private fun parseDirection(dirStr: String, originalMarker: String): Direction {
        return try {
            Direction.valueOf(dirStr.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warn(
                "repeat 마커 '$originalMarker'의 direction 값 '$dirStr'이(가) 올바르지 않습니다. " +
                "사용 가능한 값: DOWN, RIGHT. 기본값 DOWN을 사용합니다."
            )
            Direction.DOWN
        }
    }

    /**
     * 시트명이 포함될 수 있는 범위 문자열을 파싱합니다.
     */
    private fun parseRangeWithSheet(rangeStr: String, originalMarker: String): ParsedRange {
        val trimmed = rangeStr.trim()

        val exclamationIndex = findExclamationIndex(trimmed)

        return if (exclamationIndex >= 0) {
            val sheetPart = trimmed.substring(0, exclamationIndex).unquote()
            val rangePart = trimmed.substring(exclamationIndex + 1)

            val range = try {
                CellRangeAddress.valueOf(rangePart)
            } catch (e: Exception) {
                throw TemplateProcessingException.invalidRange(originalMarker, rangeStr)
            }

            ParsedRange(sheetName = sheetPart, range = range)
        } else {
            val range = try {
                CellRangeAddress.valueOf(trimmed)
            } catch (e: Exception) {
                throw TemplateProcessingException.invalidRange(originalMarker, rangeStr)
            }

            ParsedRange(sheetName = null, range = range)
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

        // 1. range의 시작 셀에 jx:each 코멘트 추가
        val targetRow = targetSheet.getRow(marker.range.firstRow)
            ?: targetSheet.createRow(marker.range.firstRow)
        val targetCell = targetRow.getCell(marker.range.firstColumn)
            ?: targetRow.createCell(marker.range.firstColumn)

        val lastCellRef = CellAddress(marker.range.lastRow, marker.range.lastColumn).formatAsString()

        val jxlsComment = buildJxlsComment(marker, lastCellRef)
        addCellComment(workbook, targetSheet, targetCell, jxlsComment)

        logger.debug(
            "repeat 마커 변환 완료: {} -> jx:each (시트: '{}', 범위: {})",
            marker.originalMarker, marker.targetSheetName, marker.range.formatAsString()
        )

        // 2. 마커 셀 내용 삭제
        val markerSheet = workbook.getSheet(marker.markerSheetName) ?: return
        val markerRow = markerSheet.getRow(marker.markerCell.row)
        val markerCell = markerRow?.getCell(marker.markerCell.column)
        markerCell?.setCellValue("")
    }

    /**
     * JXLS jx:each 코멘트 문자열을 생성합니다.
     */
    private fun buildJxlsComment(marker: RepeatMarker, lastCellRef: String): String {
        val directionParam = if (marker.direction == Direction.RIGHT) {
            " direction=\"RIGHT\""
        } else {
            ""
        }

        return "jx:each(items=\"${marker.collection}\" var=\"${marker.variable}\" lastCell=\"${lastCellRef}\"${directionParam})"
    }

    /**
     * 셀에 코멘트를 추가합니다.
     */
    private fun addCellComment(workbook: XSSFWorkbook, sheet: Sheet, cell: Cell, commentText: String) {
        val factory = workbook.creationHelper
        val drawing = sheet.createDrawingPatriarch()

        cell.removeCellComment()

        val anchor = XSSFClientAnchor(
            0, 0, 0, 0,
            cell.columnIndex, cell.rowIndex,
            cell.columnIndex + 3, cell.rowIndex + 2
        )

        val comment = drawing.createCellComment(anchor)
        comment.string = factory.createRichTextString(commentText)
        cell.cellComment = comment
    }
}
