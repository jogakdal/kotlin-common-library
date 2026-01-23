package com.hunet.common.excel.engine

import org.apache.poi.ss.usermodel.ConditionType
import org.apache.poi.ss.usermodel.Footer
import org.apache.poi.ss.usermodel.Header
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule

/**
 * 시트 레이아웃 적용을 담당하는 프로세서.
 *
 * SXSSF 모드에서 병합 영역, 조건부 서식, 헤더/푸터, 인쇄 설정 등
 * 시트 레이아웃 관련 설정을 적용합니다.
 *
 * 처리 대상:
 * - 병합 영역 (반복 영역 내 복제 포함)
 * - 조건부 서식 (반복 영역 내 복제 포함)
 * - 헤더/푸터 (변수 치환 포함)
 * - 인쇄 설정
 */
internal class SheetLayoutApplier {

    /**
     * 병합 영역 적용 (SXSSF 모드용)
     *
     * 반복 영역 내 병합은 각 아이템마다 복제됩니다.
     */
    fun applyMergedRegions(
        sheet: SXSSFSheet,
        mergedRegions: List<CellRangeAddress>,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
        data: Map<String, Any>,
        totalRowOffset: Int
    ) {
        val addedRegions = mutableSetOf<String>()

        for (region in mergedRegions) {
            val overlappingRepeat = repeatRegions.values.find { repeat ->
                region.firstRow >= repeat.templateRowIndex && region.firstRow <= repeat.repeatEndRowIndex
            }

            if (overlappingRepeat != null) {
                val items = data[overlappingRepeat.collectionName] as? List<*> ?: continue
                val relativeStartRow = region.firstRow - overlappingRepeat.templateRowIndex
                val rowSpan = region.lastRow - region.firstRow

                items.indices.forEach { index ->
                    val newFirstRow = overlappingRepeat.templateRowIndex + index + relativeStartRow
                    val newLastRow = newFirstRow + rowSpan
                    val key = "$newFirstRow:$newLastRow:${region.firstColumn}:${region.lastColumn}"

                    if (key !in addedRegions) {
                        runCatching {
                            sheet.addMergedRegion(CellRangeAddress(
                                newFirstRow, newLastRow, region.firstColumn, region.lastColumn
                            ))
                        }
                        addedRegions.add(key)
                    }
                }
            } else {
                val maxRepeatEndRow = repeatRegions.values.maxOfOrNull { it.repeatEndRowIndex } ?: -1
                val offset = if (region.firstRow > maxRepeatEndRow) totalRowOffset else 0

                val newFirstRow = region.firstRow + offset
                val newLastRow = region.lastRow + offset
                val key = "$newFirstRow:$newLastRow:${region.firstColumn}:${region.lastColumn}"

                if (key !in addedRegions) {
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
    fun applyConditionalFormattings(
        sheet: SXSSFSheet,
        conditionalFormattings: List<ConditionalFormattingSpec>,
        repeatRegions: Map<Int, RowSpec.RepeatRow>,
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
                        ConditionType.CELL_VALUE_IS -> {
                            scf.createConditionalFormattingRule(
                                ruleInfo.comparisonOperator,
                                ruleInfo.formula1 ?: "",
                                ruleInfo.formula2
                            )
                        }
                        ConditionType.FORMULA -> {
                            scf.createConditionalFormattingRule(ruleInfo.formula1 ?: "TRUE")
                        }
                        else -> null
                    }

                    // dxfId 설정 (리플렉션 사용)
                    if (rule != null && ruleInfo.dxfId >= 0) {
                        runCatching {
                            val xssfRule = rule as? XSSFConditionalFormattingRule
                            if (xssfRule != null) {
                                // XSSFConditionalFormattingRule의 _cfRule 필드에 접근
                                val cfRuleField = xssfRule.javaClass.getDeclaredField("_cfRule")
                                cfRuleField.isAccessible = true
                                val ctCfRule = cfRuleField.get(xssfRule) as CTCfRule
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

    /**
     * 헤더/푸터 설정 적용 (SXSSF 모드용)
     *
     * 템플릿의 헤더/푸터를 복사하고 변수 치환 적용
     *
     * @param workbook SXSSF 워크북
     * @param sheetIndex 시트 인덱스
     * @param headerFooter 헤더/푸터 정보
     * @param data 변수 치환 데이터
     * @param textEvaluator 텍스트 내 변수를 치환하는 함수
     */
    fun applyHeaderFooter(
        workbook: SXSSFWorkbook,
        sheetIndex: Int,
        headerFooter: HeaderFooterSpec?,
        data: Map<String, Any>,
        textEvaluator: (String, Map<String, Any>) -> String
    ) {
        if (headerFooter == null) return

        // SXSSFWorkbook의 내부 XSSFWorkbook을 통해 XSSFSheet 접근
        val xssfSheet = workbook.xssfWorkbook.getSheetAt(sheetIndex)

        // 홀수 페이지 헤더/푸터 (기본)
        val oddHeaderStr = buildHeaderFooterString(
            headerFooter.leftHeader?.let { textEvaluator(it, data) },
            headerFooter.centerHeader?.let { textEvaluator(it, data) },
            headerFooter.rightHeader?.let { textEvaluator(it, data) }
        )
        val oddFooterStr = buildHeaderFooterString(
            headerFooter.leftFooter?.let { textEvaluator(it, data) },
            headerFooter.centerFooter?.let { textEvaluator(it, data) },
            headerFooter.rightFooter?.let { textEvaluator(it, data) }
        )

        if (oddHeaderStr != null || oddFooterStr != null) {
            oddHeaderStr?.let { xssfSheet.oddHeader.apply { left = ""; center = ""; right = "" } }
            applyHeaderFooterParts(
                xssfSheet.oddHeader, xssfSheet.oddFooter, data, textEvaluator,
                headerFooter.leftHeader, headerFooter.centerHeader, headerFooter.rightHeader,
                headerFooter.leftFooter, headerFooter.centerFooter, headerFooter.rightFooter
            )
        }

        // 첫 페이지용 (differentFirst=true일 때)
        if (headerFooter.differentFirst) {
            applyHeaderFooterParts(
                xssfSheet.firstHeader, xssfSheet.firstFooter, data, textEvaluator,
                headerFooter.firstLeftHeader, headerFooter.firstCenterHeader, headerFooter.firstRightHeader,
                headerFooter.firstLeftFooter, headerFooter.firstCenterFooter, headerFooter.firstRightFooter
            )
        }

        // 짝수 페이지용 (differentOddEven=true일 때)
        if (headerFooter.differentOddEven) {
            applyHeaderFooterParts(
                xssfSheet.evenHeader, xssfSheet.evenFooter, data, textEvaluator,
                headerFooter.evenLeftHeader, headerFooter.evenCenterHeader, headerFooter.evenRightHeader,
                headerFooter.evenLeftFooter, headerFooter.evenCenterFooter, headerFooter.evenRightFooter
            )
        }
    }

    /**
     * 헤더/푸터 부분 적용 (중복 코드 제거용 헬퍼)
     */
    private fun applyHeaderFooterParts(
        header: Header,
        footer: Footer,
        data: Map<String, Any>,
        textEvaluator: (String, Map<String, Any>) -> String,
        leftH: String?, centerH: String?, rightH: String?,
        leftF: String?, centerF: String?, rightF: String?
    ) {
        leftH?.let { header.left = textEvaluator(it, data) }
        centerH?.let { header.center = textEvaluator(it, data) }
        rightH?.let { header.right = textEvaluator(it, data) }
        leftF?.let { footer.left = textEvaluator(it, data) }
        centerF?.let { footer.center = textEvaluator(it, data) }
        rightF?.let { footer.right = textEvaluator(it, data) }
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

    /**
     * 인쇄 설정 적용 (SXSSF 모드용)
     */
    fun applyPrintSetup(sheet: SXSSFSheet, printSetup: PrintSetupSpec?) {
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
}
