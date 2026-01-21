package com.hunet.common.excel.engine

import com.hunet.common.excel.FormulaExpansionException
import com.hunet.common.excel.toByteArray
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
 * - 청사진 기반 순차 생성
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
        // 1. 템플릿 XSSFWorkbook을 열고 분석 (dxfs 등 스타일 유지를 위해 닫지 않음)
        val templateWorkbook = XSSFWorkbook(ByteArrayInputStream(templateBytes))

        try {
            val blueprint = context.analyzer.analyzeFromWorkbook(templateWorkbook)
            val styleRegistry = extractStyles(templateWorkbook)

            // 2. 템플릿 워크북의 기존 시트 내용을 모두 삭제 (시트 구조는 유지)
            for (i in 0 until templateWorkbook.numberOfSheets) {
                val sheet = templateWorkbook.getSheetAt(i)
                // 모든 행 삭제
                val lastRowNum = sheet.lastRowNum
                for (rowIdx in lastRowNum downTo 0) {
                    sheet.getRow(rowIdx)?.let { sheet.removeRow(it) }
                }
                // 병합 영역 삭제
                while (sheet.numMergedRegions > 0) {
                    sheet.removeMergedRegion(0)
                }
                // 조건부 서식 삭제
                val scf = sheet.sheetConditionalFormatting
                while (scf.numConditionalFormattings > 0) {
                    scf.removeConditionalFormatting(0)
                }
            }

            // 3. 템플릿 기반 SXSSF 워크북 생성 (dxfs 유지)
            return SXSSFWorkbook(templateWorkbook, 100).use { workbook ->
                val styleMap = styleRegistry.mapValues { (_, style) ->
                    workbook.xssfWorkbook.getCellStyleAt(style.index.toInt())
                }
                val imageLocations = mutableListOf<ImageLocation>()

                blueprint.sheets.forEachIndexed { index, sheetBlueprint ->
                    val sheet = workbook.getSheetAt(index) as SXSSFSheet
                    processSheetSxssf(sheet, sheetBlueprint, data, styleMap, imageLocations, index, context)
                }

                // SXSSF에서는 이미지를 바로 삽입
                insertImagesSxssf(workbook, imageLocations, data, context)

                // 수식 강제 재계산 플래그 설정
                for (i in 0 until workbook.numberOfSheets) {
                    workbook.getSheetAt(i).forceFormulaRecalculation = true
                }

                ByteArrayOutputStream().also { out ->
                    workbook.write(out)
                }.toByteArray()
            }
        } finally {
            templateWorkbook.close()
        }
    }

    private fun extractStyles(workbook: XSSFWorkbook): Map<Short, XSSFCellStyle> {
        val styles = mutableMapOf<Short, XSSFCellStyle>()
        for (i in 0 until workbook.numCellStyles) {
            val style = workbook.getCellStyleAt(i) as XSSFCellStyle
            styles[i.toShort()] = style
        }
        return styles
    }

    private fun processSheetSxssf(
        sheet: SXSSFSheet,
        blueprint: SheetBlueprint,
        data: Map<String, Any>,
        styleMap: Map<Short, CellStyle>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
        context: RenderingContext
    ) {
        // 열 너비 설정
        blueprint.columnWidths.forEach { (col, width) ->
            sheet.setColumnWidth(col, width)
        }

        // 헤더/푸터 설정 (변수 치환 적용)
        context.sheetLayoutApplier.applyHeaderFooter(
            sheet.workbook as SXSSFWorkbook, sheetIndex, blueprint.headerFooter, data, context.evaluateText
        )

        // 인쇄 설정 적용
        context.sheetLayoutApplier.applyPrintSetup(sheet, blueprint.printSetup)

        var currentRowIndex = 0
        var rowOffset = 0

        // 반복 영역 정보 수집
        val repeatRegions = blueprint.rows.filterIsInstance<RowBlueprint.RepeatRow>()
            .associateBy { it.templateRowIndex }

        // 행 작성 컨텍스트 생성
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

        for (rowBlueprint in blueprint.rows) {
            when (rowBlueprint) {
                is RowBlueprint.StaticRow -> {
                    writeRowSxssf(ctx, currentRowIndex, rowBlueprint, null, 0, rowOffset)
                    currentRowIndex++
                }

                is RowBlueprint.RepeatRow -> {
                    if (rowBlueprint.templateRowIndex in processedRepeatRows) continue

                    val items = data[rowBlueprint.collectionName] as? List<*> ?: emptyList<Any>()
                    val templateRowCount = rowBlueprint.repeatEndRowIndex - rowBlueprint.templateRowIndex + 1

                    when (rowBlueprint.direction) {
                        RepeatDirection.DOWN -> {
                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowBlueprint.itemVariable to item)
                                } else data

                                val itemCtx = ctx.copy(data = itemData)

                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowBlueprint.templateRowIndex + templateOffset
                                    val currentRowBp = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                        ?: continue

                                    val formulaRepeatIndex = itemIdx * templateRowCount + templateOffset

                                    writeRowSxssf(
                                        itemCtx, currentRowIndex, currentRowBp,
                                        rowBlueprint.itemVariable, formulaRepeatIndex, rowOffset,
                                        repeatRow = rowBlueprint
                                    )
                                    currentRowIndex++
                                }
                            }
                            rowOffset += (items.size * templateRowCount) - templateRowCount
                        }

                        RepeatDirection.RIGHT -> {
                            writeRowSxssfWithRightExpansion(
                                sheet, currentRowIndex, rowBlueprint, blueprint, data, items, styleMap,
                                blueprint.mergedRegions, imageLocations, sheetIndex, context
                            )
                            currentRowIndex += templateRowCount
                        }
                    }

                    for (r in rowBlueprint.templateRowIndex..rowBlueprint.repeatEndRowIndex) {
                        processedRepeatRows.add(r)
                    }
                }

                is RowBlueprint.RepeatContinuation -> {
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
        val repeatRegions: Map<Int, RowBlueprint.RepeatRow>,
        val imageLocations: MutableList<ImageLocation>,
        val context: RenderingContext
    ) {
        fun copy(data: Map<String, Any>): RowWriteContext =
            RowWriteContext(sheet, sheetIndex, data, styleMap, templateMergedRegions, repeatRegions, imageLocations, context)
    }

    private fun writeRowSxssf(
        ctx: RowWriteContext,
        rowIndex: Int,
        blueprint: RowBlueprint,
        itemVariable: String?,
        repeatIndex: Int,
        rowOffset: Int,
        repeatRow: RowBlueprint.RepeatRow? = null
    ) {
        val row = ctx.sheet.createRow(rowIndex)
        blueprint.height?.let { row.height = it }

        val repeatColRange = (blueprint as? RowBlueprint.RepeatRow)?.let {
            it.repeatStartCol..it.repeatEndCol
        } ?: repeatRow?.let {
            it.repeatStartCol..it.repeatEndCol
        }

        for (cellBlueprint in blueprint.cells) {
            if (repeatColRange != null && repeatIndex > 0) {
                if (cellBlueprint.columnIndex !in repeatColRange) {
                    continue
                }
            }

            val cell = row.createCell(cellBlueprint.columnIndex)
            ctx.styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }

            when (val content = cellBlueprint.content) {
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

                    if (rowOffset > 0 && blueprint is RowBlueprint.StaticRow) {
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
                                            cellRef = "${FormulaAdjuster.indexToColumnName(cellBlueprint.columnIndex)}${rowIndex + 1}",
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
                    val templateRow = blueprint.templateRowIndex
                    val mergedRegion = ctx.templateMergedRegions.find { region ->
                        templateRow >= region.firstRow && templateRow <= region.lastRow &&
                            cellBlueprint.columnIndex >= region.firstColumn && cellBlueprint.columnIndex <= region.lastColumn
                    }?.let { templateRegion ->
                        val rowDiff = rowIndex - templateRow
                        CellRangeAddress(
                            templateRegion.firstRow + rowDiff,
                            templateRegion.lastRow + rowDiff,
                            templateRegion.firstColumn,
                            templateRegion.lastColumn
                        )
                    }

                    ctx.imageLocations.add(
                        ImageLocation(
                            sheetIndex = ctx.sheetIndex,
                            imageName = content.imageName,
                            rowIndex = rowIndex,
                            colIndex = cellBlueprint.columnIndex,
                            mergedRegion = mergedRegion
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
        repeatRow: RowBlueprint.RepeatRow,
        blueprint: SheetBlueprint,
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

            val rowBlueprint = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
            val cellBlueprints = rowBlueprint?.cells ?: continue

            val row = sheet.createRow(currentRowIndex)
            rowBlueprint.height?.let { row.height = it }

            // 반복 영역 밖의 셀들
            for (cellBlueprint in cellBlueprints) {
                if (cellBlueprint.columnIndex !in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                    val actualColIndex = if (cellBlueprint.columnIndex > repeatRow.repeatEndCol) {
                        cellBlueprint.columnIndex + colShiftAmount
                    } else {
                        cellBlueprint.columnIndex
                    }
                    val cell = row.createCell(actualColIndex)
                    styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }

                    val adjustedContent = if (cellBlueprint.content is CellContent.Formula) {
                        var formula = (cellBlueprint.content as CellContent.Formula).formula

                        if (colShiftAmount > 0) {
                            formula = FormulaAdjuster.adjustForColumnExpansion(
                                formula,
                                repeatRow.repeatEndCol + 1,
                                colShiftAmount
                            )
                        }

                        if (cellBlueprint.columnIndex > repeatRow.repeatEndCol && items.size > 1) {
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
                                        cellRef = "${FormulaAdjuster.indexToColumnName(actualColIndex)}${currentRowIndex + 1}",
                                        formula = formula
                                    )
                                }
                                formula = expandedFormula
                            }
                        }

                        CellContent.Formula(formula)
                    } else {
                        cellBlueprint.content
                    }

                    writeCellContentSxssf(
                        cell, adjustedContent, data, null, sheet, templateMergedRegions,
                        imageLocations, sheetIndex, currentRowIndex, actualColIndex, context
                    )
                }
            }

            // 각 아이템에 대해 열 방향으로 확장
            items.forEachIndexed { itemIdx, item ->
                val itemData = if (item != null) {
                    data + (repeatRow.itemVariable to item)
                } else data

                val colOffset = itemIdx * templateColCount

                for (cellBlueprint in cellBlueprints) {
                    if (cellBlueprint.columnIndex in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                        val targetColIdx = repeatRow.repeatStartCol + colOffset + (cellBlueprint.columnIndex - repeatRow.repeatStartCol)
                        val cell = row.createCell(targetColIdx)
                        styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }
                        writeCellContentSxssf(
                            cell, cellBlueprint.content, itemData, repeatRow.itemVariable,
                            sheet, templateMergedRegions, imageLocations, sheetIndex, currentRowIndex, targetColIdx, context
                        )
                    }
                }
            }
        }
    }

    private fun writeCellContentSxssf(
        cell: org.apache.poi.ss.usermodel.Cell,
        content: CellContent,
        data: Map<String, Any>,
        itemVariable: String?,
        sheet: SXSSFSheet,
        templateMergedRegions: List<CellRangeAddress>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int,
        rowIndex: Int,
        colIndex: Int,
        context: RenderingContext
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
                imageLocations.add(
                    ImageLocation(
                        sheetIndex = sheetIndex,
                        imageName = content.imageName,
                        rowIndex = rowIndex,
                        colIndex = colIndex,
                        mergedRegion = null
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
                location.rowIndex, location.colIndex, location.mergedRegion
            )
        }
    }

    private fun setCellValue(cell: org.apache.poi.ss.usermodel.Cell, value: Any?) {
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
