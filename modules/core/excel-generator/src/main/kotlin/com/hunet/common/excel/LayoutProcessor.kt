package com.hunet.common.excel

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
    fun backup(template: InputStream): WorkbookLayout =
        XSSFWorkbook(template).use { workbook ->
            WorkbookLayout(
                (0 until workbook.numberOfSheets).associate { index ->
                    val sheet = workbook.getSheetAt(index)
                    val lastColumn = sheet.lastColumnWithData
                    val lastRow = sheet.lastRowWithData

                    val columnWidths = (0..lastColumn + 10).associateWith { sheet.getColumnWidth(it) }
                    val rowHeights = (0..lastRow + 10).associateWith { rowIndex ->
                        sheet.getRow(rowIndex)?.height ?: sheet.defaultRowHeight
                    }

                    index to SheetLayout(columnWidths, rowHeights)
                }
            )
        }

    /**
     * 백업된 레이아웃을 워크북에 복원합니다.
     */
    fun restore(outputBytes: ByteArray, layout: WorkbookLayout): ByteArray =
        XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
            layout.sheetLayouts
                .filter { (index, _) -> index < workbook.numberOfSheets }
                .forEach { (index, sheetLayout) ->
                    val sheet = workbook.getSheetAt(index)
                    sheetLayout.columnWidths.forEach { (col, width) -> sheet.setColumnWidth(col, width) }
                    sheetLayout.rowHeights.forEach { (row, height) ->
                        sheet.getRow(row)?.height = height
                    }
                }
            workbook.toByteArray()
        }

    // 확장 프로퍼티
    private val Sheet.lastRowWithData: Int
        get() = asSequence()
            .flatMap { it.asSequence() }
            .filterNot { it.isEmpty }
            .maxOfOrNull { it.rowIndex } ?: -1

    private val Sheet.lastColumnWithData: Int
        get() = asSequence()
            .flatMap { it.asSequence() }
            .filterNot { it.isEmpty }
            .maxOfOrNull { it.columnIndex } ?: -1

    private val org.apache.poi.ss.usermodel.Cell.isEmpty: Boolean
        get() = cellComment == null && when (cellType) {
            CellType.BLANK -> true
            CellType.STRING -> stringCellValue.isNullOrBlank()
            else -> false
        }

    private fun XSSFWorkbook.toByteArray(): ByteArray =
        ByteArrayOutputStream().also { write(it) }.toByteArray()
}
