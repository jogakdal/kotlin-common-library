package com.hunet.common.excel

import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.ss.usermodel.DataValidationHelper
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Excel 데이터 유효성 검사(Data Validation) 백업 및 확장을 담당하는 프로세서.
 */
internal class DataValidationProcessor {

    data class ValidationInfo(
        val ranges: List<CellRangeAddress>,
        val validationType: Int,
        val operatorType: Int,
        val formula1: String?,
        val formula2: String?,
        val explicitListValues: List<String>?,
        val showErrorBox: Boolean,
        val showPromptBox: Boolean,
        val errorTitle: String?,
        val errorText: String?,
        val promptTitle: String?,
        val promptText: String?,
        val emptyCellAllowed: Boolean
    )

    data class SheetValidations(
        val validations: List<ValidationInfo>,
        val lastDataRow: Int
    )

    data class WorkbookValidations(
        val sheetValidations: Map<Int, SheetValidations>
    )

    /**
     * 템플릿에서 데이터 유효성 검사 정보를 백업합니다.
     */
    fun backup(template: InputStream): WorkbookValidations =
        XSSFWorkbook(template).use { workbook ->
            WorkbookValidations(
                workbook.sheetIterator().asSequence()
                    .mapIndexedNotNull { index, sheet ->
                        (sheet as? XSSFSheet)?.let { xssfSheet ->
                            val validations = xssfSheet.dataValidations.orEmpty().mapNotNull { dv ->
                                val regions = dv.regions?.cellRangeAddresses?.toList().orEmpty()
                                regions.takeIf { it.isNotEmpty() }?.let {
                                    val constraint = dv.validationConstraint
                                    ValidationInfo(
                                        ranges = regions.map { r ->
                                            CellRangeAddress(r.firstRow, r.lastRow, r.firstColumn, r.lastColumn)
                                        },
                                        validationType = constraint.validationType,
                                        operatorType = constraint.operator,
                                        formula1 = constraint.formula1,
                                        formula2 = constraint.formula2,
                                        explicitListValues = constraint.explicitListValues?.toList(),
                                        showErrorBox = dv.showErrorBox,
                                        showPromptBox = dv.showPromptBox,
                                        errorTitle = dv.errorBoxTitle,
                                        errorText = dv.errorBoxText,
                                        promptTitle = dv.promptBoxTitle,
                                        promptText = dv.promptBoxText,
                                        emptyCellAllowed = dv.emptyCellAllowed
                                    )
                                }
                            }
                            index to SheetValidations(validations, xssfSheet.lastRowWithData)
                        }
                    }.toMap()
            )
        }

    /**
     * 백업된 데이터 유효성 검사를 확장하여 새로 생성된 행에도 적용합니다.
     */
    fun expand(outputBytes: ByteArray, backup: WorkbookValidations): ByteArray =
        XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
            backup.sheetValidations
                .filter { (index, _) -> index < workbook.numberOfSheets }
                .forEach { (index, sheetValidations) ->
                    val sheet = workbook.getSheetAt(index) ?: return@forEach
                    val currentLastDataRow = sheet.lastRowWithData
                    val helper = sheet.dataValidationHelper

                    sheetValidations.validations.forEach { validationInfo ->
                        val expandedRanges = validationInfo.ranges.map { range ->
                            expandRangeIfNeeded(range, sheetValidations.lastDataRow, currentLastDataRow)
                        }

                        createValidation(helper, validationInfo, expandedRanges)?.let { validation ->
                            sheet.addValidationData(validation)
                        }
                    }
                }
            workbook.toByteArray()
        }

    // ========== 내부 유틸리티 ==========

    private fun expandRangeIfNeeded(
        originalRange: CellRangeAddress,
        templateLastRow: Int,
        currentLastRow: Int
    ): CellRangeAddress {
        val rowExpansion = currentLastRow - templateLastRow
        val shouldExpand = originalRange.firstRow == originalRange.lastRow &&
            originalRange.firstRow <= templateLastRow &&
            rowExpansion > 0

        return if (shouldExpand) {
            CellRangeAddress(
                originalRange.firstRow,
                originalRange.lastRow + rowExpansion,
                originalRange.firstColumn,
                originalRange.lastColumn
            )
        } else {
            originalRange
        }
    }

    private fun createValidation(
        helper: DataValidationHelper,
        info: ValidationInfo,
        ranges: List<CellRangeAddress>
    ): DataValidation? {
        val constraint = createConstraint(helper, info) ?: return null
        val addressList = CellRangeAddressList().apply {
            ranges.forEach { addCellRangeAddress(it) }
        }

        return helper.createValidation(constraint, addressList).apply {
            showErrorBox = info.showErrorBox
            showPromptBox = info.showPromptBox
            emptyCellAllowed = info.emptyCellAllowed
            info.errorTitle?.let { createErrorBox(it, info.errorText.orEmpty()) }
                ?: info.errorText?.let { createErrorBox("", it) }
            info.promptTitle?.let { createPromptBox(it, info.promptText.orEmpty()) }
                ?: info.promptText?.let { createPromptBox("", it) }
        }
    }

    private fun createConstraint(
        helper: DataValidationHelper,
        info: ValidationInfo
    ): DataValidationConstraint? = when (info.validationType) {
        DataValidationConstraint.ValidationType.LIST ->
            info.explicitListValues?.let { helper.createExplicitListConstraint(it.toTypedArray()) }
                ?: helper.createFormulaListConstraint(info.formula1)
        DataValidationConstraint.ValidationType.INTEGER ->
            helper.createIntegerConstraint(info.operatorType, info.formula1, info.formula2)
        DataValidationConstraint.ValidationType.DECIMAL ->
            helper.createDecimalConstraint(info.operatorType, info.formula1, info.formula2)
        DataValidationConstraint.ValidationType.DATE ->
            helper.createDateConstraint(info.operatorType, info.formula1, info.formula2, null)
        DataValidationConstraint.ValidationType.TIME ->
            helper.createTimeConstraint(info.operatorType, info.formula1, info.formula2)
        DataValidationConstraint.ValidationType.TEXT_LENGTH ->
            helper.createTextLengthConstraint(info.operatorType, info.formula1, info.formula2)
        DataValidationConstraint.ValidationType.FORMULA ->
            helper.createCustomConstraint(info.formula1)
        else -> null
    }
}
