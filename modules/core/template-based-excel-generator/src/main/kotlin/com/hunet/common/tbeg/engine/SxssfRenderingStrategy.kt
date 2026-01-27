package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.exception.FormulaExpansionException
import com.hunet.common.tbeg.removeAbsPath
import com.hunet.common.tbeg.setInitialView
import com.hunet.common.tbeg.toColumnLetter
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFFormulaEvaluator
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * SXSSF 기반 스트리밍 렌더링 전략.
 *
 * 특징:
 * - 명세 기반 순차 생성
 * - 100행 버퍼로 메모리 효율적
 * - 수식 재계산은 Excel이 파일 열 때 수행
 *
 * 장점:
 * - 대용량 데이터 처리에 적합
 * - 메모리 사용량 최소화
 *
 * 단점:
 * - 이미 작성된 행 접근 불가
 * - 차트/피벗 테이블은 별도 처리 필요
 */
internal class SxssfRenderingStrategy : AbstractRenderingStrategy() {
    override val name: String = "SXSSF"

    // 템플릿에서 추출한 스타일을 SXSSF 워크북에 매핑
    private var styleMap: Map<Short, CellStyle> = emptyMap()

    // ========== 추상 메서드 구현 ==========

    override fun <T> withWorkbook(
        templateBytes: ByteArray,
        block: (workbook: Workbook, xssfWorkbook: XSSFWorkbook) -> T
    ): T {
        // dxfs 스타일 유지를 위해 템플릿 워크북을 닫지 않고 재사용
        val templateWorkbook = XSSFWorkbook(ByteArrayInputStream(templateBytes))

        try {
            return SXSSFWorkbook(templateWorkbook, 100).use { workbook ->
                block(workbook, templateWorkbook)
            }
        } finally {
            templateWorkbook.close()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun beforeProcessSheets(
        workbook: Workbook,
        blueprint: WorkbookSpec,
        data: Map<String, Any>,
        context: RenderingContext
    ) {
        val sxssfWorkbook = workbook as SXSSFWorkbook
        val xssfWorkbook = sxssfWorkbook.xssfWorkbook

        // 스타일 추출 및 매핑
        val styleRegistry = extractStyles(xssfWorkbook)
        styleMap = styleRegistry.mapValues { (_, style) ->
            xssfWorkbook.getCellStyleAt(style.index.toInt())
        }

        // 시트 내용 클리어 (새로 생성)
        clearSheetContents(xssfWorkbook)
    }

    override fun processSheet(
        sheet: Sheet,
        sheetIndex: Int,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        val sxssfSheet = sheet as SXSSFSheet
        processSheetSxssf(sxssfSheet, sheetIndex, blueprint, data, imageLocations, context)
    }

    @Suppress("UNUSED_PARAMETER")
    override fun afterProcessSheets(workbook: Workbook, context: RenderingContext) {
        val sxssfWorkbook = workbook as SXSSFWorkbook

        // 수식 평가 및 calcChain 정리
        evaluateFormulasAndClearCalcChain(sxssfWorkbook)

        for (i in 0 until sxssfWorkbook.numberOfSheets) {
            sxssfWorkbook.getSheetAt(i).forceFormulaRecalculation = true
        }

        // 파일 열 때 첫 번째 시트 A1 셀에 포커스
        sxssfWorkbook.setInitialView()
    }

    override fun finalizeWorkbook(workbook: Workbook): ByteArray {
        // 저장 후 absPath 제거 (Excel이 파일 열 때 "수정됨" 상태 방지)
        return ByteArrayOutputStream().also { out ->
            workbook.write(out)
        }.toByteArray().removeAbsPath()
    }

    // ========== SXSSF 특화 로직 ==========

    /**
     * SXSSF에서 수식 평가 및 calcChain 정리.
     */
    private fun evaluateFormulasAndClearCalcChain(workbook: SXSSFWorkbook) {
        val xssfWorkbook = workbook.xssfWorkbook

        runCatching {
            // SXSSF에서 수식 평가 (아직 flush되지 않은 행만 평가 가능)
            SXSSFFormulaEvaluator.evaluateAllFormulaCells(workbook, false)
        }

        // calcChain 비우기
        clearCalcChain(xssfWorkbook)
    }

    private fun clearSheetContents(workbook: XSSFWorkbook) {
        // calcChain 항목 비우기 (템플릿의 수식 참조 정리)
        clearCalcChain(workbook)

        for (i in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            for (rowIdx in sheet.lastRowNum downTo 0) {
                sheet.getRow(rowIdx)?.let { sheet.removeRow(it) }
            }
            while (sheet.numMergedRegions > 0) {
                sheet.removeMergedRegion(0)
            }
            val scf = sheet.sheetConditionalFormatting
            while (scf.numConditionalFormattings > 0) {
                scf.removeConditionalFormatting(0)
            }
        }
    }

    private fun extractStyles(workbook: XSSFWorkbook): Map<Short, XSSFCellStyle> =
        (0 until workbook.numCellStyles).associate { i ->
            i.toShort() to workbook.getCellStyleAt(i) as XSSFCellStyle
        }

    private fun processSheetSxssf(
        sheet: SXSSFSheet,
        sheetIndex: Int,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        blueprint.columnWidths.forEach { (col, width) -> sheet.setColumnWidth(col, width) }
        context.sheetLayoutApplier.applyHeaderFooter(
            sheet.workbook as SXSSFWorkbook, sheetIndex, blueprint.headerFooter, data, context.evaluateText
        )
        context.sheetLayoutApplier.applyPrintSetup(sheet, blueprint.printSetup)

        var currentRowIndex = 0
        var rowOffset = 0

        val repeatRegions = blueprint.rows.filterIsInstance<RowSpec.RepeatRow>()
            .associateBy { it.templateRowIndex }

        val ctx = RowWriteContext(
            sheet = sheet,
            sheetIndex = sheetIndex,
            data = data,
            styleMap = styleMap,
            repeatRegions = repeatRegions,
            imageLocations = imageLocations,
            context = context
        )

        val processedRepeatRows = mutableSetOf<Int>()

        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    writeRowSxssf(ctx, currentRowIndex, rowSpec, 0, rowOffset, totalRowOffset = rowOffset)
                    currentRowIndex++
                }

                is RowSpec.RepeatRow -> {
                    if (rowSpec.templateRowIndex in processedRepeatRows) continue

                    val items = data[rowSpec.collectionName] as? List<*> ?: emptyList<Any>()
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            val totalRepeatOffset = (items.size * templateRowCount) - templateRowCount

                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowSpec.itemVariable to item)
                                } else data

                                val itemCtx = ctx.copy(data = itemData)

                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowSpec.templateRowIndex + templateOffset
                                    val currentRowBp = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                        ?: continue

                                    val formulaRepeatIndex = itemIdx * templateRowCount + templateOffset

                                    writeRowSxssf(
                                        itemCtx, currentRowIndex, currentRowBp,
                                        formulaRepeatIndex, rowOffset,
                                        repeatRow = rowSpec,
                                        repeatItemIndex = itemIdx,
                                        totalRowOffset = rowOffset + totalRepeatOffset
                                    )
                                    currentRowIndex++
                                }
                            }
                            rowOffset += totalRepeatOffset
                        }

                        RepeatDirection.RIGHT -> {
                            writeRowSxssfWithRightExpansion(
                                sheet, currentRowIndex, rowSpec, blueprint, data, items,
                                imageLocations, sheetIndex, context
                            )
                            currentRowIndex += templateRowCount
                        }
                    }

                    for (r in rowSpec.templateRowIndex..rowSpec.repeatEndRowIndex) {
                        processedRepeatRows.add(r)
                    }
                }

                is RowSpec.RepeatContinuation -> {
                    // RepeatRow에서 이미 처리됨
                }
            }
        }

        // 병합 영역 설정
        context.sheetLayoutApplier.applyMergedRegions(
            sheet, blueprint.mergedRegions, repeatRegions, data, rowOffset
        )

        // 조건부 서식 적용
        context.sheetLayoutApplier.applyConditionalFormattings(
            sheet, blueprint.conditionalFormattings, repeatRegions, data, rowOffset
        )
    }

    private data class RowWriteContext(
        val sheet: SXSSFSheet,
        val sheetIndex: Int,
        val data: Map<String, Any>,
        val styleMap: Map<Short, CellStyle>,
        val repeatRegions: Map<Int, RowSpec.RepeatRow>,
        val imageLocations: MutableList<ImageLocation>,
        val context: RenderingContext
    ) {
        fun copy(data: Map<String, Any>): RowWriteContext =
            RowWriteContext(sheet, sheetIndex, data, styleMap, repeatRegions, imageLocations, context)
    }

    private fun writeRowSxssf(
        ctx: RowWriteContext,
        rowIndex: Int,
        blueprint: RowSpec,
        repeatIndex: Int,
        rowOffset: Int,
        repeatRow: RowSpec.RepeatRow? = null,
        repeatItemIndex: Int = 0,
        totalRowOffset: Int = 0
    ) {
        val row = ctx.sheet.createRow(rowIndex)
        blueprint.height?.let { row.height = it }

        val repeatColRange = (blueprint as? RowSpec.RepeatRow)?.let {
            it.repeatStartCol..it.repeatEndCol
        } ?: repeatRow?.let {
            it.repeatStartCol..it.repeatEndCol
        }

        cellLoop@ for (cellSpec in blueprint.cells) {
            if (repeatColRange != null && repeatIndex > 0) {
                if (cellSpec.columnIndex !in repeatColRange) {
                    continue
                }
            }

            val cell = row.createCell(cellSpec.columnIndex)
            ctx.styleMap[cellSpec.styleIndex]?.let { cell.cellStyle = it }

            processCellContentSxssf(
                cell, cellSpec.content, ctx.data, ctx.sheetIndex,
                ctx.imageLocations, ctx.context,
                repeatIndex, rowOffset, totalRowOffset, repeatItemIndex,
                ctx.repeatRegions, blueprint, cellSpec.columnIndex, rowIndex
            )
        }
    }

    /**
     * SXSSF용 셀 내용 처리.
     * 수식은 FormulaAdjuster로 수동 조정이 필요합니다.
     */
    private fun processCellContentSxssf(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        repeatIndex: Int,
        rowOffset: Int,
        totalRowOffset: Int,
        repeatItemIndex: Int,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
        blueprint: RowSpec,
        columnIndex: Int,
        rowIndex: Int
    ) {
        when (content) {
            is CellContent.Formula -> {
                // SXSSF에서 수식은 수동 조정 필요
                processFormulaSxssf(
                    cell, content, data,
                    repeatIndex, rowOffset, repeatRegions, blueprint, columnIndex, rowIndex
                )
            }

            is CellContent.FormulaWithVariables -> {
                var substitutedFormula = context.evaluateText(content.formula, data)
                if (repeatIndex > 0) {
                    substitutedFormula = FormulaAdjuster.adjustForRepeatIndex(substitutedFormula, repeatIndex)
                }
                cell.cellFormula = substitutedFormula
            }

            is CellContent.ImageMarker -> {
                processImageMarker(
                    cell, content, sheetIndex, imageLocations,
                    totalRowOffset, 0, repeatItemIndex
                )
            }

            else -> {
                // 나머지는 부모 클래스의 공통 처리
                processCellContent(
                    cell, content, data, sheetIndex, imageLocations, context,
                    totalRowOffset, 0, repeatItemIndex
                )
            }
        }
    }

    /**
     * SXSSF에서 수식을 처리합니다.
     * shiftRows가 없으므로 FormulaAdjuster로 수동 조정합니다.
     */
    private fun processFormulaSxssf(
        cell: Cell,
        content: CellContent.Formula,
        data: Map<String, Any>,
        repeatIndex: Int,
        rowOffset: Int,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
        blueprint: RowSpec,
        columnIndex: Int,
        rowIndex: Int
    ) {
        var adjustedFormula = content.formula
        var formulaExpanded = false

        if (repeatIndex > 0) {
            adjustedFormula = FormulaAdjuster.adjustForRepeatIndex(adjustedFormula, repeatIndex)
        }

        if (rowOffset > 0 && blueprint is RowSpec.StaticRow) {
            val repeatEndRow = repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
            if (repeatEndRow >= 0 && blueprint.templateRowIndex > repeatEndRow) {
                for (repeatRegion in repeatRegions.values) {
                    val items = data[repeatRegion.collectionName] as? List<*> ?: continue
                    val templateRowCount = repeatRegion.repeatEndRowIndex - repeatRegion.templateRowIndex + 1
                    val (expanded, hasDiscontinuous) = FormulaAdjuster.expandSingleRefToRange(
                        adjustedFormula,
                        repeatRegion.templateRowIndex,
                        repeatRegion.repeatEndRowIndex,
                        items.size,
                        templateRowCount
                    )
                    if (expanded != adjustedFormula) {
                        if (hasDiscontinuous && items.size > 255) {
                            throw FormulaExpansionException(
                                sheetName = cell.sheet.sheetName,
                                cellRef = "${toColumnLetter(columnIndex)}${rowIndex + 1}",
                                formula = content.formula
                            )
                        }
                        adjustedFormula = expanded
                        formulaExpanded = true
                    }
                }

                if (!formulaExpanded) {
                    adjustedFormula = FormulaAdjuster.adjustForRowExpansion(
                        adjustedFormula, 0, repeatEndRow, rowOffset
                    )
                }
            }
        }

        cell.cellFormula = adjustedFormula
    }

    private fun writeRowSxssfWithRightExpansion(
        sheet: SXSSFSheet,
        startRowIndex: Int,
        repeatRow: RowSpec.RepeatRow,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        items: List<*>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
        context: RenderingContext
    ) {
        val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
        val colShiftAmount = (items.size - 1) * templateColCount

        for (rowOffset in 0 until templateRowCount) {
            val templateRowIdx = repeatRow.templateRowIndex + rowOffset
            val currentRowIndex = startRowIndex + rowOffset

            val rowSpec = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
            val cellSpecs = rowSpec?.cells ?: continue

            val row = sheet.createRow(currentRowIndex)
            rowSpec.height?.let { row.height = it }

            // 반복 영역 밖의 셀들
            for (cellSpec in cellSpecs) {
                if (cellSpec.columnIndex !in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                    val actualColIndex = if (cellSpec.columnIndex > repeatRow.repeatEndCol) {
                        cellSpec.columnIndex + colShiftAmount
                    } else {
                        cellSpec.columnIndex
                    }
                    val cell = row.createCell(actualColIndex)
                    styleMap[cellSpec.styleIndex]?.let { cell.cellStyle = it }

                    val adjustedContent = adjustContentForRightExpansion(
                        cellSpec, repeatRow, items, colShiftAmount, sheet, currentRowIndex, actualColIndex
                    )

                    writeCellContentSxssf(
                        cell, adjustedContent, data, imageLocations, sheetIndex,
                        context, colOffset = colShiftAmount
                    )
                }
            }

            // 각 아이템에 대해 열 방향으로 확장
            items.forEachIndexed { itemIdx, item ->
                val itemData = if (item != null) {
                    data + (repeatRow.itemVariable to item)
                } else data

                val colOffset = itemIdx * templateColCount

                for (cellSpec in cellSpecs) {
                    if (cellSpec.columnIndex in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                        val targetColIdx = repeatRow.repeatStartCol + colOffset + (cellSpec.columnIndex - repeatRow.repeatStartCol)
                        val cell = row.createCell(targetColIdx)
                        styleMap[cellSpec.styleIndex]?.let { cell.cellStyle = it }
                        writeCellContentSxssf(
                            cell, cellSpec.content, itemData, imageLocations, sheetIndex,
                            context, colOffset = colShiftAmount, repeatItemIndex = itemIdx
                        )
                    }
                }
            }
        }
    }

    /**
     * 오른쪽 확장 시 수식을 조정합니다.
     */
    private fun adjustContentForRightExpansion(
        cellSpec: CellSpec,
        repeatRow: RowSpec.RepeatRow,
        items: List<*>,
        colShiftAmount: Int,
        sheet: SXSSFSheet,
        currentRowIndex: Int,
        actualColIndex: Int
    ): CellContent {
        if (cellSpec.content !is CellContent.Formula) return cellSpec.content

        val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
        var formula = cellSpec.content.formula

        if (colShiftAmount > 0) {
            formula = FormulaAdjuster.adjustForColumnExpansion(
                formula,
                repeatRow.repeatEndCol + 1,
                colShiftAmount
            )
        }

        if (cellSpec.columnIndex > repeatRow.repeatEndCol && items.size > 1) {
            val (expandedFormula, hasDiscontinuous) = FormulaAdjuster.expandSingleRefToColumnRange(
                formula,
                repeatRow.repeatStartCol,
                repeatRow.repeatEndCol,
                repeatRow.templateRowIndex,
                repeatRow.repeatEndRowIndex,
                items.size,
                templateColCount
            )
            if (expandedFormula != formula) {
                if (hasDiscontinuous && items.size > 255) {
                    throw FormulaExpansionException(
                        sheetName = sheet.sheetName,
                        cellRef = "${toColumnLetter(actualColIndex)}${currentRowIndex + 1}",
                        formula = formula
                    )
                }
                formula = expandedFormula
            }
        }

        return CellContent.Formula(formula)
    }

    private fun writeCellContentSxssf(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
        context: RenderingContext,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        when (content) {
            is CellContent.Formula -> {
                cell.cellFormula = content.formula
            }

            is CellContent.FormulaWithVariables -> {
                val substitutedFormula = context.evaluateText(content.formula, data)
                cell.cellFormula = substitutedFormula
            }

            is CellContent.ImageMarker -> {
                processImageMarker(
                    cell, content, sheetIndex, imageLocations,
                    0, colOffset, repeatItemIndex
                )
            }

            else -> {
                processCellContent(
                    cell, content, data, sheetIndex, imageLocations, context,
                    0, colOffset, repeatItemIndex
                )
            }
        }
    }
}
