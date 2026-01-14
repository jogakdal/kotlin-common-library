package com.hunet.common.excel

import com.hunet.common.logging.commonLogger
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePart
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFPivotTable
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 피벗 테이블 처리를 담당하는 프로세서.
 */
internal class PivotTableProcessor(
    private val config: ExcelGeneratorConfig
) {
    private val styleCache = mutableMapOf<XSSFWorkbook, MutableMap<String, XSSFCellStyle>>()

    // ========== 데이터 클래스 ==========

    data class PivotTableInfo(
        val pivotTableSheetName: String,
        val pivotTableLocation: String,
        val originalLocationRef: String,
        val sourceSheetName: String,
        val sourceRange: CellRangeAddress,
        val rowLabelFields: List<Int>,
        val dataFields: List<DataFieldInfo>,
        val pivotTableName: String,
        val rowHeaderCaption: String?,
        val grandTotalCaption: String?,
        val pivotTableXml: String,
        val pivotCacheDefXml: String,
        val pivotCacheRecordsXml: String?,
        val originalStyles: PivotTableStyles? = null,
        val originalFormatsXml: String? = null
    )

    data class PivotTableStyles(
        val headerStyles: Map<Int, StyleInfo>,
        val dataRowStyles: Map<Int, StyleInfo>,
        val grandTotalStyles: Map<Int, StyleInfo>
    )

    data class StyleInfo(
        val fontBold: Boolean = false,
        val fontItalic: Boolean = false,
        val fontUnderline: Byte = Font.U_NONE,
        val fontStrikeout: Boolean = false,
        val fontName: String? = null,
        val fontSize: Short = 11,
        val fontColorRgb: String? = null,
        val fillForegroundColorRgb: String? = null,
        val fillPatternType: FillPatternType = FillPatternType.NO_FILL,
        val borderTop: BorderStyle = BorderStyle.NONE,
        val borderBottom: BorderStyle = BorderStyle.NONE,
        val borderLeft: BorderStyle = BorderStyle.NONE,
        val borderRight: BorderStyle = BorderStyle.NONE,
        val horizontalAlignment: HorizontalAlignment = HorizontalAlignment.GENERAL,
        val verticalAlignment: VerticalAlignment = VerticalAlignment.CENTER,
        val dataFormat: Short = 0
    )

    data class DataFieldInfo(
        val fieldIndex: Int,
        val function: DataConsolidateFunction,
        val name: String?
    )

    private data class DataRow(val values: Map<Int, Any?>)

    private data class SourceData(
        val records: List<List<Any?>>,
        val fields: List<FieldMeta>
    )

    private data class FieldMeta(
        val name: String,
        val isAxisField: Boolean,
        val isNumeric: Boolean,
        val sharedItems: List<String>,
        val minValue: Double?,
        val maxValue: Double?,
        val isInteger: Boolean
    )

    // ========== 공개 API ==========

    fun extractAndRemove(inputBytes: ByteArray): Pair<List<PivotTableInfo>, ByteArray> {
        val stylesMap = mutableMapOf<String, PivotTableStyles>()

        val hasPivotTable = inputBytes.useWorkbook { workbook ->
            workbook.sheetSequence()
                .filterIsInstance<XSSFSheet>()
                .flatMap { it.pivotTables.orEmpty() }
                .onEach { pivotTable ->
                    extractOriginalStyles(pivotTable)?.let { styles ->
                        val name = pivotTable.ctPivotTableDefinition?.name ?: "PivotTable"
                        stylesMap[name] = styles
                        LOG.debug("원본 스타일 추출 완료: $name")
                    }
                }
                .any()
        }

        if (!hasPivotTable) {
            LOG.debug("피벗 테이블이 없음, 원본 반환")
            return emptyList<PivotTableInfo>() to inputBytes
        }

        val pivotTableInfos = OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
            val cacheSourceMap = pkg.parts
                .filter { "/pivotCache/pivotCacheDefinition" in it.partName.name }
                .mapNotNull { part ->
                    val xml = part.readText()
                    val sheet = SHEET_ATTR_REGEX.find(xml)?.groupValues?.get(1)
                    val ref = WORKSHEET_SOURCE_REF_REGEX.find(xml)?.groupValues?.get(1)
                    if (sheet != null && ref != null) {
                        LOG.debug("피벗 캐시 발견: ${part.partName.name} -> $sheet!$ref")
                        part.partName.name to (sheet to ref)
                    } else null
                }.toMap()

            pkg.parts
                .filter { "/pivotTables/pivotTable" in it.partName.name }
                .mapNotNull { parsePivotTablePart(it, pkg, cacheSourceMap, stylesMap) }
        }

        return pivotTableInfos to removePivotReferencesFromZip(inputBytes)
    }

    fun recreate(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>) =
        if (pivotTableInfos.isEmpty()) inputBytes
        else inputBytes
            .useWorkbook { workbook ->
                pivotTableInfos.forEach { recreatePivotTable(workbook, it) }
                workbook.toByteArray()
            }
            .let { populatePivotCache(it, pivotTableInfos) }
            .let { adjustPivotTableStructure(it, pivotTableInfos) }

    // ========== 스타일 추출 ==========

    private fun extractOriginalStyles(pivotTable: XSSFPivotTable): PivotTableStyles? = runCatching {
        val location = pivotTable.ctPivotTableDefinition?.location ?: return null
        val ref = location.ref ?: return null
        val range = CellRangeAddress.valueOf(ref)
        val sheet = pivotTable.parentSheet

        val headerRowNum = range.firstRow
        val dataRowNum = range.firstRow + 1
        val grandTotalRowNum = range.lastRow

        LOG.debug(
            "원본 스타일 추출 - range: ${range.formatAsString()}, " +
                "header: $headerRowNum, data: $dataRowNum, grandTotal: $grandTotalRowNum"
        )

        val headerStyles = sheet.getRow(headerRowNum).extractStyleInfos(range)
        val dataRowStyles = if (dataRowNum < grandTotalRowNum) {
            sheet.getRow(dataRowNum).extractStyleInfos(range)
        } else headerStyles
        val grandTotalStyles = sheet.getRow(grandTotalRowNum).extractStyleInfos(range)

        LOG.debug(
            "원본 스타일 - header: ${headerStyles.size}개, " +
                "dataRow: ${dataRowStyles.size}개, grandTotal: ${grandTotalStyles.size}개"
        )

        PivotTableStyles(headerStyles, dataRowStyles, grandTotalStyles)
    }.onFailure { LOG.warn("원본 스타일 추출 실패: ${it.message}") }.getOrNull()

    private fun Row?.extractStyleInfos(range: CellRangeAddress): Map<Int, StyleInfo> =
        this?.let {
            (range.firstColumn..range.lastColumn).mapNotNull { colIdx ->
                getCell(colIdx)?.let { cell ->
                    (cell.cellStyle as? XSSFCellStyle)?.let { style ->
                        (colIdx - range.firstColumn) to style.toStyleInfo()
                    }
                }
            }.toMap()
        } ?: emptyMap()

    private fun XSSFCellStyle.toStyleInfo(): StyleInfo {
        val fontColorRgb = font?.xssfColor?.argbHex
        val fillColorRgb = fillForegroundXSSFColor?.argbHex?.takeIf { fillPattern != FillPatternType.NO_FILL }

        return StyleInfo(
            fontBold = font?.bold ?: false,
            fontItalic = font?.italic ?: false,
            fontUnderline = font?.underline ?: Font.U_NONE,
            fontStrikeout = font?.strikeout ?: false,
            fontName = font?.fontName,
            fontSize = font?.fontHeightInPoints ?: 11,
            fontColorRgb = fontColorRgb,
            fillForegroundColorRgb = fillColorRgb,
            fillPatternType = fillPattern,
            borderTop = borderTop,
            borderBottom = borderBottom,
            borderLeft = borderLeft,
            borderRight = borderRight,
            horizontalAlignment = alignment,
            verticalAlignment = verticalAlignment,
            dataFormat = dataFormat
        )
    }

    // ========== 피벗 테이블 파싱 ==========

    private fun parsePivotTablePart(
        part: PackagePart,
        pkg: OPCPackage,
        cacheSourceMap: Map<String, Pair<String, String>>,
        stylesMap: Map<String, PivotTableStyles>
    ): PivotTableInfo? {
        val xml = part.readText()

        val pivotTableName = NAME_ATTR_REGEX.find(xml)?.groupValues?.get(1) ?: "PivotTable"
        val fullLocationRef = LOCATION_REF_REGEX.find(xml)?.groupValues?.get(1) ?: "A1:A1"
        val location = fullLocationRef.substringBefore(":")

        val rowLabelFields = ROW_FIELDS_REGEX.find(xml)?.groupValues?.get(1)?.let { content ->
            FIELD_X_REGEX.findAll(content).map { it.groupValues[1].toInt() }.toList()
        } ?: emptyList()

        val dataFields = DATA_FIELD_REGEX.findAll(xml)
            .mapNotNull { parseDataField(it.value) }
            .toList()

        val rowHeaderCaption = ROW_HEADER_CAPTION_REGEX.find(xml)?.groupValues?.get(1)
        val grandTotalCaption = GRAND_TOTAL_CAPTION_REGEX.find(xml)?.groupValues?.get(1)
        val originalFormatsXml = FORMATS_REGEX.find(xml)?.value?.also {
            LOG.debug("원본 formats XML 추출: ${it.length}자")
        }

        val (sourceSheet, sourceRef) = cacheSourceMap.values.firstOrNull() ?: run {
            LOG.warn("피벗 테이블 '$pivotTableName' 캐시 소스 정보 없음")
            return null
        }

        val pivotTableSheetName = findPivotTableSheetName(pkg, part.partName.name) ?: sourceSheet

        LOG.debug(
            "피벗 테이블 발견: '$pivotTableName' " +
                "(위치: $pivotTableSheetName!$location, 소스: $sourceSheet!$sourceRef)"
        )

        return PivotTableInfo(
            pivotTableSheetName = pivotTableSheetName,
            pivotTableLocation = location,
            originalLocationRef = fullLocationRef,
            sourceSheetName = sourceSheet,
            sourceRange = CellRangeAddress.valueOf(sourceRef),
            rowLabelFields = rowLabelFields,
            dataFields = dataFields,
            pivotTableName = pivotTableName,
            rowHeaderCaption = rowHeaderCaption,
            grandTotalCaption = grandTotalCaption,
            pivotTableXml = "",
            pivotCacheDefXml = "",
            pivotCacheRecordsXml = null,
            originalStyles = stylesMap[pivotTableName],
            originalFormatsXml = originalFormatsXml
        )
    }

    private fun parseDataField(xml: String): DataFieldInfo? {
        val fld = FLD_ATTR_REGEX.find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val subtotal = SUBTOTAL_ATTR_REGEX.find(xml)?.groupValues?.get(1) ?: "sum"
        val name = NAME_ATTR_REGEX.find(xml)?.groupValues?.get(1)

        val function = when (subtotal) {
            "count" -> DataConsolidateFunction.COUNT
            "average" -> DataConsolidateFunction.AVERAGE
            "max" -> DataConsolidateFunction.MAX
            "min" -> DataConsolidateFunction.MIN
            else -> DataConsolidateFunction.SUM
        }

        return DataFieldInfo(fld, function, name)
    }

    private fun findPivotTableSheetName(pkg: OPCPackage, pivotTablePartName: String): String? {
        val workbookPart = pkg.parts.find { it.partName.name == "/xl/workbook.xml" } ?: return null
        val sheetNames = SHEET_NAME_REGEX.findAll(workbookPart.readText())
            .map { it.groupValues[1] }
            .toList()

        val pivotFileName = pivotTablePartName.substringAfterLast("/")

        return sheetNames.withIndex().firstNotNullOfOrNull { (index, sheetName) ->
            pkg.parts
                .find { it.partName.name == "/xl/worksheets/_rels/sheet${index + 1}.xml.rels" }
                ?.readText()
                ?.takeIf { pivotFileName in it }
                ?.let { sheetName }
        }
    }

    // ========== 피벗 테이블 재생성 ==========

    private fun recreatePivotTable(workbook: XSSFWorkbook, info: PivotTableInfo) {
        val pivotSheet = workbook.getSheet(info.pivotTableSheetName)
        val sourceSheet = workbook.getSheet(info.sourceSheetName)

        if (pivotSheet == null || sourceSheet == null) {
            LOG.warn(
                "피벗 테이블 재생성 실패: 시트를 찾을 수 없음 " +
                    "(pivot=${info.pivotTableSheetName}, source=${info.sourceSheetName})"
            )
            return
        }

        val newLastRow = sourceSheet.findLastRowWithData(info.sourceRange)
        val newSourceRange = info.sourceRange.copy(lastRow = newLastRow)

        LOG.debug(
            "피벗 테이블 재생성: ${info.pivotTableName} " +
                "(범위: ${info.sourceRange.formatAsString()} -> ${newSourceRange.formatAsString()})"
        )

        runCatching {
            val pivotLocation = CellReference(info.pivotTableLocation)
            val originalStyles = info.originalStyles

            originalStyles?.let {
                LOG.debug(
                    "원본 스타일 사용 - dataRow: ${it.dataRowStyles.size}개, " +
                        "grandTotal: ${it.grandTotalStyles.size}개"
                )
            } ?: LOG.debug("원본 스타일 없음, 기본 스타일 사용")

            // 피벗 테이블 영역 클리어
            val expectedDataRows = sourceSheet.uniqueValuesFromSourceData(newSourceRange, info.rowLabelFields).size
            val expectedRows = 1 + expectedDataRows + 1
            val expectedCols = 1 + info.dataFields.size
            pivotSheet.clearArea(pivotLocation.row, pivotLocation.col.toInt(), expectedRows + 2, expectedCols + 1)

            // 피벗 테이블 생성
            val areaReference = AreaReference(
                "${info.sourceSheetName}!${newSourceRange.formatAsString()}",
                workbook.spreadsheetVersion
            )
            val pivotTable = pivotSheet.createPivotTable(areaReference, pivotLocation, sourceSheet).apply {
                ctPivotTableDefinition.name = info.pivotTableName
            }

            info.rowLabelFields.forEach { pivotTable.addRowLabel(it) }
            info.dataFields.forEach { pivotTable.addColumnLabel(it.function, it.fieldIndex, it.name) }
            info.rowHeaderCaption?.let {
                pivotTable.ctPivotTableDefinition.rowHeaderCaption = it
                LOG.debug("rowHeaderCaption 적용: '$it'")
            }

            fillPivotTableCells(
                workbook, pivotSheet, sourceSheet, newSourceRange, pivotLocation,
                info.rowLabelFields, info.dataFields, info.rowHeaderCaption, info.grandTotalCaption,
                originalStyles?.headerStyles.orEmpty(),
                originalStyles?.dataRowStyles.orEmpty()
            )

            LOG.debug("피벗 테이블 재생성 완료: ${info.pivotTableName}")
        }.onFailure {
            throw IllegalStateException("피벗 테이블 재생성 실패: ${info.pivotTableName}", it)
        }
    }

    private fun XSSFSheet.uniqueValuesFromSourceData(
        range: CellRangeAddress,
        rowLabelFields: List<Int>
    ) = rowLabelFields.firstOrNull()?.let { axisFieldIdx ->
        ((range.firstRow + 1)..range.lastRow)
            .mapNotNull { rowNum ->
                getRow(rowNum)?.getCell(range.firstColumn + axisFieldIdx)?.cellValue?.toString()
            }
            .distinct()
    } ?: emptyList()

    private fun XSSFSheet.findLastRowWithData(range: CellRangeAddress) =
        ((range.firstRow + 1)..lastRowNum)
            .takeWhile { rowNum ->
                getRow(rowNum)?.let { row ->
                    (range.firstColumn..range.lastColumn).any { colIdx ->
                        row.getCell(colIdx)?.hasData == true
                    }
                } ?: false
            }
            .lastOrNull() ?: range.firstRow

    private fun XSSFSheet.clearArea(startRow: Int, startCol: Int, rows: Int, cols: Int) {
        val defaultStyle = workbook.getCellStyleAt(0)
        (startRow until startRow + rows).forEach { rowIdx ->
            getRow(rowIdx)?.let { row ->
                (startCol until startCol + cols).forEach { colIdx ->
                    row.getCell(colIdx)?.apply {
                        setBlank()
                        cellStyle = defaultStyle
                    }
                }
            }
        }
    }

    // ========== 셀 채우기 ==========

    private fun fillPivotTableCells(
        workbook: XSSFWorkbook,
        pivotSheet: XSSFSheet,
        sourceSheet: XSSFSheet,
        sourceRange: CellRangeAddress,
        pivotLocation: CellReference,
        rowLabelFields: List<Int>,
        dataFields: List<DataFieldInfo>,
        rowHeaderCaption: String?,
        grandTotalCaption: String?,
        headerStyles: Map<Int, StyleInfo>,
        dataRowStyles: Map<Int, StyleInfo>
    ) {
        if (rowLabelFields.isEmpty() || dataFields.isEmpty()) return

        val headerRow = sourceSheet.getRow(sourceRange.firstRow) ?: return
        val headers = (sourceRange.firstColumn..sourceRange.lastColumn).map { colIdx ->
            headerRow.getCell(colIdx)?.stringValue ?: "Field$colIdx"
        }

        val dataRows = ((sourceRange.firstRow + 1)..sourceRange.lastRow).mapNotNull { rowNum ->
            sourceSheet.getRow(rowNum)?.let { row ->
                DataRow((sourceRange.firstColumn..sourceRange.lastColumn).associate { colIdx ->
                    (colIdx - sourceRange.firstColumn) to row.getCell(colIdx)?.cellValue
                })
            }
        }

        if (dataRows.isEmpty()) return

        val axisFieldIdx = rowLabelFields.first()
        val uniqueValues = dataRows.mapNotNull { it.values[axisFieldIdx]?.toString() }.distinct()
        if (uniqueValues.isEmpty()) return

        val startRow = pivotLocation.row
        val startCol = pivotLocation.col.toInt()
        val localStyleCache = mutableMapOf<StyleInfo, XSSFCellStyle>()

        val axisAlignmentStyle = dataRowStyles[0]?.let { workbook.getOrCreateAlignmentOnlyStyle(it) }

        // 헤더 행
        pivotSheet.getOrCreateRow(startRow).apply {
            getOrCreateCell(startCol).apply {
                setCellValue(rowHeaderCaption ?: headers.getOrNull(axisFieldIdx) ?: "Row Labels")
                (headerStyles[0] ?: dataRowStyles[0])?.let { cellStyle = workbook.getOrCreateAlignmentOnlyStyle(it) }
            }

            dataFields.forEachIndexed { idx, dataField ->
                getOrCreateCell(startCol + 1 + idx).apply {
                    setCellValue(dataField.name ?: "Values")
                    headerStyles[1 + idx]?.let { cellStyle = workbook.getOrCreateAlignmentOnlyStyle(it) }
                }
            }
        }

        // 데이터 행들
        uniqueValues.forEachIndexed { rowIdx, axisValue ->
            pivotSheet.getOrCreateRow(startRow + 1 + rowIdx).apply {
                getOrCreateCell(startCol).apply {
                    setCellValue(axisValue)
                    dataRowStyles[0]?.let { cellStyle = workbook.getOrCreateStyle(it, localStyleCache) }
                }

                val matchingRows = dataRows.filter { it.values[axisFieldIdx]?.toString() == axisValue }
                dataFields.forEachIndexed { dataIdx, dataField ->
                    getOrCreateCell(startCol + 1 + dataIdx).apply {
                        setCellValue(matchingRows.aggregateForField(dataField))
                        cellStyle = workbook.getOrCreateStyleWithNumberFormat(
                            dataRowStyles[1 + dataIdx], dataField.function, localStyleCache
                        )
                    }
                }
            }
        }

        // 총합계 행
        pivotSheet.getOrCreateRow(startRow + 1 + uniqueValues.size).apply {
            getOrCreateCell(startCol).apply {
                setCellValue(grandTotalCaption ?: "전체")
                axisAlignmentStyle?.let { cellStyle = it }
            }

            dataFields.forEachIndexed { dataIdx, dataField ->
                getOrCreateCell(startCol + 1 + dataIdx).apply {
                    setCellValue(dataRows.aggregateForField(dataField))
                    cellStyle = workbook.getNumberFormatOnlyStyle(dataField.function)
                }
            }
        }

        LOG.debug("피벗 테이블 셀 값 채우기 완료: ${uniqueValues.size}개 항목")
    }

    // ========== 스타일 생성 ==========

    private fun XSSFWorkbook.getOrCreateStyle(
        styleInfo: StyleInfo,
        cache: MutableMap<StyleInfo, XSSFCellStyle>
    ): XSSFCellStyle = cache.getOrPut(styleInfo) { createStyleFrom(styleInfo) }

    private fun XSSFWorkbook.getOrCreateStyleWithNumberFormat(
        styleInfo: StyleInfo?,
        function: DataConsolidateFunction,
        cache: MutableMap<StyleInfo, XSSFCellStyle>
    ): XSSFCellStyle {
        val numberFormat = function.formatIndex

        if (styleInfo != null && styleInfo.dataFormat.toInt() != 0) {
            return getOrCreateStyle(styleInfo, cache)
        }

        val effectiveStyleInfo = (styleInfo ?: StyleInfo()).copy(dataFormat = numberFormat)
        return cache.getOrPut(effectiveStyleInfo) { createStyleFrom(effectiveStyleInfo) }
    }

    private fun XSSFWorkbook.createStyleFrom(styleInfo: StyleInfo): XSSFCellStyle =
        createCellStyle().apply {
            val font = (createFont() as XSSFFont).apply {
                bold = styleInfo.fontBold
                italic = styleInfo.fontItalic
                underline = styleInfo.fontUnderline
                strikeout = styleInfo.fontStrikeout
                styleInfo.fontName?.let { fontName = it }
                fontHeightInPoints = styleInfo.fontSize
                styleInfo.fontColorRgb?.let { colorHex ->
                    runCatching { setColor(XSSFColor(colorHex.toRgbBytes(), null)) }
                        .onFailure { LOG.debug("글꼴 색상 설정 실패: ${it.message}") }
                }
            }
            setFont(font)

            fillPattern = styleInfo.fillPatternType
            if (styleInfo.fillPatternType != FillPatternType.NO_FILL) {
                styleInfo.fillForegroundColorRgb?.let { colorHex ->
                    runCatching { setFillForegroundColor(XSSFColor(colorHex.toRgbBytes(), null)) }
                        .onFailure { LOG.debug("채우기 색상 설정 실패: ${it.message}") }
                }
            }

            borderTop = styleInfo.borderTop
            borderBottom = styleInfo.borderBottom
            borderLeft = styleInfo.borderLeft
            borderRight = styleInfo.borderRight
            alignment = styleInfo.horizontalAlignment
            verticalAlignment = styleInfo.verticalAlignment

            if (styleInfo.dataFormat.toInt() != 0) {
                dataFormat = styleInfo.dataFormat
            }
        }

    private fun XSSFWorkbook.getOrCreateAlignmentOnlyStyle(styleInfo: StyleInfo) =
        styleCache.getOrPut(this) { mutableMapOf() }
            .getOrPut("alignOnly_${styleInfo.horizontalAlignment}_${styleInfo.verticalAlignment}") {
                createCellStyle().apply {
                    alignment = styleInfo.horizontalAlignment
                    verticalAlignment = styleInfo.verticalAlignment
                }
            }

    private fun XSSFWorkbook.getNumberFormatOnlyStyle(function: DataConsolidateFunction) =
        styleCache.getOrPut(this) { mutableMapOf() }
            .getOrPut("numFmtOnly_${function.formatIndex}") {
                createCellStyle().apply { dataFormat = function.formatIndex }
            }

    // ========== 집계 ==========

    private fun List<DataRow>.aggregateForField(dataField: DataFieldInfo): Double = when (dataField.function) {
        DataConsolidateFunction.COUNT -> count { it.values[dataField.fieldIndex] != null }.toDouble()
        DataConsolidateFunction.COUNT_NUMS -> count { it.values[dataField.fieldIndex] is Number }.toDouble()
        else -> mapNotNull { (it.values[dataField.fieldIndex] as? Number)?.toDouble() }.aggregate(dataField.function)
    }

    private fun List<Double>.aggregate(function: DataConsolidateFunction): Double = when (function) {
        DataConsolidateFunction.SUM -> sum()
        DataConsolidateFunction.AVERAGE -> takeIf { it.isNotEmpty() }?.average() ?: 0.0
        DataConsolidateFunction.COUNT, DataConsolidateFunction.COUNT_NUMS -> size.toDouble()
        DataConsolidateFunction.MAX -> maxOrNull() ?: 0.0
        DataConsolidateFunction.MIN -> minOrNull() ?: 0.0
        else -> sum()
    }

    // ========== 피벗 테이블 구조 조정 ==========

    private fun adjustPivotTableStructure(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) return inputBytes

        val infoByName = pivotTableInfos.associateBy { it.pivotTableName }

        return OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
            pkg.getPartsByContentType(PIVOT_TABLE_CONTENT_TYPE).forEach { part ->
                var xml = part.readText()
                var modified = false

                val pivotTableName = NAME_ATTR_REGEX.find(xml)?.groupValues?.get(1)
                val info = pivotTableName?.let { infoByName[it] }

                if (info == null) {
                    LOG.debug("피벗 테이블 info를 찾을 수 없음: $pivotTableName")
                    return@forEach
                }

                val expectedColCount = info.dataFields.size
                val originalRange = CellRangeAddress.valueOf(info.originalLocationRef)
                val expectedCols = originalRange.lastColumn - originalRange.firstColumn + 1

                // colItems count 조정
                COL_ITEMS_COUNT_REGEX.find(xml)?.let { colItemsMatch ->
                    val currentCount = colItemsMatch.groupValues[1].toInt()
                    if (currentCount > expectedColCount) {
                        xml = COL_ITEMS_FULL_REGEX.replace(xml) { match ->
                            val content = match.groupValues[1]
                            val iElements = I_ELEMENT_REGEX.findAll(content)
                                .take(expectedColCount)
                                .joinToString("") { it.value }
                            """<colItems count="$expectedColCount">$iElements</colItems>"""
                        }
                        modified = true
                    }
                }

                // location ref 조정
                LOCATION_WITH_REF_REGEX.find(xml)?.let { locationMatch ->
                    val currentRef = locationMatch.groupValues[2]
                    val currentRange = CellRangeAddress.valueOf(currentRef)
                    val newLastCol = currentRange.firstColumn + expectedCols - 1
                    val newRef = currentRange.copy(lastColumn = newLastCol).formatAsString()
                    if (currentRef != newRef) {
                        xml = xml.replace("""ref="$currentRef"""", """ref="$newRef"""")
                        modified = true
                    }
                }

                if (modified) {
                    part.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                    LOG.debug("피벗 테이블 XML 구조 조정 완료: $pivotTableName")
                }
            }

            ByteArrayOutputStream().also { pkg.save(it) }.toByteArray()
        }
    }

    // ========== ZIP 처리 ==========

    private fun removePivotReferencesFromZip(inputBytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
                ZipOutputStream(output).use { zos ->
                    zis.entries().forEach { entry ->
                        val entryName = entry.name

                        if ("pivotCache" in entryName || "pivotTables" in entryName) {
                            LOG.debug("피벗 파트 제거: $entryName")
                            return@forEach
                        }

                        val content = zis.readBytes().let { bytes ->
                            when {
                                entryName == "[Content_Types].xml" -> {
                                    LOG.debug("Content_Types에서 피벗 참조 제거")
                                    bytes.decodeToString()
                                        .replace(PIVOT_CACHE_OVERRIDE_REGEX, "")
                                        .replace(PIVOT_TABLE_OVERRIDE_REGEX, "")
                                        .encodeToByteArray()
                                }
                                "worksheets/_rels/" in entryName && entryName.endsWith(".rels") -> {
                                    bytes.decodeToString().let { xml ->
                                        if ("pivotTable" in xml) {
                                            LOG.debug("워크시트 관계에서 피벗 참조 제거: $entryName")
                                            xml.replace(PIVOT_TABLE_REL_REGEX, "").let { cleaned ->
                                                if ("<Relationship" !in cleaned) EMPTY_RELATIONSHIPS_XML else cleaned
                                            }.encodeToByteArray()
                                        } else bytes
                                    }
                                }
                                entryName == "xl/workbook.xml" -> {
                                    bytes.decodeToString().let { xml ->
                                        if ("<pivotCaches" in xml) {
                                            LOG.debug("워크북에서 pivotCaches 제거")
                                            xml.replace(PIVOT_CACHES_REGEX, "")
                                                .replace(PIVOT_CACHES_EMPTY_REGEX, "")
                                                .encodeToByteArray()
                                        } else bytes
                                    }
                                }
                                entryName == "xl/_rels/workbook.xml.rels" -> {
                                    bytes.decodeToString().let { xml ->
                                        if ("pivotCache" in xml) {
                                            LOG.debug("워크북 관계에서 피벗 캐시 참조 제거")
                                            xml.replace(PIVOT_CACHE_REL_REGEX, "").encodeToByteArray()
                                        } else bytes
                                    }
                                }
                                else -> bytes
                            }
                        }

                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(content)
                        zos.closeEntry()
                    }
                }
            }
        }.toByteArray()

    // ========== 피벗 캐시 빌더 ==========

    private fun populatePivotCache(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) return inputBytes

        val axisFieldIndices = pivotTableInfos.flatMap { it.rowLabelFields }.toSet()

        val sourceDataMap = inputBytes.useWorkbook { workbook ->
            OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
                pkg.parts
                    .filter { "/pivotCache/pivotCacheDefinition" in it.partName.name }
                    .mapNotNull { part ->
                        val xml = part.readText()
                        val sheetName = SHEET_ATTR_REGEX.find(xml)?.groupValues?.get(1) ?: return@mapNotNull null
                        val ref = WORKSHEET_SOURCE_REF_REGEX.find(xml)?.groupValues?.get(1) ?: return@mapNotNull null
                        val sheet = workbook.getSheet(sheetName) ?: return@mapNotNull null

                        val range = CellRangeAddress.valueOf(ref)
                        val headerRow = sheet.getRow(range.firstRow)
                        val fieldNames = (range.firstColumn..range.lastColumn).map { colIdx ->
                            headerRow?.getCell(colIdx)?.stringValue ?: "Field$colIdx"
                        }

                        val records = ((range.firstRow + 1)..range.lastRow).mapNotNull { rowIdx ->
                            sheet.getRow(rowIdx)?.let { row ->
                                val cellValues = (range.firstColumn..range.lastColumn).map { colIdx ->
                                    row.getCell(colIdx)?.cellValue
                                }
                                cellValues.takeIf { it.any { v -> v != null } }
                            }
                        }

                        val fields = fieldNames.mapIndexed { idx, name ->
                            val isAxisField = idx in axisFieldIndices
                            val columnValues = records.mapNotNull { it.getOrNull(idx) }
                            val numericValues = columnValues.filterIsInstance<Number>().map { it.toDouble() }
                            val isNumeric = columnValues.isNotEmpty() && columnValues.all { it is Number }

                            FieldMeta(
                                name = name,
                                isAxisField = isAxisField,
                                isNumeric = isNumeric,
                                sharedItems = if (isAxisField) {
                                    columnValues.map { it.toString() }.distinct()
                                } else emptyList(),
                                minValue = numericValues.minOrNull(),
                                maxValue = numericValues.maxOrNull(),
                                isInteger = numericValues.all { it == it.toLong().toDouble() }
                            )
                        }

                        LOG.debug(
                            "피벗 소스 데이터 추출: ${part.partName.name} - ${records.size}개 레코드"
                        )
                        part.partName.name to SourceData(records, fields)
                    }.toMap()
            }
        }

        if (sourceDataMap.isEmpty()) return inputBytes

        return ByteArrayOutputStream().also { output ->
            ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
                ZipOutputStream(output).use { zos ->
                    zis.entries().forEach { entry ->
                        val entryName = entry.name
                        val content = zis.readBytes().let { bytes ->
                            when {
                                "/pivotCache/pivotCacheDefinition" in entryName ->
                                    sourceDataMap["/$entryName"]?.let { data ->
                                        buildPivotCacheDefinition(
                                            bytes.decodeToString(), data.records.size, data.fields
                                        ).encodeToByteArray()
                                    } ?: bytes

                                "/pivotCache/pivotCacheRecords" in entryName -> {
                                    val defPath = "/$entryName".replace("pivotCacheRecords", "pivotCacheDefinition")
                                    sourceDataMap[defPath]?.let { data ->
                                        buildPivotCacheRecords(data.records, data.fields).encodeToByteArray()
                                    } ?: bytes
                                }

                                "/pivotTables/pivotTable" in entryName ->
                                    sourceDataMap.values.firstOrNull()?.let { data ->
                                        buildPivotTableDefinition(bytes.decodeToString(), data.fields)
                                            .encodeToByteArray()
                                    } ?: bytes

                                else -> bytes
                            }
                        }

                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(content)
                        zos.closeEntry()
                    }
                }
            }
        }.toByteArray()
    }

    private fun buildPivotCacheDefinition(originalXml: String, recordCount: Int, fields: List<FieldMeta>): String {
        var xml = originalXml
            .replace(REFRESH_ON_LOAD_REGEX, """refreshOnLoad="0"""")
            .replace(REFRESHED_VERSION_REGEX, """refreshedVersion="8"""")

        xml = if ("recordCount=" in xml) {
            xml.replace(RECORD_COUNT_REGEX, """recordCount="$recordCount"""")
        } else {
            xml.replace("<pivotCacheDefinition ", """<pivotCacheDefinition recordCount="$recordCount" """)
        }

        fields.forEach { field ->
            val sharedItemsXml = when {
                field.isAxisField && field.sharedItems.isNotEmpty() -> {
                    val items = field.sharedItems.joinToString("") { """<s v="${it.escapeXml()}"/>""" }
                    """<sharedItems count="${field.sharedItems.size}">$items</sharedItems>"""
                }
                field.isNumeric -> {
                    val containsInteger = if (field.isInteger) """containsInteger="1" """ else ""
                    "<sharedItems containsSemiMixedTypes=\"0\" containsString=\"0\" " +
                        "containsNumber=\"1\" $containsInteger" +
                        "minValue=\"${field.minValue}\" maxValue=\"${field.maxValue}\"/>"
                }
                else -> "<sharedItems/>"
            }

            val pattern = Regex(
                """<cacheField[^>]*name="${field.name}"[^>]*>.*?</cacheField>""",
                RegexOption.DOT_MATCHES_ALL
            )
            xml = pattern.replace(xml) { match ->
                match.value.replace(SHARED_ITEMS_REGEX, sharedItemsXml)
            }
        }

        return xml
    }

    private fun buildPivotCacheRecords(
        records: List<List<Any?>>,
        fields: List<FieldMeta>
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("<pivotCacheRecords xmlns=\"$SPREADSHEET_NS\" count=\"${records.size}\">")

        records.forEach { record ->
            append("<r>")
            record.forEachIndexed { idx, value ->
                val field = fields.getOrNull(idx)
                append(
                    when {
                        value == null -> "<m/>"
                        field?.isAxisField == true -> {
                            val sharedIndex = field.sharedItems.indexOf(value.toString()).coerceAtLeast(0)
                            """<x v="$sharedIndex"/>"""
                        }
                        value is Number -> """<n v="$value"/>"""
                        value is Boolean -> """<b v="${if (value) "1" else "0"}"/>"""
                        else -> """<s v="${value.toString().escapeXml()}"/>"""
                    }
                )
            }
            append("</r>")
        }

        append("</pivotCacheRecords>")
    }

    private fun buildPivotTableDefinition(originalXml: String, fields: List<FieldMeta>): String {
        var xml = originalXml
            .replace(TRUE_FALSE_ATTR_REGEX) { if (it.groupValues[1] == "true") """"1"""" else """"0"""" }
            .replace(UPDATED_VERSION_REGEX, """updatedVersion="8"""")

        // location ref 업데이트
        LOCATION_REF_FULL_REGEX.find(xml)?.let { locationMatch ->
            val refValue = locationMatch.groupValues[1]
            val parts = refValue.split(":")
            if (parts.size == 2) {
                val startCell = parts[0]
                val startCol = startCell.replace(Regex("""\d+"""), "")
                val startRowNum = startCell.replace(Regex("""[A-Z]+"""), "").toIntOrNull() ?: 1

                val uniqueValuesCount = fields.filter { it.isAxisField }
                    .flatMap { it.sharedItems }
                    .distinct().size

                val dataFieldsCount = xml.split("<dataField").size - 1
                val newEndCol = (startCol.toColumnIndex() + dataFieldsCount).toColumnLetter()
                val newEndRow = startRowNum + uniqueValuesCount + 1

                xml = xml.replace("""ref="$refValue"""", """ref="$startCell:$newEndCol$newEndRow"""")
            }
        }

        // pivotField items 수정
        val axisFields = fields.mapIndexedNotNull { idx, f -> idx.takeIf { f.isAxisField } }.toSet()

        xml = PIVOT_FIELD_REGEX.replace(xml) { matchResult ->
            if (AXIS_ATTR_REGEX.find(matchResult.value) != null) {
                val axisFieldIdx = axisFields.firstOrNull() ?: return@replace matchResult.value
                val sharedItems = fields.getOrNull(axisFieldIdx)?.sharedItems ?: return@replace matchResult.value

                val itemsXml = buildString {
                    append("""<items count="${sharedItems.size + 1}">""")
                    sharedItems.indices.forEach { i -> append("""<item x="$i"/>""") }
                    append("""<item t="default"/>""")
                    append("</items>")
                }

                matchResult.value.replace(ITEMS_REGEX, itemsXml)
            } else {
                matchResult.value
            }
        }

        // rowItems 추가
        if ("<rowItems" !in xml && axisFields.isNotEmpty()) {
            val sharedItems = fields.getOrNull(axisFields.first())?.sharedItems.orEmpty()

            if (sharedItems.isNotEmpty()) {
                val rowItemsXml = buildString {
                    append("""<rowItems count="${sharedItems.size + 1}">""")
                    append("<i><x/></i>")
                    (1 until sharedItems.size).forEach { i -> append("""<i><x v="$i"/></i>""") }
                    append("""<i t="grand"><x/></i>""")
                    append("</rowItems>")
                }

                xml = when {
                    "</rowFields>" in xml -> xml.replace("</rowFields>", "</rowFields>$rowItemsXml")
                    else -> xml.replace("</pivotFields>", "</pivotFields>$rowItemsXml")
                }
            }
        }

        // colItems 추가
        val dataFieldsCount = xml.split("<dataField").size - 1
        if ("<colItems" !in xml && dataFieldsCount > 0) {
            val colItemsXml = if (dataFieldsCount > 1) {
                buildString {
                    append("""<colItems count="$dataFieldsCount">""")
                    (0 until dataFieldsCount).forEach { i ->
                        append(if (i == 0) "<i><x/></i>" else """<i><x v="$i"/></i>""")
                    }
                    append("</colItems>")
                }
            } else {
                """<colItems count="1"><i/></colItems>"""
            }

            xml = when {
                "</colFields>" in xml -> xml.replace("</colFields>", "</colFields>$colItemsXml")
                "</rowItems>" in xml -> xml.replace("</rowItems>", "</rowItems>$colItemsXml")
                else -> xml.replace("</rowFields>", "</rowFields>$colItemsXml")
            }
        }

        // dataField에 baseField/baseItem 추가
        xml = DATA_FIELD_SELF_CLOSING_REGEX.replace(xml) { match ->
            val attrs = match.groupValues[1]
            if ("baseField" !in attrs) """<dataField$attrs baseField="0" baseItem="0"/>""" else match.value
        }

        // colFields 추가 (다중 데이터 필드용)
        if (dataFieldsCount > 1 && "<colFields" !in xml) {
            xml = xml.replace("</rowItems>", """</rowItems><colFields count="1"><field x="-2"/></colFields>""")
        }

        // firstHeaderRow="0" 항상 적용
        if ("""firstHeaderRow="0"""" !in xml) {
            xml = xml.replace(FIRST_HEADER_ROW_REGEX, """firstHeaderRow="0"""")
        }

        return xml
    }

    // ========== 확장 함수 ==========

    private fun PackagePart.readText() = inputStream.bufferedReader().readText()

    private fun XSSFWorkbook.toByteArray() = ByteArrayOutputStream().also { write(it) }.toByteArray()

    private fun XSSFWorkbook.sheetSequence(): Sequence<Sheet> = sheetIterator().asSequence()

    private fun <T> ByteArray.useWorkbook(block: (XSSFWorkbook) -> T): T =
        XSSFWorkbook(ByteArrayInputStream(this)).use(block)

    private fun ZipInputStream.entries() = generateSequence { nextEntry }

    private fun Row.getOrCreateCell(col: Int): Cell = getCell(col) ?: createCell(col)

    private fun XSSFSheet.getOrCreateRow(row: Int): Row = getRow(row) ?: createRow(row)

    private fun CellRangeAddress.copy(
        firstRow: Int = this.firstRow,
        lastRow: Int = this.lastRow,
        firstColumn: Int = this.firstColumn,
        lastColumn: Int = this.lastColumn
    ) = CellRangeAddress(firstRow, lastRow, firstColumn, lastColumn)

    private val Cell.cellValue: Any?
        get() = when (cellType) {
            CellType.STRING -> stringCellValue.takeIf { it.isNotBlank() }
            CellType.NUMERIC -> numericCellValue
            CellType.BOOLEAN -> booleanCellValue
            CellType.FORMULA -> runCatching { numericCellValue }
                .getOrElse { stringCellValue.takeIf { it.isNotBlank() } }
            else -> null
        }

    private val Cell.stringValue: String?
        get() = when (cellType) {
            CellType.STRING -> stringCellValue.takeIf { it.isNotBlank() }
            CellType.NUMERIC -> numericCellValue.toString()
            CellType.BOOLEAN -> booleanCellValue.toString()
            else -> null
        }

    private val Cell.hasData: Boolean
        get() = cellType != CellType.BLANK && when (cellType) {
            CellType.STRING -> stringCellValue.trim().isNotEmpty()
            CellType.NUMERIC, CellType.BOOLEAN -> true
            else -> false
        }

    private val DataConsolidateFunction.formatIndex: Short
        get() = when (this) {
            DataConsolidateFunction.SUM, DataConsolidateFunction.COUNT, DataConsolidateFunction.COUNT_NUMS ->
                config.pivotIntegerFormatIndex
            else -> config.pivotDecimalFormatIndex
        }

    private fun String.toRgbBytes() =
        removePrefix("#").removePrefix("FF").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        val LOG by commonLogger()

        private const val PIVOT_TABLE_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.pivotTable+xml"

        private const val EMPTY_RELATIONSHIPS_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""

        private const val SPREADSHEET_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

        // 정규식 패턴들 (재사용을 위해 컴파일)
        private val SHEET_ATTR_REGEX = Regex("""sheet="([^"]+)"""")
        private val WORKSHEET_SOURCE_REF_REGEX = Regex("""<worksheetSource[^>]*ref="([^"]+)"""")
        private val NAME_ATTR_REGEX = Regex("""name="([^"]+)"""")
        private val LOCATION_REF_REGEX = Regex("""<location[^>]*ref="([^"]+)"""")
        private val ROW_FIELDS_REGEX = Regex("""<rowFields[^>]*>(.+?)</rowFields>""")
        private val FIELD_X_REGEX = Regex("""<field x="(\d+)"""")
        private val DATA_FIELD_REGEX = Regex("""<dataField[^>]+>""")
        private val ROW_HEADER_CAPTION_REGEX = Regex("""rowHeaderCaption="([^"]+)"""")
        private val GRAND_TOTAL_CAPTION_REGEX = Regex("""grandTotalCaption="([^"]+)"""")
        private val FORMATS_REGEX = Regex("""<formats\s+count="\d+">(.*?)</formats>""", RegexOption.DOT_MATCHES_ALL)
        private val FLD_ATTR_REGEX = Regex("""fld="(\d+)"""")
        private val SUBTOTAL_ATTR_REGEX = Regex("""subtotal="([^"]*)"""")
        private val SHEET_NAME_REGEX = Regex("""<sheet[^>]*name="([^"]+)"[^>]*r:id="rId\d+"""")
        private val COL_ITEMS_COUNT_REGEX = Regex("""<colItems\s+count="(\d+)">""")
        private val COL_ITEMS_FULL_REGEX = Regex(
            """<colItems\s+count="\d+">(.*?)</colItems>""", RegexOption.DOT_MATCHES_ALL
        )
        private val I_ELEMENT_REGEX = Regex("""<i[^>]*/>|<i[^>]*>.*?</i>""", RegexOption.DOT_MATCHES_ALL)
        private val LOCATION_WITH_REF_REGEX = Regex("""(<location[^>]*ref=")([^"]+)(")""")
        private val PIVOT_CACHE_OVERRIDE_REGEX = Regex("""<Override[^>]*pivotCache[^>]*/>\s*""")
        private val PIVOT_TABLE_OVERRIDE_REGEX = Regex("""<Override[^>]*pivotTable[^>]*/>\s*""")
        private val PIVOT_TABLE_REL_REGEX = Regex("""<Relationship[^>]*pivotTable[^>]*/>\s*""")
        private val PIVOT_CACHES_REGEX = Regex("""<pivotCaches>.*?</pivotCaches>""", RegexOption.DOT_MATCHES_ALL)
        private val PIVOT_CACHES_EMPTY_REGEX = Regex("""<pivotCaches/>""")
        private val PIVOT_CACHE_REL_REGEX = Regex("""<Relationship[^>]*pivotCache[^>]*/>\s*""")
        private val REFRESH_ON_LOAD_REGEX = Regex("""refreshOnLoad="(true|1)"""")
        private val REFRESHED_VERSION_REGEX = Regex("""refreshedVersion="\d+"""")
        private val RECORD_COUNT_REGEX = Regex("""recordCount="\d+"""")
        private val SHARED_ITEMS_REGEX = Regex(
            """<sharedItems[^>]*/>|<sharedItems[^>]*>.*?</sharedItems>""", RegexOption.DOT_MATCHES_ALL
        )
        private val TRUE_FALSE_ATTR_REGEX = Regex(""""(true|false)"""")
        private val UPDATED_VERSION_REGEX = Regex("""updatedVersion="\d+"""")
        private val LOCATION_REF_FULL_REGEX = Regex("""<location[^>]*ref="([^"]+)"[^>]*/?>""")
        private val PIVOT_FIELD_REGEX = Regex("""<pivotField[^>]*>(.*?)</pivotField>""", RegexOption.DOT_MATCHES_ALL)
        private val AXIS_ATTR_REGEX = Regex("""axis="([^"]+)"""")
        private val ITEMS_REGEX = Regex("""<items[^>]*>.*?</items>""", RegexOption.DOT_MATCHES_ALL)
        private val DATA_FIELD_SELF_CLOSING_REGEX = Regex("""<dataField([^>]*)/>""")
        private val FIRST_HEADER_ROW_REGEX = Regex("""firstHeaderRow="\d+"""")
    }
}
