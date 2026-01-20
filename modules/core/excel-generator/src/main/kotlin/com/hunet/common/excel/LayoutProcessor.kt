package com.hunet.common.excel

import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Excel 레이아웃(열 너비, 행 높이, 병합 셀) 백업 및 복원을 담당하는 프로세서.
 */
internal class LayoutProcessor {

    data class SheetLayout(
        val columnWidths: Map<Int, Int>,
        val rowHeights: Map<Int, Short>,
        val mergedRegions: List<CellRangeAddress> = emptyList()
    )

    data class WorkbookLayout(
        val sheetLayouts: Map<Int, SheetLayout>
    )

    /**
     * 템플릿에서 레이아웃 정보를 백업합니다.
     */
    fun backup(template: InputStream) = XSSFWorkbook(template).use { workbook ->
        backupFromWorkbook(workbook)
    }

    /**
     * 워크북에서 직접 레이아웃 정보를 백업합니다.
     */
    fun backupFromWorkbook(workbook: XSSFWorkbook) = WorkbookLayout(
        (0 until workbook.numberOfSheets).associateWith { index ->
            workbook.getSheetAt(index).let { sheet ->
                SheetLayout(
                    columnWidths = (0..sheet.lastColumnWithData + 10)
                        .associateWith { sheet.getColumnWidth(it) },
                    rowHeights = (0..sheet.lastRowWithData + 10)
                        .associateWith { sheet.getRow(it)?.height ?: sheet.defaultRowHeight },
                    mergedRegions = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it) }
                )
            }
        }
    )

    /**
     * 백업된 레이아웃을 워크북에 복원합니다.
     */
    fun restore(outputBytes: ByteArray, layout: WorkbookLayout) =
        XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
            restoreInPlace(workbook, layout)
            workbook.toByteArray()
        }

    /**
     * 백업된 레이아웃을 워크북에 직접 복원합니다.
     */
    fun restoreInPlace(workbook: XSSFWorkbook, layout: WorkbookLayout) {
        layout.sheetLayouts
            .filterKeys { it < workbook.numberOfSheets }
            .forEach { (index, sheetLayout) ->
                workbook.getSheetAt(index).apply {
                    sheetLayout.columnWidths.forEach { (col, width) -> setColumnWidth(col, width) }
                    sheetLayout.rowHeights.forEach { (row, height) -> getRow(row)?.height = height }

                    // 병합 셀 복원: 백업된 영역 중 현재 워크북에 없는 영역만 추가
                    val existingRegions = (0 until numMergedRegions)
                        .map { getMergedRegion(it) }
                        .map { it.formatAsString() }
                        .toSet()

                    sheetLayout.mergedRegions.forEach { region ->
                        if (region.formatAsString() !in existingRegions) {
                            addMergedRegion(region)
                        }
                    }
                }
            }
    }
}
