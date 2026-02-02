package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.engine.core.parseCellRef
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.AreaReference
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
        // repeat 마커 패턴
        // 예: ${repeat(employees, A6:C6, emp)}, ${repeat(employees, DataRange, emp, DOWN)}
        private val REPEAT_PATTERN = Regex(
            """\$\{repeat\s*\(\s*(?:collection\s*=\s*)?["'`]?(\w+)["'`]?\s*,\s*(?:range\s*=\s*)?["'`]?([A-Za-z]+\d+:[A-Za-z]+\d+|\w+)["'`]?\s*(?:,\s*(?:var\s*=\s*)?["'`]?(\w+)["'`]?)?(?:\s*,\s*(?:direction\s*=\s*)?["'`]?(DOWN|RIGHT)["'`]?)?\s*\)}""",
            RegexOption.IGNORE_CASE
        )

        // ${variableName}
        private val VARIABLE_PATTERN = Regex("""\$\{(\w+)}""")

        // 아이템 필드 패턴: ${item.field} 또는 ${item.field.subfield}
        private val ITEM_FIELD_PATTERN = Regex("""\$\{(\w+)\.(\w+(?:\.\w+)*)}""")

        // 이미지 마커 패턴: ${image(name)}, ${image(name, position)}, ${image(name, position, size)}
        private val IMAGE_PATTERN = Regex(
            """\$\{image\((\w+)(?:\s*,\s*["'`]?([A-Za-z]*\d*)["'`]?)?(?:\s*,\s*["'`]?(-?\d+:-?\d+)["'`]?)?\)}""",
            RegexOption.IGNORE_CASE
        )

        // 수식 형태 repeat 마커: =TBEG_REPEAT(collection, range, var, direction)
        private val FORMULA_REPEAT_PATTERN = Regex(
            """TBEG_REPEAT\s*\(\s*["'`]?(\w+)["'`]?\s*,\s*["'`]?([A-Za-z]+\d+:[A-Za-z]+\d+|\w+)["'`]?\s*(?:,\s*["'`]?(\w+)["'`]?)?(?:\s*,\s*["'`]?(DOWN|RIGHT)["'`]?)?\s*\)""",
            RegexOption.IGNORE_CASE
        )

        // 수식 형태 이미지 마커: =TBEG_IMAGE(name, position, size)
        private val FORMULA_IMAGE_PATTERN = Regex(
            """TBEG_IMAGE\s*\(\s*["'`]?(\w+)["'`]?(?:\s*,\s*["'`]?([A-Za-z]+\d+(?::[A-Za-z]+\d+)?)["'`]?)?(?:\s*,\s*["'`]?(-?\d+:-?\d+)["'`]?)?\s*\)""",
            RegexOption.IGNORE_CASE
        )

        // 컬렉션 크기 마커: ${size(collection)}
        private val SIZE_PATTERN = Regex(
            """\$\{size\s*\(\s*["'`]?(\w+)["'`]?\s*\)}""",
            RegexOption.IGNORE_CASE
        )

        // =TBEG_SIZE(collection) - 컬렉션 크기 마커 (수식 형태)
        private val FORMULA_SIZE_PATTERN = Regex(
            """TBEG_SIZE\s*\(\s*["'`]?(\w+)["'`]?\s*\)""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * 템플릿을 분석하여 워크북 명세 생성
     */
    fun analyze(template: InputStream) =
        XSSFWorkbook(template).use { workbook ->
            WorkbookSpec(
                sheets = (0 until workbook.numberOfSheets).map { index ->
                    analyzeSheet(workbook, workbook.getSheetAt(index), index)
                }
            )
        }

    /**
     * 워크북과 함께 분석 (워크북 재사용 시)
     */
    fun analyzeFromWorkbook(workbook: XSSFWorkbook) =
        WorkbookSpec(
            sheets = (0 until workbook.numberOfSheets).map { index ->
                analyzeSheet(workbook, workbook.getSheetAt(index), index)
            }
        )

    private fun analyzeSheet(workbook: XSSFWorkbook, sheet: Sheet, sheetIndex: Int) =
        findRepeatRegions(workbook, sheet).let { repeatRegions ->
            SheetSpec(
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
     * 부작용 없이 읽기만 수행한다.
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

        if (listOf(oddHeader, oddFooter, evenHeader, evenFooter, firstHeader, firstFooter).all { it == null }) {
            return null
        }

        // 헤더/푸터 문자열에서 L/C/R 섹션 추출 헬퍼
        fun String?.section(s: Char) = parseHeaderFooterSection(this, s)

        return HeaderFooterSpec(
            leftHeader = oddHeader.section('L'),
            centerHeader = oddHeader.section('C'),
            rightHeader = oddHeader.section('R'),
            leftFooter = oddFooter.section('L'),
            centerFooter = oddFooter.section('C'),
            rightFooter = oddFooter.section('R'),
            differentFirst = ctHf.differentFirst,
            differentOddEven = ctHf.differentOddEven,
            scaleWithDoc = ctHf.isSetScaleWithDoc && ctHf.scaleWithDoc,
            alignWithMargins = ctHf.isSetAlignWithMargins && ctHf.alignWithMargins,
            firstLeftHeader = firstHeader.section('L'),
            firstCenterHeader = firstHeader.section('C'),
            firstRightHeader = firstHeader.section('R'),
            firstLeftFooter = firstFooter.section('L'),
            firstCenterFooter = firstFooter.section('C'),
            firstRightFooter = firstFooter.section('R'),
            evenLeftHeader = evenHeader.section('L'),
            evenCenterHeader = evenHeader.section('C'),
            evenRightHeader = evenHeader.section('R'),
            evenLeftFooter = evenFooter.section('L'),
            evenCenterFooter = evenFooter.section('C'),
            evenRightFooter = evenFooter.section('R')
        )
    }

    /**
     * 헤더/푸터 문자열에서 특정 섹션(L/C/R) 추출
     * Excel 형식: "&L왼쪽텍스트&C중앙텍스트&R오른쪽텍스트"
     */
    private fun parseHeaderFooterSection(hf: String?, section: Char): String? {
        if (hf.isNullOrEmpty()) return null

        val marker = "&$section"
        val startIdx = hf.indexOf(marker, ignoreCase = true)
        if (startIdx < 0) return null

        val contentStart = startIdx + 2
        if (contentStart >= hf.length) return null

        val nextSectionIdx = listOf('L', 'C', 'R')
            .filter { it != section }
            .mapNotNull { hf.indexOf("&$it", contentStart, ignoreCase = true).takeIf { idx -> idx >= 0 } }
            .minOrNull() ?: hf.length

        return hf.substring(contentStart, nextSectionIdx).takeIf { it.isNotEmpty() }
    }

    /**
     * 인쇄 설정 추출
     */
    private fun extractPrintSetup(sheet: Sheet) =
        sheet.printSetup?.let { ps ->
            PrintSetupSpec(
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
     * 템플릿의 조건부 서식을 명세에 저장하여 SXSSF 모드에서 복원할 수 있도록 한다.
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
                val dxfId = runCatching {
                    // ctCfRule.dxfId 접근
                    val ctRule = rule.javaClass.getDeclaredField("_cfRule").apply { isAccessible = true }.get(rule)
                    val getDxfId = ctRule.javaClass.getMethod("getDxfId")
                    (getDxfId.invoke(ctRule) as? Long)?.toInt() ?: -1
                }.getOrDefault(-1)

                ConditionalFormattingRuleSpec(
                    conditionType = rule.conditionType ?: ConditionType.CELL_VALUE_IS,
                    comparisonOperator = rule.comparisonOperation,
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
     * ${repeat(...)} 또는 =TBEG_REPEAT(...) 마커를 찾아 반복 영역 정보 추출
     */
    private fun findRepeatRegions(workbook: Workbook, sheet: Sheet): List<RepeatRegionSpec> =
        buildList {
            sheet.forEach { row ->
                row.forEach { cell ->
                    val (pattern, content) = when (cell.cellType) {
                        CellType.STRING -> REPEAT_PATTERN to cell.stringCellValue
                        CellType.FORMULA -> FORMULA_REPEAT_PATTERN to cell.cellFormula
                        else -> return@forEach
                    }
                    content ?: return@forEach

                    pattern.find(content)?.let { match ->
                        add(createRepeatRegionSpec(workbook, match))
                    }
                }
            }
        }

    private fun createRepeatRegionSpec(workbook: Workbook, match: MatchResult) =
        parseRange(workbook, match.groupValues[2]).let { range ->
            val collection = match.groupValues[1]
            RepeatRegionSpec(
                collection = collection,
                variable = match.groupValues[3].ifEmpty { collection },
                startRow = range.start.row,
                endRow = range.end.row,
                startCol = range.start.col,
                endCol = range.end.col,
                direction = if (match.groupValues[4].uppercase() == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN
            )
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
            val (startRow, startCol) = parseCellRef(startRef)
            val (endRow, endCol) = parseCellRef(endRef)
            return CellRange(CellCoord(startRow, startCol), CellCoord(endRow, endCol))
        }

        // Named Range 조회
        val namedRange = workbook.getName(range)
            ?: throw IllegalArgumentException("Named Range를 찾을 수 없습니다: $range")

        val formula = namedRange.refersToFormula
        // 참조가 깨진 경우(#REF!) 처리
        if (formula.contains("#REF!")) {
            throw IllegalArgumentException("Named Range '$range'의 참조가 유효하지 않습니다: $formula")
        }

        val areaRef = AreaReference(formula, workbook.spreadsheetVersion)
        return CellRange(
            CellCoord(areaRef.firstCell.row, areaRef.firstCell.col.toInt()),
            CellCoord(areaRef.lastCell.row, areaRef.lastCell.col.toInt())
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

    private fun buildStaticRow(sheet: Sheet, rowIndex: Int) =
        sheet.getRow(rowIndex).let { row ->
            RowSpec.StaticRow(
                templateRowIndex = rowIndex,
                height = row?.height,
                cells = buildCellSpecs(row, null)
            )
        }

    private fun buildRepeatRow(sheet: Sheet, rowIndex: Int, region: RepeatRegionSpec) =
        sheet.getRow(rowIndex).let { row ->
            RowSpec.RepeatRow(
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
    ) = sheet.getRow(rowIndex).let { row ->
        RowSpec.RepeatContinuation(
            templateRowIndex = rowIndex,
            height = row?.height,
            cells = buildCellSpecs(row, repeatItemVariable),
            parentRepeatRowIndex = parentRowIndex
        )
    }

    private fun buildCellSpecs(row: Row?, repeatItemVariable: String?) =
        row?.mapNotNull { cell ->
            CellSpec(
                columnIndex = cell.columnIndex,
                styleIndex = cell.cellStyle?.index ?: 0,
                content = analyzeCellContent(cell, repeatItemVariable)
            )
        } ?: emptyList()

    private fun analyzeCellContent(cell: Cell, repeatItemVariable: String?) =
        when (cell.cellType) {
            CellType.BLANK -> CellContent.Empty
            CellType.BOOLEAN -> CellContent.StaticBoolean(cell.booleanCellValue)
            CellType.NUMERIC -> CellContent.StaticNumber(cell.numericCellValue)
            CellType.FORMULA -> analyzeFormulaContent(cell.cellFormula)
            CellType.STRING -> analyzeStringContent(cell.stringCellValue, repeatItemVariable)
            else -> CellContent.Empty
        }

    /**
     * 수식 내용 분석 - TBEG 마커 또는 변수 포함 여부 확인
     */
    private fun analyzeFormulaContent(formula: String): CellContent {
        // TBEG_REPEAT 수식 마커
        FORMULA_REPEAT_PATTERN.find(formula)?.let { match ->
            val collection = match.groupValues[1]
            val range = match.groupValues[2]
            val variable = match.groupValues[3].ifEmpty { collection }
            val direction = if (match.groupValues[4].uppercase() == "RIGHT")
                RepeatDirection.RIGHT else RepeatDirection.DOWN
            return CellContent.RepeatMarker(collection, range, variable, direction)
        }

        // TBEG_IMAGE 수식 마커
        FORMULA_IMAGE_PATTERN.find(formula)?.let { match ->
            val name = match.groupValues[1]
            val positionOrRange = match.groupValues[2].takeIf { it.isNotEmpty() }
            val sizeStr = match.groupValues[3].takeIf { it.isNotEmpty() }

            val sizeSpec = when {
                positionOrRange?.contains(":") == true -> ImageSizeSpec.FIT_TO_CELL
                sizeStr != null -> parseSizeSpec(sizeStr)
                else -> ImageSizeSpec.FIT_TO_CELL
            }
            return CellContent.ImageMarker(name, positionOrRange, sizeSpec)
        }

        // TBEG_SIZE 수식 마커
        FORMULA_SIZE_PATTERN.find(formula)?.let { match ->
            val collectionName = match.groupValues[1]
            return CellContent.SizeMarker(collectionName, "=$formula")
        }

        // 일반 수식 - 변수 포함 여부 확인
        return VARIABLE_PATTERN.findAll(formula).map { it.groupValues[1] }.toList().let { variables ->
            if (variables.isNotEmpty()) CellContent.FormulaWithVariables(formula, variables)
            else CellContent.Formula(formula)
        }
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

        // 컬렉션 크기 마커 - ${size(collection)}
        SIZE_PATTERN.find(text)?.let { match ->
            val collectionName = match.groupValues[1]
            return CellContent.SizeMarker(collectionName, text)
        }

        // 수식 마커가 문자열로 저장된 경우 - =TBEG_SIZE(collection)
        // (수식 복사 시 Named Range 검증 실패로 문자열로 변환된 경우)
        if (text.startsWith("=")) {
            FORMULA_SIZE_PATTERN.find(text)?.let { match ->
                val collectionName = match.groupValues[1]
                return CellContent.SizeMarker(collectionName, text)
            }
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
     */
    private fun parseSizeSpec(sizeStr: String?) =
        sizeStr?.split(":")
            ?.takeIf { it.size == 2 }
            ?.let { ImageSizeSpec(it[0].toIntOrNull() ?: 0, it[1].toIntOrNull() ?: 0) }
            ?: ImageSizeSpec.FIT_TO_CELL

    private fun Sheet.maxColumnIndex(): Int =
        maxOfOrNull { row -> row.lastCellNum.toInt() } ?: 0
}
