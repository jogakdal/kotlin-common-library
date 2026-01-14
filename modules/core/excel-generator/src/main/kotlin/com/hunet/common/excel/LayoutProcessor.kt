package com.hunet.common.excel

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Excel 레이아웃(열 너비, 행 높이) 백업 및 복원을 담당하는 프로세서.
 */
internal class LayoutProcessor {

    data class SheetLayout(
        val columnWidths: Map<Int, Int>,
        val rowHeights: Map<Int, Short>
    )

    data class WorkbookLayout(
        val sheetLayouts: Map<Int, SheetLayout>
    )

    /**
     * 템플릿에서 레이아웃 정보를 백업합니다.
     */
    fun backup(template: InputStream) = XSSFWorkbook(template).use { workbook ->
        WorkbookLayout(
            (0 until workbook.numberOfSheets).associateWith { index ->
                workbook.getSheetAt(index).let { sheet ->
                    SheetLayout(
                        columnWidths = (0..sheet.lastColumnWithData + 10)
                            .associateWith { sheet.getColumnWidth(it) },
                        rowHeights = (0..sheet.lastRowWithData + 10)
                            .associateWith { sheet.getRow(it)?.height ?: sheet.defaultRowHeight }
                    )
                }
            }
        )
    }

    /**
     * 백업된 레이아웃을 워크북에 복원합니다.
     */
    fun restore(outputBytes: ByteArray, layout: WorkbookLayout) =
        XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
            layout.sheetLayouts
                .filterKeys { it < workbook.numberOfSheets }
                .forEach { (index, sheetLayout) ->
                    workbook.getSheetAt(index).apply {
                        sheetLayout.columnWidths.forEach { (col, width) -> setColumnWidth(col, width) }
                        sheetLayout.rowHeights.forEach { (row, height) -> getRow(row)?.height = height }
                    }
                }
            workbook.toByteArray()
        }

    // 확장 프로퍼티
    private val Sheet.lastRowWithData
        get() = cellSequence().filterNot { it.isEmpty }.maxOfOrNull { it.rowIndex } ?: -1

    private val Sheet.lastColumnWithData
        get() = cellSequence().filterNot { it.isEmpty }.maxOfOrNull { it.columnIndex } ?: -1

    private val Cell.isEmpty
        get() = cellComment == null && when (cellType) {
            CellType.BLANK -> true
            CellType.STRING -> stringCellValue.isNullOrBlank()
            else -> false
        }

    private fun Sheet.cellSequence() = asSequence().flatMap { it.asSequence() }

    private fun XSSFWorkbook.toByteArray() =
        ByteArrayOutputStream().also { write(it) }.toByteArray()
}
