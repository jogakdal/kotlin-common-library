package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.engine.core.*
import org.apache.poi.ss.formula.FormulaParseException
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream

/**
 * XSSF 기반 비스트리밍 렌더링 전략.
 *
 * 특징:
 * - 전체 워크북을 메모리에 로드
 * - PositionCalculator를 사용하여 repeat 확장 위치 계산
 * - 셀 복사 방식으로 템플릿 행 복사 (수식 자동 조정)
 * - evaluateAll()로 수식 재계산
 *
 * 장점:
 * - 모든 POI 기능 사용 가능
 * - 독립적인 열 그룹 간 영향 없음
 *
 * 단점:
 * - 대용량 데이터 시 메모리 사용량 증가
 */
internal class XssfRenderingStrategy : AbstractRenderingStrategy() {
    companion object {
        private val REPEAT_MARKER_PATTERN = Regex("""\$\{repeat\s*\(""", RegexOption.IGNORE_CASE)
        private val FORMULA_MARKER_PATTERN = Regex("""TBEG_(REPEAT|IMAGE|SIZE)\s*\(""", RegexOption.IGNORE_CASE)
    }

    /** repeat 영역을 고유하게 식별하기 위한 키 (같은 collection이 여러 위치에서 사용될 때) */
    private data class RepeatKey(val collection: String, val startRow: Int, val startCol: Int)

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
    ) = XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
        block(workbook, workbook)
    }

    override fun processSheet(
        sheet: Sheet,
        sheetIndex: Int,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) = processSheetXssf(sheet as XSSFSheet, blueprint, data, imageLocations, context)

    override fun afterProcessSheets(workbook: Workbook, context: RenderingContext) {
        val xssfWorkbook = workbook as XSSFWorkbook

        // 수식 재계산
        xssfWorkbook.creationHelper.createFormulaEvaluator().evaluateAll()

        // calcChain 비우기 (반복 확장 후 불일치 상태 방지)
        clearCalcChain(xssfWorkbook)

        // 파일 열 때 첫 번째 시트 A1 셀에 포커스
        xssfWorkbook.setInitialView()
    }

    override fun finalizeWorkbook(workbook: Workbook) =
        (workbook as XSSFWorkbook).toByteArray()

    // ========== XSSF 특화 로직 ==========

    private fun processSheetXssf(
        sheet: XSSFSheet,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        // 반복 영역 추출
        val repeatRows = blueprint.rows.filterIsInstance<RowSpec.RepeatRow>()
        val repeatRegions = repeatRows.map { row ->
            RepeatRegionSpec(
                row.collectionName, row.itemVariable,
                row.templateRowIndex, row.repeatEndRowIndex,
                row.repeatStartCol, row.repeatEndCol, row.direction,
                row.emptyRangeSpec
            )
        }

        // repeat 영역 겹침 검증
        PositionCalculator.validateNoOverlap(repeatRegions)

        // collection 크기 추출 및 PositionCalculator 생성
        val collectionSizes = PositionCalculator.extractCollectionSizes(data, repeatRegions)
        val calculator = PositionCalculator(repeatRegions, collectionSizes)

        // 기존 방식과의 호환성을 위해 열 그룹도 계산
        val columnGroups = ColumnGroup.fromRepeatRegions(repeatRegions)

        // 각 repeat 영역이 속한 열 그룹 매핑 (region 시작 위치 기반)
        val repeatToColumnGroup = mutableMapOf<RepeatKey, ColumnGroup?>()
        for (repeatRow in repeatRows) {
            if (repeatRow.direction == RepeatDirection.DOWN) {
                val key = RepeatKey(repeatRow.collectionName, repeatRow.templateRowIndex, repeatRow.repeatStartCol)
                repeatToColumnGroup[key] = columnGroups.find { group ->
                    group.repeatRegions.any {
                        it.collection == repeatRow.collectionName &&
                            it.startRow == repeatRow.templateRowIndex &&
                            it.startCol == repeatRow.repeatStartCol
                    }
                }
            }
        }

        // 반복 영역 확장 (뒤에서부터 처리하여 인덱스 꼬임 방지)
        val rowOffsets = mutableMapOf<Int, Int>()

        for (repeatRow in repeatRows.reversed()) {
            val rawItems = data[repeatRow.collectionName] as? Collection<*> ?: continue
            // 빈 컬렉션이면 최소 1개 반복 단위(빈 행/열)를 위해 null 아이템 추가
            val items: Collection<Any?> = rawItems.ifEmpty { listOf(null) }
            val expansion = calculator.getExpansionForRegion(
                repeatRow.collectionName, repeatRow.templateRowIndex, repeatRow.repeatStartCol
            ) ?: continue

            when (repeatRow.direction) {
                RepeatDirection.DOWN -> {
                    val key = RepeatKey(repeatRow.collectionName, repeatRow.templateRowIndex, repeatRow.repeatStartCol)
                    val columnGroup = repeatToColumnGroup[key]
                    expandRowsDownWithCalculator(
                        sheet, repeatRow, items, blueprint, context, columnGroup, expansion
                    )
                    rowOffsets[repeatRow.templateRowIndex] = expansion.rowExpansion
                    context.repeatExpansionProcessor.expandFormulasAfterRepeat(
                        sheet, repeatRow, items.size,
                        repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1,
                        expansion.rowExpansion
                    )
                }

                RepeatDirection.RIGHT -> {
                    val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
                    val colsToInsert = expansion.colExpansion

                    if (colsToInsert > 0) {
                        context.repeatExpansionProcessor.expandColumnsRight(sheet, repeatRow, items.size)
                    }

                    context.repeatExpansionProcessor.expandFormulasAfterRightRepeat(
                        sheet, repeatRow, items.size, templateColCount, colsToInsert
                    )
                }
            }
        }

        substituteVariablesXssfWithCalculator(
            sheet, blueprint, data, rowOffsets, imageLocations, context,
            columnGroups, repeatToColumnGroup, calculator
        )

        // emptyRange 원본 셀 클리어 (같은 시트에 있는 경우만)
        clearEmptyRangeCells(sheet, repeatRows)
    }

    /**
     * emptyRange 원본 셀들의 내용과 스타일을 클리어한다.
     * emptyRange가 같은 시트를 참조하는 경우에만 해당 범위의 셀들을 빈 셀로 만든다.
     */
    private fun clearEmptyRangeCells(sheet: XSSFSheet, repeatRows: List<RowSpec.RepeatRow>) {
        val defaultStyle = sheet.workbook.getCellStyleAt(0)
        for (row in repeatRows) {
            val emptyRange = row.emptyRangeSpec ?: continue
            // 같은 시트인 경우만 처리 (sheetName이 null이면 같은 시트)
            if (emptyRange.sheetName != null && emptyRange.sheetName != sheet.sheetName) continue

            for (rowIdx in emptyRange.startRow..emptyRange.endRow) {
                val sheetRow = sheet.getRow(rowIdx) ?: continue
                for (colIdx in emptyRange.startCol..emptyRange.endCol) {
                    val cell = sheetRow.getCell(colIdx) ?: continue
                    cell.setBlank()
                    cell.cellStyle = defaultStyle
                }
            }
        }
    }

    /**
     * 행을 아래 방향으로 확장한다. (PositionCalculator 사용)
     */
    private fun expandRowsDownWithCalculator(
        sheet: XSSFSheet,
        repeatRow: RowSpec.RepeatRow,
        items: Collection<*>,
        blueprint: SheetSpec,
        context: RenderingContext,
        columnGroup: ColumnGroup?,
        expansion: PositionCalculator.RepeatExpansion
    ) {
        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
        val rowsToInsert = expansion.rowExpansion

        if (rowsToInsert > 0) {
            val insertPosition = repeatRow.repeatEndRowIndex + 1

            if (columnGroup != null && insertPosition <= sheet.lastRowNum) {
                // 열 그룹이 있는 경우: 해당 열 범위만 선택적으로 이동
                shiftRowsInColumnRange(
                    sheet, insertPosition, sheet.lastRowNum, rowsToInsert,
                    columnGroup.startCol, columnGroup.endCol
                )
            } else if (columnGroup == null && insertPosition <= sheet.lastRowNum) {
                // 열 그룹이 없는 경우 (단일 repeat): 기존 방식 사용
                sheet.shiftRows(insertPosition, sheet.lastRowNum, rowsToInsert)
            }

            for (itemIdx in 1 until items.size) {
                for (templateOffset in 0 until templateRowCount) {
                    val templateRowIndex = repeatRow.templateRowIndex + templateOffset
                    val templateRow = sheet.getRow(templateRowIndex)
                    val newRowIndex = repeatRow.templateRowIndex + (itemIdx * templateRowCount) + templateOffset
                    val newRow = sheet.getRow(newRowIndex) ?: sheet.createRow(newRowIndex)

                    if (templateRow != null) {
                        // 열 그룹 범위 내의 셀만 복사
                        val colStart = columnGroup?.startCol ?: repeatRow.repeatStartCol
                        val colEnd = columnGroup?.endCol ?: repeatRow.repeatEndCol

                        for (colIdx in colStart..minOf(colEnd, templateRow.lastCellNum.toInt())) {
                            val templateCell = templateRow.getCell(colIdx) ?: continue
                            if (colIdx !in repeatRow.repeatStartCol..repeatRow.repeatEndCol) continue
                            // 템플릿 셀이 BLANK면 새 셀을 생성하지 않음 (SXSSF와 동일하게)
                            if (templateCell.cellType == CellType.BLANK) continue

                            val newCell = newRow.createCell(colIdx)
                            newCell.cellStyle = templateCell.cellStyle
                            copyCellValue(templateCell, newCell)
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
     * 지정된 열 범위 내의 셀만 아래로 이동시킵니다.
     * 다른 열 범위의 셀은 원래 위치에 유지된다.
     */
    private fun shiftRowsInColumnRange(
        sheet: XSSFSheet,
        startRow: Int,
        endRow: Int,
        shiftAmount: Int,
        startCol: Int,
        endCol: Int
    ) {
        // 아래에서 위로 처리하여 덮어쓰기 방지
        for (rowIdx in endRow downTo startRow) {
            val sourceRow = sheet.getRow(rowIdx) ?: continue
            val targetRowIdx = rowIdx + shiftAmount
            val targetRow = sheet.getRow(targetRowIdx) ?: sheet.createRow(targetRowIdx)

            // 지정된 열 범위의 셀만 이동
            for (colIdx in startCol..minOf(endCol, sourceRow.lastCellNum.toInt())) {
                val sourceCell = sourceRow.getCell(colIdx) ?: continue

                // BLANK 셀은 타겟에 생성하지 않고 원본만 제거
                if (sourceCell.cellType == CellType.BLANK) {
                    sourceRow.removeCell(sourceCell)
                    continue
                }

                val targetCell = targetRow.createCell(colIdx)
                targetCell.cellStyle = sourceCell.cellStyle
                copyCellValue(sourceCell, targetCell)

                // 원래 위치의 셀 비우기
                sourceRow.removeCell(sourceCell)
            }
        }
    }

    /**
     * 셀 값 복사 (타입에 따라)
     */
    private fun copyCellValue(source: Cell, target: Cell) = when (source.cellType) {
        CellType.STRING -> target.setCellValue(source.stringCellValue)
        CellType.NUMERIC -> target.setCellValue(source.numericCellValue)
        CellType.BOOLEAN -> target.setCellValue(source.booleanCellValue)
        CellType.FORMULA -> runCatching {
            target.cellFormula = source.cellFormula
        }.onFailure {
            // TBEG 마커 수식(예: TBEG_SIZE(collection))은 Named Range 검증 실패 가능
            // 문자열로 임시 저장 (나중에 템플릿 처리 시 실제 값으로 치환됨)
            target.setCellValue("=${source.cellFormula}")
        }
        else -> {} // BLANK 셀은 생성하지 않음 (호출 전에 체크됨)
    }

    /**
     * 시트에서 모든 TBEG 마커 셀을 비웁니다.
     * shiftRows 전에 호출하여 POI 경고를 방지한다.
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

    /**
     * 변수 치환 (PositionCalculator 사용)
     */
    private fun substituteVariablesXssfWithCalculator(
        sheet: XSSFSheet,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        rowOffsets: Map<Int, Int>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        columnGroups: List<ColumnGroup>,
        repeatToColumnGroup: Map<RepeatKey, ColumnGroup?>,
        calculator: PositionCalculator
    ) {
        val sheetIndex = sheet.workbook.getSheetIndex(sheet)

        // 각 열 그룹별 누적 오프셋 관리
        val columnGroupCurrentOffsets = mutableMapOf<Int, Int>()
        columnGroups.forEach { columnGroupCurrentOffsets[it.groupId] = 0 }

        // 열 그룹에 속하지 않는 열을 위한 기본 오프셋 (열 그룹이 없는 경우)
        var defaultOffset = 0

        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    processStaticRowWithCalculator(
                        sheet, rowSpec, data, sheetIndex, imageLocations, context,
                        columnGroups, columnGroupCurrentOffsets, defaultOffset, calculator
                    )
                }

                is RowSpec.RepeatRow -> {
                    val rawItems = data[rowSpec.collectionName] as? Collection<*> ?: emptyList<Any>()
                    val isEmpty = rawItems.isEmpty()
                    // 빈 컬렉션이면 최소 1개 반복 단위(빈 행/열)를 위해 null 아이템 추가
                    val items: Collection<Any?> = rawItems.ifEmpty { listOf(null) }
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1
                    val repeatKey = RepeatKey(rowSpec.collectionName, rowSpec.templateRowIndex, rowSpec.repeatStartCol)
                    val expansion = calculator.getExpansionForRegion(
                        rowSpec.collectionName, rowSpec.templateRowIndex, rowSpec.repeatStartCol
                    )

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            val columnGroup = repeatToColumnGroup[repeatKey]
                            val currentOffset = if (columnGroup != null) {
                                columnGroupCurrentOffsets[columnGroup.groupId] ?: 0
                            } else {
                                defaultOffset
                            }

                            processDownRepeatWithCalculator(
                                sheet, rowSpec, items, isEmpty, blueprint, data, sheetIndex,
                                imageLocations, context, currentOffset, rowOffsets, columnGroup, calculator
                            )

                            val addedOffset = rowOffsets[rowSpec.templateRowIndex] ?: 0
                            if (columnGroup != null) {
                                columnGroupCurrentOffsets[columnGroup.groupId] =
                                    (columnGroupCurrentOffsets[columnGroup.groupId] ?: 0) + addedOffset
                            } else {
                                defaultOffset += addedOffset
                            }
                        }

                        RepeatDirection.RIGHT -> {
                            processRightRepeat(
                                sheet, rowSpec, items, blueprint, data, sheetIndex,
                                imageLocations, context, defaultOffset
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

    /**
     * 정적 행 처리 (PositionCalculator 사용)
     */
    private fun processStaticRowWithCalculator(
        sheet: XSSFSheet,
        rowSpec: RowSpec.StaticRow,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        columnGroups: List<ColumnGroup>,
        columnGroupCurrentOffsets: Map<Int, Int>,
        defaultOffset: Int,
        calculator: PositionCalculator
    ) {
        for (cellSpec in rowSpec.cells) {
            // 셀이 속한 열 그룹 찾기
            val columnGroup = columnGroups.find {
                cellSpec.columnIndex in it.startCol..it.endCol
            }

            val rowOffset = if (columnGroup != null) {
                columnGroupCurrentOffsets[columnGroup.groupId] ?: 0
            } else {
                defaultOffset
            }

            val actualRowIndex = rowSpec.templateRowIndex + rowOffset
            val row = sheet.getRow(actualRowIndex) ?: continue
            val cell = row.getCell(cellSpec.columnIndex) ?: continue

            processCellContentXssf(
                cell, cellSpec.content, data, sheetIndex, imageLocations, context,
                rowOffset = rowOffset,
                calculator = calculator
            )
        }
    }

    /**
     * DOWN 방향 repeat 처리 (PositionCalculator 사용)
     */
    private fun processDownRepeatWithCalculator(
        sheet: XSSFSheet,
        rowSpec: RowSpec.RepeatRow,
        items: Collection<*>,
        isEmpty: Boolean,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        currentOffset: Int,
        rowOffsets: Map<Int, Int>,
        columnGroup: ColumnGroup?,
        calculator: PositionCalculator
    ) {
        val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1
        val totalRepeatOffset = rowOffsets[rowSpec.templateRowIndex] ?: 0

        // 빈 컬렉션이고 emptyRangeContent가 있으면 그 내용을 출력
        if (isEmpty && rowSpec.emptyRangeContent != null) {
            writeEmptyRangeContent(sheet, rowSpec, currentOffset)
            return
        }

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

                // 열 그룹이 있으면 범위 내 셀만 반복 처리
                val repeatCellSpecs = if (columnGroup != null) {
                    cellSpecs.filter { it.columnIndex in columnGroup.startCol..columnGroup.endCol }
                } else {
                    cellSpecs
                }

                for (cellSpec in repeatCellSpecs) {
                    val cell = row.getCell(cellSpec.columnIndex) ?: continue
                    processCellContentXssf(
                        cell, cellSpec.content, itemData, sheetIndex,
                        imageLocations, context,
                        rowOffset = currentOffset + totalRepeatOffset,
                        repeatItemIndex = itemIdx,
                        calculator = calculator
                    )
                }

                // 열 그룹 바깥의 셀은 첫 번째 아이템에서만 처리 (이미지 마커 등)
                if (columnGroup != null && itemIdx == 0 && templateOffset == 0) {
                    val nonRepeatCellSpecs = cellSpecs.filter {
                        it.columnIndex !in columnGroup.startCol..columnGroup.endCol
                    }
                    // 원본 행에서 셀 처리 (이동되지 않은 위치)
                    val originalRow = sheet.getRow(rowSpec.templateRowIndex) ?: continue
                    for (cellSpec in nonRepeatCellSpecs) {
                        val cell = originalRow.getCell(cellSpec.columnIndex) ?: continue
                        processCellContentXssf(
                            cell, cellSpec.content, data, sheetIndex,
                            imageLocations, context,
                            rowOffset = currentOffset + totalRepeatOffset,
                            repeatItemIndex = 0,
                            calculator = calculator
                        )
                    }
                }
            }
        }
    }

    private fun processRightRepeat(
        sheet: XSSFSheet,
        rowSpec: RowSpec.RepeatRow,
        items: Collection<*>,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext,
        currentOffset: Int
    ) {
        val templateColCount = rowSpec.repeatEndCol - rowSpec.repeatStartCol + 1
        val colShiftAmount = (items.size - 1) * templateColCount

        for (rowIdx in rowSpec.templateRowIndex..rowSpec.repeatEndRowIndex) {
            val actualRowIdx = rowIdx + currentOffset
            val row = sheet.getRow(actualRowIdx) ?: continue

            val currentRowSpec = blueprint.rows.find { it.templateRowIndex == rowIdx }
            val cellSpecs = currentRowSpec?.cells ?: continue

            // 반복 영역 내 셀 처리
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

            // 반복 영역 오른쪽 셀 처리 (밀린 위치에서)
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

    /**
     * XSSF용 셀 내용 처리.
     * 기본 처리는 부모 클래스에 위임하고, 이미지 마커와 Formula는 XSSF 특화 처리한다.
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
        repeatItemIndex: Int = 0,
        calculator: PositionCalculator? = null
    ) {
        // 이미지 마커는 calculator를 사용하여 직접 처리
        if (content is CellContent.ImageMarker) {
            processImageMarkerWithCalculator(
                cell, content, sheetIndex, imageLocations,
                rowOffset, colOffset, repeatItemIndex, calculator
            )
            return
        }

        // 부모 클래스의 공통 처리 (Formula는 XSSF에서 특별 처리 필요 없음 - POI가 자동 조정)
        processCellContent(
            cell, content, data, sheetIndex, imageLocations, context,
            rowOffset, colOffset, repeatItemIndex
        )
    }

    /**
     * PositionCalculator를 사용하여 이미지 마커를 처리한다.
     * 이미지의 위치를 모든 repeat 확장에 따라 정확하게 계산한다.
     */
    private fun processImageMarkerWithCalculator(
        cell: Cell,
        content: CellContent.ImageMarker,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        rowOffset: Int,
        colOffset: Int,
        repeatItemIndex: Int,
        calculator: PositionCalculator?
    ) {
        // position이 지정된 이미지는 첫 번째 반복에서만 처리 (중복 방지)
        if (content.position != null && repeatItemIndex > 0) {
            cell.setBlank()
            return
        }

        // position 조정 (PositionCalculator 사용)
        val adjustedPosition = if (content.position != null && calculator != null) {
            // calculator를 사용하여 모든 repeat 확장을 고려한 최종 위치 계산
            adjustPositionWithCalculator(content.position, calculator)
        } else if (content.position != null && (rowOffset != 0 || colOffset != 0)) {
            // calculator가 없으면 기존 방식 사용
            adjustPosition(content.position, rowOffset, colOffset)
        } else {
            content.position
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
     * PositionCalculator를 사용하여 위치 문자열을 조정한다.
     * 모든 repeat 확장에 의한 위치 변화를 고려한다.
     *
     * @param position 원본 위치 (단일 셀 "B5" 또는 범위 "B5:D10")
     * @param calculator 위치 계산기
     */
    private fun adjustPositionWithCalculator(position: String, calculator: PositionCalculator) =
        if (position.contains(":")) {
            // 범위: B5:D10 -> 각 끝점의 최종 위치 계산
            val (start, end) = position.split(":")
            val (startRow, startCol) = parseCellRef(start)
            val (endRow, endCol) = parseCellRef(end)
            val (finalStartRow, finalStartCol) = calculator.getFinalPosition(startRow, startCol)
            val (finalEndRow, finalEndCol) = calculator.getFinalPosition(endRow, endCol)
            toRangeRef(finalStartRow, finalStartCol, finalEndRow, finalEndCol)
        } else {
            // 단일 셀: B5 -> 최종 위치
            val (row, col) = parseCellRef(position)
            val (finalRow, finalCol) = calculator.getFinalPosition(row, col)
            toCellRef(finalRow, finalCol)
        }

    // ========== emptyRange 처리 ==========

    /**
     * 빈 컬렉션일 때 emptyRangeContent를 repeat 영역에 출력한다.
     */
    private fun writeEmptyRangeContent(
        sheet: XSSFSheet,
        rowSpec: RowSpec.RepeatRow,
        currentOffset: Int
    ) {
        val emptyRangeContent = rowSpec.emptyRangeContent ?: return
        val workbook = sheet.workbook
        val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1
        val templateColCount = rowSpec.repeatEndCol - rowSpec.repeatStartCol + 1

        // emptyRangeContent의 행/열 개수
        val contentRowCount = emptyRangeContent.rowCount
        val contentColCount = emptyRangeContent.colCount

        // emptyRangeContent가 단일 셀이고 repeat 영역이 더 크면 병합
        if (emptyRangeContent.isSingleCell && (templateRowCount > 1 || templateColCount > 1)) {
            val startRow = rowSpec.templateRowIndex + currentOffset
            val endRow = startRow + templateRowCount - 1
            val startCol = rowSpec.repeatStartCol
            val endCol = rowSpec.repeatEndCol

            // 첫 번째 셀에 내용 설정
            val row = sheet.getRow(startRow) ?: sheet.createRow(startRow)
            val cell = row.getCell(startCol) ?: row.createCell(startCol)
            val snapshot = emptyRangeContent.cells[0][0]
            writeCellFromSnapshot(cell, snapshot, workbook)

            // 병합 영역 추가
            sheet.addMergedRegion(CellRangeAddress(startRow, endRow, startCol, endCol))
            return
        }

        // 실제 출력할 행/열 개수 (repeat 영역과 emptyRange 중 작은 것)
        val rowsToWrite = minOf(templateRowCount, contentRowCount)
        val colsToWrite = minOf(templateColCount, contentColCount)

        for (rowOffset in 0 until rowsToWrite) {
            val actualRowIndex = rowSpec.templateRowIndex + currentOffset + rowOffset
            val row = sheet.getRow(actualRowIndex) ?: sheet.createRow(actualRowIndex)

            // 행 높이 설정
            emptyRangeContent.rowHeights.getOrNull(rowOffset)?.let { row.height = it }

            for (colOffset in 0 until colsToWrite) {
                val colIndex = rowSpec.repeatStartCol + colOffset
                val snapshot = emptyRangeContent.cells.getOrNull(rowOffset)?.getOrNull(colOffset) ?: continue
                val cell = row.getCell(colIndex) ?: row.createCell(colIndex)
                writeCellFromSnapshot(cell, snapshot, workbook)
            }
        }

        // emptyRangeContent의 병합 영역 복사 (상대 좌표를 실제 좌표로 변환)
        for (mergedRegion in emptyRangeContent.mergedRegions) {
            val actualRegion = CellRangeAddress(
                mergedRegion.firstRow + rowSpec.templateRowIndex + currentOffset,
                mergedRegion.lastRow + rowSpec.templateRowIndex + currentOffset,
                mergedRegion.firstColumn + rowSpec.repeatStartCol,
                mergedRegion.lastColumn + rowSpec.repeatStartCol
            )
            sheet.addMergedRegion(actualRegion)
        }
    }

    /**
     * CellSnapshot 내용을 셀에 쓴다.
     */
    private fun writeCellFromSnapshot(cell: Cell, snapshot: CellSnapshot, workbook: Workbook) {
        workbook.getCellStyleAt(snapshot.styleIndex.toInt())?.let { cell.cellStyle = it }
        when (snapshot.cellType) {
            CellType.STRING -> cell.setCellValue(snapshot.value as? String ?: "")
            CellType.NUMERIC -> cell.setCellValue(snapshot.value as? Double ?: 0.0)
            CellType.BOOLEAN -> cell.setCellValue(snapshot.value as? Boolean ?: false)
            CellType.FORMULA -> snapshot.formula?.let { cell.cellFormula = it }
            CellType.BLANK, CellType._NONE -> cell.setBlank()
            else -> {}
        }
    }
}
