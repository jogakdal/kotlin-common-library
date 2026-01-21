package com.hunet.common.excel.engine

import com.hunet.common.excel.findMergedRegion
import com.hunet.common.excel.toByteArray
import org.apache.poi.ss.usermodel.CellCopyPolicy
import org.apache.poi.ss.usermodel.CellType
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

            blueprint.sheets.forEachIndexed { index, sheetBlueprint ->
                val sheet = workbook.getSheetAt(index) as XSSFSheet
                processSheetXssf(sheet, sheetBlueprint, data, imageLocations, context)
            }

            // 이미지 삽입 (반복 처리 후)
            insertImages(workbook, imageLocations, data, context)

            // 수식 재계산
            workbook.creationHelper.createFormulaEvaluator().evaluateAll()

            workbook.toByteArray()
        }
    }

    private fun processSheetXssf(
        sheet: XSSFSheet,
        blueprint: SheetBlueprint,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        // 1. 반복 영역 확장 (뒤에서부터 처리하여 인덱스 꼬임 방지)
        val repeatRows = blueprint.rows.filterIsInstance<RowBlueprint.RepeatRow>().reversed()
        val rowOffsets = mutableMapOf<Int, Int>()

        for (repeatRow in repeatRows) {
            val items = data[repeatRow.collectionName] as? List<*> ?: continue

            when (repeatRow.direction) {
                RepeatDirection.DOWN -> {
                    val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
                    val totalRowsNeeded = items.size * templateRowCount
                    val rowsToInsert = totalRowsNeeded - templateRowCount

                    if (rowsToInsert > 0) {
                        val insertPosition = repeatRow.repeatEndRowIndex + 1
                        if (insertPosition <= sheet.lastRowNum) {
                            sheet.shiftRows(insertPosition, sheet.lastRowNum, rowsToInsert)
                        }

                        val repeatColRange = repeatRow.repeatStartCol..repeatRow.repeatEndCol
                        for (itemIdx in 1 until items.size) {
                            for (templateOffset in 0 until templateRowCount) {
                                val templateRowIndex = repeatRow.templateRowIndex + templateOffset
                                val templateRow = sheet.getRow(templateRowIndex)
                                val newRowIndex = repeatRow.templateRowIndex + (itemIdx * templateRowCount) + templateOffset
                                val newRow = sheet.createRow(newRowIndex)

                                if (templateRow != null) {
                                    val copyPolicy = CellCopyPolicy.Builder()
                                        .cellStyle(true)
                                        .cellValue(true)
                                        .cellFormula(true)
                                        .mergedRegions(false)
                                        .build()
                                    newRow.copyRowFrom(templateRow, copyPolicy)

                                    newRow.forEach { cell ->
                                        if (cell.columnIndex !in repeatColRange) {
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
                    val actualColCount = items.size
                    val colsToInsert = (actualColCount - 1) * templateColCount

                    if (colsToInsert > 0) {
                        context.repeatExpansionProcessor.expandColumnsRight(sheet, repeatRow, items.size)
                    }

                    context.repeatExpansionProcessor.expandFormulasAfterRightRepeat(
                        sheet, repeatRow, items.size, templateColCount, colsToInsert
                    )
                }
            }
        }

        // 2. 반복 마커 셀 비우기
        clearRepeatMarkers(sheet)

        // 3. 변수 치환
        substituteVariablesXssf(sheet, blueprint, data, rowOffsets, imageLocations, context)
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
        blueprint: SheetBlueprint,
        data: Map<String, Any>,
        rowOffsets: Map<Int, Int>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        var currentOffset = 0

        for (rowBlueprint in blueprint.rows) {
            when (rowBlueprint) {
                is RowBlueprint.StaticRow -> {
                    val actualRowIndex = rowBlueprint.templateRowIndex + currentOffset
                    val row = sheet.getRow(actualRowIndex) ?: continue
                    substituteRowVariables(sheet, row, rowBlueprint.cells, data, imageLocations, context)
                }

                is RowBlueprint.RepeatRow -> {
                    val items = data[rowBlueprint.collectionName] as? List<*> ?: continue
                    val templateRowCount = rowBlueprint.repeatEndRowIndex - rowBlueprint.templateRowIndex + 1

                    when (rowBlueprint.direction) {
                        RepeatDirection.DOWN -> {
                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowBlueprint.itemVariable to item)
                                } else data

                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowBlueprint.templateRowIndex + templateOffset
                                    val actualRowIndex = rowBlueprint.templateRowIndex + currentOffset +
                                        (itemIdx * templateRowCount) + templateOffset
                                    val row = sheet.getRow(actualRowIndex) ?: continue

                                    val currentRowBlueprint = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                    val cellBlueprints = currentRowBlueprint?.cells ?: continue

                                    substituteRowVariables(
                                        sheet, row, cellBlueprints, itemData,
                                        imageLocations, context
                                    )
                                }
                            }
                            currentOffset += rowOffsets[rowBlueprint.templateRowIndex] ?: 0
                        }

                        RepeatDirection.RIGHT -> {
                            val templateColCount = rowBlueprint.repeatEndCol - rowBlueprint.repeatStartCol + 1

                            for (rowIdx in rowBlueprint.templateRowIndex..rowBlueprint.repeatEndRowIndex) {
                                val actualRowIdx = rowIdx + currentOffset
                                val row = sheet.getRow(actualRowIdx) ?: continue

                                val currentRowBlueprint = blueprint.rows.find { it.templateRowIndex == rowIdx }
                                val cellBlueprints = currentRowBlueprint?.cells ?: continue

                                items.forEachIndexed { itemIdx, item ->
                                    val itemData = if (item != null) {
                                        data + (rowBlueprint.itemVariable to item)
                                    } else data

                                    val colStart = rowBlueprint.repeatStartCol + (itemIdx * templateColCount)

                                    for (cellBlueprint in cellBlueprints
                                        .filter { it.columnIndex in rowBlueprint.repeatStartCol..rowBlueprint.repeatEndCol }) {

                                        val targetColIdx = colStart + (cellBlueprint.columnIndex - rowBlueprint.repeatStartCol)
                                        val cell = row.getCell(targetColIdx) ?: continue

                                        processCellContent(
                                            cell, cellBlueprint.content, itemData,
                                            sheet.workbook.getSheetIndex(sheet), imageLocations, context
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is RowBlueprint.RepeatContinuation -> {
                    // RepeatRow에서 이미 처리됨
                }
            }
        }
    }

    private fun substituteRowVariables(
        sheet: XSSFSheet,
        row: org.apache.poi.ss.usermodel.Row,
        cellBlueprints: List<CellBlueprint>,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
    ) {
        val sheetIndex = sheet.workbook.getSheetIndex(sheet)
        for (cellBlueprint in cellBlueprints) {
            val cell = row.getCell(cellBlueprint.columnIndex) ?: continue
            processCellContent(cell, cellBlueprint.content, data, sheetIndex, imageLocations, context)
        }
    }

    private fun processCellContent(
        cell: org.apache.poi.ss.usermodel.Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>,
        context: RenderingContext
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
            val mergedRegion = sheet.findMergedRegion(location.rowIndex, location.colIndex)

            context.imageInserter.insertImageXssf(
                workbook, sheet, imageBytes,
                location.rowIndex, location.colIndex, mergedRegion
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
