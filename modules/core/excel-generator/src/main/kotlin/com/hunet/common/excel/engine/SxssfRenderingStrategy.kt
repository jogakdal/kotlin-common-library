package com.hunet.common.excel.engine

import com.hunet.common.excel.exception.FormulaExpansionException
import com.hunet.common.excel.findMergedRegion
import com.hunet.common.excel.parseCellRef
import com.hunet.common.excel.setInitialView
import com.hunet.common.excel.toColumnLetter
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.util.CellRangeAddress
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
internal class SxssfRenderingStrategy : RenderingStrategy {
    override val name: String = "SXSSF"

    override fun render(
        templateBytes: ByteArray,
        data: Map<String, Any>,
        context: RenderingContext
    ): ByteArray {
        // dxfs 스타일 유지를 위해 템플릿 워크북을 닫지 않고 재사용
        val templateWorkbook = XSSFWorkbook(ByteArrayInputStream(templateBytes))

        try {
            val blueprint = context.analyzer.analyzeFromWorkbook(templateWorkbook)
            val styleRegistry = extractStyles(templateWorkbook)

            clearSheetContents(templateWorkbook)

            return SXSSFWorkbook(templateWorkbook, 100).use { workbook ->
                val styleMap = styleRegistry.mapValues { (_, style) ->
                    workbook.xssfWorkbook.getCellStyleAt(style.index.toInt())
                }
                val imageLocations = mutableListOf<ImageLocation>()

                blueprint.sheets.forEachIndexed { index, sheetSpec ->
                    val sheet = workbook.getSheetAt(index) as SXSSFSheet
                    processSheetSxssf(sheet, sheetSpec, data, styleMap, imageLocations, index, context)
                }

                insertImagesSxssf(workbook, imageLocations, data, context)

                for (i in 0 until workbook.numberOfSheets) {
                    workbook.getSheetAt(i).forceFormulaRecalculation = true
                }

                // 파일 열 때 첫 번째 시트 A1 셀에 포커스
                workbook.setInitialView()

                ByteArrayOutputStream().also { out ->
                    workbook.write(out)
                }.toByteArray()
            }
        } finally {
            templateWorkbook.close()
        }
    }

    private fun clearSheetContents(workbook: XSSFWorkbook) {
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
        blueprint: SheetSpec,
        data: Map<String, Any>,
        styleMap: Map<Short, CellStyle>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
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
            templateMergedRegions = blueprint.mergedRegions,
            repeatRegions = repeatRegions,
            imageLocations = imageLocations,
            context = context
        )

        val processedRepeatRows = mutableSetOf<Int>()

        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    writeRowSxssf(ctx, currentRowIndex, rowSpec, null, 0, rowOffset, totalRowOffset = rowOffset)
                    currentRowIndex++
                }

                is RowSpec.RepeatRow -> {
                    if (rowSpec.templateRowIndex in processedRepeatRows) continue

                    val items = data[rowSpec.collectionName] as? List<*> ?: emptyList<Any>()
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            // 전체 반복 오프셋 (반복 완료 후의 총 오프셋)
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
                                        rowSpec.itemVariable, formulaRepeatIndex, rowOffset,
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
                                sheet, currentRowIndex, rowSpec, blueprint, data, items, styleMap,
                                blueprint.mergedRegions, imageLocations, sheetIndex, context
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
        val templateMergedRegions: List<CellRangeAddress>,
        val repeatRegions: Map<Int, RowSpec.RepeatRow>,
        val imageLocations: MutableList<ImageLocation>,
        val context: RenderingContext
    ) {
        fun copy(data: Map<String, Any>): RowWriteContext =
            RowWriteContext(sheet, sheetIndex, data, styleMap, templateMergedRegions, repeatRegions, imageLocations, context)
    }

    private fun writeRowSxssf(
        ctx: RowWriteContext,
        rowIndex: Int,
        blueprint: RowSpec,
        itemVariable: String?,
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

            when (val content = cellSpec.content) {
                is CellContent.Empty -> {}

                is CellContent.StaticString -> {
                    val evaluated = ctx.context.evaluateText(content.value, ctx.data)
                    cell.setCellValue(evaluated)
                }

                is CellContent.StaticNumber -> cell.setCellValue(content.value)

                is CellContent.StaticBoolean -> cell.setCellValue(content.value)

                is CellContent.Variable -> {
                    val evaluated = ctx.context.evaluateText(content.originalText, ctx.data)
                    setCellValue(cell, evaluated)
                }

                is CellContent.ItemField -> {
                    val item = ctx.data[content.itemVariable]
                    val value = ctx.context.resolveFieldPath(item, content.fieldPath)
                    setCellValue(cell, value)
                }

                is CellContent.Formula -> {
                    var adjustedFormula = content.formula
                    var formulaExpanded = false

                    if (repeatIndex > 0) {
                        adjustedFormula = FormulaAdjuster.adjustForRepeatIndex(adjustedFormula, repeatIndex)
                    }

                    if (rowOffset > 0 && blueprint is RowSpec.StaticRow) {
                        val repeatEndRow = ctx.repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
                        if (repeatEndRow >= 0 && blueprint.templateRowIndex > repeatEndRow) {
                            for (repeatRegion in ctx.repeatRegions.values) {
                                val items = ctx.data[repeatRegion.collectionName] as? List<*> ?: continue
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
                                            sheetName = ctx.sheet.sheetName,
                                            cellRef = "${toColumnLetter(cellSpec.columnIndex)}${rowIndex + 1}",
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

                is CellContent.FormulaWithVariables -> {
                    var substitutedFormula = ctx.context.evaluateText(content.formula, ctx.data)

                    if (repeatIndex > 0) {
                        substitutedFormula = FormulaAdjuster.adjustForRepeatIndex(substitutedFormula, repeatIndex)
                    }

                    cell.cellFormula = substitutedFormula
                }

                is CellContent.ImageMarker -> {
                    // position이 지정된 이미지는 첫 번째 반복에서만 처리 (중복 방지)
                    if (content.position != null && repeatItemIndex > 0) {
                        cell.setBlank()
                        continue@cellLoop
                    }

                    val (targetRow, targetCol) = if (content.position != null) {
                        val (baseRow, baseCol) = parseCellRef(content.position)
                        (baseRow + totalRowOffset) to baseCol
                    } else {
                        rowIndex to cellSpec.columnIndex
                    }

                    ctx.imageLocations.add(
                        ImageLocation(
                            sheetIndex = ctx.sheetIndex,
                            imageName = content.imageName,
                            rowIndex = targetRow,
                            colIndex = targetCol,
                            sizeSpec = content.sizeSpec
                        )
                    )
                    cell.setBlank()
                }

                is CellContent.RepeatMarker -> {
                    cell.setBlank()
                }
            }
        }
    }

    private fun writeRowSxssfWithRightExpansion(
        sheet: SXSSFSheet,
        startRowIndex: Int,
        repeatRow: RowSpec.RepeatRow,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        items: List<*>,
        styleMap: Map<Short, CellStyle>,
        templateMergedRegions: List<CellRangeAddress>,
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

                    val adjustedContent = if (cellSpec.content is CellContent.Formula) {
                        var formula = (cellSpec.content as CellContent.Formula).formula

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

                        CellContent.Formula(formula)
                    } else {
                        cellSpec.content
                    }

                    writeCellContentSxssf(
                        cell, adjustedContent, data, null, sheet, templateMergedRegions,
                        imageLocations, sheetIndex, currentRowIndex, actualColIndex, context,
                        colOffset = colShiftAmount
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
                            cell, cellSpec.content, itemData, repeatRow.itemVariable,
                            sheet, templateMergedRegions, imageLocations, sheetIndex, currentRowIndex, targetColIdx, context,
                            colOffset = colShiftAmount,
                            repeatItemIndex = itemIdx
                        )
                    }
                }
            }
        }
    }

    private fun writeCellContentSxssf(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        itemVariable: String?,
        sheet: SXSSFSheet,
        templateMergedRegions: List<CellRangeAddress>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
        rowIndex: Int,
        colIndex: Int,
        context: RenderingContext,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        when (content) {
            is CellContent.Empty -> {}

            is CellContent.StaticString -> {
                val evaluated = context.evaluateText(content.value, data)
                cell.setCellValue(evaluated)
            }

            is CellContent.StaticNumber -> cell.setCellValue(content.value)

            is CellContent.StaticBoolean -> cell.setCellValue(content.value)

            is CellContent.Variable -> {
                val evaluated = context.evaluateText(content.originalText, data)
                setCellValue(cell, evaluated)
            }

            is CellContent.ItemField -> {
                val item = data[content.itemVariable]
                val value = context.resolveFieldPath(item, content.fieldPath)
                setCellValue(cell, value)
            }

            is CellContent.Formula -> {
                cell.cellFormula = content.formula
            }

            is CellContent.FormulaWithVariables -> {
                val substitutedFormula = context.evaluateText(content.formula, data)
                cell.cellFormula = substitutedFormula
            }

            is CellContent.ImageMarker -> {
                // position이 지정된 이미지는 첫 번째 반복에서만 처리 (중복 방지)
                if (content.position != null && repeatItemIndex > 0) {
                    cell.setBlank()
                    return
                }

                val (targetRow, targetCol) = if (content.position != null) {
                    val (baseRow, baseCol) = parseCellRef(content.position)
                    baseRow to (baseCol + colOffset)
                } else {
                    rowIndex to colIndex
                }

                imageLocations.add(
                    ImageLocation(
                        sheetIndex = sheetIndex,
                        imageName = content.imageName,
                        rowIndex = targetRow,
                        colIndex = targetCol,
                        sizeSpec = content.sizeSpec
                    )
                )
                cell.setBlank()
            }

            is CellContent.RepeatMarker -> {
                cell.setBlank()
            }
        }
    }

    private fun insertImagesSxssf(
        workbook: SXSSFWorkbook,
        imageLocations: List<ImageLocation>,
        data: Map<String, Any>,
        context: RenderingContext
    ) {
        for (location in imageLocations) {
            val imageBytes = data["image.${location.imageName}"] as? ByteArray
                ?: data[location.imageName] as? ByteArray
                ?: continue

            val sheet = workbook.getSheetAt(location.sheetIndex)

            context.imageInserter.insertImage(
                workbook, sheet, imageBytes,
                location.rowIndex, location.colIndex,
                location.sizeSpec,
                sheet.findMergedRegion(location.rowIndex, location.colIndex)
            )
        }
    }

    private fun setCellValue(cell: Cell, value: Any?) {
        when (value) {
            null -> cell.setBlank()
            is String -> cell.setCellValue(value)
            is Number -> cell.setCellValue(value.toDouble())
            is Boolean -> cell.setCellValue(value)
            is java.time.LocalDate -> cell.setCellValue(value)
            is java.time.LocalDateTime -> cell.setCellValue(value)
            is java.util.Date -> cell.setCellValue(value)
            else -> cell.setCellValue(value.toString())
        }
    }
}
