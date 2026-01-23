package com.hunet.common.excel.engine

import com.hunet.common.excel.findMergedRegion
import com.hunet.common.excel.parseCellRef
import com.hunet.common.excel.setInitialView
import com.hunet.common.excel.toByteArray
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellCopyPolicy
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
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
internal class XssfRenderingStrategy : RenderingStrategy {
    companion object {
        private val REPEAT_MARKER_PATTERN = Regex("""\$\{repeat\s*\(""", RegexOption.IGNORE_CASE)
    }

    override val name: String = "XSSF"

    override fun render(
        templateBytes: ByteArray,
        data: Map<String, Any>,
        context: RenderingContext
    ): ByteArray {
        return XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            val blueprint = context.analyzer.analyzeFromWorkbook(workbook)
            val imageLocations = mutableListOf<ImageLocation>()
            val floatingImageLocations = mutableListOf<FloatingImageLocation>()

            blueprint.sheets.forEachIndexed { index, sheetSpec ->
                val sheet = workbook.getSheetAt(index) as XSSFSheet
                processSheetXssf(sheet, sheetSpec, data, imageLocations, floatingImageLocations, context)
            }

            // 이미지 삽입 (반복 처리 후)
            insertImages(workbook, imageLocations, data, context)
            insertFloatingImages(workbook, floatingImageLocations, data, context)

            // 수식 재계산
            workbook.creationHelper.createFormulaEvaluator().evaluateAll()

            // 파일 열 때 첫 번째 시트 A1 셀에 포커스
            workbook.setInitialView()

            workbook.toByteArray()
        }
    }

    private fun processSheetXssf(
        sheet: XSSFSheet,
        blueprint: SheetSpec,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        floatingImageLocations: MutableList<FloatingImageLocation>,
        context: RenderingContext
    ) {
        // 반복 영역 확장 (뒤에서부터 처리하여 인덱스 꼬임 방지)
        val repeatRows = blueprint.rows.filterIsInstance<RowSpec.RepeatRow>().reversed()
        val rowOffsets = mutableMapOf<Int, Int>()

        for (repeatRow in repeatRows) {
            val items = data[repeatRow.collectionName] as? List<*> ?: continue

            when (repeatRow.direction) {
                RepeatDirection.DOWN -> {
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

        clearRepeatMarkers(sheet)
        substituteVariablesXssf(sheet, blueprint, data, rowOffsets, imageLocations, floatingImageLocations, context)
    }

    private fun clearRepeatMarkers(sheet: XSSFSheet) {
        sheet.forEach { row ->
            row.forEach { cell ->
                if (cell.cellType == CellType.STRING) {
                    val text = cell.stringCellValue ?: return@forEach
                    if (REPEAT_MARKER_PATTERN.containsMatchIn(text)) {
                        cell.setBlank()
                    }
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
        floatingImageLocations: MutableList<FloatingImageLocation>,
        context: RenderingContext
    ) {
        var currentOffset = 0

        for (rowSpec in blueprint.rows) {
            when (rowSpec) {
                is RowSpec.StaticRow -> {
                    val actualRowIndex = rowSpec.templateRowIndex + currentOffset
                    val row = sheet.getRow(actualRowIndex) ?: continue
                    substituteRowVariables(
                        sheet, row, rowSpec.cells, data, imageLocations, floatingImageLocations, context,
                        rowOffset = currentOffset
                    )
                }

                is RowSpec.RepeatRow -> {
                    val items = data[rowSpec.collectionName] as? List<*> ?: continue
                    val templateRowCount = rowSpec.repeatEndRowIndex - rowSpec.templateRowIndex + 1

                    when (rowSpec.direction) {
                        RepeatDirection.DOWN -> {
                            // 전체 반복 오프셋 (반복 완료 후의 총 오프셋)
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
                                        sheet, row, cellSpecs, itemData,
                                        imageLocations, floatingImageLocations, context,
                                        // floatimage position에는 전체 오프셋 적용
                                        rowOffset = currentOffset + totalRepeatOffset,
                                        repeatItemIndex = itemIdx
                                    )
                                }
                            }
                            currentOffset += totalRepeatOffset
                        }

                        RepeatDirection.RIGHT -> {
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

                                        processCellContent(
                                            cell, cellSpec.content, itemData,
                                            sheet.workbook.getSheetIndex(sheet), imageLocations, floatingImageLocations, context,
                                            // floatimage position에는 전체 오프셋 적용
                                            rowOffset = currentOffset, colOffset = colShiftAmount,
                                            repeatItemIndex = itemIdx
                                        )
                                    }
                                }

                                // 2. 반복 영역 오른쪽 셀 처리 (밀린 위치에서)
                                for (cellSpec in cellSpecs
                                    .filter { it.columnIndex > rowSpec.repeatEndCol }) {

                                    val shiftedColIdx = cellSpec.columnIndex + colShiftAmount
                                    val cell = row.getCell(shiftedColIdx) ?: continue

                                    processCellContent(
                                        cell, cellSpec.content, data,
                                        sheet.workbook.getSheetIndex(sheet), imageLocations, floatingImageLocations, context,
                                        rowOffset = currentOffset, colOffset = colShiftAmount
                                    )
                                }
                            }
                        }
                    }
                }

                is RowSpec.RepeatContinuation -> {
                    // RepeatRow에서 이미 처리됨
                }
            }
        }
    }

    private fun substituteRowVariables(
        sheet: XSSFSheet,
        row: Row,
        cellSpecs: List<CellSpec>,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        floatingImageLocations: MutableList<FloatingImageLocation>,
        context: RenderingContext,
        rowOffset: Int = 0,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        val sheetIndex = sheet.workbook.getSheetIndex(sheet)
        for (cellSpec in cellSpecs) {
            val cell = row.getCell(cellSpec.columnIndex) ?: continue
            processCellContent(
                cell, cellSpec.content, data, sheetIndex,
                imageLocations, floatingImageLocations, context,
                rowOffset, colOffset, repeatItemIndex
            )
        }
    }

    private fun processCellContent(
        cell: Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        floatingImageLocations: MutableList<FloatingImageLocation>,
        context: RenderingContext,
        rowOffset: Int = 0,
        colOffset: Int = 0,
        repeatItemIndex: Int = 0
    ) {
        when (content) {
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
                // 일반 수식은 그대로 유지
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
                        rowIndex = cell.rowIndex,
                        colIndex = cell.columnIndex
                    )
                )
                cell.setBlank()
            }

            is CellContent.FloatingImageMarker -> {
                // position이 지정된 floatimage는 첫 번째 반복에서만 처리 (중복 방지)
                if (content.position != null && repeatItemIndex > 0) {
                    cell.setBlank()
                    return
                }

                val (targetRow, targetCol) = if (content.position != null) {
                    val (baseRow, baseCol) = parseCellRef(content.position)
                    // position으로 지정된 셀에 현재 반복 오프셋 적용
                    (baseRow + rowOffset) to (baseCol + colOffset)
                } else {
                    cell.rowIndex to cell.columnIndex
                }

                floatingImageLocations.add(
                    FloatingImageLocation(
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

            is CellContent.StaticString -> {
                val evaluated = context.evaluateText(content.value, data)
                if (evaluated != content.value) {
                    setCellValue(cell, evaluated)
                }
            }

            else -> {
                // 그 외 정적 값(숫자, 불린)은 그대로 유지
            }
        }
    }

    private fun insertImages(
        workbook: XSSFWorkbook,
        imageLocations: List<ImageLocation>,
        data: Map<String, Any>,
        context: RenderingContext
    ) {
        for (location in imageLocations) {
            val imageBytes = data["image.${location.imageName}"] as? ByteArray
                ?: data[location.imageName] as? ByteArray
                ?: continue

            val sheet = workbook.getSheetAt(location.sheetIndex) as XSSFSheet
            context.imageInserter.insertImageXssf(
                workbook, sheet, imageBytes,
                location.rowIndex, location.colIndex,
                sheet.findMergedRegion(location.rowIndex, location.colIndex)
            )
        }
    }

    private fun insertFloatingImages(
        workbook: XSSFWorkbook,
        floatingImageLocations: List<FloatingImageLocation>,
        data: Map<String, Any>,
        context: RenderingContext
    ) {
        for (location in floatingImageLocations) {
            val imageBytes = data["image.${location.imageName}"] as? ByteArray
                ?: data[location.imageName] as? ByteArray
                ?: continue

            val sheet = workbook.getSheetAt(location.sheetIndex) as XSSFSheet
            context.imageInserter.insertFloatingImage(
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

/**
 * 이미지 위치 정보.
 */
internal data class ImageLocation(
    val sheetIndex: Int,
    val imageName: String,
    val rowIndex: Int,
    val colIndex: Int,
    val mergedRegion: CellRangeAddress? = null
)

/**
 * 플로팅 이미지 위치 정보.
 */
internal data class FloatingImageLocation(
    val sheetIndex: Int,
    val imageName: String,
    val rowIndex: Int,
    val colIndex: Int,
    val sizeSpec: ImageSizeSpec
)
