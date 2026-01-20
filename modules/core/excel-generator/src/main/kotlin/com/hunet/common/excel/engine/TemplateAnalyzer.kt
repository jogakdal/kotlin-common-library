package com.hunet.common.excel.engine

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * 템플릿 분석기 - 템플릿을 분석하여 청사진 생성
 */
class TemplateAnalyzer {

    companion object {
        // ${repeat(collection=employees, range=A6:C6, var=emp)}
        // ${repeat(collection=employees, range=B5:B7, var=emp, direction=RIGHT)}
        // ${repeat(employees, A6:C6, emp)}
        private val REPEAT_PATTERN = Regex(
            """\$\{repeat\s*\(\s*(?:collection\s*=\s*)?(\w+)\s*,\s*(?:range\s*=\s*)?([A-Za-z]+\d+:[A-Za-z]+\d+)\s*(?:,\s*(?:var\s*=\s*)?(\w+))?(?:\s*,\s*(?:direction\s*=\s*)?(DOWN|RIGHT))?\s*\)\}""",
            RegexOption.IGNORE_CASE
        )

        // ${variableName}
        private val VARIABLE_PATTERN = Regex("""\$\{(\w+)}""")

        // ${item.field} or ${item.field.subfield}
        private val ITEM_FIELD_PATTERN = Regex("""\$\{(\w+)\.(\w+(?:\.\w+)*)}""")

        // ${image.name}
        private val IMAGE_PATTERN = Regex("""\$\{image\.(\w+)}""")
    }

    /**
     * 템플릿을 분석하여 워크북 청사진 생성
     */
    fun analyze(template: InputStream): WorkbookBlueprint {
        return XSSFWorkbook(template).use { workbook ->
            WorkbookBlueprint(
                sheets = (0 until workbook.numberOfSheets).map { index ->
                    analyzeSheet(workbook, workbook.getSheetAt(index), index)
                }
            )
        }
    }

    /**
     * 워크북과 함께 분석 (워크북 재사용 시)
     */
    fun analyzeFromWorkbook(workbook: XSSFWorkbook): WorkbookBlueprint {
        return WorkbookBlueprint(
            sheets = (0 until workbook.numberOfSheets).map { index ->
                analyzeSheet(workbook, workbook.getSheetAt(index), index)
            }
        )
    }

    private fun analyzeSheet(workbook: XSSFWorkbook, sheet: Sheet, sheetIndex: Int): SheetBlueprint {
        // 1. 반복 마커 찾기
        val repeatRegions = findRepeatRegions(sheet)

        // 2. 행별 청사진 생성
        val rows = buildRowBlueprints(sheet, repeatRegions)

        // 3. 병합 영역 수집
        val mergedRegions = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it) }

        // 4. 열 너비 수집
        val lastCol = sheet.maxColumnIndex()
        val columnWidths = (0..lastCol).associateWith { sheet.getColumnWidth(it) }

        // 5. 헤더/푸터 정보 추출
        val headerFooter = extractHeaderFooter(sheet)

        // 6. 인쇄 설정 추출
        val printSetup = extractPrintSetup(sheet)

        return SheetBlueprint(
            sheetName = sheet.sheetName,
            sheetIndex = sheetIndex,
            rows = rows,
            mergedRegions = mergedRegions,
            columnWidths = columnWidths,
            defaultRowHeight = sheet.defaultRowHeight,
            headerFooter = headerFooter,
            printSetup = printSetup
        )
    }

    /**
     * 헤더/푸터 정보 추출
     *
     * 주의: XSSFSheet의 getOddHeader(), getEvenHeader() 등의 메서드는
     * 해당 요소가 없을 때 새로 생성하므로, ctWorksheet를 직접 사용하여
     * 부작용 없이 읽기만 수행합니다.
     */
    private fun extractHeaderFooter(sheet: Sheet): HeaderFooterInfo? {
        val xssfSheet = sheet as? XSSFSheet ?: return null

        // ctWorksheet를 통해 headerFooter 요소에 직접 접근 (부작용 없음)
        val ctHf = xssfSheet.ctWorksheet?.headerFooter ?: return null

        // 헤더/푸터 값 추출 (null이면 빈 문자열이 아닌 null)
        val oddHeader = ctHf.oddHeader?.takeIf { it.isNotEmpty() }
        val oddFooter = ctHf.oddFooter?.takeIf { it.isNotEmpty() }
        val evenHeader = ctHf.evenHeader?.takeIf { it.isNotEmpty() }
        val evenFooter = ctHf.evenFooter?.takeIf { it.isNotEmpty() }
        val firstHeader = ctHf.firstHeader?.takeIf { it.isNotEmpty() }
        val firstFooter = ctHf.firstFooter?.takeIf { it.isNotEmpty() }

        // 헤더/푸터가 설정되어 있는지 확인
        val hasAnyHeaderFooter = listOf(
            oddHeader, oddFooter, evenHeader, evenFooter, firstHeader, firstFooter
        ).any { it != null }

        if (!hasAnyHeaderFooter) return null

        // Excel 헤더/푸터 형식: &L, &C, &R로 좌/중/우 구분
        // 예: "&L왼쪽&C중앙&R오른쪽"
        return HeaderFooterInfo(
            leftHeader = parseHeaderFooterSection(oddHeader, 'L'),
            centerHeader = parseHeaderFooterSection(oddHeader, 'C'),
            rightHeader = parseHeaderFooterSection(oddHeader, 'R'),
            leftFooter = parseHeaderFooterSection(oddFooter, 'L'),
            centerFooter = parseHeaderFooterSection(oddFooter, 'C'),
            rightFooter = parseHeaderFooterSection(oddFooter, 'R'),
            differentFirst = ctHf.differentFirst,
            differentOddEven = ctHf.differentOddEven,
            scaleWithDoc = ctHf.isSetScaleWithDoc && ctHf.scaleWithDoc,
            alignWithMargins = ctHf.isSetAlignWithMargins && ctHf.alignWithMargins,
            firstLeftHeader = parseHeaderFooterSection(firstHeader, 'L'),
            firstCenterHeader = parseHeaderFooterSection(firstHeader, 'C'),
            firstRightHeader = parseHeaderFooterSection(firstHeader, 'R'),
            firstLeftFooter = parseHeaderFooterSection(firstFooter, 'L'),
            firstCenterFooter = parseHeaderFooterSection(firstFooter, 'C'),
            firstRightFooter = parseHeaderFooterSection(firstFooter, 'R'),
            evenLeftHeader = parseHeaderFooterSection(evenHeader, 'L'),
            evenCenterHeader = parseHeaderFooterSection(evenHeader, 'C'),
            evenRightHeader = parseHeaderFooterSection(evenHeader, 'R'),
            evenLeftFooter = parseHeaderFooterSection(evenFooter, 'L'),
            evenCenterFooter = parseHeaderFooterSection(evenFooter, 'C'),
            evenRightFooter = parseHeaderFooterSection(evenFooter, 'R')
        )
    }

    /**
     * 헤더/푸터 문자열에서 특정 섹션(L/C/R) 추출
     * Excel 형식: "&L왼쪽텍스트&C중앙텍스트&R오른쪽텍스트"
     */
    private fun parseHeaderFooterSection(headerFooter: String?, section: Char): String? {
        if (headerFooter.isNullOrEmpty()) return null

        val sectionMarker = "&$section"
        val startIdx = headerFooter.indexOf(sectionMarker, ignoreCase = true)
        if (startIdx < 0) return null

        val contentStart = startIdx + 2  // &L, &C, &R 이후
        if (contentStart >= headerFooter.length) return null

        // 다음 섹션 마커 찾기
        val nextSectionIdx = listOf('L', 'C', 'R')
            .filter { it != section }
            .mapNotNull { marker ->
                val idx = headerFooter.indexOf("&$marker", contentStart, ignoreCase = true)
                if (idx >= 0) idx else null
            }
            .minOrNull() ?: headerFooter.length

        val content = headerFooter.substring(contentStart, nextSectionIdx)
        return content.takeIf { it.isNotEmpty() }
    }

    /**
     * 인쇄 설정 추출
     */
    private fun extractPrintSetup(sheet: Sheet): PrintSetupInfo? {
        val ps = sheet.printSetup ?: return null
        return PrintSetupInfo(
            paperSize = ps.paperSize,
            landscape = ps.landscape,
            fitWidth = ps.fitWidth,
            fitHeight = ps.fitHeight,
            scale = ps.scale,
            headerMargin = ps.headerMargin,
            footerMargin = ps.footerMargin
        )
    }

    /**
     * ${repeat(...)} 마커를 찾아 반복 영역 정보 추출
     */
    private fun findRepeatRegions(sheet: Sheet): List<RepeatRegionInfo> {
        val regions = mutableListOf<RepeatRegionInfo>()

        sheet.forEach { row ->
            row.forEach { cell ->
                if (cell.cellType == CellType.STRING) {
                    val text = cell.stringCellValue ?: return@forEach
                    REPEAT_PATTERN.find(text)?.let { match ->
                        val collection = match.groupValues[1]
                        val range = match.groupValues[2]
                        val variable = match.groupValues[3].ifEmpty { collection }
                        val directionStr = match.groupValues[4].uppercase()
                        val direction = if (directionStr == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN

                        val (startCell, endCell) = parseRange(range)
                        regions.add(RepeatRegionInfo(
                            collection = collection,
                            variable = variable,
                            startRow = startCell.first,
                            endRow = endCell.first,
                            startCol = startCell.second,
                            endCol = endCell.second,
                            direction = direction
                        ))
                    }
                }
            }
        }

        return regions
    }

    /**
     * 범위 문자열 파싱 (예: "A6:C8" -> Pair(5,0) to Pair(7,2))
     */
    private fun parseRange(range: String): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val parts = range.split(":")
        val start = parseCellRef(parts[0])
        val end = parseCellRef(parts[1])
        return start to end
    }

    /**
     * 셀 참조 파싱 (예: "A6" -> Pair(5, 0), "b7" -> Pair(6, 1))
     */
    private fun parseCellRef(ref: String): Pair<Int, Int> {
        val colPart = ref.takeWhile { it.isLetter() }.uppercase()
        val rowPart = ref.dropWhile { it.isLetter() }
        val col = colPart.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        val row = rowPart.toInt() - 1
        return row to col
    }

    /**
     * 행별 청사진 생성
     */
    private fun buildRowBlueprints(sheet: Sheet, repeatRegions: List<RepeatRegionInfo>): List<RowBlueprint> {
        val rows = mutableListOf<RowBlueprint>()
        val lastRow = sheet.lastRowNum

        var skipUntil = -1

        for (rowIndex in 0..lastRow) {
            if (rowIndex <= skipUntil) continue

            val row = sheet.getRow(rowIndex)

            // 이 행이 반복 영역의 시작인지 확인
            val repeatRegion = repeatRegions.find { it.startRow == rowIndex }

            if (repeatRegion != null) {
                // 반복 영역 처리
                rows.add(buildRepeatRow(sheet, rowIndex, repeatRegion))

                // 반복 영역 내 나머지 행들도 처리 - repeatRegion.variable을 직접 전달
                for (contRowIndex in (rowIndex + 1)..repeatRegion.endRow) {
                    rows.add(buildRepeatContinuationRow(sheet, contRowIndex, rowIndex, repeatRegion.variable))
                }

                skipUntil = repeatRegion.endRow
            } else {
                // 일반 행
                rows.add(buildStaticRow(sheet, rowIndex))
            }
        }

        return rows
    }

    private fun buildStaticRow(sheet: Sheet, rowIndex: Int): RowBlueprint.StaticRow {
        val row = sheet.getRow(rowIndex)
        return RowBlueprint.StaticRow(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellBlueprints(row, null)
        )
    }

    private fun buildRepeatRow(
        sheet: Sheet,
        rowIndex: Int,
        region: RepeatRegionInfo
    ): RowBlueprint.RepeatRow {
        val row = sheet.getRow(rowIndex)
        return RowBlueprint.RepeatRow(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellBlueprints(row, region.variable),
            collectionName = region.collection,
            itemVariable = region.variable,
            repeatEndRowIndex = region.endRow,
            repeatStartCol = region.startCol,
            repeatEndCol = region.endCol,
            direction = region.direction
        )
    }

    private fun buildRepeatContinuationRow(
        sheet: Sheet,
        rowIndex: Int,
        parentRowIndex: Int,
        repeatItemVariable: String
    ): RowBlueprint.RepeatContinuation {
        val row = sheet.getRow(rowIndex)
        return RowBlueprint.RepeatContinuation(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellBlueprints(row, repeatItemVariable),
            parentRepeatRowIndex = parentRowIndex
        )
    }

    private fun findRepeatRegionInRow(row: org.apache.poi.ss.usermodel.Row): RepeatRegionInfo? {
        row.forEach { cell ->
            if (cell.cellType == CellType.STRING) {
                REPEAT_PATTERN.find(cell.stringCellValue ?: "")?.let { match ->
                    val collection = match.groupValues[1]
                    val range = match.groupValues[2]
                    val variable = match.groupValues[3].ifEmpty { collection }
                    val directionStr = match.groupValues[4].uppercase()
                    val direction = if (directionStr == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN
                    val (startCell, endCell) = parseRange(range)
                    return RepeatRegionInfo(collection, variable, startCell.first, endCell.first, startCell.second, endCell.second, direction)
                }
            }
        }
        return null
    }

    private fun buildCellBlueprints(
        row: org.apache.poi.ss.usermodel.Row?,
        repeatItemVariable: String?
    ): List<CellBlueprint> {
        if (row == null) return emptyList()

        return row.mapNotNull { cell ->
            CellBlueprint(
                columnIndex = cell.columnIndex,
                styleIndex = cell.cellStyle?.index ?: 0,
                content = analyzeCellContent(cell, repeatItemVariable)
            )
        }
    }

    private fun analyzeCellContent(cell: Cell, repeatItemVariable: String?): CellContent {
        return when (cell.cellType) {
            CellType.BLANK -> CellContent.Empty
            CellType.BOOLEAN -> CellContent.StaticBoolean(cell.booleanCellValue)
            CellType.NUMERIC -> CellContent.StaticNumber(cell.numericCellValue)
            CellType.FORMULA -> analyzeFormulaContent(cell.cellFormula)
            CellType.STRING -> analyzeStringContent(cell.stringCellValue, repeatItemVariable)
            else -> CellContent.Empty
        }
    }

    /**
     * 수식 내용 분석 - 변수 포함 여부 확인
     */
    private fun analyzeFormulaContent(formula: String): CellContent {
        val variables = VARIABLE_PATTERN.findAll(formula).map { it.groupValues[1] }.toList()
        return if (variables.isNotEmpty()) {
            CellContent.FormulaWithVariables(formula, variables)
        } else {
            CellContent.Formula(formula)
        }
    }

    private fun analyzeStringContent(text: String?, repeatItemVariable: String?): CellContent {
        if (text.isNullOrEmpty()) return CellContent.Empty

        // 1. 반복 마커 확인
        REPEAT_PATTERN.find(text)?.let { match ->
            val directionStr = match.groupValues[4].uppercase()
            val direction = if (directionStr == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN
            return CellContent.RepeatMarker(
                collection = match.groupValues[1],
                range = match.groupValues[2],
                variable = match.groupValues[3].ifEmpty { match.groupValues[1] },
                direction = direction
            )
        }

        // 2. 이미지 마커 확인
        IMAGE_PATTERN.find(text)?.let { match ->
            return CellContent.ImageMarker(match.groupValues[1])
        }

        // 3. 아이템 필드 확인 (${emp.name})
        ITEM_FIELD_PATTERN.find(text)?.let { match ->
            val itemVar = match.groupValues[1]
            val fieldPath = match.groupValues[2]
            // 반복 영역 내 변수와 일치하는지 확인
            if (repeatItemVariable != null && itemVar == repeatItemVariable) {
                return CellContent.ItemField(itemVar, fieldPath, text)
            }
        }

        // 4. 단순 변수 확인 (${title})
        if (VARIABLE_PATTERN.containsMatchIn(text)) {
            val varName = VARIABLE_PATTERN.find(text)!!.groupValues[1]
            return CellContent.Variable(varName, text)
        }

        // 5. 일반 문자열
        return CellContent.StaticString(text)
    }

    private fun Sheet.maxColumnIndex(): Int {
        var max = 0
        forEach { row ->
            row.lastCellNum.let { if (it > max) max = it.toInt() }
        }
        return max
    }
}
