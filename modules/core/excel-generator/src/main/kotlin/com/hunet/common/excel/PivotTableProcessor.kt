package com.hunet.common.excel

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataConsolidateFunction
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 피벗 테이블 처리를 담당하는 프로세서.
 */
internal class PivotTableProcessor(
    private val config: ExcelGeneratorConfig
) {
    private val logger = LoggerFactory.getLogger(PivotTableProcessor::class.java)

    // 숫자 서식 스타일 캐시
    private val numberStyleCache = mutableMapOf<XSSFWorkbook, MutableMap<String, XSSFCellStyle>>()

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
        // 원본 템플릿에서 추출한 스타일 (JXLS 처리 전)
        val originalStyles: PivotTableStyles? = null,
        // 원본 피벗 테이블의 formats XML (dxf 스타일 참조)
        val originalFormatsXml: String? = null
    )

    /**
     * 피벗 테이블 영역의 원본 스타일 정보.
     */
    data class PivotTableStyles(
        val headerStyles: Map<Int, StyleInfo>,
        val dataRowStyles: Map<Int, StyleInfo>,
        val grandTotalStyles: Map<Int, StyleInfo>
    )

    /**
     * 셀 스타일 정보 (워크북에 독립적).
     */
    data class StyleInfo(
        val fontBold: Boolean = false,
        val fontItalic: Boolean = false,
        val fontUnderline: Byte = org.apache.poi.ss.usermodel.Font.U_NONE,  // U_NONE, U_SINGLE, U_DOUBLE, etc.
        val fontStrikeout: Boolean = false,
        val fontName: String? = null,
        val fontSize: Short = 11,
        val fontColorRgb: String? = null,  // ARGB hex (예: "FF000000" 검정)
        val fillForegroundColorRgb: String? = null,  // ARGB hex (예: "FFFFFF00")
        val fillPatternType: org.apache.poi.ss.usermodel.FillPatternType = org.apache.poi.ss.usermodel.FillPatternType.NO_FILL,
        val borderTop: org.apache.poi.ss.usermodel.BorderStyle = org.apache.poi.ss.usermodel.BorderStyle.NONE,
        val borderBottom: org.apache.poi.ss.usermodel.BorderStyle = org.apache.poi.ss.usermodel.BorderStyle.NONE,
        val borderLeft: org.apache.poi.ss.usermodel.BorderStyle = org.apache.poi.ss.usermodel.BorderStyle.NONE,
        val borderRight: org.apache.poi.ss.usermodel.BorderStyle = org.apache.poi.ss.usermodel.BorderStyle.NONE,
        val horizontalAlignment: org.apache.poi.ss.usermodel.HorizontalAlignment = org.apache.poi.ss.usermodel.HorizontalAlignment.GENERAL,
        val verticalAlignment: org.apache.poi.ss.usermodel.VerticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER,
        val dataFormat: Short = 0
    )

    data class DataFieldInfo(
        val fieldIndex: Int,
        val function: DataConsolidateFunction,
        val name: String?
    )

    /**
     * 피벗 테이블 소스 데이터의 행을 나타냅니다.
     */
    private data class DataRow(val values: Map<Int, Any?>)

    // ========== 공개 API ==========

    /**
     * 피벗 테이블 정보를 추출하고 워크북에서 제거합니다.
     */
    fun extractAndRemove(inputBytes: ByteArray): Pair<List<PivotTableInfo>, ByteArray> {
        val pivotTableInfos = mutableListOf<PivotTableInfo>()

        // 워크북을 열어서 피벗 테이블 존재 여부 확인 및 원본 스타일 추출
        val stylesMap = mutableMapOf<String, PivotTableStyles>()  // pivotTableName -> styles

        val hasPivotTable = XSSFWorkbook(ByteArrayInputStream(inputBytes)).use { workbook ->
            var found = false
            workbook.sheetIterator().asSequence().forEach { sheet ->
                val xssfSheet = sheet as? XSSFSheet ?: return@forEach
                xssfSheet.pivotTables?.forEach { pivotTable ->
                    found = true
                    // 원본 템플릿에서 스타일 추출 (JXLS 처리 전)
                    val styles = extractOriginalStyles(xssfSheet, pivotTable)
                    if (styles != null) {
                        val name = pivotTable.ctPivotTableDefinition?.name ?: "PivotTable"
                        stylesMap[name] = styles
                        logger.debug("원본 스타일 추출 완료: $name")
                    }
                }
            }
            found
        }

        if (!hasPivotTable) {
            logger.debug("피벗 테이블이 없음, 원본 반환")
            return Pair(emptyList(), inputBytes)
        }

        // 피벗 테이블 정보 수집 (XML 직접 파싱)
        org.apache.poi.openxml4j.opc.OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
            val cacheSourceMap = mutableMapOf<String, Pair<String, String>>()

            pkg.parts.filter { it.partName.name.contains("/pivotCache/pivotCacheDefinition") }.forEach { part ->
                val xml = part.inputStream.bufferedReader().readText()
                val sheetMatch = Regex("""sheet="([^"]+)"""").find(xml)
                val refMatch = Regex("""<worksheetSource[^>]*ref="([^"]+)"""").find(xml)

                if (sheetMatch != null && refMatch != null) {
                    cacheSourceMap[part.partName.name] = Pair(sheetMatch.groupValues[1], refMatch.groupValues[1])
                    logger.debug("피벗 캐시 발견: ${part.partName.name} -> ${sheetMatch.groupValues[1]}!${refMatch.groupValues[1]}")
                }
            }

            pkg.parts.filter { it.partName.name.contains("/pivotTables/pivotTable") }.forEach { part ->
                parsePivotTablePart(part, pkg, cacheSourceMap, stylesMap)?.let { pivotTableInfos.add(it) }
            }
        }

        val cleanedBytes = removePivotReferencesFromZip(inputBytes)
        return Pair(pivotTableInfos, cleanedBytes)
    }

    /**
     * 원본 템플릿에서 피벗 테이블 영역의 스타일을 추출합니다.
     */
    private fun extractOriginalStyles(sheet: XSSFSheet, pivotTable: org.apache.poi.xssf.usermodel.XSSFPivotTable): PivotTableStyles? {
        return try {
            val location = pivotTable.ctPivotTableDefinition?.location ?: return null
            val ref = location.ref ?: return null
            val range = CellRangeAddress.valueOf(ref)

            val headerRowNum = range.firstRow
            val dataRowNum = range.firstRow + 1
            val grandTotalRowNum = range.lastRow

            logger.debug("원본 스타일 추출 - range: ${range.formatAsString()}, header: $headerRowNum, data: $dataRowNum, grandTotal: $grandTotalRowNum")

            val headerStyles = extractRowStyleInfos(sheet.getRow(headerRowNum), range)
            val dataRowStyles = if (dataRowNum < grandTotalRowNum) {
                extractRowStyleInfos(sheet.getRow(dataRowNum), range)
            } else {
                headerStyles
            }
            val grandTotalStyles = extractRowStyleInfos(sheet.getRow(grandTotalRowNum), range)

            logger.debug("원본 스타일 - header: ${headerStyles.size}개, dataRow: ${dataRowStyles.size}개, grandTotal: ${grandTotalStyles.size}개")

            PivotTableStyles(headerStyles, dataRowStyles, grandTotalStyles)
        } catch (e: Exception) {
            logger.warn("원본 스타일 추출 실패: ${e.message}")
            null
        }
    }

    /**
     * 행의 각 셀에서 StyleInfo를 추출합니다.
     */
    private fun extractRowStyleInfos(row: org.apache.poi.ss.usermodel.Row?, range: CellRangeAddress): Map<Int, StyleInfo> {
        if (row == null) return emptyMap()
        val styles = mutableMapOf<Int, StyleInfo>()

        for (colIdx in range.firstColumn..range.lastColumn) {
            val cell = row.getCell(colIdx)
            if (cell != null) {
                val cellStyle = cell.cellStyle as? XSSFCellStyle ?: continue
                styles[colIdx - range.firstColumn] = extractStyleInfo(cellStyle)
            }
        }
        return styles
    }

    /**
     * XSSFCellStyle에서 StyleInfo를 추출합니다.
     */
    private fun extractStyleInfo(style: XSSFCellStyle): StyleInfo {
        val font = style.font as? org.apache.poi.xssf.usermodel.XSSFFont
        val fillColorRgb = if (style.fillPattern != org.apache.poi.ss.usermodel.FillPatternType.NO_FILL) {
            style.fillForegroundXSSFColor?.argbHex
        } else null

        // 글꼴 색상 추출 (테마 색상 또는 RGB)
        val fontColorRgb = font?.xssfColor?.argbHex

        return StyleInfo(
            fontBold = font?.bold ?: false,
            fontItalic = font?.italic ?: false,
            fontUnderline = font?.underline ?: org.apache.poi.ss.usermodel.Font.U_NONE,
            fontStrikeout = font?.strikeout ?: false,
            fontName = font?.fontName,
            fontSize = font?.fontHeightInPoints ?: 11,
            fontColorRgb = fontColorRgb,
            fillForegroundColorRgb = fillColorRgb,
            fillPatternType = style.fillPattern,
            borderTop = style.borderTop,
            borderBottom = style.borderBottom,
            borderLeft = style.borderLeft,
            borderRight = style.borderRight,
            horizontalAlignment = style.alignment,
            verticalAlignment = style.verticalAlignment,
            dataFormat = style.dataFormat
        )
    }

    /**
     * 피벗 테이블을 재생성합니다.
     */
    fun recreate(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) return inputBytes

        val bytesWithPivotTable = XSSFWorkbook(ByteArrayInputStream(inputBytes)).use { workbook ->
            pivotTableInfos.forEach { info -> recreatePivotTable(workbook, info) }
            workbook.toByteArray()
        }

        // 피벗 캐시 채우기 (이 과정에서 colItems 등이 생성됨)
        val bytesWithCache = populatePivotCache(bytesWithPivotTable, pivotTableInfos)

        // 피벗 테이블 범위 및 colItems 조정 (마지막에 수행)
        return adjustPivotTableStructure(bytesWithCache, pivotTableInfos)
    }

    /**
     * 저장된 피벗 테이블의 XML 구조를 원본 템플릿에 맞게 조정합니다.
     */
    private fun adjustPivotTableStructure(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) return inputBytes

        // 피벗 테이블 이름으로 빠르게 검색하기 위한 맵
        val infoByName = pivotTableInfos.associateBy { it.pivotTableName }

        return org.apache.poi.openxml4j.opc.OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
            val pivotTableParts = pkg.getPartsByContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.pivotTable+xml"
            )

            pivotTableParts.forEach { part ->
                var xml = part.inputStream.bufferedReader().readText()
                var modified = false

                // XML에서 피벗 테이블 이름 추출하여 해당 info 찾기
                val nameMatch = Regex("""name="([^"]+)"""").find(xml)
                val pivotTableName = nameMatch?.groupValues?.get(1)
                val info = pivotTableName?.let { infoByName[it] }

                if (info == null) {
                    logger.debug("피벗 테이블 info를 찾을 수 없음: $pivotTableName")
                    return@forEach
                }

                val expectedColCount = info.dataFields.size
                val originalRange = CellRangeAddress.valueOf(info.originalLocationRef)
                val expectedCols = originalRange.lastColumn - originalRange.firstColumn + 1

                // colItems count 조정
                val colItemsMatch = Regex("""<colItems\s+count="(\d+)">""").find(xml)
                if (colItemsMatch != null) {
                    val currentCount = colItemsMatch.groupValues[1].toInt()
                    if (currentCount > expectedColCount) {
                        // colItems 수정: 초과 <i> 요소 제거
                        val colItemsPattern = Regex("""<colItems\s+count="\d+">(.*?)</colItems>""", RegexOption.DOT_MATCHES_ALL)
                        xml = colItemsPattern.replace(xml) { match ->
                            val content = match.groupValues[1]
                            val iElements = Regex("""<i[^>]*/>|<i[^>]*>.*?</i>""", RegexOption.DOT_MATCHES_ALL).findAll(content).take(expectedColCount).map { it.value }.joinToString("")
                            """<colItems count="$expectedColCount">$iElements</colItems>"""
                        }
                        modified = true
                    }
                }

                // location ref 조정
                val locationMatch = Regex("""(<location[^>]*ref=")([^"]+)(")""").find(xml)
                if (locationMatch != null) {
                    val currentRef = locationMatch.groupValues[2]
                    val currentRange = CellRangeAddress.valueOf(currentRef)
                    val newLastCol = currentRange.firstColumn + expectedCols - 1
                    val newRange = CellRangeAddress(
                        currentRange.firstRow, currentRange.lastRow,
                        currentRange.firstColumn, newLastCol
                    )
                    val newRef = newRange.formatAsString()
                    if (currentRef != newRef) {
                        xml = xml.replace("""ref="$currentRef"""", """ref="$newRef"""")
                        modified = true
                    }
                }

                // 원본 formats XML은 삽입하지 않음
                // - 템플릿의 formats는 원래 데이터 행 개수에 맞춰 설계됨
                // - 새로운 피벗 테이블의 행 개수가 다르면 formats 참조가 잘못 적용됨
                // - PivotStyleLight16 기본 스타일 + 명시적 셀 스타일로 대체

                // 수정된 XML 저장
                if (modified) {
                    part.outputStream.use { os ->
                        os.write(xml.toByteArray(Charsets.UTF_8))
                    }
                    logger.debug("피벗 테이블 XML 구조 조정 완료: $pivotTableName")
                }
            }

            ByteArrayOutputStream().also { pkg.save(it) }.toByteArray()
        }
    }

    // ========== 내부 함수 ==========

    private fun parsePivotTablePart(
        part: org.apache.poi.openxml4j.opc.PackagePart,
        pkg: org.apache.poi.openxml4j.opc.OPCPackage,
        cacheSourceMap: Map<String, Pair<String, String>>,
        stylesMap: Map<String, PivotTableStyles>
    ): PivotTableInfo? {
        val xml = part.inputStream.bufferedReader().readText()

        val nameMatch = Regex("""name="([^"]+)"""").find(xml)
        val pivotTableName = nameMatch?.groupValues?.get(1) ?: "PivotTable"

        val locationMatch = Regex("""<location[^>]*ref="([^"]+)"""").find(xml)
        val fullLocationRef = locationMatch?.groupValues?.get(1) ?: "A1:A1"
        val location = fullLocationRef.split(":").firstOrNull() ?: "A1"

        val rowLabelFields = mutableListOf<Int>()
        Regex("""<rowFields[^>]*>(.+?)</rowFields>""").find(xml)?.let { rowFieldsMatch ->
            Regex("""<field x="(\d+)"""").findAll(rowFieldsMatch.groupValues[1]).forEach {
                rowLabelFields.add(it.groupValues[1].toInt())
            }
        }

        val dataFields = mutableListOf<DataFieldInfo>()
        Regex("""<dataField[^>]+>""").findAll(xml).forEach { match ->
            parseDataField(match.value)?.let { dataFields.add(it) }
        }

        val rowHeaderCaption = Regex("""rowHeaderCaption="([^"]+)"""").find(xml)?.groupValues?.get(1)
        val grandTotalCaption = Regex("""grandTotalCaption="([^"]+)"""").find(xml)?.groupValues?.get(1)

        // 원본 formats XML 추출 (grand total 및 데이터 행 스타일용)
        val formatsMatch = Regex("""<formats\s+count="\d+">(.*?)</formats>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        val originalFormatsXml = formatsMatch?.value
        if (originalFormatsXml != null) {
            logger.debug("원본 formats XML 추출: ${originalFormatsXml.length}자")
        }

        val (sourceSheet, sourceRef) = cacheSourceMap.values.firstOrNull() ?: run {
            logger.warn("피벗 테이블 '$pivotTableName' 캐시 소스 정보 없음")
            return null
        }

        val pivotTableSheetName = findPivotTableSheetName(pkg, part.partName.name) ?: sourceSheet

        logger.debug("피벗 테이블 발견: '$pivotTableName' (위치: $pivotTableSheetName!$location, 소스: $sourceSheet!$sourceRef)")

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

    private fun parseDataField(dataFieldXml: String): DataFieldInfo? {
        val fld = Regex("""fld="(\d+)"""").find(dataFieldXml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val subtotal = Regex("""subtotal="([^"]*)"""").find(dataFieldXml)?.groupValues?.get(1) ?: "sum"
        val name = Regex("""name="([^"]+)"""").find(dataFieldXml)?.groupValues?.get(1)

        val function = when (subtotal) {
            "count" -> DataConsolidateFunction.COUNT
            "average" -> DataConsolidateFunction.AVERAGE
            "max" -> DataConsolidateFunction.MAX
            "min" -> DataConsolidateFunction.MIN
            else -> DataConsolidateFunction.SUM
        }

        return DataFieldInfo(fld, function, name)
    }

    private fun findPivotTableSheetName(pkg: org.apache.poi.openxml4j.opc.OPCPackage, pivotTablePartName: String): String? {
        val workbookPart = pkg.parts.find { it.partName.name == "/xl/workbook.xml" }
        val sheetNames = mutableListOf<String>()

        workbookPart?.let {
            val xml = it.inputStream.bufferedReader().readText()
            Regex("""<sheet[^>]*name="([^"]+)"[^>]*r:id="(rId\d+)"""").findAll(xml).forEach { match ->
                sheetNames.add(match.groupValues[1])
            }
        }

        for ((index, sheetName) in sheetNames.withIndex()) {
            val sheetRelsPartName = "/xl/worksheets/_rels/sheet${index + 1}.xml.rels"
            val relsPart = pkg.parts.find { it.partName.name == sheetRelsPartName }

            relsPart?.let {
                val relsXml = it.inputStream.bufferedReader().readText()
                val pivotFileName = pivotTablePartName.substringAfterLast("/")
                if (relsXml.contains(pivotFileName)) {
                    return sheetName
                }
            }
        }

        return null
    }

    private fun recreatePivotTable(workbook: XSSFWorkbook, info: PivotTableInfo) {
        val pivotSheet = workbook.getSheet(info.pivotTableSheetName) as? XSSFSheet
        val sourceSheet = workbook.getSheet(info.sourceSheetName) as? XSSFSheet

        if (pivotSheet == null || sourceSheet == null) {
            logger.warn("피벗 테이블 재생성 실패: 시트를 찾을 수 없음 (pivot=${info.pivotTableSheetName}, source=${info.sourceSheetName})")
            return
        }

        val newLastRow = findLastRowWithData(sourceSheet, info.sourceRange)
        val newSourceRange = CellRangeAddress(
            info.sourceRange.firstRow, newLastRow,
            info.sourceRange.firstColumn, info.sourceRange.lastColumn
        )

        logger.debug("피벗 테이블 재생성: ${info.pivotTableName} (범위: ${info.sourceRange.formatAsString()} -> ${newSourceRange.formatAsString()})")

        try {
            val pivotLocation = org.apache.poi.ss.util.CellReference(info.pivotTableLocation)

            // 원본 템플릿에서 추출한 스타일 사용
            val originalStyles = info.originalStyles
            if (originalStyles != null) {
                logger.debug("원본 스타일 사용 - dataRow: ${originalStyles.dataRowStyles.size}개, grandTotal: ${originalStyles.grandTotalStyles.size}개")
            } else {
                logger.debug("원본 스타일 없음, 기본 스타일 사용")
            }

            // 피벗 테이블 영역만 지우기 (다른 피벗 테이블과 겹침 방지)
            val expectedDataRows = uniqueValuesFromSourceData(sourceSheet, newSourceRange, info.rowLabelFields).size
            val expectedRows = 1 + expectedDataRows + 1  // 헤더 + 데이터 행들 + 총합계
            val expectedCols = 1 + info.dataFields.size  // 축 열 + 데이터 열들
            clearPivotTableArea(pivotSheet, pivotLocation.row, pivotLocation.col.toInt(), expectedRows + 2, expectedCols + 1)

            val areaReference = org.apache.poi.ss.util.AreaReference(
                "${info.sourceSheetName}!${newSourceRange.formatAsString()}",
                workbook.spreadsheetVersion
            )

            val pivotTable = pivotSheet.createPivotTable(areaReference, pivotLocation, sourceSheet)

            // 원본 피벗 테이블 이름 설정 (adjustPivotTableStructure에서 매칭에 사용)
            pivotTable.ctPivotTableDefinition.name = info.pivotTableName

            info.rowLabelFields.forEach { fieldIdx -> pivotTable.addRowLabel(fieldIdx) }
            info.dataFields.forEach { dataField ->
                pivotTable.addColumnLabel(dataField.function, dataField.fieldIndex, dataField.name)
            }

            info.rowHeaderCaption?.let { caption ->
                pivotTable.ctPivotTableDefinition.rowHeaderCaption = caption
                logger.debug("rowHeaderCaption 적용: '$caption'")
            }

            fillPivotTableCellsWithStyleInfo(
                workbook, pivotSheet, sourceSheet, newSourceRange, pivotLocation,
                info.rowLabelFields, info.dataFields, info.rowHeaderCaption, info.grandTotalCaption,
                originalStyles?.headerStyles ?: emptyMap(),
                originalStyles?.dataRowStyles ?: emptyMap()
            )

            // 피벗 테이블 구조 조정은 recreate()에서 adjustPivotTableStructure()로 수행됨

            logger.debug("피벗 테이블 재생성 완료: ${info.pivotTableName}")
        } catch (e: Exception) {
            throw IllegalStateException("피벗 테이블 재생성 실패: ${info.pivotTableName}", e)
        }
    }

    /**
     * 소스 데이터에서 축 필드의 고유 값들을 추출합니다.
     */
    private fun uniqueValuesFromSourceData(sheet: XSSFSheet, range: CellRangeAddress, rowLabelFields: List<Int>): List<String> {
        if (rowLabelFields.isEmpty()) return emptyList()

        val axisFieldIdx = rowLabelFields.first()
        val uniqueValues = mutableListOf<String>()

        for (rowNum in (range.firstRow + 1)..range.lastRow) {
            val row = sheet.getRow(rowNum) ?: continue
            val cell = row.getCell(range.firstColumn + axisFieldIdx)
            val value = getCellValue(cell)?.toString()
            if (value != null && value !in uniqueValues) {
                uniqueValues.add(value)
            }
        }

        return uniqueValues
    }

    private fun findLastRowWithData(sheet: XSSFSheet, range: CellRangeAddress): Int {
        var lastRow = range.firstRow
        for (rowNum in range.firstRow + 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowNum) ?: continue
            var hasData = false
            for (colIdx in range.firstColumn..range.lastColumn) {
                val cell = row.getCell(colIdx)
                if (cell != null && cell.cellType != CellType.BLANK) {
                    val value = when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue.trim()
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> ""
                    }
                    if (value.isNotEmpty()) {
                        hasData = true
                        break
                    }
                }
            }
            if (hasData) lastRow = rowNum else break
        }
        return lastRow
    }

    private fun clearPivotTableArea(sheet: XSSFSheet, startRow: Int, startCol: Int, rows: Int, cols: Int) {
        val defaultStyle = sheet.workbook.getCellStyleAt(0)
        for (rowIdx in startRow until startRow + rows) {
            val row = sheet.getRow(rowIdx) ?: continue
            for (colIdx in startCol until startCol + cols) {
                row.getCell(colIdx)?.apply {
                    setBlank()
                    cellStyle = defaultStyle
                }
            }
        }
    }

    private fun fillPivotTableCellsWithStyleInfo(
        workbook: XSSFWorkbook,
        pivotSheet: XSSFSheet,
        sourceSheet: XSSFSheet,
        sourceRange: CellRangeAddress,
        pivotLocation: org.apache.poi.ss.util.CellReference,
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
            getCellStringValue(headerRow.getCell(colIdx)) ?: "Field$colIdx"
        }

        val dataRows = mutableListOf<DataRow>()

        for (rowNum in (sourceRange.firstRow + 1)..sourceRange.lastRow) {
            val row = sourceSheet.getRow(rowNum) ?: continue
            val values = mutableMapOf<Int, Any?>()
            for (colIdx in sourceRange.firstColumn..sourceRange.lastColumn) {
                values[colIdx - sourceRange.firstColumn] = getCellValue(row.getCell(colIdx))
            }
            dataRows.add(DataRow(values))
        }

        if (dataRows.isEmpty()) return

        val axisFieldIdx = rowLabelFields.first()
        val uniqueValues = dataRows.mapNotNull { it.values[axisFieldIdx]?.toString() }.distinct()

        if (uniqueValues.isEmpty()) return

        val startRow = pivotLocation.row
        val startCol = pivotLocation.col.toInt()

        // 스타일 캐시 (StyleInfo -> XSSFCellStyle)
        val styleCache = mutableMapOf<StyleInfo, XSSFCellStyle>()

        // 축 셀 (직급 열)의 정렬 스타일 (배경색 제외)
        val axisAlignmentStyle = dataRowStyles[0]?.let { styleInfo ->
            getOrCreateAlignmentOnlyStyle(workbook, styleInfo)
        }

        // 헤더 행
        val pivotHeaderRow = pivotSheet.getRow(startRow) ?: pivotSheet.createRow(startRow)
        val headerCaptionCell = pivotHeaderRow.getCell(startCol) ?: pivotHeaderRow.createCell(startCol)
        headerCaptionCell.setCellValue(rowHeaderCaption ?: headers.getOrNull(axisFieldIdx) ?: "Row Labels")
        // 헤더 캡션 셀: headerStyles[0]의 정렬 적용 (없으면 dataRowStyles[0] 정렬 사용)
        (headerStyles[0] ?: dataRowStyles[0])?.let { styleInfo ->
            headerCaptionCell.cellStyle = getOrCreateAlignmentOnlyStyle(workbook, styleInfo)
        }

        dataFields.forEachIndexed { idx, dataField ->
            val headerCell = pivotHeaderRow.getCell(startCol + 1 + idx) ?: pivotHeaderRow.createCell(startCol + 1 + idx)
            headerCell.setCellValue(dataField.name ?: "Values")
            // 헤더 데이터 셀: headerStyles[1+idx]의 정렬 적용 (배경색 제외)
            headerStyles[1 + idx]?.let { styleInfo ->
                headerCell.cellStyle = getOrCreateAlignmentOnlyStyle(workbook, styleInfo)
            }
        }

        // 데이터 행들
        uniqueValues.forEachIndexed { rowIdx, axisValue ->
            val pivotRowNum = startRow + 1 + rowIdx
            val pivotRow = pivotSheet.getRow(pivotRowNum) ?: pivotSheet.createRow(pivotRowNum)

            val axisCell = pivotRow.getCell(startCol) ?: pivotRow.createCell(startCol)
            axisCell.setCellValue(axisValue)
            dataRowStyles[0]?.let { styleInfo ->
                axisCell.cellStyle = getOrCreateStyleFromInfo(workbook, styleInfo, styleCache)
            }

            dataFields.forEachIndexed { dataIdx, dataField ->
                val matchingRows = dataRows.filter { it.values[axisFieldIdx]?.toString() == axisValue }

                val dataCell = pivotRow.getCell(startCol + 1 + dataIdx) ?: pivotRow.createCell(startCol + 1 + dataIdx)
                dataCell.setCellValue(aggregateForField(matchingRows, dataField))

                val styleInfo = dataRowStyles[1 + dataIdx]
                dataCell.cellStyle = getOrCreateStyleFromInfoWithNumberFormat(workbook, styleInfo, dataField.function, styleCache)
            }
        }

        // 총합계 행 - 값 설정 + 숫자 서식만 적용 (배경색은 피벗 테이블 자동 스타일링에 맡김)
        val grandTotalRowNum = startRow + 1 + uniqueValues.size
        val grandTotalRow = pivotSheet.getRow(grandTotalRowNum) ?: pivotSheet.createRow(grandTotalRowNum)

        val grandTotalLabelCell = grandTotalRow.getCell(startCol) ?: grandTotalRow.createCell(startCol)
        grandTotalLabelCell.setCellValue(grandTotalCaption ?: "전체")
        axisAlignmentStyle?.let { grandTotalLabelCell.cellStyle = it }

        dataFields.forEachIndexed { dataIdx, dataField ->
            val grandTotalCell = grandTotalRow.getCell(startCol + 1 + dataIdx) ?: grandTotalRow.createCell(startCol + 1 + dataIdx)
            grandTotalCell.setCellValue(aggregateForField(dataRows, dataField))
            // 숫자 서식만 적용 (배경색 없음)
            grandTotalCell.cellStyle = getNumberFormatOnlyStyle(workbook, dataField.function)
        }

        logger.debug("피벗 테이블 셀 값 채우기 완료: ${uniqueValues.size}개 항목")
    }

    /**
     * StyleInfo에서 XSSFCellStyle을 생성하거나 캐시에서 반환합니다.
     */
    private fun getOrCreateStyleFromInfo(
        workbook: XSSFWorkbook,
        styleInfo: StyleInfo,
        cache: MutableMap<StyleInfo, XSSFCellStyle>
    ): XSSFCellStyle {
        return cache.getOrPut(styleInfo) {
            createStyleFromInfo(workbook, styleInfo)
        }
    }

    /**
     * StyleInfo에서 숫자 서식이 적용된 XSSFCellStyle을 생성합니다.
     */
    private fun getOrCreateStyleFromInfoWithNumberFormat(
        workbook: XSSFWorkbook,
        styleInfo: StyleInfo?,
        function: DataConsolidateFunction,
        cache: MutableMap<StyleInfo, XSSFCellStyle>
    ): XSSFCellStyle {
        val isInteger = function in listOf(DataConsolidateFunction.SUM, DataConsolidateFunction.COUNT, DataConsolidateFunction.COUNT_NUMS)
        val numberFormat = if (isInteger) config.pivotIntegerFormatIndex else config.pivotDecimalFormatIndex

        // styleInfo가 있고 이미 숫자 서식이 있으면 그대로 사용
        if (styleInfo != null && styleInfo.dataFormat.toInt() != 0) {
            return getOrCreateStyleFromInfo(workbook, styleInfo, cache)
        }

        // styleInfo가 없거나 숫자 서식이 없으면 숫자 서식 추가
        val effectiveStyleInfo = (styleInfo ?: StyleInfo()).copy(dataFormat = numberFormat)
        return cache.getOrPut(effectiveStyleInfo) {
            createStyleFromInfo(workbook, effectiveStyleInfo)
        }
    }

    /**
     * StyleInfo에서 XSSFCellStyle을 생성합니다.
     */
    private fun createStyleFromInfo(workbook: XSSFWorkbook, styleInfo: StyleInfo): XSSFCellStyle {
        return workbook.createCellStyle().apply {
            // 폰트
            val font = (workbook.createFont() as org.apache.poi.xssf.usermodel.XSSFFont).apply {
                bold = styleInfo.fontBold
                italic = styleInfo.fontItalic
                underline = styleInfo.fontUnderline
                strikeout = styleInfo.fontStrikeout
                styleInfo.fontName?.let { fontName = it }
                fontHeightInPoints = styleInfo.fontSize
                // 글꼴 색상 적용
                styleInfo.fontColorRgb?.let { colorHex ->
                    try {
                        setColor(org.apache.poi.xssf.usermodel.XSSFColor(hexToByteArray(colorHex), null))
                    } catch (e: Exception) {
                        logger.debug("글꼴 색상 설정 실패: ${e.message}")
                    }
                }
            }
            setFont(font)

            // 채우기
            fillPattern = styleInfo.fillPatternType
            if (styleInfo.fillPatternType != org.apache.poi.ss.usermodel.FillPatternType.NO_FILL && styleInfo.fillForegroundColorRgb != null) {
                try {
                    val color = org.apache.poi.xssf.usermodel.XSSFColor(
                        hexToByteArray(styleInfo.fillForegroundColorRgb),
                        null
                    )
                    setFillForegroundColor(color)
                } catch (e: Exception) {
                    logger.debug("채우기 색상 설정 실패: ${e.message}")
                }
            }

            // 테두리
            borderTop = styleInfo.borderTop
            borderBottom = styleInfo.borderBottom
            borderLeft = styleInfo.borderLeft
            borderRight = styleInfo.borderRight

            // 정렬
            alignment = styleInfo.horizontalAlignment
            verticalAlignment = styleInfo.verticalAlignment

            // 숫자 서식
            if (styleInfo.dataFormat.toInt() != 0) {
                dataFormat = styleInfo.dataFormat
            }
        }
    }

    /**
     * ARGB hex 문자열을 byte 배열로 변환합니다.
     */
    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("#").removePrefix("FF")  // ARGB에서 RGB로
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * 데이터 행들에 대해 집계 함수를 적용합니다.
     * COUNT 함수는 모든 non-null 값을 카운트하고,
     * 나머지 함수(SUM, AVERAGE, MAX, MIN 등)는 숫자 값만 사용합니다.
     */
    private fun aggregateForField(rows: List<DataRow>, dataField: DataFieldInfo): Double {
        return when (dataField.function) {
            DataConsolidateFunction.COUNT -> {
                // COUNT: 모든 non-null 값 카운트 (문자열 포함)
                rows.count { it.values[dataField.fieldIndex] != null }.toDouble()
            }
            DataConsolidateFunction.COUNT_NUMS -> {
                // COUNT_NUMS: 숫자 값만 카운트
                rows.count { it.values[dataField.fieldIndex] is Number }.toDouble()
            }
            else -> {
                // SUM, AVERAGE, MAX, MIN 등: 숫자 값에 대해 집계
                val numericValues = rows.mapNotNull { (it.values[dataField.fieldIndex] as? Number)?.toDouble() }
                aggregate(numericValues, dataField.function)
            }
        }
    }

    private fun aggregate(values: List<Double>, function: DataConsolidateFunction): Double = when (function) {
        DataConsolidateFunction.SUM -> values.sum()
        DataConsolidateFunction.AVERAGE -> if (values.isNotEmpty()) values.average() else 0.0
        DataConsolidateFunction.COUNT, DataConsolidateFunction.COUNT_NUMS -> values.size.toDouble()
        DataConsolidateFunction.MAX -> values.maxOrNull() ?: 0.0
        DataConsolidateFunction.MIN -> values.minOrNull() ?: 0.0
        else -> values.sum()
    }

    /**
     * 정렬만 있는 스타일을 생성합니다 (배경색 없음).
     */
    private fun getOrCreateAlignmentOnlyStyle(workbook: XSSFWorkbook, styleInfo: StyleInfo): XSSFCellStyle {
        val cacheKey = "alignOnly_${styleInfo.horizontalAlignment}_${styleInfo.verticalAlignment}"
        val workbookCache = numberStyleCache.getOrPut(workbook) { mutableMapOf() }

        return workbookCache.getOrPut(cacheKey) {
            workbook.createCellStyle().apply {
                alignment = styleInfo.horizontalAlignment
                verticalAlignment = styleInfo.verticalAlignment
            }
        }
    }

    /**
     * 숫자 서식만 있는 스타일을 생성합니다 (배경색 없음).
     */
    private fun getNumberFormatOnlyStyle(workbook: XSSFWorkbook, function: DataConsolidateFunction): XSSFCellStyle {
        val isInteger = function in listOf(DataConsolidateFunction.SUM, DataConsolidateFunction.COUNT, DataConsolidateFunction.COUNT_NUMS)
        val formatIndex = if (isInteger) config.pivotIntegerFormatIndex else config.pivotDecimalFormatIndex

        val cacheKey = "numFmtOnly_${formatIndex}"
        val workbookCache = numberStyleCache.getOrPut(workbook) { mutableMapOf() }

        return workbookCache.getOrPut(cacheKey) {
            workbook.createCellStyle().apply {
                dataFormat = formatIndex
            }
        }
    }

    private fun getCellValue(cell: org.apache.poi.ss.usermodel.Cell?): Any? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.takeIf { it.isNotBlank() }
            CellType.NUMERIC -> cell.numericCellValue
            CellType.BOOLEAN -> cell.booleanCellValue
            CellType.FORMULA -> try { cell.numericCellValue } catch (e: Exception) { cell.stringCellValue.takeIf { it.isNotBlank() } }
            else -> null
        }
    }

    private fun getCellStringValue(cell: org.apache.poi.ss.usermodel.Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.takeIf { it.isNotBlank() }
            CellType.NUMERIC -> cell.numericCellValue.toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            else -> null
        }
    }

    // ========== ZIP 처리 ==========

    private fun removePivotReferencesFromZip(inputBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
            java.util.zip.ZipOutputStream(output).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    if (entryName.contains("pivotCache") || entryName.contains("pivotTables")) {
                        logger.debug("피벗 파트 제거: $entryName")
                        entry = zis.nextEntry
                        continue
                    }

                    var content = zis.readBytes()

                    when {
                        entryName == "[Content_Types].xml" -> {
                            val xml = String(content, Charsets.UTF_8)
                            content = xml
                                .replace(Regex("""<Override[^>]*pivotCache[^>]*/>\s*"""), "")
                                .replace(Regex("""<Override[^>]*pivotTable[^>]*/>\s*"""), "")
                                .toByteArray(Charsets.UTF_8)
                            logger.debug("Content_Types에서 피벗 참조 제거")
                        }
                        entryName.contains("worksheets/_rels/") && entryName.endsWith(".rels") -> {
                            val xml = String(content, Charsets.UTF_8)
                            if (xml.contains("pivotTable")) {
                                val cleanedXml = xml.replace(Regex("""<Relationship[^>]*pivotTable[^>]*/>\s*"""), "")
                                content = (if (!cleanedXml.contains("<Relationship")) {
                                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
                                } else cleanedXml).toByteArray(Charsets.UTF_8)
                                logger.debug("워크시트 관계에서 피벗 참조 제거: $entryName")
                            }
                        }
                        entryName == "xl/workbook.xml" -> {
                            val xml = String(content, Charsets.UTF_8)
                            if (xml.contains("<pivotCaches")) {
                                content = xml
                                    .replace(Regex("""<pivotCaches>.*?</pivotCaches>""", RegexOption.DOT_MATCHES_ALL), "")
                                    .replace(Regex("""<pivotCaches/>"""), "")
                                    .toByteArray(Charsets.UTF_8)
                                logger.debug("워크북에서 pivotCaches 제거")
                            }
                        }
                        entryName == "xl/_rels/workbook.xml.rels" -> {
                            val xml = String(content, Charsets.UTF_8)
                            if (xml.contains("pivotCache")) {
                                content = xml.replace(Regex("""<Relationship[^>]*pivotCache[^>]*/>\s*"""), "")
                                    .toByteArray(Charsets.UTF_8)
                                logger.debug("워크북 관계에서 피벗 캐시 참조 제거")
                            }
                        }
                    }

                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    zos.write(content)
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    // ========== 피벗 캐시 빌더 ==========

    private fun populatePivotCache(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) return inputBytes

        val axisFieldIndices = pivotTableInfos.flatMap { it.rowLabelFields }.toSet()

        data class SourceData(
            val records: List<List<Any?>>,
            val fields: List<Map<String, Any?>>
        )

        val sourceDataMap = mutableMapOf<String, SourceData>()

        XSSFWorkbook(ByteArrayInputStream(inputBytes)).use { workbook ->
            org.apache.poi.openxml4j.opc.OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
                pkg.parts.filter { it.partName.name.contains("/pivotCache/pivotCacheDefinition") }.forEach { part ->
                    val xml = part.inputStream.bufferedReader().readText()
                    val sheetMatch = Regex("""sheet="([^"]+)"""").find(xml)
                    val refMatch = Regex("""<worksheetSource[^>]*ref="([^"]+)"""").find(xml)

                    if (sheetMatch != null && refMatch != null) {
                        val sheetName = sheetMatch.groupValues[1]
                        val ref = refMatch.groupValues[1]
                        val sheet = workbook.getSheet(sheetName) ?: return@forEach

                        val range = CellRangeAddress.valueOf(ref)
                        val records = mutableListOf<List<Any?>>()
                        val fieldNames = mutableListOf<String>()

                        val headerRow = sheet.getRow(range.firstRow)
                        for (colIdx in range.firstColumn..range.lastColumn) {
                            fieldNames.add(getCellStringValue(headerRow?.getCell(colIdx)) ?: "Field$colIdx")
                        }

                        for (rowIdx in (range.firstRow + 1)..range.lastRow) {
                            val row = sheet.getRow(rowIdx) ?: continue
                            val cellValues = mutableListOf<Any?>()
                            var hasData = false
                            for (colIdx in range.firstColumn..range.lastColumn) {
                                val value = getCellValue(row.getCell(colIdx))
                                cellValues.add(value)
                                if (value != null) hasData = true
                            }
                            if (hasData) records.add(cellValues)
                        }

                        val fields = fieldNames.mapIndexed { idx, name ->
                            val isAxisField = axisFieldIndices.contains(idx)
                            val columnValues = records.mapNotNull { it.getOrNull(idx) }
                            val numericValues = columnValues.filterIsInstance<Number>().map { it.toDouble() }
                            val isNumeric = columnValues.isNotEmpty() && columnValues.all { it is Number }
                            val sharedItems = if (isAxisField) columnValues.map { it.toString() }.distinct() else emptyList()

                            mapOf(
                                "name" to name,
                                "isAxisField" to isAxisField,
                                "isNumeric" to isNumeric,
                                "sharedItems" to sharedItems,
                                "minValue" to (if (isNumeric) numericValues.minOrNull() else null),
                                "maxValue" to (if (isNumeric) numericValues.maxOrNull() else null),
                                "isInteger" to numericValues.all { it == it.toLong().toDouble() }
                            )
                        }

                        sourceDataMap[part.partName.name] = SourceData(records, fields)
                        logger.debug("피벗 소스 데이터 추출: ${part.partName.name} - ${records.size}개 레코드")
                    }
                }
            }
        }

        if (sourceDataMap.isEmpty()) return inputBytes

        val output = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
            java.util.zip.ZipOutputStream(output).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    var content = zis.readBytes()

                    when {
                        entryName.contains("/pivotCache/pivotCacheDefinition") -> {
                            sourceDataMap["/$entryName"]?.let { data ->
                                content = buildPivotCacheDefinition(String(content, Charsets.UTF_8), data.records.size, data.fields)
                                    .toByteArray(Charsets.UTF_8)
                            }
                        }
                        entryName.contains("/pivotCache/pivotCacheRecords") -> {
                            val defPath = "/$entryName".replace("pivotCacheRecords", "pivotCacheDefinition")
                            sourceDataMap[defPath]?.let { data ->
                                content = buildPivotCacheRecords(data.records, data.fields).toByteArray(Charsets.UTF_8)
                            }
                        }
                        entryName.contains("/pivotTables/pivotTable") -> {
                            sourceDataMap.values.firstOrNull()?.let { data ->
                                content = buildPivotTableDefinition(String(content, Charsets.UTF_8), data.fields)
                                    .toByteArray(Charsets.UTF_8)
                            }
                        }
                    }

                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    zos.write(content)
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        return output.toByteArray()
    }

    private fun buildPivotCacheDefinition(originalXml: String, recordCount: Int, fields: List<Map<String, Any?>>): String {
        var xml = originalXml
            .replace(Regex("""refreshOnLoad="(true|1)""""), """refreshOnLoad="0"""")
            .replace(Regex("""refreshedVersion="\d+""""), """refreshedVersion="8"""")

        xml = if (xml.contains("recordCount=")) {
            xml.replace(Regex("""recordCount="\d+""""), """recordCount="$recordCount"""")
        } else {
            xml.replace("<pivotCacheDefinition ", """<pivotCacheDefinition recordCount="$recordCount" """)
        }

        @Suppress("UNCHECKED_CAST")
        for (field in fields) {
            val name = field["name"] as? String ?: continue
            val isAxisField = field["isAxisField"] as? Boolean ?: false
            val isNumeric = field["isNumeric"] as? Boolean ?: false
            val sharedItems = field["sharedItems"] as? List<*> ?: emptyList<String>()
            val minValue = field["minValue"] as? Number
            val maxValue = field["maxValue"] as? Number
            val isInteger = field["isInteger"] as? Boolean ?: false

            val sharedItemsXml = when {
                isAxisField && sharedItems.isNotEmpty() -> {
                    val items = sharedItems.joinToString("") { """<s v="${it.toString().escapeXml()}"/>""" }
                    """<sharedItems count="${sharedItems.size}">$items</sharedItems>"""
                }
                isNumeric -> {
                    val containsInteger = if (isInteger) """containsInteger="1" """ else ""
                    """<sharedItems containsSemiMixedTypes="0" containsString="0" containsNumber="1" ${containsInteger}minValue="$minValue" maxValue="$maxValue"/>"""
                }
                else -> "<sharedItems/>"
            }

            val cacheFieldPattern = """<cacheField[^>]*name="$name"[^>]*>.*?</cacheField>"""
            xml = xml.replace(Regex(cacheFieldPattern, RegexOption.DOT_MATCHES_ALL)) { match ->
                match.value.replace(Regex("""<sharedItems[^>]*/>|<sharedItems[^>]*>.*?</sharedItems>""", RegexOption.DOT_MATCHES_ALL), sharedItemsXml)
            }
        }

        return xml
    }

    private fun buildPivotCacheRecords(records: List<List<Any?>>, fields: List<Map<String, Any?>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<pivotCacheRecords xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${records.size}">""")

        for (record in records) {
            sb.append("<r>")
            for ((idx, value) in record.withIndex()) {
                val field = fields.getOrNull(idx)
                val isAxisField = field?.get("isAxisField") as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val sharedItems = field?.get("sharedItems") as? List<String> ?: emptyList()

                when {
                    value == null -> sb.append("<m/>")
                    isAxisField -> {
                        val sharedIndex = sharedItems.indexOf(value.toString()).takeIf { it >= 0 } ?: 0
                        sb.append("""<x v="$sharedIndex"/>""")
                    }
                    value is Number -> sb.append("""<n v="${value}"/>""")
                    value is Boolean -> sb.append("""<b v="${if (value) "1" else "0"}"/>""")
                    else -> sb.append("""<s v="${value.toString().escapeXml()}"/>""")
                }
            }
            sb.append("</r>")
        }

        sb.append("</pivotCacheRecords>")
        return sb.toString()
    }

    private fun buildPivotTableDefinition(originalXml: String, fields: List<Map<String, Any?>>): String {
        var xml = originalXml
            .replace(Regex(""""(true|false)"""")) { if (it.groupValues[1] == "true") """"1"""" else """"0"""" }
            .replace(Regex("""updatedVersion="\d+""""), """updatedVersion="8"""")

        // location ref 업데이트
        val locationMatch = Regex("""<location[^>]*ref="([^"]+)"[^>]*/?>""").find(xml)
        if (locationMatch != null) {
            val refValue = locationMatch.groupValues[1]
            val parts = refValue.split(":")
            if (parts.size == 2) {
                val startCell = parts[0]
                val startCol = startCell.replace(Regex("""\d+"""), "")
                val startRowNum = startCell.replace(Regex("""[A-Z]+"""), "").toIntOrNull() ?: 1

                val axisFieldsCount = fields.count { it["isAxisField"] == true }
                val uniqueValuesCount = fields.filter { it["isAxisField"] == true }
                    .flatMap { (it["sharedItems"] as? List<*>)?.map { s -> s.toString() } ?: emptyList() }
                    .distinct().size

                val dataFieldsCount = xml.split("<dataField").size - 1
                val newEndCol = (startCol.toColumnIndex() + dataFieldsCount).toColumnLetter()
                val newEndRow = startRowNum + uniqueValuesCount + 1

                xml = xml.replace("""ref="$refValue"""", """ref="$startCell:$newEndCol$newEndRow"""")
            }
        }

        // pivotField items 수정
        val axisFields = fields.mapIndexedNotNull { idx, f -> if (f["isAxisField"] == true) idx else null }.toSet()

        @Suppress("UNCHECKED_CAST")
        xml = Regex("""<pivotField[^>]*>(.*?)</pivotField>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { matchResult ->
            val pivotFieldContent = matchResult.groupValues[1]
            val axisAttr = Regex("""axis="([^"]+)"""").find(matchResult.value)

            if (axisAttr != null) {
                val axisFieldIdx = axisFields.firstOrNull() ?: return@replace matchResult.value
                val sharedItems = fields.getOrNull(axisFieldIdx)?.get("sharedItems") as? List<*> ?: return@replace matchResult.value

                val itemsXml = buildString {
                    append("""<items count="${sharedItems.size + 1}">""")
                    for (i in sharedItems.indices) append("""<item x="$i"/>""")
                    append("""<item t="default"/>""")
                    append("</items>")
                }

                matchResult.value.replace(Regex("""<items[^>]*>.*?</items>""", RegexOption.DOT_MATCHES_ALL), itemsXml)
            } else {
                matchResult.value
            }
        }

        // rowItems 추가
        if (!xml.contains("<rowItems") && axisFields.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val firstAxisField = fields.getOrNull(axisFields.first())
            val sharedItems = firstAxisField?.get("sharedItems") as? List<*> ?: emptyList<String>()

            if (sharedItems.isNotEmpty()) {
                val rowItemsXml = buildString {
                    append("""<rowItems count="${sharedItems.size + 1}">""")
                    append("<i><x/></i>")
                    for (i in 1 until sharedItems.size) append("""<i><x v="$i"/></i>""")
                    append("""<i t="grand"><x/></i>""")
                    append("</rowItems>")
                }

                xml = if (xml.contains("</rowFields>")) {
                    xml.replace("</rowFields>", "</rowFields>$rowItemsXml")
                } else {
                    xml.replace("</pivotFields>", "</pivotFields>$rowItemsXml")
                }
            }
        }

        // colItems 추가
        val dataFieldsCount = xml.split("<dataField").size - 1
        if (!xml.contains("<colItems") && dataFieldsCount > 0) {
            val colItemsXml = if (dataFieldsCount > 1) {
                buildString {
                    append("""<colItems count="$dataFieldsCount">""")
                    for (i in 0 until dataFieldsCount) {
                        if (i == 0) append("<i><x/></i>") else append("""<i><x v="$i"/></i>""")
                    }
                    append("</colItems>")
                }
            } else {
                """<colItems count="1"><i/></colItems>"""
            }

            xml = when {
                xml.contains("</colFields>") -> xml.replace("</colFields>", "</colFields>$colItemsXml")
                xml.contains("</rowItems>") -> xml.replace("</rowItems>", "</rowItems>$colItemsXml")
                else -> xml.replace("</rowFields>", "</rowFields>$colItemsXml")
            }
        }

        // dataField에 baseField/baseItem 추가
        xml = Regex("""<dataField([^>]*)/>""").replace(xml) { match ->
            val attrs = match.groupValues[1]
            if (!attrs.contains("baseField")) {
                """<dataField$attrs baseField="0" baseItem="0"/>"""
            } else match.value
        }

        // colFields 추가 (다중 데이터 필드용)
        if (dataFieldsCount > 1 && !xml.contains("<colFields")) {
            val colFieldsXml = """<colFields count="1"><field x="-2"/></colFields>"""
            xml = xml.replace("</rowItems>", "</rowItems>$colFieldsXml")
        }

        // firstHeaderRow="0" 항상 적용 (피벗 테이블 스타일링 올바르게 적용되도록)
        if (!xml.contains("""firstHeaderRow="0"""")) {
            xml = xml.replace(Regex("""firstHeaderRow="\d+""""), """firstHeaderRow="0"""")
        }

        return xml
    }

    private fun XSSFWorkbook.toByteArray(): ByteArray =
        ByteArrayOutputStream().also { write(it) }.toByteArray()
}
