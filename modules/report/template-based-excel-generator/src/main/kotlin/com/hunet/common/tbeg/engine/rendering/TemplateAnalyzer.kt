package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.engine.core.extractSheetReference
import com.hunet.common.tbeg.engine.core.parseCellRef
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellRangeAddress
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
        // 범위 패턴: 시트 참조 + 절대 좌표 지원
        // 예: A1:C3, $A$1:$C$3, 'Sheet1'!A1:C3, 'Sheet Name'!$A$1:$C$3
        private const val RANGE_PATTERN = """(?:'[^']+'!)?\$?[A-Za-z]+\$?\d+:\$?[A-Za-z]+\$?\d+"""
        private const val RANGE_OR_NAME = """$RANGE_PATTERN|\w+"""

        // repeat 마커 패턴 (명시적 파라미터 + empty 지원)
        // 예: ${repeat(employees, A6:C6, emp)}, ${repeat(employees, DataRange, emp, DOWN, A10:C10)}
        //     ${repeat(collection=employees, range=A6:C6, var=emp, direction=DOWN, empty='Empty'!A1:C1)}
        private val REPEAT_PATTERN = Regex(
            """\$\{repeat\s*\(\s*""" +
            """(?:collection\s*=\s*)?["'`]?(\w+)["'`]?\s*,\s*""" +           // 그룹1: collection
            """(?:range\s*=\s*)?["'`]?($RANGE_OR_NAME)["'`]?\s*""" +         // 그룹2: range
            """(?:,\s*(?:var\s*=\s*)?["'`]?(\w+)["'`]?)?\s*""" +             // 그룹3: var (선택)
            """(?:,\s*(?:direction\s*=\s*)?["'`]?(DOWN|RIGHT)["'`]?)?\s*""" + // 그룹4: direction (선택)
            """(?:,\s*(?:empty\s*=\s*)?["'`]?($RANGE_OR_NAME)["'`]?)?\s*""" + // 그룹5: empty (선택)
            """\)}""",
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

        // 수식 형태 repeat 마커 (명시적 파라미터 + empty 지원)
        // 예: =TBEG_REPEAT(employees, A6:C6, emp), =TBEG_REPEAT(collection=employees, range=A6:C6, empty=A10:C10)
        private val FORMULA_REPEAT_PATTERN = Regex(
            """TBEG_REPEAT\s*\(\s*""" +
            """(?:collection\s*=\s*)?["'`]?(\w+)["'`]?\s*,\s*""" +           // 그룹1: collection
            """(?:range\s*=\s*)?["'`]?($RANGE_OR_NAME)["'`]?\s*""" +         // 그룹2: range
            """(?:,\s*(?:var\s*=\s*)?["'`]?(\w+)["'`]?)?\s*""" +             // 그룹3: var (선택)
            """(?:,\s*(?:direction\s*=\s*)?["'`]?(DOWN|RIGHT)["'`]?)?\s*""" + // 그룹4: direction (선택)
            """(?:,\s*(?:empty\s*=\s*)?["'`]?($RANGE_OR_NAME)["'`]?)?\s*""" + // 그룹5: empty (선택)
            """\)""",
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
                rows = buildRowSpecs(workbook, sheet, repeatRegions),
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

    private fun createRepeatRegionSpec(workbook: Workbook, match: MatchResult): RepeatRegionSpec {
        val range = parseRange(workbook, match.groupValues[2])
        val collection = match.groupValues[1]

        // 4번째 그룹이 DOWN/RIGHT인지 확인
        val group4 = match.groupValues[4].uppercase()
        val isGroup4Direction = group4 == "DOWN" || group4 == "RIGHT"

        // direction과 emptyRange 결정
        // - 4번째가 DOWN/RIGHT면: direction = 4번째, empty = 5번째
        // - 4번째가 DOWN/RIGHT가 아니면: direction = DOWN (기본값), empty = 4번째 (하위 호환성)
        val direction: RepeatDirection
        val emptyRangeStr: String?

        if (isGroup4Direction) {
            direction = if (group4 == "RIGHT") RepeatDirection.RIGHT else RepeatDirection.DOWN
            emptyRangeStr = match.groupValues[5].takeIf { it.isNotEmpty() }
        } else {
            direction = RepeatDirection.DOWN
            // 4번째 그룹이 비어있지 않으면 empty로 취급 (하위 호환성)
            emptyRangeStr = match.groupValues[4].takeIf { it.isNotEmpty() }
                ?: match.groupValues[5].takeIf { it.isNotEmpty() }
        }

        // empty 범위 파싱
        val emptyRange = emptyRangeStr?.let { parseEmptyRangeSpec(workbook, it) }

        return RepeatRegionSpec(
            collection = collection,
            variable = match.groupValues[3].ifEmpty { collection },
            startRow = range.start.row,
            endRow = range.end.row,
            startCol = range.start.col,
            endCol = range.end.col,
            direction = direction,
            emptyRange = emptyRange
        )
    }

    /**
     * empty 범위 문자열을 EmptyRangeSpec으로 파싱
     */
    private fun parseEmptyRangeSpec(workbook: Workbook, rangeStr: String): EmptyRangeSpec {
        val (sheetName, rangeWithoutSheet) = extractSheetReference(rangeStr)
        val range = parseRange(workbook, rangeWithoutSheet)

        return EmptyRangeSpec(
            sheetName = sheetName,
            startRow = range.start.row,
            endRow = range.end.row,
            startCol = range.start.col,
            endCol = range.end.col
        )
    }

    /**
     * 범위 문자열 파싱 (시트 참조는 미리 제거된 상태)
     *
     * @param workbook Named Range 조회용 워크북
     * @param range 셀 범위("A6:C8", "$A$6:$C$8"), 단일 셀("H10"), 또는 Named Range("DataRange")
     * @return CellRange
     */
    private fun parseRange(workbook: Workbook, range: String): CellRange {
        // 시트 참조가 포함된 경우 제거 (parseEmptyRangeSpec에서 이미 처리하지만 안전을 위해)
        val (_, rangeWithoutSheet) = extractSheetReference(range)

        // 콜론이 있으면 범위 셀 참조
        if (":" in rangeWithoutSheet) {
            val (startRef, endRef) = rangeWithoutSheet.split(":")
            val (startRow, startCol) = parseCellRef(startRef)  // $ 기호는 parseCellRef에서 무시됨
            val (endRow, endCol) = parseCellRef(endRef)
            return CellRange(CellCoord(startRow, startCol), CellCoord(endRow, endCol))
        }

        // 단일 셀 참조 패턴 체크 (A1, $A$1, H10 등)
        val singleCellPattern = Regex("""^\$?[A-Za-z]+\$?\d+$""")
        if (singleCellPattern.matches(rangeWithoutSheet)) {
            val (row, col) = parseCellRef(rangeWithoutSheet)
            return CellRange(CellCoord(row, col), CellCoord(row, col))
        }

        // Named Range 조회
        val namedRange = workbook.getName(rangeWithoutSheet)
            ?: throw IllegalArgumentException("Named Range를 찾을 수 없습니다: $rangeWithoutSheet")

        val formula = namedRange.refersToFormula
        // 참조가 깨진 경우(#REF!) 처리
        if (formula.contains("#REF!")) {
            throw IllegalArgumentException("Named Range '$rangeWithoutSheet'의 참조가 유효하지 않습니다: $formula")
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
    private fun buildRowSpecs(workbook: Workbook, sheet: Sheet, repeatRegions: List<RepeatRegionSpec>): List<RowSpec> {
        val repeatByStartRow = repeatRegions.associateBy { it.startRow }

        return buildList {
            var skipUntil = -1
            for (rowIndex in 0..sheet.lastRowNum) {
                if (rowIndex <= skipUntil) continue

                repeatByStartRow[rowIndex]?.let { region ->
                    add(buildRepeatRow(workbook, sheet, rowIndex, region))
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

    private fun buildRepeatRow(workbook: Workbook, sheet: Sheet, rowIndex: Int, region: RepeatRegionSpec) =
        sheet.getRow(rowIndex).let { row ->
            // emptyRange가 있으면 해당 셀 내용을 미리 읽어둠
            val emptyRangeContent = region.emptyRange?.let { spec ->
                readEmptyRangeContent(workbook, sheet, spec)
            }

            RowSpec.RepeatRow(
                templateRowIndex = rowIndex,
                height = row?.height,
                cells = buildCellSpecs(row, region.variable),
                collectionName = region.collection,
                itemVariable = region.variable,
                repeatEndRowIndex = region.endRow,
                repeatStartCol = region.startCol,
                repeatEndCol = region.endCol,
                direction = region.direction,
                emptyRangeSpec = region.emptyRange,
                emptyRangeContent = emptyRangeContent
            )
        }

    /**
     * empty 범위의 셀 내용을 미리 읽어 EmptyRangeContent로 반환
     */
    private fun readEmptyRangeContent(
        workbook: Workbook,
        currentSheet: Sheet,
        spec: EmptyRangeSpec
    ): EmptyRangeContent {
        // 시트 결정: sheetName이 null이면 현재 시트, 아니면 해당 이름의 시트
        val targetSheet = spec.sheetName?.let { workbook.getSheet(it) } ?: currentSheet

        val cells = mutableListOf<List<CellSnapshot>>()
        val rowHeights = mutableListOf<Short?>()

        for (rowIdx in spec.startRow..spec.endRow) {
            val row = targetSheet.getRow(rowIdx)
            rowHeights.add(row?.height)

            val rowCells = mutableListOf<CellSnapshot>()
            for (colIdx in spec.startCol..spec.endCol) {
                val cell = row?.getCell(colIdx)
                rowCells.add(createCellSnapshot(cell))
            }
            cells.add(rowCells)
        }

        // 병합 영역 수집 (emptyRange 내에 있는 것만, 상대 좌표로 변환)
        val mergedRegions = (0 until targetSheet.numMergedRegions)
            .map { targetSheet.getMergedRegion(it) }
            .filter { region ->
                region.firstRow >= spec.startRow && region.lastRow <= spec.endRow &&
                region.firstColumn >= spec.startCol && region.lastColumn <= spec.endCol
            }
            .map { region ->
                // 상대 좌표로 변환 (emptyRange 시작 기준)
                CellRangeAddress(
                    region.firstRow - spec.startRow,
                    region.lastRow - spec.startRow,
                    region.firstColumn - spec.startCol,
                    region.lastColumn - spec.startCol
                )
            }

        return EmptyRangeContent(cells, mergedRegions, rowHeights)
    }

    /**
     * 셀 스냅샷 생성
     */
    private fun createCellSnapshot(cell: Cell?): CellSnapshot {
        if (cell == null) {
            return CellSnapshot(
                value = null,
                cellType = CellType.BLANK,
                styleIndex = 0,
                formula = null
            )
        }

        val (value, formula) = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue to null
            CellType.NUMERIC -> cell.numericCellValue to null
            CellType.BOOLEAN -> cell.booleanCellValue to null
            CellType.FORMULA -> null to cell.cellFormula
            CellType.BLANK -> null to null
            else -> null to null
        }

        return CellSnapshot(
            value = value,
            cellType = cell.cellType,
            styleIndex = cell.cellStyle?.index ?: 0,
            formula = formula
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
            val emptyRange = match.groupValues[5].takeIf { it.isNotEmpty() }
            return CellContent.RepeatMarker(collection, range, variable, direction, emptyRange)
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
            val emptyRange = match.groupValues[5].takeIf { it.isNotEmpty() }
            return CellContent.RepeatMarker(
                collection = match.groupValues[1],
                range = match.groupValues[2],
                variable = match.groupValues[3].ifEmpty { match.groupValues[1] },
                direction = direction,
                emptyRange = emptyRange
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
     * @param sizeStr "fit", "original", 또는 "width:height" 형식 (예: "100:200", "0:-1", "-1:-1")
     */
    private fun parseSizeSpec(sizeStr: String?) = when (sizeStr?.lowercase()) {
        null, "", "fit" -> ImageSizeSpec.FIT_TO_CELL
        "original" -> ImageSizeSpec.ORIGINAL
        else -> sizeStr.split(":")
            .takeIf { it.size == 2 }
            ?.let { ImageSizeSpec(it[0].toIntOrNull() ?: 0, it[1].toIntOrNull() ?: 0) }
            ?: ImageSizeSpec.FIT_TO_CELL
    }

    private fun Sheet.maxColumnIndex(): Int =
        maxOfOrNull { row -> row.lastCellNum.toInt() } ?: 0
}
