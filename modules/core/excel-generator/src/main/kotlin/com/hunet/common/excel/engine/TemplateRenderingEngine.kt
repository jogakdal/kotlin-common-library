package com.hunet.common.excel.engine

import com.hunet.common.excel.ExcelDataProvider
import com.hunet.common.excel.StreamingMode
import com.hunet.common.excel.findMergedRegion
import com.hunet.common.excel.toByteArray
import com.hunet.common.lib.VariableProcessor
import org.apache.poi.ss.usermodel.CellCopyPolicy
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 템플릿 렌더링 엔진 - Excel 템플릿 기반 데이터 바인딩
 *
 * ## 지원 문법
 * - `${변수명}` - 단순 변수 치환
 * - `${item.field}` - 반복 항목의 필드 접근
 * - `${object.method()}` - 메서드 호출 (예: `${employees.size()}`)
 * - `${repeat(collection, range, var)}` - 반복 처리
 * - `${image.name}` - 이미지 삽입
 * - `HYPERLINK("${url}", "${text}")` - 수식 내 변수 치환
 *
 * ## 처리 방식
 * - **비스트리밍 모드**: XSSF 기반 템플릿 변환 (shiftRows + copyRowFrom)
 * - **스트리밍 모드**: SXSSF 기반 순차 생성 (청사진 기반)
 */
class TemplateRenderingEngine(
    private val streamingMode: StreamingMode = StreamingMode.DISABLED
) {
    companion object {
        private val REPEAT_MARKER_PATTERN = Regex("""\$\{repeat\s*\(""", RegexOption.IGNORE_CASE)
    }

    private val analyzer = TemplateAnalyzer()
    private val variableProcessor = VariableProcessor(emptyList())
    private val imageInserter = ImageInserter()

    // 리플렉션 결과 캐시 (성능 최적화)
    private val fieldCache = mutableMapOf<Pair<Class<*>, String>, java.lang.reflect.Field?>()
    private val getterCache = mutableMapOf<Pair<Class<*>, String>, java.lang.reflect.Method?>()

    /**
     * 템플릿에 데이터를 바인딩하여 Excel 생성
     */
    fun process(template: InputStream, data: Map<String, Any>): ByteArray {
        val templateBytes = template.readBytes()

        return when (streamingMode) {
            StreamingMode.DISABLED -> processWithXssf(templateBytes, data)
            StreamingMode.ENABLED -> processWithSxssf(templateBytes, data)
        }
    }

    /**
     * 템플릿에 DataProvider 데이터를 바인딩하여 Excel 생성
     */
    fun process(template: InputStream, dataProvider: ExcelDataProvider): ByteArray {
        val data = buildDataMap(dataProvider)
        return process(template, data)
    }

    private fun buildDataMap(dataProvider: ExcelDataProvider): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        dataProvider.getAvailableNames().forEach { name ->
            dataProvider.getValue(name)?.let { data[name] = it }
            dataProvider.getItems(name)?.let { iterator ->
                data[name] = iterator.asSequence().toList()
            }
            dataProvider.getImage(name)?.let { data["image.$name"] = it }
        }
        return data
    }

    // ========== XSSF 모드 (비스트리밍) ==========

    /**
     * XSSF 기반 템플릿 변환 방식
     * - shiftRows()로 행 삽입 공간 확보
     * - copyRowFrom()으로 템플릿 행 복사 (수식 자동 조정)
     */
    private fun processWithXssf(templateBytes: ByteArray, data: Map<String, Any>): ByteArray {
        return XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            val blueprint = analyzer.analyzeFromWorkbook(workbook)
            val imageLocations = mutableListOf<ImageLocation>()

            blueprint.sheets.forEachIndexed { index, sheetBlueprint ->
                val sheet = workbook.getSheetAt(index) as XSSFSheet
                processSheetXssf(sheet, sheetBlueprint, data, imageLocations)
            }

            // 이미지 삽입 (반복 처리 후)
            insertImages(workbook, imageLocations, data)

            // 수식 재계산
            workbook.creationHelper.createFormulaEvaluator().evaluateAll()

            workbook.toByteArray()
        }
    }

    private data class ImageLocation(
        val sheetIndex: Int,
        val imageName: String,
        val rowIndex: Int,
        val colIndex: Int,
        val mergedRegion: CellRangeAddress? = null
    )

    /**
     * SXSSF 모드 행 작성 컨텍스트 - 시트 전체에서 공유되는 정보
     */
    private data class RowWriteContext(
        val sheet: SXSSFSheet,
        val sheetIndex: Int,
        val data: Map<String, Any>,
        val styleMap: Map<Short, org.apache.poi.ss.usermodel.CellStyle>,
        val templateMergedRegions: List<CellRangeAddress>,
        val repeatRegions: Map<Int, RowBlueprint.RepeatRow>,
        val imageLocations: MutableList<ImageLocation>
    )

    private fun processSheetXssf(
        sheet: XSSFSheet,
        blueprint: SheetBlueprint,
        data: Map<String, Any>,
        imageLocations: MutableList<ImageLocation>
    ) {
        // 1. 반복 영역 확장 (뒤에서부터 처리하여 인덱스 꼬임 방지)
        val repeatRows = blueprint.rows.filterIsInstance<RowBlueprint.RepeatRow>().reversed()
        val rowOffsets = mutableMapOf<Int, Int>()
        val colOffsets = mutableMapOf<Int, Int>()  // RIGHT 확장을 위한 열 오프셋

        for (repeatRow in repeatRows) {
            val items = data[repeatRow.collectionName] as? List<*> ?: continue

            when (repeatRow.direction) {
                RepeatDirection.DOWN -> {
                    val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
                    val totalRowsNeeded = items.size * templateRowCount  // 다중 행 템플릿: 아이템 수 × 템플릿 행 수
                    val rowsToInsert = totalRowsNeeded - templateRowCount

                    if (rowsToInsert > 0) {
                        // 행 삽입 공간 확보 (수식 참조 자동 조정됨)
                        val insertPosition = repeatRow.repeatEndRowIndex + 1
                        if (insertPosition <= sheet.lastRowNum) {
                            sheet.shiftRows(insertPosition, sheet.lastRowNum, rowsToInsert)
                        }

                        // 다중 행 템플릿: 모든 템플릿 행을 각 아이템에 대해 복사
                        val repeatColRange = repeatRow.repeatStartCol..repeatRow.repeatEndCol
                        for (itemIdx in 1 until items.size) {  // 첫 번째 아이템은 원본 템플릿 사용
                            for (templateOffset in 0 until templateRowCount) {
                                val templateRowIndex = repeatRow.templateRowIndex + templateOffset
                                val templateRow = sheet.getRow(templateRowIndex)
                                val newRowIndex = repeatRow.templateRowIndex + (itemIdx * templateRowCount) + templateOffset
                                val newRow = sheet.createRow(newRowIndex)

                                if (templateRow != null) {
                                    // 병합 영역은 별도로 처리
                                    val copyPolicy = CellCopyPolicy.Builder()
                                        .cellStyle(true)
                                        .cellValue(true)
                                        .cellFormula(true)
                                        .mergedRegions(false)  // 병합은 copyMergedRegionsForRepeat에서 처리
                                        .build()
                                    newRow.copyRowFrom(templateRow, copyPolicy)

                                    // repeat 범위 밖의 셀 비우기 (첫 번째 아이템에만 유지)
                                    newRow.forEach { cell ->
                                        if (cell.columnIndex !in repeatColRange) {
                                            cell.setBlank()
                                        }
                                    }
                                }
                            }
                        }

                        // 다중 행 템플릿의 병합 영역 복제
                        copyMergedRegionsForRepeat(sheet, repeatRow, items.size, blueprint.mergedRegions)

                        // 조건부 서식 범위 확장
                        expandConditionalFormattingForRepeat(sheet, repeatRow, items.size)
                    }

                    rowOffsets[repeatRow.templateRowIndex] = rowsToInsert

                    // 반복 영역 이후 행의 수식 확장
                    expandFormulasAfterRepeat(
                        sheet, repeatRow, items.size, templateRowCount, rowsToInsert
                    )
                }

                RepeatDirection.RIGHT -> {
                    val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
                    val actualColCount = items.size
                    val colsToInsert = (actualColCount - 1) * templateColCount  // 추가로 삽입할 열 수

                    if (colsToInsert > 0) {
                        // 템플릿 열을 오른쪽으로 확장
                        expandColumnsRight(sheet, repeatRow, items.size)
                    }

                    colOffsets[repeatRow.templateRowIndex] = colsToInsert

                    // 반복 영역 오른쪽 수식 확장
                    expandFormulasAfterRightRepeat(
                        sheet, repeatRow, items.size, templateColCount, colsToInsert
                    )
                }
            }
        }

        // 2. 반복 마커 셀 비우기
        clearRepeatMarkers(sheet)

        // 3. 변수 치환
        substituteVariablesXssf(sheet, blueprint, data, rowOffsets, imageLocations, colOffsets)
    }

    /**
     * RIGHT 방향 확장 - 템플릿 열을 오른쪽으로 복사
     * 확장 시 반복 영역 오른쪽의 기존 셀들을 오른쪽으로 이동
     */
    private fun expandColumnsRight(
        sheet: XSSFSheet,
        repeatRow: RowBlueprint.RepeatRow,
        itemCount: Int
    ) {
        val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
        val colsToInsert = (itemCount - 1) * templateColCount  // 추가로 삽입할 열 수

        if (colsToInsert <= 0) return

        // 1. 반복 영역 내 행의 오른쪽 기존 셀들만 오른쪽으로 이동
        val shiftStartCol = repeatRow.repeatEndCol + 1
        shiftColumnsRight(
            sheet, shiftStartCol, colsToInsert,
            repeatRow.templateRowIndex, repeatRow.repeatEndRowIndex
        )

        // 2. 반복 영역의 모든 행에 대해 열 복사
        for (rowIdx in repeatRow.templateRowIndex..repeatRow.repeatEndRowIndex) {
            val row = sheet.getRow(rowIdx) ?: continue

            // 템플릿 열의 셀 정보 수집 (스타일, 값)
            val templateCells = (repeatRow.repeatStartCol..repeatRow.repeatEndCol).map { colIdx ->
                row.getCell(colIdx)
            }

            // 데이터 개수만큼 열 복사 (첫 번째 열은 이미 있으므로 1부터 시작)
            for (itemIdx in 1 until itemCount) {
                for ((templateOffset, templateCell) in templateCells.withIndex()) {
                    if (templateCell == null) continue

                    val newColIdx = repeatRow.repeatStartCol + (itemIdx * templateColCount) + templateOffset
                    val newCell = row.createCell(newColIdx)

                    // 스타일 복사
                    newCell.cellStyle = templateCell.cellStyle

                    // 값 복사 (변수 치환은 나중에)
                    when (templateCell.cellType) {
                        CellType.STRING -> newCell.setCellValue(templateCell.stringCellValue)
                        CellType.NUMERIC -> newCell.setCellValue(templateCell.numericCellValue)
                        CellType.BOOLEAN -> newCell.setCellValue(templateCell.booleanCellValue)
                        CellType.FORMULA -> newCell.cellFormula = templateCell.cellFormula
                        else -> {}
                    }
                }
            }
        }
    }

    /**
     * 지정된 행 범위 내에서 지정된 열부터 오른쪽의 셀을 오른쪽으로 이동
     */
    private fun shiftColumnsRight(
        sheet: XSSFSheet,
        startCol: Int,
        shiftAmount: Int,
        startRow: Int,
        endRow: Int
    ) {
        // 지정된 행 범위 내에서만 처리
        for (rowIdx in startRow..endRow) {
            val row = sheet.getRow(rowIdx) ?: continue
            // 오른쪽에서 왼쪽으로 처리하여 덮어쓰기 방지
            val cellsToMove = row.toList().filter { it.columnIndex >= startCol }
                .sortedByDescending { it.columnIndex }

            for (cell in cellsToMove) {
                val newColIdx = cell.columnIndex + shiftAmount
                val newCell = row.createCell(newColIdx)

                // 스타일 복사 (같은 워크북 내 스타일이므로 인덱스로 복사)
                newCell.cellStyle = sheet.workbook.getCellStyleAt(cell.cellStyle.index.toInt())

                // 값 복사
                when (cell.cellType) {
                    CellType.STRING -> newCell.setCellValue(cell.stringCellValue)
                    CellType.NUMERIC -> newCell.setCellValue(cell.numericCellValue)
                    CellType.BOOLEAN -> newCell.setCellValue(cell.booleanCellValue)
                    CellType.FORMULA -> {
                        // 수식의 열 참조 조정
                        val adjustedFormula = FormulaAdjuster.adjustForColumnExpansion(
                            cell.cellFormula, startCol, shiftAmount
                        )
                        newCell.cellFormula = adjustedFormula
                    }
                    CellType.BLANK -> newCell.setBlank()
                    else -> {}
                }

                // 원래 셀 비우기
                row.removeCell(cell)
            }
        }
    }

    /**
     * 다중 행 반복 템플릿의 병합 영역을 각 아이템에 대해 복제
     */
    private fun copyMergedRegionsForRepeat(
        sheet: XSSFSheet,
        repeatRow: RowBlueprint.RepeatRow,
        itemCount: Int,
        templateMergedRegions: List<CellRangeAddress>
    ) {
        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1

        // 반복 영역 내 병합 영역 찾기
        val repeatMergedRegions = templateMergedRegions.filter { region ->
            region.firstRow >= repeatRow.templateRowIndex &&
                region.lastRow <= repeatRow.repeatEndRowIndex &&
                region.firstColumn >= repeatRow.repeatStartCol &&
                region.lastColumn <= repeatRow.repeatEndCol
        }

        // 각 추가 아이템에 대해 병합 영역 복제
        for (itemIdx in 1 until itemCount) {
            val rowOffset = itemIdx * templateRowCount
            for (templateRegion in repeatMergedRegions) {
                val newRegion = CellRangeAddress(
                    templateRegion.firstRow + rowOffset,
                    templateRegion.lastRow + rowOffset,
                    templateRegion.firstColumn,
                    templateRegion.lastColumn
                )
                // 이미 병합된 영역이면 무시
                runCatching { sheet.addMergedRegion(newRegion) }
            }
        }
    }

    /**
     * 조건부 서식 범위를 반복 영역에 맞게 확장 (XSSF 모드)
     *
     * 템플릿의 반복 영역에 조건부 서식이 있으면, 각 반복 아이템에 대해
     * 조건부 서식을 복제합니다.
     */
    private fun expandConditionalFormattingForRepeat(
        sheet: XSSFSheet,
        repeatRow: RowBlueprint.RepeatRow,
        itemCount: Int
    ) {
        if (itemCount <= 1) return

        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
        val scf = sheet.sheetConditionalFormatting
        val cfCount = scf.numConditionalFormattings

        // 반복 영역과 겹치는 조건부 서식 찾기 및 확장
        for (i in 0 until cfCount) {
            val cf = scf.getConditionalFormattingAt(i) ?: continue
            val ranges = cf.formattingRanges

            // 반복 영역 내의 범위만 필터
            val repeatRanges = ranges.filter { range ->
                range.firstRow >= repeatRow.templateRowIndex &&
                        range.lastRow <= repeatRow.repeatEndRowIndex &&
                        range.firstColumn >= repeatRow.repeatStartCol &&
                        (repeatRow.repeatEndCol == Int.MAX_VALUE || range.lastColumn <= repeatRow.repeatEndCol)
            }

            if (repeatRanges.isEmpty()) continue

            // 규칙 복사
            val rules = (0 until cf.numberOfRules).mapNotNull { cf.getRule(it) }.toTypedArray()
            if (rules.isEmpty()) continue

            // 각 추가 아이템에 대해 새 범위 생성 및 추가
            for (itemIdx in 1 until itemCount) {
                val rowOffset = itemIdx * templateRowCount
                val newRanges = repeatRanges.map { range ->
                    CellRangeAddress(
                        range.firstRow + rowOffset,
                        range.lastRow + rowOffset,
                        range.firstColumn,
                        range.lastColumn
                    )
                }.toTypedArray()

                scf.addConditionalFormatting(newRanges, rules)
            }
        }
    }

    /**
     * 반복 영역 이후 행의 수식에서 반복 영역 내 셀 참조를 범위로 확장 (XSSF 모드)
     *
     * 예: 반복 영역 A7:B8, 데이터 3개
     * - B11의 `=SUM(B8)` → B13으로 이동 (shiftRows에 의해)
     * - 수식을 `=SUM(B8,B10,B12)`로 확장 (2행 템플릿이므로 비연속)
     */
    private fun expandFormulasAfterRepeat(
        sheet: XSSFSheet,
        repeatRow: RowBlueprint.RepeatRow,
        itemCount: Int,
        templateRowCount: Int,
        rowsInserted: Int
    ) {
        if (itemCount <= 1 || rowsInserted <= 0) return

        // shiftRows 후 반복 영역의 끝 위치
        val newRepeatEndRow = repeatRow.repeatEndRowIndex + rowsInserted

        // 반복 영역 이후의 행에서 수식 셀 찾기
        for (rowIdx in (newRepeatEndRow + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(rowIdx) ?: continue
            row.forEach { cell ->
                if (cell.cellType == CellType.FORMULA) {
                    val originalFormula = cell.cellFormula
                    val (expandedFormula, hasDiscontinuous) = FormulaAdjuster.expandSingleRefToRange(
                        originalFormula,
                        repeatRow.templateRowIndex,
                        repeatRow.repeatEndRowIndex,
                        itemCount,
                        templateRowCount
                    )

                    if (expandedFormula != originalFormula) {
                        // 비연속 참조이고 인자 수가 255개를 초과하면 경고
                        if (hasDiscontinuous && itemCount > 255) {
                            throw com.hunet.common.excel.FormulaExpansionException(
                                sheetName = sheet.sheetName,
                                cellRef = cell.address.formatAsString(),
                                formula = originalFormula
                            )
                        }
                        cell.cellFormula = expandedFormula
                    }
                }
            }
        }
    }

    /**
     * RIGHT 방향 반복 영역 오른쪽의 수식에서 반복 영역 내 셀 참조를 범위로 확장 (XSSF 모드)
     *
     * 예: 반복 영역 B7:C8 (2열×2행), 데이터 3개
     * - G7의 `=SUM(B7)` 수식
     * - 수식을 `=SUM(B7,D7,F7)`로 확장 (2열 템플릿이므로 비연속)
     */
    private fun expandFormulasAfterRightRepeat(
        sheet: XSSFSheet,
        repeatRow: RowBlueprint.RepeatRow,
        itemCount: Int,
        templateColCount: Int,
        colsInserted: Int
    ) {
        if (itemCount <= 1) return

        // 반복 영역 오른쪽의 새 시작 열
        val newColStart = repeatRow.repeatEndCol + colsInserted + 1

        // 반복 영역의 행 범위 내에서 수식 셀 찾기
        for (rowIdx in repeatRow.templateRowIndex..repeatRow.repeatEndRowIndex) {
            val row = sheet.getRow(rowIdx) ?: continue
            row.forEach { cell ->
                // 반복 영역 오른쪽에 있는 수식 셀만 처리
                if (cell.columnIndex >= newColStart && cell.cellType == CellType.FORMULA) {
                    val originalFormula = cell.cellFormula

                    // XSSF 모드에서 확장된 데이터 열(repeatStartCol~repeatEndCol + colsInserted)은
                    // shiftColumnsRight 이후에 생성되므로 추가 열 이동이 필요 없음.
                    // 순환 참조도 발생하지 않음: 확장 범위(열 1~itemCount*templateColCount)가
                    // 수식 셀 위치(newColStart 이상)를 포함하지 않음.
                    val (expandedFormula, hasDiscontinuous) = FormulaAdjuster.expandSingleRefToColumnRange(
                        originalFormula,
                        repeatRow.repeatStartCol,
                        repeatRow.repeatEndCol,
                        repeatRow.templateRowIndex,
                        repeatRow.repeatEndRowIndex,
                        itemCount,
                        templateColCount
                    )

                    if (expandedFormula != originalFormula) {
                        // 비연속 참조이고 인자 수가 255개를 초과하면 경고
                        if (hasDiscontinuous && itemCount > 255) {
                            throw com.hunet.common.excel.FormulaExpansionException(
                                sheetName = sheet.sheetName,
                                cellRef = cell.address.formatAsString(),
                                formula = originalFormula
                            )
                        }
                        cell.cellFormula = expandedFormula
                    }
                }
            }
        }
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
        colOffsets: Map<Int, Int> = emptyMap()
    ) {
        var currentOffset = 0

        for (rowBlueprint in blueprint.rows) {
            when (rowBlueprint) {
                is RowBlueprint.StaticRow -> {
                    val actualRowIndex = rowBlueprint.templateRowIndex + currentOffset
                    val row = sheet.getRow(actualRowIndex) ?: continue
                    substituteRowVariables(sheet, row, rowBlueprint.cells, data, null, imageLocations)
                }

                is RowBlueprint.RepeatRow -> {
                    val items = data[rowBlueprint.collectionName] as? List<*> ?: continue
                    val templateRowCount = rowBlueprint.repeatEndRowIndex - rowBlueprint.templateRowIndex + 1

                    when (rowBlueprint.direction) {
                        RepeatDirection.DOWN -> {
                            // 다중 행 템플릿: 각 아이템에 대해 모든 템플릿 행 처리
                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowBlueprint.itemVariable to item)
                                } else data

                                // 각 템플릿 행에 대해 변수 치환
                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowBlueprint.templateRowIndex + templateOffset
                                    val actualRowIndex = rowBlueprint.templateRowIndex + currentOffset + (itemIdx * templateRowCount) + templateOffset
                                    val row = sheet.getRow(actualRowIndex) ?: continue

                                    // 해당 템플릿 행의 셀 청사진 가져오기
                                    val currentRowBlueprint = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                    val cellBlueprints = currentRowBlueprint?.cells ?: continue

                                    substituteRowVariables(sheet, row, cellBlueprints, itemData, rowBlueprint.itemVariable, imageLocations)
                                }
                            }
                            currentOffset += rowOffsets[rowBlueprint.templateRowIndex] ?: 0
                        }

                        RepeatDirection.RIGHT -> {
                            // RIGHT 확장: 반복 영역의 모든 행에 대해 열 방향으로 데이터 치환
                            val templateColCount = rowBlueprint.repeatEndCol - rowBlueprint.repeatStartCol + 1

                            for (rowIdx in rowBlueprint.templateRowIndex..rowBlueprint.repeatEndRowIndex) {
                                val actualRowIdx = rowIdx + currentOffset
                                val row = sheet.getRow(actualRowIdx) ?: continue

                                // 현재 행의 셀 청사진 가져오기 (RepeatRow 또는 RepeatContinuation)
                                val currentRowBlueprint = blueprint.rows.find { it.templateRowIndex == rowIdx }
                                val cellBlueprints = currentRowBlueprint?.cells ?: continue

                                items.forEachIndexed { itemIdx, item ->
                                    val itemData = if (item != null) {
                                        data + (rowBlueprint.itemVariable to item)
                                    } else data

                                    // 해당 아이템의 열 범위 계산
                                    val colStart = rowBlueprint.repeatStartCol + (itemIdx * templateColCount)

                                    // 해당 열 범위의 셀에 변수 치환 적용
                                    for (cellBlueprint in cellBlueprints
                                        .filter { it.columnIndex in rowBlueprint.repeatStartCol..rowBlueprint.repeatEndCol }) {

                                        val targetColIdx = colStart + (cellBlueprint.columnIndex - rowBlueprint.repeatStartCol)
                                        val cell = row.getCell(targetColIdx) ?: continue

                                        substituteCell(cell, cellBlueprint.content, itemData, rowBlueprint.itemVariable, sheet, imageLocations)
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

    /**
     * 셀 내용 처리 - XSSF 모드 공통 로직
     */
    private fun processCellContent(
        cell: org.apache.poi.ss.usermodel.Cell,
        content: CellContent,
        data: Map<String, Any>,
        sheetIndex: Int,
        imageLocations: MutableList<ImageLocation>
    ) {
        when (content) {
            is CellContent.Variable -> {
                val evaluated = evaluateText(content.originalText, data)
                setCellValue(cell, evaluated)
            }

            is CellContent.ItemField -> {
                val item = data[content.itemVariable]
                val value = resolveFieldPath(item, content.fieldPath)
                setCellValue(cell, value)
            }

            is CellContent.Formula -> {
                // 일반 수식은 그대로 유지
            }

            is CellContent.FormulaWithVariables -> {
                val substitutedFormula = evaluateText(content.formula, data)
                cell.cellFormula = substitutedFormula
            }

            is CellContent.ImageMarker -> {
                imageLocations.add(ImageLocation(
                    sheetIndex = sheetIndex,
                    imageName = content.imageName,
                    rowIndex = cell.rowIndex,
                    colIndex = cell.columnIndex
                ))
                cell.setBlank()
            }

            is CellContent.RepeatMarker -> {
                cell.setBlank()
            }

            is CellContent.StaticString -> {
                // 정적 문자열에도 ${...} 표현식이 있을 수 있음 (예: ${employees.size()}명)
                val evaluated = evaluateText(content.value, data)
                if (evaluated != content.value) {
                    setCellValue(cell, evaluated)
                }
            }

            else -> {
                // 그 외 정적 값(숫자, 불린)은 그대로 유지
            }
        }
    }

    private fun substituteCell(
        cell: org.apache.poi.ss.usermodel.Cell,
        content: CellContent,
        data: Map<String, Any>,
        itemVariable: String?,
        sheet: XSSFSheet,
        imageLocations: MutableList<ImageLocation>
    ) {
        processCellContent(cell, content, data, sheet.workbook.getSheetIndex(sheet), imageLocations)
    }

    private fun substituteRowVariables(
        sheet: XSSFSheet,
        row: org.apache.poi.ss.usermodel.Row,
        cellBlueprints: List<CellBlueprint>,
        data: Map<String, Any>,
        itemVariable: String?,
        imageLocations: MutableList<ImageLocation>
    ) {
        val sheetIndex = sheet.workbook.getSheetIndex(sheet)
        for (cellBlueprint in cellBlueprints) {
            val cell = row.getCell(cellBlueprint.columnIndex) ?: continue
            processCellContent(cell, cellBlueprint.content, data, sheetIndex, imageLocations)
        }
    }

    /**
     * 이미지 삽입
     */
    private fun insertImages(
        workbook: XSSFWorkbook,
        imageLocations: List<ImageLocation>,
        data: Map<String, Any>
    ) {
        for (location in imageLocations) {
            val imageBytes = data["image.${location.imageName}"] as? ByteArray
                ?: data[location.imageName] as? ByteArray
                ?: continue

            val sheet = workbook.getSheetAt(location.sheetIndex) as XSSFSheet
            val mergedRegion = sheet.findMergedRegion(location.rowIndex, location.colIndex)

            imageInserter.insertImageXssf(
                workbook, sheet, imageBytes,
                location.rowIndex, location.colIndex, mergedRegion
            )
        }
    }

    // ========== SXSSF 모드 (스트리밍) ==========

    /**
     * SXSSF 기반 순차 생성 방식
     * - 템플릿을 분석하여 청사진 생성
     * - 청사진에 따라 순차적으로 행 출력
     * - 수식 참조는 미리 계산하여 설정
     */
    private fun processWithSxssf(templateBytes: ByteArray, data: Map<String, Any>): ByteArray {
        // 1. 템플릿 XSSFWorkbook을 열고 분석 (dxfs 등 스타일 유지를 위해 닫지 않음)
        val templateWorkbook = XSSFWorkbook(ByteArrayInputStream(templateBytes))

        try {
            val blueprint = analyzer.analyzeFromWorkbook(templateWorkbook)
            val styleRegistry = extractStyles(templateWorkbook)

            // 2. 템플릿 워크북의 기존 시트 내용을 모두 삭제 (시트 구조는 유지)
            // 이렇게 하면 dxfs(조건부 서식 스타일) 등이 유지됨
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
                // 조건부 서식 삭제 (새로 확장된 범위로 추가할 예정)
                val scf = sheet.sheetConditionalFormatting
                while (scf.numConditionalFormattings > 0) {
                    scf.removeConditionalFormatting(0)
                }
            }

            // 3. 템플릿 기반 SXSSF 워크북 생성 (dxfs 유지)
            return SXSSFWorkbook(templateWorkbook, 100).use { workbook ->
                val styleMap = styleRegistry.mapValues { (_, style) ->
                    // 스타일 인덱스로 직접 참조 (이미 템플릿에 있으므로 복사 불필요)
                    workbook.xssfWorkbook.getCellStyleAt(style.index.toInt())
                }
                val imageLocations = mutableListOf<ImageLocation>()

                blueprint.sheets.forEachIndexed { index, sheetBlueprint ->
                    val sheet = workbook.getSheetAt(index) as SXSSFSheet
                    processSheetSxssf(sheet, sheetBlueprint, data, styleMap, imageLocations, index)
                }

                // SXSSF에서는 이미지를 바로 삽입
                insertImagesSxssf(workbook, imageLocations, data)

                // 수식 강제 재계산 플래그 설정 (Excel이 파일을 열 때 수식 재계산)
                // SXSSF에서는 스트리밍 특성상 evaluateAll()을 호출할 수 없으므로
                // Excel에게 재계산을 요청
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
        styleMap: Map<Short, org.apache.poi.ss.usermodel.CellStyle>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int
    ) {
        // 열 너비 설정
        blueprint.columnWidths.forEach { (col, width) ->
            sheet.setColumnWidth(col, width)
        }

        // 헤더/푸터 설정 (변수 치환 적용) - 워크북에서 XSSFSheet에 접근
        applyHeaderFooter(sheet.workbook as SXSSFWorkbook, sheetIndex, blueprint.headerFooter, data)

        // 인쇄 설정 적용
        applyPrintSetup(sheet, blueprint.printSetup)

        var currentRowIndex = 0
        var rowOffset = 0  // 반복으로 인한 누적 오프셋

        // 반복 영역 정보 수집
        val repeatRegions = blueprint.rows.filterIsInstance<RowBlueprint.RepeatRow>()
            .associateBy { it.templateRowIndex }

        // 행 작성 컨텍스트 생성 (시트 전체에서 공유)
        val ctx = RowWriteContext(
            sheet = sheet,
            sheetIndex = sheetIndex,
            data = data,
            styleMap = styleMap,
            templateMergedRegions = blueprint.mergedRegions,
            repeatRegions = repeatRegions,
            imageLocations = imageLocations
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
                            // 다중 행 템플릿: 각 아이템에 대해 모든 템플릿 행 작성
                            items.forEachIndexed { itemIdx, item ->
                                val itemData = if (item != null) {
                                    data + (rowBlueprint.itemVariable to item)
                                } else data

                                // 아이템별 컨텍스트 생성 (data만 다름)
                                val itemCtx = ctx.copy(data = itemData)

                                // 각 템플릿 행에 대해 작성
                                for (templateOffset in 0 until templateRowCount) {
                                    val templateRowIdx = rowBlueprint.templateRowIndex + templateOffset
                                    val currentRowBp = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
                                        ?: continue

                                    // 수식 조정 및 셀 스킵을 위한 올바른 반복 인덱스 계산
                                    // 다중 행 템플릿: itemIdx * templateRowCount + templateOffset
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
                            // RIGHT 확장: 템플릿 행 수만큼 행을 생성하고, 각 행에서 열을 오른쪽으로 확장
                            writeRowSxssfWithRightExpansion(
                                sheet, currentRowIndex, rowBlueprint, blueprint, data, items, styleMap,
                                blueprint.mergedRegions, imageLocations, sheetIndex
                            )
                            currentRowIndex += templateRowCount
                        }
                    }

                    // 반복 영역 내 다른 행들은 건너뛰기
                    for (r in rowBlueprint.templateRowIndex..rowBlueprint.repeatEndRowIndex) {
                        processedRepeatRows.add(r)
                    }
                }

                is RowBlueprint.RepeatContinuation -> {
                    // RepeatRow에서 이미 처리됨
                }
            }
        }

        // 병합 영역 설정 (오프셋 적용)
        applyMergedRegions(sheet, blueprint.mergedRegions, repeatRegions, data, rowOffset)

        // 조건부 서식 적용 (오프셋 적용)
        applyConditionalFormattings(sheet, blueprint.conditionalFormattings, repeatRegions, data, rowOffset)
    }

    /**
     * RIGHT 확장 모드로 행 작성 - 열 방향으로 아이템 확장
     * 반복 영역 오른쪽의 기존 셀들은 확장된 열 수만큼 오른쪽으로 이동
     */
    private fun writeRowSxssfWithRightExpansion(
        sheet: SXSSFSheet,
        startRowIndex: Int,
        repeatRow: RowBlueprint.RepeatRow,
        blueprint: SheetBlueprint,
        data: Map<String, Any>,
        items: List<*>,
        styleMap: Map<Short, org.apache.poi.ss.usermodel.CellStyle>,
        templateMergedRegions: List<CellRangeAddress>,
        imageLocations: MutableList<ImageLocation>,
        sheetIndex: Int
    ) {
        val templateColCount = repeatRow.repeatEndCol - repeatRow.repeatStartCol + 1
        val templateRowCount = repeatRow.repeatEndRowIndex - repeatRow.templateRowIndex + 1
        // 열 확장으로 인한 오프셋 계산 (추가 삽입되는 열 수)
        val colShiftAmount = (items.size - 1) * templateColCount

        // 반복 영역 내 각 행에 대해 처리
        for (rowOffset in 0 until templateRowCount) {
            val templateRowIdx = repeatRow.templateRowIndex + rowOffset
            val currentRowIndex = startRowIndex + rowOffset

            // 현재 템플릿 행의 청사진 가져오기 (RepeatRow 또는 RepeatContinuation)
            val rowBlueprint = blueprint.rows.find { it.templateRowIndex == templateRowIdx }
            val cellBlueprints = rowBlueprint?.cells ?: continue

            val row = sheet.createRow(currentRowIndex)
            rowBlueprint.height?.let { row.height = it }

            // 반복 영역 밖의 셀들 (모든 행에서 처리)
            for (cellBlueprint in cellBlueprints) {
                if (cellBlueprint.columnIndex !in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                    // 반복 영역 오른쪽 셀은 열 오프셋 적용
                    val actualColIndex = if (cellBlueprint.columnIndex > repeatRow.repeatEndCol) {
                        cellBlueprint.columnIndex + colShiftAmount
                    } else {
                        cellBlueprint.columnIndex
                    }
                    val cell = row.createCell(actualColIndex)
                    styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }

                    // 수식 내 열 참조 처리: 1) 위치 이동 (원래 참조에 대해), 2) 범위 확장
                    // 순서가 중요함: 먼저 원래 수식의 참조를 이동하고, 그 다음 확장해야
                    // 확장된 범위가 추가로 이동되지 않음
                    val adjustedContent = if (cellBlueprint.content is CellContent.Formula) {
                        var formula = (cellBlueprint.content as CellContent.Formula).formula

                        // 1. 먼저 열 위치 이동 (원래 수식의 참조에 대해)
                        // 예: SUM(B7)에서 B7(열 1)은 startCol(2) 미만이므로 이동 안 함
                        if (colShiftAmount > 0) {
                            formula = FormulaAdjuster.adjustForColumnExpansion(
                                formula,
                                repeatRow.repeatEndCol + 1,
                                colShiftAmount
                            )
                        }

                        // 2. 그 다음 범위 확장 (확장된 범위는 추가 이동 없음)
                        // 예: SUM(B7) → SUM(B7:CW7)
                        // 확장 범위(열 1~100)가 수식 셀(열 104)을 포함하지 않으므로 순환 참조 없음
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
                                    throw com.hunet.common.excel.FormulaExpansionException(
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

                    writeCellContentSxssf(cell, adjustedContent, data, null, sheet, templateMergedRegions, imageLocations, sheetIndex, currentRowIndex, actualColIndex)
                }
            }

            // 각 아이템에 대해 열 방향으로 확장
            items.forEachIndexed { itemIdx, item ->
                val itemData = if (item != null) {
                    data + (repeatRow.itemVariable to item)
                } else data

                // 해당 아이템의 열 시작 위치
                val colOffset = itemIdx * templateColCount

                // 반복 범위 내 셀들 복사
                for (cellBlueprint in cellBlueprints) {
                    if (cellBlueprint.columnIndex in repeatRow.repeatStartCol..repeatRow.repeatEndCol) {
                        val targetColIdx = repeatRow.repeatStartCol + colOffset + (cellBlueprint.columnIndex - repeatRow.repeatStartCol)
                        val cell = row.createCell(targetColIdx)
                        styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }
                        writeCellContentSxssf(cell, cellBlueprint.content, itemData, repeatRow.itemVariable, sheet, templateMergedRegions, imageLocations, sheetIndex, currentRowIndex, targetColIdx)
                    }
                }
            }
        }
    }

    /**
     * 셀 내용 작성 (SXSSF 모드용)
     */
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
        colIndex: Int
    ) {
        when (content) {
            is CellContent.Empty -> {}

            is CellContent.StaticString -> {
                val evaluated = evaluateText(content.value, data)
                cell.setCellValue(evaluated)
            }

            is CellContent.StaticNumber -> cell.setCellValue(content.value)

            is CellContent.StaticBoolean -> cell.setCellValue(content.value)

            is CellContent.Variable -> {
                val evaluated = evaluateText(content.originalText, data)
                setCellValue(cell, evaluated)
            }

            is CellContent.ItemField -> {
                val item = data[content.itemVariable]
                val value = resolveFieldPath(item, content.fieldPath)
                setCellValue(cell, value)
            }

            is CellContent.Formula -> {
                cell.cellFormula = content.formula
            }

            is CellContent.FormulaWithVariables -> {
                val substitutedFormula = evaluateText(content.formula, data)
                cell.cellFormula = substitutedFormula
            }

            is CellContent.ImageMarker -> {
                imageLocations.add(ImageLocation(
                    sheetIndex = sheetIndex,
                    imageName = content.imageName,
                    rowIndex = rowIndex,
                    colIndex = colIndex,
                    mergedRegion = null
                ))
                cell.setBlank()
            }

            is CellContent.RepeatMarker -> {
                cell.setBlank()
            }
        }
    }

    /**
     * SXSSF 모드로 한 행 작성
     *
     * @param ctx 시트 전체에서 공유되는 컨텍스트
     * @param rowIndex 출력할 행 인덱스
     * @param blueprint 행 청사진
     * @param itemVariable 반복 아이템 변수명
     * @param repeatIndex 반복 인덱스 (0부터 시작)
     * @param rowOffset 반복으로 인한 누적 행 오프셋
     * @param repeatRow 다중 행 반복을 위한 반복 영역 정보
     */
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

        // repeat 열 범위 확인 (RepeatRow 또는 전달된 repeatRow에서)
        val repeatColRange = (blueprint as? RowBlueprint.RepeatRow)?.let {
            it.repeatStartCol..it.repeatEndCol
        } ?: repeatRow?.let {
            it.repeatStartCol..it.repeatEndCol
        }

        for (cellBlueprint in blueprint.cells) {
            // repeat 범위 밖의 셀은 첫 번째 반복에서만 출력
            if (repeatColRange != null && repeatIndex > 0) {
                if (cellBlueprint.columnIndex !in repeatColRange) {
                    continue  // 범위 밖 셀은 건너뜀
                }
            }

            val cell = row.createCell(cellBlueprint.columnIndex)
            ctx.styleMap[cellBlueprint.styleIndex]?.let { cell.cellStyle = it }

            when (val content = cellBlueprint.content) {
                is CellContent.Empty -> {}

                is CellContent.StaticString -> {
                    // 정적 문자열에도 표현식이 있을 수 있음
                    val evaluated = evaluateText(content.value, ctx.data)
                    cell.setCellValue(evaluated)
                }

                is CellContent.StaticNumber -> cell.setCellValue(content.value)

                is CellContent.StaticBoolean -> cell.setCellValue(content.value)

                is CellContent.Variable -> {
                    val evaluated = evaluateText(content.originalText, ctx.data)
                    setCellValue(cell, evaluated)
                }

                is CellContent.ItemField -> {
                    val item = ctx.data[content.itemVariable]
                    val value = resolveFieldPath(item, content.fieldPath)
                    setCellValue(cell, value)
                }

                is CellContent.Formula -> {
                    // 수식 참조 조정
                    var adjustedFormula = content.formula
                    var formulaExpanded = false

                    // 반복 인덱스에 따른 조정
                    if (repeatIndex > 0) {
                        adjustedFormula = FormulaAdjuster.adjustForRepeatIndex(adjustedFormula, repeatIndex)
                    }

                    // 반복으로 인한 전체 오프셋 적용 (반복 영역 이후 수식)
                    if (rowOffset > 0 && blueprint is RowBlueprint.StaticRow) {
                        val repeatEndRow = ctx.repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
                        if (repeatEndRow >= 0 && blueprint.templateRowIndex > repeatEndRow) {
                            // 1. 먼저 반복 영역 내 단일 셀 참조를 범위로 확장
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
                                        throw com.hunet.common.excel.FormulaExpansionException(
                                            sheetName = ctx.sheet.sheetName,
                                            cellRef = "${FormulaAdjuster.indexToColumnName(cellBlueprint.columnIndex)}${rowIndex + 1}",
                                            formula = content.formula
                                        )
                                    }
                                    adjustedFormula = expanded
                                    formulaExpanded = true
                                }
                            }

                            // 2. 수식이 확장되지 않은 경우에만 행 오프셋 적용
                            // (확장된 수식은 이미 올바른 셀 위치를 참조함)
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
                    // 수식 내 변수 치환
                    var substitutedFormula = evaluateText(content.formula, ctx.data)

                    // 반복 인덱스에 따른 조정
                    if (repeatIndex > 0) {
                        substitutedFormula = FormulaAdjuster.adjustForRepeatIndex(substitutedFormula, repeatIndex)
                    }

                    cell.cellFormula = substitutedFormula
                }

                is CellContent.ImageMarker -> {
                    // 템플릿 행에서 이미지 셀의 병합 영역 찾기
                    val templateRow = blueprint.templateRowIndex
                    val mergedRegion = ctx.templateMergedRegions.find { region ->
                        templateRow >= region.firstRow && templateRow <= region.lastRow &&
                            cellBlueprint.columnIndex >= region.firstColumn && cellBlueprint.columnIndex <= region.lastColumn
                    }?.let { templateRegion ->
                        // 현재 출력 행에 맞게 병합 영역 조정
                        val rowDiff = rowIndex - templateRow
                        CellRangeAddress(
                            templateRegion.firstRow + rowDiff,
                            templateRegion.lastRow + rowDiff,
                            templateRegion.firstColumn,
                            templateRegion.lastColumn
                        )
                    }

                    // 이미지 위치 기록 (병합 영역 포함)
                    ctx.imageLocations.add(ImageLocation(
                        sheetIndex = ctx.sheetIndex,
                        imageName = content.imageName,
                        rowIndex = rowIndex,
                        colIndex = cellBlueprint.columnIndex,
                        mergedRegion = mergedRegion
                    ))
                    cell.setBlank()
                }

                is CellContent.RepeatMarker -> {
                    cell.setBlank()
                }
            }
        }
    }

    private fun insertImagesSxssf(
        workbook: SXSSFWorkbook,
        imageLocations: List<ImageLocation>,
        data: Map<String, Any>
    ) {
        for (location in imageLocations) {
            val imageBytes = data["image.${location.imageName}"] as? ByteArray
                ?: data[location.imageName] as? ByteArray
                ?: continue

            val sheet = workbook.getSheetAt(location.sheetIndex)

            imageInserter.insertImage(
                workbook, sheet, imageBytes,
                location.rowIndex, location.colIndex, location.mergedRegion
            )
        }
    }

    private fun applyMergedRegions(
        sheet: SXSSFSheet,
        mergedRegions: List<CellRangeAddress>,
        repeatRegions: Map<Int, RowBlueprint.RepeatRow>,
        data: Map<String, Any>,
        totalRowOffset: Int
    ) {
        // 이미 추가된 병합 영역 추적 (겹침 방지)
        val addedRegions = mutableSetOf<String>()

        for (region in mergedRegions) {
            // 반복 영역과 겹치는지 확인
            val overlappingRepeat = repeatRegions.values.find { repeat ->
                region.firstRow >= repeat.templateRowIndex && region.firstRow <= repeat.repeatEndRowIndex
            }

            if (overlappingRepeat != null) {
                // 반복 영역 내 병합: 각 반복 항목마다 병합 생성
                val items = data[overlappingRepeat.collectionName] as? List<*> ?: continue
                val relativeStartRow = region.firstRow - overlappingRepeat.templateRowIndex
                val rowSpan = region.lastRow - region.firstRow

                items.indices.forEach { index ->
                    val newFirstRow = overlappingRepeat.templateRowIndex + index + relativeStartRow
                    val newLastRow = newFirstRow + rowSpan
                    val key = "$newFirstRow:$newLastRow:${region.firstColumn}:${region.lastColumn}"

                    if (key !in addedRegions) {
                        // 겹치는 영역 무시
                        runCatching {
                            sheet.addMergedRegion(CellRangeAddress(
                                newFirstRow, newLastRow, region.firstColumn, region.lastColumn
                            ))
                        }
                        addedRegions.add(key)
                    }
                }
            } else {
                // 반복 영역 외부 병합
                val maxRepeatEndRow = repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
                val offset = if (region.firstRow > maxRepeatEndRow) totalRowOffset else 0

                val newFirstRow = region.firstRow + offset
                val newLastRow = region.lastRow + offset
                val key = "$newFirstRow:$newLastRow:${region.firstColumn}:${region.lastColumn}"

                if (key !in addedRegions) {
                    // 겹치는 영역 무시
                    runCatching {
                        sheet.addMergedRegion(CellRangeAddress(
                            newFirstRow, newLastRow, region.firstColumn, region.lastColumn
                        ))
                    }
                    addedRegions.add(key)
                }
            }
        }
    }

    /**
     * 조건부 서식 적용 (SXSSF 모드용)
     *
     * 템플릿의 조건부 서식을 SXSSF 시트에 적용합니다.
     * 반복 영역에 있는 조건부 서식은 각 반복 아이템에 대해 복제됩니다.
     */
    private fun applyConditionalFormattings(
        sheet: SXSSFSheet,
        conditionalFormattings: List<ConditionalFormattingInfo>,
        repeatRegions: Map<Int, RowBlueprint.RepeatRow>,
        data: Map<String, Any>,
        totalRowOffset: Int
    ) {
        if (conditionalFormattings.isEmpty()) return

        // SXSSFWorkbook의 내부 XSSFWorkbook을 통해 XSSFSheet 접근
        val xssfSheet = (sheet.workbook as SXSSFWorkbook).xssfWorkbook.getSheetAt(sheet.workbook.getSheetIndex(sheet))
        val scf = xssfSheet.sheetConditionalFormatting

        for (cfInfo in conditionalFormattings) {
            val allRanges = mutableListOf<CellRangeAddress>()

            for (range in cfInfo.ranges) {
                // 이 범위가 어떤 반복 영역에 속하는지 확인
                val overlappingRepeat = repeatRegions.values.find { repeat ->
                    range.firstRow >= repeat.templateRowIndex && range.lastRow <= repeat.repeatEndRowIndex
                }

                if (overlappingRepeat != null) {
                    // 반복 영역 내 조건부 서식: 각 반복 아이템마다 복제
                    val items = data[overlappingRepeat.collectionName] as? List<*> ?: continue
                    val templateRowCount = overlappingRepeat.repeatEndRowIndex - overlappingRepeat.templateRowIndex + 1
                    val relativeStartRow = range.firstRow - overlappingRepeat.templateRowIndex
                    val rowSpan = range.lastRow - range.firstRow

                    for (itemIdx in items.indices) {
                        val rowOffset = itemIdx * templateRowCount
                        val newFirstRow = overlappingRepeat.templateRowIndex + rowOffset + relativeStartRow
                        val newLastRow = newFirstRow + rowSpan
                        allRanges.add(CellRangeAddress(
                            newFirstRow, newLastRow, range.firstColumn, range.lastColumn
                        ))
                    }
                } else {
                    // 반복 영역 외부: 오프셋만 적용
                    val maxRepeatEndRow = repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
                    val offset = if (range.firstRow > maxRepeatEndRow) totalRowOffset else 0

                    allRanges.add(CellRangeAddress(
                        range.firstRow + offset,
                        range.lastRow + offset,
                        range.firstColumn,
                        range.lastColumn
                    ))
                }
            }

            if (allRanges.isEmpty()) continue

            // 규칙 생성 및 적용
            // POI의 SheetConditionalFormatting을 사용하여 규칙 추가
            // dxfId를 유지하기 위해 리플렉션으로 내부 CTCfRule에 접근
            val rules = cfInfo.rules.mapNotNull { ruleInfo ->
                runCatching {
                    val rule = when (ruleInfo.conditionType) {
                        org.apache.poi.ss.usermodel.ConditionType.CELL_VALUE_IS -> {
                            scf.createConditionalFormattingRule(
                                ruleInfo.comparisonOperator,
                                ruleInfo.formula1 ?: "",
                                ruleInfo.formula2
                            )
                        }
                        org.apache.poi.ss.usermodel.ConditionType.FORMULA -> {
                            scf.createConditionalFormattingRule(ruleInfo.formula1 ?: "TRUE")
                        }
                        else -> null
                    }

                    // dxfId 설정 (리플렉션 사용)
                    if (rule != null && ruleInfo.dxfId >= 0) {
                        runCatching {
                            val xssfRule = rule as? org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule
                            if (xssfRule != null) {
                                // XSSFConditionalFormattingRule의 _cfRule 필드에 접근
                                val cfRuleField = xssfRule.javaClass.getDeclaredField("_cfRule")
                                cfRuleField.isAccessible = true
                                val ctCfRule = cfRuleField.get(xssfRule) as org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule
                                ctCfRule.dxfId = ruleInfo.dxfId.toLong()
                            }
                        } // 리플렉션 실패 시 dxfId 없이 진행
                    }

                    rule
                }.getOrNull()
            }.toTypedArray()

            if (rules.isNotEmpty()) {
                scf.addConditionalFormatting(allRanges.toTypedArray(), rules)
            }
        }
    }

    // ========== 헤더/푸터 및 인쇄 설정 ==========

    /**
     * 헤더/푸터 설정 적용 (SXSSF 모드용)
     * 템플릿의 헤더/푸터를 복사하고 변수 치환 적용
     *
     * SXSSFWorkbook에서 내부 XSSFWorkbook을 통해 XSSFSheet에 접근하여 설정
     */
    private fun applyHeaderFooter(
        workbook: SXSSFWorkbook,
        sheetIndex: Int,
        headerFooter: HeaderFooterInfo?,
        data: Map<String, Any>
    ) {
        if (headerFooter == null) return

        // SXSSFWorkbook의 내부 XSSFWorkbook을 통해 XSSFSheet 접근
        val xssfSheet = workbook.xssfWorkbook.getSheetAt(sheetIndex)

        // 홀수 페이지 헤더/푸터 (기본)
        val oddHeaderStr = buildHeaderFooterString(
            evaluateTextOrNull(headerFooter.leftHeader, data),
            evaluateTextOrNull(headerFooter.centerHeader, data),
            evaluateTextOrNull(headerFooter.rightHeader, data)
        )
        val oddFooterStr = buildHeaderFooterString(
            evaluateTextOrNull(headerFooter.leftFooter, data),
            evaluateTextOrNull(headerFooter.centerFooter, data),
            evaluateTextOrNull(headerFooter.rightFooter, data)
        )

        if (oddHeaderStr != null || oddFooterStr != null) {
            oddHeaderStr?.let { xssfSheet.oddHeader.apply { left = ""; center = ""; right = "" } }
            applyHeaderFooterParts(
                xssfSheet.oddHeader, xssfSheet.oddFooter, data,
                headerFooter.leftHeader, headerFooter.centerHeader, headerFooter.rightHeader,
                headerFooter.leftFooter, headerFooter.centerFooter, headerFooter.rightFooter
            )
        }

        // 첫 페이지용 (differentFirst=true일 때)
        if (headerFooter.differentFirst) {
            applyHeaderFooterParts(
                xssfSheet.firstHeader, xssfSheet.firstFooter, data,
                headerFooter.firstLeftHeader, headerFooter.firstCenterHeader, headerFooter.firstRightHeader,
                headerFooter.firstLeftFooter, headerFooter.firstCenterFooter, headerFooter.firstRightFooter
            )
        }

        // 짝수 페이지용 (differentOddEven=true일 때)
        if (headerFooter.differentOddEven) {
            applyHeaderFooterParts(
                xssfSheet.evenHeader, xssfSheet.evenFooter, data,
                headerFooter.evenLeftHeader, headerFooter.evenCenterHeader, headerFooter.evenRightHeader,
                headerFooter.evenLeftFooter, headerFooter.evenCenterFooter, headerFooter.evenRightFooter
            )
        }
    }

    /**
     * 헤더/푸터 부분 적용 (중복 코드 제거용 헬퍼)
     */
    private fun applyHeaderFooterParts(
        header: org.apache.poi.ss.usermodel.Header,
        footer: org.apache.poi.ss.usermodel.Footer,
        data: Map<String, Any>,
        leftH: String?, centerH: String?, rightH: String?,
        leftF: String?, centerF: String?, rightF: String?
    ) {
        leftH?.let { header.left = evaluateText(it, data) }
        centerH?.let { header.center = evaluateText(it, data) }
        rightH?.let { header.right = evaluateText(it, data) }
        leftF?.let { footer.left = evaluateText(it, data) }
        centerF?.let { footer.center = evaluateText(it, data) }
        rightF?.let { footer.right = evaluateText(it, data) }
    }

    /**
     * 헤더/푸터 문자열 조합 (Excel 형식: &L...&C...&R...)
     */
    private fun buildHeaderFooterString(left: String?, center: String?, right: String?): String? {
        if (left == null && center == null && right == null) return null
        val sb = StringBuilder()
        left?.let { sb.append("&L").append(it) }
        center?.let { sb.append("&C").append(it) }
        right?.let { sb.append("&R").append(it) }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun evaluateTextOrNull(text: String?, data: Map<String, Any>): String? {
        return text?.let { evaluateText(it, data) }
    }

    /**
     * 인쇄 설정 적용 (SXSSF 모드용)
     */
    private fun applyPrintSetup(sheet: SXSSFSheet, printSetup: PrintSetupInfo?) {
        if (printSetup == null) return

        val ps = sheet.printSetup
        ps.paperSize = printSetup.paperSize
        ps.landscape = printSetup.landscape
        ps.fitWidth = printSetup.fitWidth
        ps.fitHeight = printSetup.fitHeight
        ps.scale = printSetup.scale
        ps.headerMargin = printSetup.headerMargin
        ps.footerMargin = printSetup.footerMargin
    }

    // ========== 유틸리티 ==========

    /**
     * 텍스트 내 ${...} 표현식을 평가하여 치환
     */
    private fun evaluateText(text: String, data: Map<String, Any>): String {
        @Suppress("UNCHECKED_CAST")
        return variableProcessor.processWithData(text, data as Map<String, Any?>)
    }

    private fun resolveFieldPath(obj: Any?, fieldPath: String): Any? {
        if (obj == null) return null

        val fields = fieldPath.split(".")
        var current: Any? = obj

        for (field in fields) {
            current = when (current) {
                is Map<*, *> -> current[field]
                else -> resolveField(current!!, field)
            }
            if (current == null) break
        }

        return current
    }

    /**
     * 리플렉션으로 필드/getter 값을 가져옴 (캐싱 적용)
     */
    private fun resolveField(obj: Any, field: String): Any? {
        val clazz = obj::class.java
        val cacheKey = clazz to field

        // 1. 필드 캐시 확인
        val cachedField = fieldCache.getOrPut(cacheKey) {
            runCatching {
                clazz.getDeclaredField(field).apply { isAccessible = true }
            }.getOrNull()
        }

        if (cachedField != null) {
            return runCatching { cachedField.get(obj) }.getOrNull()
        }

        // 2. getter 캐시 확인
        val cachedGetter = getterCache.getOrPut(cacheKey) {
            runCatching {
                clazz.getMethod("get${field.replaceFirstChar { it.uppercase() }}")
            }.getOrNull()
        }

        return cachedGetter?.let { runCatching { it.invoke(obj) }.getOrNull() }
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
