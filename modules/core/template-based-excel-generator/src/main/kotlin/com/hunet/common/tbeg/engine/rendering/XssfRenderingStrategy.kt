package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.engine.core.setInitialView
import com.hunet.common.tbeg.engine.core.toByteArray
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellCopyPolicy
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream

/**
 * XSSF 기반 비스트리밍 렌더링 전략.
 *
 * 특징:
 * - 전체 워크북을 메모리에 로드
 * - shiftRows()로 행 삽입 공간 확보
 * - copyRowFrom()으로 템플릿 행 복사 (수식 자동 조정)
 * - evaluateAll()로 수식 재계산
 *
 * 장점:
 * - 모든 POI 기능 사용 가능
 * - 수식 참조 자동 조정
 *
 * 단점:
 * - 대용량 데이터 시 메모리 사용량 증가
 */
internal class XssfRenderingStrategy : AbstractRenderingStrategy() {
    companion object {
        private val REPEAT_MARKER_PATTERN = Regex("""\$\{repeat\s*\(""", RegexOption.IGNORE_CASE)
        private val FORMULA_MARKER_PATTERN = Regex("""TBEG_(REPEAT|IMAGE)\s*\(""", RegexOption.IGNORE_CASE)
    }

    override val name: String = "XSSF"

    // ========== 추상 메서드 구현 ==========

    override fun beforeProcessSheets(
        workbook: Workbook,
        blueprint: WorkbookSpec,
        data: Map<String, Any>,
        context: RenderingContext
    ) {
        // 모든 시트의 마커 셀 미리 비우기 (shiftRows 시 POI 경고 방지)
        for (i in 0 until workbook.numberOfSheets) {
            clearAllMarkers(workbook.getSheetAt(i) as XSSFSheet)
        }
    }

    override fun <T> withWorkbook(
        templateBytes: ByteArray,
        block: (workbook: Workbook, xssfWorkbook: XSSFWorkbook) -> T
    ): T {
        return XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            block(workbook, workbook)
        }
    }

    override fun processSheet(
        sheet: Sheet,
        sheetIndex: Int,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        val xssfSheet = sheet as XSSFSheet
        processSheetXssf(xssfSheet, blueprint, data, imageLocations, context)
    }

    override fun afterProcessSheets(workbook: Workbook, context: RenderingContext) {
        val xssfWorkbook = workbook as XSSFWorkbook

        // 수식 재계산
        xssfWorkbook.creationHelper.createFormulaEvaluator().evaluateAll()

        // calcChain 비우기 (반복 확장 후 불일치 상태 방지)
        clearCalcChain(xssfWorkbook)

        // 파일 열 때 첫 번째 시트 A1 셀에 포커스
        xssfWorkbook.setInitialView()
    }

    override fun finalizeWorkbook(workbook: Workbook): ByteArray {
        return (workbook as XSSFWorkbook).toByteArray()
    }

    // ========== XSSF 특화 로직 ==========

    private fun processSheetXssf(
        sheet: XSSFSheet,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        // 반복 영역 확장 (뒤에서부터 처리하여 인덱스 꼬임 방지)
        val repeatRows = blueprint.rows.filterIsInstance<RowSpec.RepeatRow>().reversed()
        val rowOffsets = mutableMapOf<Int, Int>()

        for (repeatRow in repeatRows) {
            val items = data[repeatRow.collectionName] as? List<*> ?: continue

            when (repeatRow.direction) {
                RepeatDirection.DOWN -> {
                    expandRowsDown(sheet, repeatRow, items, blueprint, context)
                    val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
                    val rowsToInsert = items.size * templateRowCount - templateRowCount
                    rowOffsets[repeatRow.templateRowIndex] = rowsToInsert
                    context.repeatExpansionProcessor.expandFormulasAfterRepeat(
                        sheet, repeatRow, items.size, templateRowCount, rowsToInsert
                    )
                }

                RepeatDirection.RIGHT -> {
                    val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
                    val colsToInsert = (items.size - 1) * templateColCount

                    if (colsToInsert > 0) {
                        context.repeatExpansionProcessor.expandColumnsRight(sheet, repeatRow, items.size)
                    }

                    context.repeatExpansionProcessor.expandFormulasAfterRightRepeat(
                        sheet, repeatRow, items.size, templateColCount, colsToInsert
                    )
                }
            }
        }

        substituteVariablesXssf(sheet, blueprint, data, rowOffsets, imageLocations, context)
    }

    /**
     * 행을 아래 방향으로 확장합니다.
     */
    private fun expandRowsDown(
        sheet: XSSFSheet,
        repeatRow: RowSpec.RepeatRow,
        items: List<*>,
        blueprint: SheetSpec,
        context: RenderingContext
    ) {
        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
        val rowsToInsert = items.size * templateRowCount - templateRowCount

        if (rowsToInsert > 0) {
            val insertPosition = repeatRow.repeatEndRowIndex + 1
            if (insertPosition <= sheet.lastRowNum) {
                sheet.shiftRows(insertPosition, sheet.lastRowNum, rowsToInsert)
            }

            for (itemIdx in 1 until items.size) {
                for (templateOffset in 0 until templateRowCount) {
                    val templateRowIndex = repeatRow.templateRowIndex + templateOffset
                    val templateRow = sheet.getRow(templateRowIndex)
                    val newRowIndex = repeatRow.templateRowIndex + (itemIdx * templateRowCount) + templateOffset
                    val newRow = sheet.createRow(newRowIndex)

                    if (templateRow != null) {
                        newRow.copyRowFrom(templateRow, CellCopyPolicy.Builder()
                            .cellStyle(true)
                            .cellValue(true)
                            .cellFormula(true)
                            .mergedRegions(false)
                            .build())

                        newRow.forEach { cell ->
                            if (cell.columnIndex !in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                                cell.setBlank()
                            }
                        }
                    }
                }
            }

            context.repeatExpansionProcessor.copyMergedRegionsForRepeat(
                sheet, repeatRow, items.size, blueprint.mergedRegions
            )
            context.repeatExpansionProcessor.expandConditionalFormattingForRepeat(
                sheet, repeatRow, items.size
            )
        }
    }

    /**
     * 시트에서 모든 TBEG 마커 셀을 비웁니다.
     * shiftRows 전에 호출하여 POI 경고를 방지합니다.
     */
    private fun clearAllMarkers(sheet: XSSFSheet) {
        sheet.forEach { row ->
            row.forEach { cell ->
                when (cell.cellType) {
                    CellType.STRING -> {
                        val text = cell.stringCellValue ?: return@forEach
                        if (REPEAT_MARKER_PATTERN.containsMatchIn(text)) {
                            cell.setBlank()
                        }
                    }
                    CellType.FORMULA -> {
                        val formula = cell.cellFormula ?: return@forEach
                        if (FORMULA_MARKER_PATTERN.containsMatchIn(formula)) {
                            cell.setBlank()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun substituteVariablesXssf(
        sheet: XSSFSheet,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        rowOffsets: Map<Int, Int>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        var currentOffset = 0
        val sheetIndex = sheet.workbook.getSheetIndex(sheet)

        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    val actualRowIndex = rowSpec.templateRowIndex + currentOffset
                    val row = sheet.getRow(actualRowIndex) ?: continue
                    substituteRowVariables(
                        sheet, row, rowSpec.cells, data, sheetIndex, imageLocations, context,
                        rowOffset = currentOffset
                    )
                }

                is RowSpec.RepeatRow -> {
                    val items = data[rowSpec.collectionName] as? List<*> ?: continue
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            processDownRepeat(
                                sheet, rowSpec, items, blueprint, data, sheetIndex,
                                imageLocations, context, currentOffset, rowOffsets
                            )
                            currentOffset += rowOffsets[rowSpec.templateRowIndex] ?: 0
                        }

                        RepeatDirection.RIGHT -> {
                            processRightRepeat(
                                sheet, rowSpec, items, blueprint, data, sheetIndex,
                                imageLocations, context, currentOffset, templateRowCount
                            )
                        }
                    }
                }

                is RowSpec.RepeatContinuation -> {
                    // RepeatRow에서 이미 처리됨
                }
            }
        }
    }

    private fun processDownRepeat(
        sheet: XSSFSheet,
        rowSpec: RowSpec.RepeatRow,
        items: List<*>,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        currentOffset: Int,
        rowOffsets: Map<Int, Int>
    ) {
        val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1
        val totalRepeatOffset = rowOffsets[rowSpec.templateRowIndex] ?: 0

        items.forEachIndexed { itemIdx, item ->
            val itemData = if (item != null) {
                data + (rowSpec.itemVariable to item)
            } else data

            for (templateOffset in 0 until templateRowCount) {
                val templateRowIdx = rowSpec.templateRowIndex + templateOffset
                val actualRowIndex = rowSpec.templateRowIndex + currentOffset +
                    (itemIdx * templateRowCount) + templateOffset
                val row = sheet.getRow(actualRowIndex) ?: continue

                val currentRowSpec = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                val cellSpecs = currentRowSpec?.cells ?: continue

                substituteRowVariables(
                    sheet, row, cellSpecs, itemData, sheetIndex,
                    imageLocations, context,
                    rowOffset = currentOffset + totalRepeatOffset,
                    repeatItemIndex = itemIdx
                )
            }
        }
    }

    private fun processRightRepeat(
        sheet: XSSFSheet,
        rowSpec: RowSpec.RepeatRow,
        items: List<*>,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        currentOffset: Int,
        templateRowCount: Int
    ) {
        val templateColCount = rowSpec.repeatEndCol - rowSpec.repeatStartCol + 1
        val colShiftAmount = (items.size - 1) * templateColCount

        for (rowIdx in rowSpec.templateRowIndex..rowSpec.repeatEndRowIndex) {
            val actualRowIdx = rowIdx + currentOffset
            val row = sheet.getRow(actualRowIdx) ?: continue

            val currentRowSpec = blueprint.rows.find { it.templateRowIndex == rowIdx }
            val cellSpecs = currentRowSpec?.cells ?: continue

            // 1. 반복 영역 내 셀 처리
            items.forEachIndexed { itemIdx, item ->
                val itemData = if (item != null) {
                    data + (rowSpec.itemVariable to item)
                } else data

                val colStart = rowSpec.repeatStartCol + (itemIdx * templateColCount)

                for (cellSpec in cellSpecs
                    .filter { it.columnIndex in rowSpec.repeatStartCol..rowSpec.repeatEndCol }) {

                    val targetColIdx = colStart + (cellSpec.columnIndex - rowSpec.repeatStartCol)
                    val cell = row.getCell(targetColIdx) ?: continue

                    processCellContentXssf(
                        cell, cellSpec.content, itemData, sheetIndex, imageLocations, context,
                        rowOffset = currentOffset, colOffset = colShiftAmount,
                        repeatItemIndex = itemIdx
                    )
                }
            }

            // 2. 반복 영역 오른쪽 셀 처리 (밀린 위치에서)
            for (cellSpec in cellSpecs.filter { it.columnIndex > rowSpec.repeatEndCol }) {
                val shiftedColIdx = cellSpec.columnIndex + colShiftAmount
                val cell = row.getCell(shiftedColIdx) ?: continue

                processCellContentXssf(
                    cell, cellSpec.content, data, sheetIndex, imageLocations, context,
                    rowOffset = currentOffset, colOffset = colShiftAmount
                )
            }
        }
    }

    private fun substituteRowVariables(
        sheet: XSSFSheet,
        row: Row,
        cellSpecs: List<CellSpec>,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        rowOffset: Int = 0,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        for (cellSpec in cellSpecs) {
            val cell = row.getCell(cellSpec.columnIndex) ?: continue
            processCellContentXssf(
                cell, cellSpec.content, data, sheetIndex,
                imageLocations, context,
                rowOffset, colOffset, repeatItemIndex
            )
        }
    }

    /**
     * XSSF용 셀 내용 처리.
     * 기본 처리는 부모 클래스에 위임하고, Formula만 XSSF 특화 처리합니다.
     */
    private fun processCellContentXssf(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        rowOffset: Int = 0,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        // 부모 클래스의 공통 처리 시도
        val handled = processCellContent(
            cell, content, data, sheetIndex, imageLocations, context,
            rowOffset, colOffset, repeatItemIndex
        )

        // Formula는 XSSF에서 특별 처리 필요 없음 (POI가 자동 조정)
        if (!handled && content is CellContent.Formula) {
            // 일반 수식은 그대로 유지 (XSSF는 shiftRows 시 자동 조정)
        }
    }
}
