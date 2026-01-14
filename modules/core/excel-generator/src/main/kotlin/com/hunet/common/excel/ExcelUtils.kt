package com.hunet.common.excel

import com.hunet.common.util.detectImageType
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * Excel 관련 유틸리티 함수 모음.
 */

// ========== Excel 열 변환 유틸리티 ==========

/**
 * Excel 열 문자를 0-based 숫자로 변환합니다.
 * 예: "A" -> 0, "B" -> 1, "Z" -> 25, "AA" -> 26
 */
internal fun String.toColumnIndex() =
    uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1

/**
 * 0-based 숫자를 Excel 열 문자로 변환합니다.
 * 예: 0 -> "A", 1 -> "B", 25 -> "Z", 26 -> "AA"
 */
internal fun Int.toColumnLetter(): String = buildString {
    generateSequence(this@toColumnLetter + 1) { n -> ((n - 1) / 26).takeIf { it > 0 } }
        .map { n -> 'A' + ((n - 1) % 26) }
        .toList()
        .reversed()
        .forEach { append(it) }
}

// ========== XML 유틸리티 ==========

/**
 * XML 특수 문자를 이스케이프합니다.
 */
internal fun String.escapeXml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

// ========== POI 확장 함수 ==========

/**
 * XSSFWorkbook을 ByteArray로 변환합니다.
 */
internal fun XSSFWorkbook.toByteArray(): ByteArray =
    ByteArrayOutputStream().also { write(it) }.toByteArray()

/**
 * XSSFWorkbook의 모든 시트를 Sequence로 반환합니다.
 */
internal val XSSFWorkbook.sheets: Sequence<Sheet>
    get() = (0 until numberOfSheets).asSequence().map { getSheetAt(it) }

/**
 * 시트의 모든 셀을 Sequence로 반환합니다.
 */
internal fun Sheet.cellSequence() = asSequence().flatMap { it.asSequence() }

/**
 * 시트에서 데이터가 있는 마지막 행 인덱스를 반환합니다.
 */
internal val Sheet.lastRowWithData
    get() = cellSequence().filterNot { it.isEmpty }.maxOfOrNull { it.rowIndex } ?: -1

/**
 * 시트에서 데이터가 있는 마지막 열 인덱스를 반환합니다.
 */
internal val Sheet.lastColumnWithData
    get() = cellSequence().filterNot { it.isEmpty }.maxOfOrNull { it.columnIndex } ?: -1

/**
 * 셀이 비어있는지 확인합니다 (코멘트도 없고 내용도 없는 경우).
 */
internal val Cell.isEmpty: Boolean
    get() = cellComment == null && when (cellType) {
        CellType.BLANK -> true
        CellType.STRING -> stringCellValue.isNullOrBlank()
        else -> false
    }

/**
 * 지정된 위치를 포함하는 병합 영역을 찾습니다.
 */
internal fun Sheet.findMergedRegion(rowIndex: Int, columnIndex: Int): CellRangeAddress? =
    (0 until numMergedRegions)
        .map { getMergedRegion(it) }
        .firstOrNull { it.isInRange(rowIndex, columnIndex) }

/**
 * POI에서 사용하는 이미지 타입 문자열을 반환합니다.
 * GIF는 PNG로, BMP는 DIB로 변환됩니다.
 */
internal fun ByteArray.detectImageTypeForPoi(): String =
    when (val type = detectImageType()) {
        "GIF" -> "PNG"   // POI에서 GIF는 PNG로 처리
        "BMP" -> "DIB"   // POI에서 BMP는 DIB로 처리
        else -> type
    }
