package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.exception.FormulaExpansionException
import com.hunet.common.tbeg.engine.core.parseCellRef
import com.hunet.common.tbeg.engine.core.removeAbsPath
import com.hunet.common.tbeg.engine.core.setInitialView
import com.hunet.common.tbeg.engine.core.toColumnLetter
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
 * - PositionCalculator를 사용하여 repeat 확장 위치 계산
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

        // repeat 영역 추출
        val repeatRows = blueprint.rows.filterIsInstance<RowSpec.RepeatRow>()
        val repeatRegions = repeatRows.map { row ->
            RepeatRegionSpec(
                row.collectionName, row.itemVariable,
                row.templateRowIndex, row.repeatEndRowIndex,
                row.repeatStartCol, row.repeatEndCol, row.direction
            )
        }

        // repeat 영역 겹침 검증
        PositionCalculator.validateNoOverlap(repeatRegions)

        // collection 크기 추출 및 PositionCalculator 생성
        val collectionSizes = PositionCalculator.extractCollectionSizes(data, repeatRegions)
        val calculator = PositionCalculator(repeatRegions, collectionSizes)
        calculator.calculate()

        val repeatRegionsMap = repeatRows.associateBy { it.templateRowIndex }

        val ctx = RowWriteContext(
            sheet = sheet,
            sheetIndex = sheetIndex,
            data = data,
            styleMap = styleMap,
            repeatRegions = repeatRegionsMap,
            imageLocations = imageLocations,
            context = context,
            calculator = calculator
        )

        // SXSSF는 행을 순차적으로 작성해야 하므로, 모든 행 정보를 먼저 수집하고 정렬
        val pendingRows = java.util.TreeMap<Int, MutableList<PendingCell>>()
        val processedRepeatRows = mutableSetOf<Int>()

        // 1단계: 모든 행 정보 수집
        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    writeCellsAtCalculatedPositions(ctx, rowSpec, mutableSetOf(), pendingRows)
                }

                is RowSpec.RepeatRow -> {
                    if (rowSpec.templateRowIndex in processedRepeatRows) continue

                    val items = data[rowSpec.collectionName] as? List<*> ?: emptyList<Any>()
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1
                    val expansion = calculator.getExpansionForRegion(
                        rowSpec.collectionName, rowSpec.templateRowIndex, rowSpec.repeatStartCol
                    )

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            val totalRepeatOffset = expansion?.rowExpansion ?: ((items.size * templateRowCount) - templateRowCount)

                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowSpec.itemVariable to item)
                                } else data

                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowSpec.templateRowIndex + templateOffset
                                    val currentRowBp = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                        ?: continue

                                    val formulaRepeatIndex = itemIdx * templateRowCount + templateOffset

                                    // PositionCalculator를 사용하여 실제 행 위치 계산
                                    val actualRow = if (expansion != null) {
                                        calculator.getRowForRepeatItem(expansion, itemIdx, templateOffset)
                                    } else {
                                        rowSpec.templateRowIndex + (itemIdx * templateRowCount) + templateOffset
                                    }

                                    // 반복 영역 내 셀만 수집 (열 그룹 범위)
                                    val repeatColRange = rowSpec.repeatStartCol..rowSpec.repeatEndCol

                                    for (cellSpec in currentRowBp.cells) {
                                        // 첫 번째 아이템에서만 열 범위 외 셀 포함, 이후는 열 범위 내만
                                        if (itemIdx > 0 && cellSpec.columnIndex !in repeatColRange) continue

                                        pendingRows.getOrPut(actualRow) { mutableListOf() }.add(
                                            PendingCell(
                                                columnIndex = cellSpec.columnIndex,
                                                styleIndex = cellSpec.styleIndex,
                                                content = cellSpec.content,
                                                height = currentRowBp.height,
                                                templateRowIndex = currentRowBp.templateRowIndex,
                                                rowSpec = currentRowBp,
                                                itemData = itemData,
                                                repeatIndex = formulaRepeatIndex,
                                                repeatItemIndex = itemIdx,
                                                totalRowOffset = totalRepeatOffset,
                                                repeatRow = rowSpec
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        RepeatDirection.RIGHT -> {
                            // RIGHT 방향은 별도 처리 (writeRowSxssfWithRightExpansion)
                            val (actualRow, _) = calculator.getFinalPosition(rowSpec.templateRowIndex, rowSpec.repeatStartCol)
                            writeRowSxssfWithRightExpansion(
                                sheet, actualRow, rowSpec, blueprint, data, items,
                                imageLocations, sheetIndex, context, calculator
                            )
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

        // 2단계: 행 인덱스 순서대로 셀 작성 (TreeMap이 자동 정렬)
        for ((actualRow, cells) in pendingRows) {
            val row = sheet.getRow(actualRow) ?: sheet.createRow(actualRow)

            // 같은 행의 첫 번째 셀에서 높이 설정
            cells.firstOrNull()?.height?.let { row.height = it }

            for (pendingCell in cells) {
                val cell = row.createCell(pendingCell.columnIndex)
                styleMap[pendingCell.styleIndex]?.let { cell.cellStyle = it }

                val cellData = pendingCell.itemData ?: data

                processCellContentSxssfWithCalculator(
                    cell, pendingCell.content, cellData, sheetIndex,
                    imageLocations, context,
                    repeatIndex = pendingCell.repeatIndex,
                    totalRowOffset = pendingCell.totalRowOffset,
                    repeatItemIndex = pendingCell.repeatItemIndex,
                    repeatRegionsMap, pendingCell.rowSpec, pendingCell.columnIndex, actualRow, calculator
                )
            }
        }

        // 전체 행 오프셋 계산 (병합 영역, 조건부 서식용)
        val maxRowOffset = calculator.getExpansions()
            .filter { it.region.direction == RepeatDirection.DOWN }
            .sumOf { it.rowExpansion }

        // 병합 영역 설정 - PositionCalculator 사용
        context.sheetLayoutApplier.applyMergedRegionsWithCalculator(
            sheet, blueprint.mergedRegions, calculator
        )

        // 조건부 서식 적용
        context.sheetLayoutApplier.applyConditionalFormattings(
            sheet, blueprint.conditionalFormattings, repeatRegionsMap, data, maxRowOffset
        )
    }

    private data class RowWriteContext(
        val sheet: SXSSFSheet,
        val sheetIndex: Int,
        val data: Map<String, Any>,
        val styleMap: Map<Short, CellStyle>,
        val repeatRegions: Map<Int, RowSpec.RepeatRow>,
        val imageLocations: MutableList<ImageLocation>,
        val context: RenderingContext,
        val calculator: PositionCalculator
    ) {
        fun copy(data: Map<String, Any>): RowWriteContext =
            RowWriteContext(sheet, sheetIndex, data, styleMap, repeatRegions, imageLocations, context, calculator)
    }

    /**
     * StaticRow의 각 셀을 PositionCalculator로 계산된 위치에 작성합니다.
     *
     * 독립적인 열 그룹에 속한 셀들은 서로 다른 행에 배치될 수 있습니다.
     * 예: 템플릿 8행의 셀 중 A열은 10행으로, F열은 8행으로 배치
     *
     * SXSSF는 순차적 행 작성만 지원하므로, 이 메서드는 셀 정보만 수집하고
     * 실제 작성은 processSheetSxssf의 정렬된 루프에서 수행됩니다.
     */
    private fun writeCellsAtCalculatedPositions(
        ctx: RowWriteContext,
        rowSpec: RowSpec.StaticRow,
        createdRows: MutableSet<Int>,
        pendingCells: MutableMap<Int, MutableList<PendingCell>>
    ) {
        // 각 셀의 실제 행 위치를 계산하여 수집 (나중에 순서대로 작성)
        for (cellSpec in rowSpec.cells) {
            val (actualRow, _) = ctx.calculator.getFinalPosition(rowSpec.templateRowIndex, cellSpec.columnIndex)
            pendingCells.getOrPut(actualRow) { mutableListOf() }.add(
                PendingCell(
                    columnIndex = cellSpec.columnIndex,
                    styleIndex = cellSpec.styleIndex,
                    content = cellSpec.content,
                    height = rowSpec.height,
                    templateRowIndex = rowSpec.templateRowIndex,
                    rowSpec = rowSpec
                )
            )
        }
    }

    /**
     * 대기 중인 셀 정보 (SXSSF 순차 작성용)
     */
    private data class PendingCell(
        val columnIndex: Int,
        val styleIndex: Short,
        val content: CellContent,
        val height: Short?,
        val templateRowIndex: Int,
        val rowSpec: RowSpec,
        val itemData: Map<String, Any>? = null,
        val repeatIndex: Int = 0,
        val repeatItemIndex: Int = 0,
        val totalRowOffset: Int = 0,
        val repeatRow: RowSpec.RepeatRow? = null
    )

    /**
     * 지정된 위치에 행을 작성합니다. (PositionCalculator 기반)
     *
     * 이미 생성된 행이 있으면 해당 행에 셀을 추가합니다.
     * 이를 통해 독립적인 열 그룹의 셀들이 같은 행에 올바르게 배치됩니다.
     */
    private fun writeRowSxssfAtPosition(
        ctx: RowWriteContext,
        actualRowIndex: Int,
        blueprint: RowSpec,
        repeatIndex: Int,
        createdRows: Set<Int>,
        repeatRow: RowSpec.RepeatRow? = null,
        repeatItemIndex: Int = 0,
        totalRowOffset: Int = 0
    ) {
        // 이미 존재하는 행이면 가져오고, 없으면 생성
        val row = ctx.sheet.getRow(actualRowIndex) ?: ctx.sheet.createRow(actualRowIndex)
        blueprint.height?.let { row.height = it }

        val repeatColRange = (blueprint as? RowSpec.RepeatRow)?.let {
            it.repeatStartCol..it.repeatEndCol
        } ?: repeatRow?.let {
            it.repeatStartCol..it.repeatEndCol
        }

        for (cellSpec in blueprint.cells) {
            if (repeatColRange != null && repeatIndex > 0) {
                if (cellSpec.columnIndex !in repeatColRange) {
                    continue
                }
            }

            val cell = row.createCell(cellSpec.columnIndex)
            ctx.styleMap[cellSpec.styleIndex]?.let { cell.cellStyle = it }

            processCellContentSxssfWithCalculator(
                cell, cellSpec.content, ctx.data, ctx.sheetIndex,
                ctx.imageLocations, ctx.context,
                repeatIndex, totalRowOffset, repeatItemIndex,
                ctx.repeatRegions, blueprint, cellSpec.columnIndex, actualRowIndex, ctx.calculator
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
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
                ctx.repeatRegions, blueprint, cellSpec.columnIndex, rowIndex, ctx.calculator
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
        rowIndex: Int,
        calculator: PositionCalculator
    ) {
        when (content) {
            is CellContent.Formula -> {
                // SXSSF에서 수식은 수동 조정 필요
                processFormulaSxssf(
                    cell, content, data,
                    repeatIndex, rowOffset, repeatRegions, blueprint, columnIndex, rowIndex, calculator
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
     * SXSSF용 셀 내용 처리 (PositionCalculator 기반).
     * 독립적인 열 그룹을 고려하여 수식 및 위치를 조정합니다.
     */
    private fun processCellContentSxssfWithCalculator(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        repeatIndex: Int,
        totalRowOffset: Int,
        repeatItemIndex: Int,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
        blueprint: RowSpec,
        columnIndex: Int,
        actualRowIndex: Int,
        calculator: PositionCalculator
    ) {
        when (content) {
            is CellContent.Formula -> {
                // PositionCalculator 기반 수식 조정
                processFormulaSxssfWithCalculator(
                    cell, content, data,
                    repeatIndex, repeatRegions, blueprint, columnIndex, actualRowIndex, calculator
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
                // PositionCalculator를 사용하여 이미지 위치 조정
                processImageMarkerWithCalculator(
                    cell, content, sheetIndex, imageLocations,
                    repeatItemIndex, calculator
                )
            }

            else -> {
                processCellContent(
                    cell, content, data, sheetIndex, imageLocations, context,
                    totalRowOffset, 0, repeatItemIndex
                )
            }
        }
    }

    /**
     * SXSSF에서 이미지 마커를 처리합니다 (PositionCalculator 기반).
     * 이미지 위치의 셀 참조를 PositionCalculator로 조정합니다.
     */
    private fun processImageMarkerWithCalculator(
        cell: Cell,
        content: CellContent.ImageMarker,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        repeatItemIndex: Int,
        calculator: PositionCalculator
    ) {
        // position이 지정된 이미지는 첫 번째 반복에서만 처리 (중복 방지)
        if (content.position != null && repeatItemIndex > 0) {
            cell.setBlank()
            return
        }

        // PositionCalculator를 사용하여 position 조정
        val adjustedPosition = content.position?.let { position ->
            adjustPositionWithCalculator(position, calculator)
        }

        imageLocations.add(
            ImageLocation(
                sheetIndex = sheetIndex,
                imageName = content.imageName,
                position = adjustedPosition,
                markerRowIndex = cell.rowIndex,
                markerColIndex = cell.columnIndex,
                sizeSpec = content.sizeSpec
            )
        )
        cell.setBlank()
    }

    /**
     * PositionCalculator를 사용하여 위치/범위 문자열을 조정합니다.
     * 단일 셀: "C10" → 해당 열의 오프셋 적용
     * 범위: "A10:B10" → 포함된 모든 열의 최대 오프셋 적용 (병합 셀 지원)
     */
    private fun adjustPositionWithCalculator(position: String, calculator: PositionCalculator): String {
        return if (position.contains(":")) {
            // 범위: getFinalRange로 최대 오프셋 적용
            val (start, end) = position.split(":")
            val (startRow, startCol) = parseCellRef(start)
            val (endRow, endCol) = parseCellRef(end)
            val finalRange = calculator.getFinalRange(startRow, endRow, startCol, endCol)
            "${toColumnName(finalRange.firstColumn)}${finalRange.firstRow + 1}:" +
                "${toColumnName(finalRange.lastColumn)}${finalRange.lastRow + 1}"
        } else {
            // 단일 셀: 해당 위치의 오프셋 적용
            adjustCellRefWithCalculator(position, calculator)
        }
    }

    /**
     * PositionCalculator를 사용하여 셀 참조를 조정합니다.
     */
    private fun adjustCellRefWithCalculator(ref: String, calculator: PositionCalculator): String {
        val (row, col) = parseCellRef(ref)
        val (newRow, newCol) = calculator.getFinalPosition(row, col)
        return toColumnName(newCol) + (newRow + 1)
    }

    /**
     * SXSSF에서 수식을 처리합니다 (PositionCalculator 기반).
     * 독립적인 열 그룹을 고려하여 수식 참조를 조정합니다.
     */
    private fun processFormulaSxssfWithCalculator(
        cell: Cell,
        content: CellContent.Formula,
        data: Map<String, Any>,
        repeatIndex: Int,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
        blueprint: RowSpec,
        columnIndex: Int,
        actualRowIndex: Int,
        calculator: PositionCalculator
    ) {
        var adjustedFormula = content.formula

        // repeat 반복 시 수식 행 조정
        if (repeatIndex > 0) {
            adjustedFormula = FormulaAdjuster.adjustForRepeatIndex(adjustedFormula, repeatIndex)
        }

        // StaticRow의 수식은 PositionCalculator로 참조 조정
        if (blueprint is RowSpec.StaticRow) {
            // 수식 내 참조를 PositionCalculator로 조정
            adjustedFormula = FormulaAdjuster.adjustWithPositionCalculator(
                adjustedFormula, calculator, blueprint.templateRowIndex, columnIndex
            )

            // SUM 등 집계 함수의 범위 확장
            for (repeatRegion in repeatRegions.values) {
                val items = data[repeatRegion.collectionName] as? List<*> ?: continue
                val expansion = calculator.getExpansionFor(repeatRegion.collectionName)

                if (expansion != null && items.size > 1) {
                    val (expanded, hasDiscontinuous) = FormulaAdjuster.expandToRangeWithCalculator(
                        adjustedFormula, expansion, items.size
                    )
                    if (expanded != adjustedFormula) {
                        if (hasDiscontinuous && items.size > 255) {
                            throw FormulaExpansionException(
                                sheetName = cell.sheet.sheetName,
                                cellRef = "${toColumnLetter(columnIndex)}${actualRowIndex + 1}",
                                formula = content.formula
                            )
                        }
                        adjustedFormula = expanded
                    }
                }
            }
        }

        try {
            cell.cellFormula = adjustedFormula
        } catch (e: Exception) {
            // 수식 설정 실패 시 원본 유지 시도
            runCatching { cell.cellFormula = content.formula }
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
        rowIndex: Int,
        calculator: PositionCalculator
    ) {
        var adjustedFormula = content.formula
        var formulaExpanded = false

        if (repeatIndex > 0) {
            adjustedFormula = FormulaAdjuster.adjustForRepeatIndex(adjustedFormula, repeatIndex)
        }

        if (rowOffset > 0 && blueprint is RowSpec.StaticRow) {
            val repeatEndRow = repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
            if (repeatEndRow >= 0 && blueprint.templateRowIndex > repeatEndRow) {
                // PositionCalculator를 사용하여 수식 확장
                for (repeatRegion in repeatRegions.values) {
                    val items = data[repeatRegion.collectionName] as? List<*> ?: continue
                    val expansion = calculator.getExpansionFor(repeatRegion.collectionName)

                    if (expansion != null && items.size > 1) {
                        val (expanded, hasDiscontinuous) = FormulaAdjuster.expandToRangeWithCalculator(
                            adjustedFormula, expansion, items.size
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
        context: RenderingContext,
        calculator: PositionCalculator
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
                        cellSpec, repeatRow, items, colShiftAmount, sheet, currentRowIndex, actualColIndex, calculator
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
        actualColIndex: Int,
        calculator: PositionCalculator
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
            val expansion = calculator.getExpansionFor(repeatRow.collectionName)
            if (expansion != null) {
                val (expandedFormula, hasDiscontinuous) = FormulaAdjuster.expandToRangeWithCalculator(
                    formula, expansion, items.size
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
