package com.hunet.common.excel.engine

import com.hunet.common.excel.toColumnIndex
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * 셀 좌표 (row, col 모두 0-based)
 */
private data class CellCoord(val row: Int, val col: Int)

/**
 * 셀 범위
 */
private data class CellRange(val start: CellCoord, val end: CellCoord)

/**
 * 템플릿 분석기 - 템플릿을 분석하여 워크북 명세 생성
 */
class TemplateAnalyzer {

    companion object {
        // ${repeat(collection=employees, range=A6:C6, var=emp)}
        // ${repeat(collection=employees, range=B5:B7, var=emp, direction=RIGHT)}
        // ${repeat(employees, A6:C6, emp)}
        // ${repeat(employees, DataRange, emp)}  ← Named Range 지원
        // Arguments can be quoted (", ', `) or unquoted
        private val REPEAT_PATTERN = Regex(
            """\$\{repeat\s*\(\s*(?:collection\s*=\s*)?["'`]?(\w+)["'`]?\s*,\s*(?:range\s*=\s*)?["'`]?([A-Za-z]+\d+:[A-Za-z]+\d+|\w+)["'`]?\s*(?:,\s*(?:var\s*=\s*)?["'`]?(\w+)["'`]?)?(?:\s*,\s*(?:direction\s*=\s*)?["'`]?(DOWN|RIGHT)["'`]?)?\s*\)\}""",
            RegexOption.IGNORE_CASE
        )

        // ${variableName}
        private val VARIABLE_PATTERN = Regex("""\$\{(\w+)}""")

        // ${item.field} or ${item.field.subfield}
        private val ITEM_FIELD_PATTERN = Regex("""\$\{(\w+)\.(\w+(?:\.\w+)*)}""")

        // ${image.name} - 레거시 문법 (하위 호환성)
        private val IMAGE_LEGACY_PATTERN = Regex("""\$\{image\.(\w+)}""")

        // ${image(name)} or ${image(name, position)} or ${image(name, position, size)}
        // Arguments can be quoted (", ', `) or unquoted: image(ci, B5, 0:-1) == image(ci, "B5", "0:-1")
        private val IMAGE_PATTERN = Regex(
            """\$\{image\((\w+)(?:\s*,\s*["'`]?([A-Za-z]*\d*)["'`]?)?(?:\s*,\s*["'`]?(-?\d+:-?\d+)["'`]?)?\)}"""
        )
    }

    /**
     * 템플릿을 분석하여 워크북 명세 생성
     */
    fun analyze(template: InputStream): WorkbookSpec {
        return XSSFWorkbook(template).use { workbook ->
            WorkbookSpec(
                sheets = (0 until workbook.numberOfSheets).map { index ->
                    analyzeSheet(workbook, workbook.getSheetAt(index), index)
                }
            )
        }
    }

    /**
     * 워크북과 함께 분석 (워크북 재사용 시)
     */
    fun analyzeFromWorkbook(workbook: XSSFWorkbook): WorkbookSpec {
        return WorkbookSpec(
            sheets = (0 until workbook.numberOfSheets).map { index ->
                analyzeSheet(workbook, workbook.getSheetAt(index), index)
            }
        )
    }

    private fun analyzeSheet(workbook: XSSFWorkbook, sheet: Sheet, sheetIndex: Int): SheetSpec {
        val repeatRegions = findRepeatRegions(workbook, sheet)

        return SheetSpec(
            sheetName = sheet.sheetName,
            sheetIndex = sheetIndex,
            rows = buildRowSpecs(sheet, repeatRegions),
            mergedRegions = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it) },
            columnWidths = (0..sheet.maxColumnIndex()).associateWith { sheet.getColumnWidth(it) },
            defaultRowHeight = sheet.defaultRowHeight,
            headerFooter = extractHeaderFooter(sheet),
            printSetup = extractPrintSetup(sheet),
            conditionalFormattings = extractConditionalFormattings(sheet)
        )
    }

    /**
     * 헤더/푸터 정보 추출
     *
     * 주의: XSSFSheet의 getOddHeader(), getEvenHeader() 등의 메서드는
     * 해당 요소가 없을 때 새로 생성하므로, ctWorksheet를 직접 사용하여
     * 부작용 없이 읽기만 수행합니다.
     */
    private fun extractHeaderFooter(sheet: Sheet): HeaderFooterSpec? {
        val xssfSheet = sheet as? XSSFSheet ?: return null
        val ctHf = xssfSheet.ctWorksheet?.headerFooter ?: return null

        val oddHeader = ctHf.oddHeader?.takeIf { it.isNotEmpty() }
        val oddFooter = ctHf.oddFooter?.takeIf { it.isNotEmpty() }
        val evenHeader = ctHf.evenHeader?.takeIf { it.isNotEmpty() }
        val evenFooter = ctHf.evenFooter?.takeIf { it.isNotEmpty() }
        val firstHeader = ctHf.firstHeader?.takeIf { it.isNotEmpty() }
        val firstFooter = ctHf.firstFooter?.takeIf { it.isNotEmpty() }

        val hasAnyHeaderFooter = listOf(
            oddHeader, oddFooter, evenHeader, evenFooter, firstHeader, firstFooter
        ).any { it != null }

        if (!hasAnyHeaderFooter) return null

        // Excel 헤더/푸터 형식: &L, &C, &R로 좌/중/우 구분 (예: "&L왼쪽&C중앙&R오른쪽")
        return HeaderFooterSpec(
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
    private fun parseHeaderFooterSection(headerFooter: String?, section: Char): String? =
        headerFooter?.takeIf { it.isNotEmpty() }?.let { hf ->
            val marker = "&$section"
            val startIdx = hf.indexOf(marker, ignoreCase = true).takeIf { it >= 0 } ?: return null
            val contentStart = startIdx + 2
            if (contentStart >= hf.length) return null

            val nextSectionIdx = listOf('L', 'C', 'R')
                .filter { it != section }
                .mapNotNull { hf.indexOf("&$it", contentStart, ignoreCase = true).takeIf { idx -> idx >= 0 } }
                .minOrNull() ?: hf.length

            hf.substring(contentStart, nextSectionIdx).takeIf { it.isNotEmpty() }
        }

    /**
     * 인쇄 설정 추출
     */
    private fun extractPrintSetup(sheet: Sheet): PrintSetupSpec? {
        val ps = sheet.printSetup ?: return null
        return PrintSetupSpec(
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
     * 조건부 서식 정보 추출 (SXSSF 모드용)
     *
     * 템플릿의 조건부 서식을 명세에 저장하여 SXSSF 모드에서 복원할 수 있도록 합니다.
     */
    private fun extractConditionalFormattings(sheet: Sheet): List<ConditionalFormattingSpec> {
        val xssfSheet = sheet as? XSSFSheet ?: return emptyList()
        val scf = xssfSheet.sheetConditionalFormatting
        val count = scf.numConditionalFormattings

        if (count == 0) return emptyList()

        return (0 until count).mapNotNull { i ->
            val cf = scf.getConditionalFormattingAt(i) ?: return@mapNotNull null
            val ranges = cf.formattingRanges.toList()
            if (ranges.isEmpty()) return@mapNotNull null

            val rules = (0 until cf.numberOfRules).mapNotNull { ruleIndex ->
                val rule = cf.getRule(ruleIndex) ?: return@mapNotNull null

                // XSSFConditionalFormattingRule에서 dxfId 추출
                val xssfRule = rule as? XSSFConditionalFormattingRule
                val dxfId = xssfRule?.let {
                    runCatching {
                        // ctCfRule.dxfId 접근
                        val ctRule = it.javaClass.getDeclaredField("_cfRule").apply { isAccessible = true }.get(it)
                        val getDxfId = ctRule.javaClass.getMethod("getDxfId")
                        (getDxfId.invoke(ctRule) as? Long)?.toInt() ?: -1
                    }.getOrDefault(-1)
                } ?: -1

                ConditionalFormattingRuleSpec(
                    conditionType = rule.conditionType ?: ConditionType.CELL_VALUE_IS,
                    comparisonOperator = rule.comparisonOperation ?: ComparisonOperator.NO_COMPARISON,
                    formula1 = rule.formula1,
                    formula2 = rule.formula2,
                    dxfId = dxfId,
                    priority = rule.priority,
                    stopIfTrue = rule.stopIfTrue
                )
            }

            if (rules.isEmpty()) return@mapNotNull null

            ConditionalFormattingSpec(
                ranges = ranges,
                rules = rules
            )
        }
    }

    /**
     * ${repeat(...)} 마커를 찾아 반복 영역 정보 추출
     */
    private fun findRepeatRegions(workbook: Workbook, sheet: Sheet): List<RepeatRegionSpec> {
        val regions = mutableListOf<RepeatRegionSpec>()

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

                        val cellRange = parseRange(workbook, range)
                        regions.add(RepeatRegionSpec(
                            collection = collection,
                            variable = variable,
                            startRow = cellRange.start.row,
                            endRow = cellRange.end.row,
                            startCol = cellRange.start.col,
                            endCol = cellRange.end.col,
                            direction = direction
                        ))
                    }
                }
            }
        }

        return regions
    }

    /**
     * 범위 문자열 파싱
     *
     * @param workbook Named Range 조회용 워크북
     * @param range 셀 범위("A6:C8") 또는 Named Range("DataRange")
     * @return CellRange
     */
    private fun parseRange(workbook: Workbook, range: String): CellRange {
        // 콜론이 있으면 직접 셀 참조
        if (":" in range) {
            val (startRef, endRef) = range.split(":")
            return CellRange(parseCellRef(startRef), parseCellRef(endRef))
        }

        // Named Range 조회
        val namedRange = workbook.getName(range)
            ?: throw IllegalArgumentException("Named Range를 찾을 수 없습니다: $range")

        val areaRef = AreaReference(namedRange.refersToFormula, workbook.spreadsheetVersion)
        val firstCell = areaRef.firstCell
        val lastCell = areaRef.lastCell

        return CellRange(
            CellCoord(firstCell.row, firstCell.col.toInt()),
            CellCoord(lastCell.row, lastCell.col.toInt())
        )
    }

    /**
     * 셀 참조 파싱 (예: "A6" -> CellCoord(row=5, col=0))
     */
    private fun parseCellRef(ref: String): CellCoord {
        val colPart = ref.takeWhile(Char::isLetter)
        val rowPart = ref.dropWhile(Char::isLetter)
        return CellCoord(
            row = rowPart.toInt() - 1,
            col = toColumnIndex(colPart)
        )
    }

    /**
     * 행별 명세 생성
     */
    private fun buildRowSpecs(sheet: Sheet, repeatRegions: List<RepeatRegionSpec>): List<RowSpec> {
        val repeatByStartRow = repeatRegions.associateBy { it.startRow }

        return buildList {
            var skipUntil = -1
            for (rowIndex in 0..sheet.lastRowNum) {
                if (rowIndex <= skipUntil) continue

                repeatByStartRow[rowIndex]?.let { region ->
                    add(buildRepeatRow(sheet, rowIndex, region))
                    (rowIndex + 1..region.endRow).forEach { contRowIndex ->
                        add(buildRepeatContinuationRow(sheet, contRowIndex, rowIndex, region.variable))
                    }
                    skipUntil = region.endRow
                } ?: add(buildStaticRow(sheet, rowIndex))
            }
        }
    }

    private fun buildStaticRow(sheet: Sheet, rowIndex: Int): RowSpec.StaticRow {
        val row = sheet.getRow(rowIndex)
        return RowSpec.StaticRow(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellSpecs(row, null)
        )
    }

    private fun buildRepeatRow(
        sheet: Sheet,
        rowIndex: Int,
        region: RepeatRegionSpec
    ): RowSpec.RepeatRow {
        val row = sheet.getRow(rowIndex)
        return RowSpec.RepeatRow(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellSpecs(row, region.variable),
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
    ): RowSpec.RepeatContinuation {
        val row = sheet.getRow(rowIndex)
        return RowSpec.RepeatContinuation(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellSpecs(row, repeatItemVariable),
            parentRepeatRowIndex = parentRowIndex
        )
    }

    private fun findRepeatRegionInRow(workbook: Workbook, row: Row): RepeatRegionSpec? {
        row.forEach { cell ->
            if (cell.cellType == CellType.STRING) {
                REPEAT_PATTERN.find(cell.stringCellValue ?: "")?.let { match ->
                    val collection = match.groupValues[1]
                    val range = match.groupValues[2]
                    val variable = match.groupValues[3].ifEmpty { collection }
                    val directionStr = match.groupValues[4].uppercase()
                    val direction = if (directionStr == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN
                    val cellRange = parseRange(workbook, range)
                    return RepeatRegionSpec(
                        collection, variable,
                        cellRange.start.row, cellRange.end.row,
                        cellRange.start.col, cellRange.end.col,
                        direction
                    )
                }
            }
        }
        return null
    }

    private fun buildCellSpecs(
        row: Row?,
        repeatItemVariable: String?
    ): List<CellSpec> {
        if (row == null) return emptyList()

        return row.mapNotNull { cell ->
            CellSpec(
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
    private fun analyzeFormulaContent(formula: String): CellContent =
        VARIABLE_PATTERN.findAll(formula).map { it.groupValues[1] }.toList().let { variables ->
            if (variables.isNotEmpty()) CellContent.FormulaWithVariables(formula, variables)
            else CellContent.Formula(formula)
        }

    private fun analyzeStringContent(text: String?, repeatItemVariable: String?): CellContent {
        if (text.isNullOrEmpty()) return CellContent.Empty

        // 반복 마커
        REPEAT_PATTERN.find(text)?.let { match ->
            val direction = if (match.groupValues[4].uppercase() == "RIGHT")
                RepeatDirection.RIGHT else RepeatDirection.DOWN
            return CellContent.RepeatMarker(
                collection = match.groupValues[1],
                range = match.groupValues[2],
                variable = match.groupValues[3].ifEmpty { match.groupValues[1] },
                direction = direction
            )
        }

        // 이미지 마커 - ${image(name, position, size)}
        IMAGE_PATTERN.find(text)?.let { match ->
            val name = match.groupValues[1]
            val position = match.groupValues[2].takeIf { it.isNotEmpty() }
            val sizeSpec = parseSizeSpec(match.groupValues[3])
            return CellContent.ImageMarker(name, position, sizeSpec)
        }

        // 이미지 마커 (레거시) - ${image.name}
        IMAGE_LEGACY_PATTERN.find(text)?.let { match ->
            return CellContent.ImageMarker(match.groupValues[1])
        }

        // 아이템 필드 (반복 영역 내 변수와 일치하는지 확인)
        ITEM_FIELD_PATTERN.find(text)?.let { match ->
            if (repeatItemVariable != null && match.groupValues[1] == repeatItemVariable) {
                return CellContent.ItemField(match.groupValues[1], match.groupValues[2], text)
            }
        }

        // 단순 변수
        VARIABLE_PATTERN.find(text)?.let { match ->
            return CellContent.Variable(match.groupValues[1], text)
        }

        return CellContent.StaticString(text)
    }

    /**
     * 이미지 크기 명세 파싱
     *
     * @param sizeStr "width:height" 형식 (예: "100:200", "0:-1", "-1:-1")
     * @return ImageSizeSpec
     */
    private fun parseSizeSpec(sizeStr: String?): ImageSizeSpec {
        if (sizeStr.isNullOrEmpty()) return ImageSizeSpec.FIT_TO_CELL
        val parts = sizeStr.split(":")
        if (parts.size != 2) return ImageSizeSpec.FIT_TO_CELL
        return ImageSizeSpec(
            width = parts[0].toIntOrNull() ?: 0,
            height = parts[1].toIntOrNull() ?: 0
        )
    }

    private fun Sheet.maxColumnIndex(): Int =
        maxOfOrNull { row -> row.lastCellNum.toInt() } ?: 0
}
