package com.hunet.common.excel

import com.hunet.common.excel.async.DefaultGenerationJob
import com.hunet.common.excel.async.ExcelGenerationListener
import com.hunet.common.excel.async.GenerationJob
import com.hunet.common.excel.async.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.jxls.common.Context
import org.jxls.util.JxlsHelper
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors

/**
 * 템플릿 기반 Excel 생성기.
 *
 * JXLS 템플릿 엔진을 사용하여 .xlsx 템플릿에 데이터를 바인딩하고 Excel 파일을 생성합니다.
 *
 * ## 기본 사용법
 * ```kotlin
 * val generator = ExcelGenerator()
 * val data = mapOf("title" to "보고서", "items" to listOf(item1, item2))
 * val bytes = generator.generate(templateStream, data)
 * ```
 *
 * ## DataProvider 사용 (지연 로딩)
 * ```kotlin
 * val provider = simpleDataProvider {
 *     value("title", "보고서")
 *     items("employees") { repository.streamAll().iterator() }
 * }
 * val result = generator.generateToFile(template, provider, outputDir, "report")
 * ```
 *
 * ## 비동기 실행 (API 서버용)
 * ```kotlin
 * val job = generator.submit(template, provider, outputDir, "report",
 *     listener = object : ExcelGenerationListener {
 *         override fun onCompleted(jobId: String, result: GenerationResult) {
 *             eventPublisher.publish(ReportReadyEvent(jobId, result.filePath))
 *         }
 *     }
 * )
 * return ResponseEntity.accepted().body(job.jobId)
 * ```
 *
 * @param config 생성기 설정
 */
class ExcelGenerator @JvmOverloads constructor(
    private val config: ExcelGeneratorConfig = ExcelGeneratorConfig()
) : Closeable {
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val templatePreprocessor = TemplatePreprocessor()

    // ========== 동기 API ==========

    /**
     * 템플릿과 데이터 맵으로 Excel을 생성합니다.
     */
    fun generate(template: InputStream, data: Map<String, Any>): ByteArray =
        generate(template, SimpleDataProvider.of(data))

    /**
     * 템플릿과 DataProvider로 Excel을 생성합니다.
     */
    fun generate(template: InputStream, dataProvider: ExcelDataProvider): ByteArray =
        ByteArrayOutputStream().use { output ->
            processTemplate(template, dataProvider, output)
            output.toByteArray()
        }

    /**
     * 템플릿 파일과 DataProvider로 Excel을 생성합니다.
     */
    fun generate(template: File, dataProvider: ExcelDataProvider): ByteArray =
        template.inputStream().use { generate(it, dataProvider) }

    /**
     * Excel을 생성하여 파일로 저장합니다.
     *
     * 파일명에 타임스탬프가 자동으로 추가됩니다.
     * 예: "report" → "report_20240106_143052.xlsx"
     */
    fun generateToFile(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String
    ): Path = generateToFileInternal(template, dataProvider, outputDir, baseFileName).first

    /**
     * 내부용: 파일 생성 후 경로와 처리된 행 수를 함께 반환합니다.
     * 예외 발생 시 생성된 파일을 삭제합니다.
     */
    private fun generateToFileInternal(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String
    ): Pair<Path, Int> {
        val outputPath = outputDir.resolve(generateFileName(baseFileName))
        Files.createDirectories(outputDir)

        return runCatching {
            val rowsProcessed = Files.newOutputStream(outputPath).use { output ->
                processTemplate(template, dataProvider, output)
            }
            outputPath to rowsProcessed
        }.onFailure {
            runCatching { Files.deleteIfExists(outputPath) }
                .onFailure { deleteError -> it.addSuppressed(deleteError) }
        }.getOrThrow()
    }

    // ========== 비동기 API (Kotlin Coroutines) ==========

    /**
     * 비동기로 Excel을 생성합니다.
     */
    suspend fun generateAsync(template: InputStream, dataProvider: ExcelDataProvider): ByteArray =
        withContext(dispatcher) { generate(template, dataProvider) }

    /**
     * 비동기로 Excel을 생성하여 파일로 저장합니다.
     */
    suspend fun generateToFileAsync(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String
    ): Path = withContext(dispatcher) {
        generateToFile(template, dataProvider, outputDir, baseFileName)
    }

    // ========== 비동기 API (Java CompletableFuture) ==========

    /**
     * CompletableFuture로 Excel을 생성합니다.
     */
    fun generateFuture(template: InputStream, dataProvider: ExcelDataProvider) =
        scope.future { generate(template, dataProvider) }

    /**
     * CompletableFuture로 Excel을 생성하여 파일로 저장합니다.
     */
    fun generateToFileFuture(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String
    ) = scope.future { generateToFile(template, dataProvider, outputDir, baseFileName) }

    // ========== 비동기 API (작업 관리) ==========

    /**
     * 비동기 작업을 제출하고 작업 핸들을 반환합니다.
     * API 서버에서 즉시 응답 후 백그라운드 처리에 적합합니다.
     */
    @JvmOverloads
    fun submit(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        listener: ExcelGenerationListener? = null
    ): GenerationJob {
        val jobId = UUID.randomUUID().toString()
        val job = DefaultGenerationJob(jobId)
        val startTime = System.currentTimeMillis()

        listener?.onStarted(jobId)

        scope.launch {
            runCatching {
                if (job.checkCancelled()) {
                    listener?.onCancelled(jobId)
                    return@launch
                }

                val (filePath, rowsProcessed) = generateToFileInternal(
                    template, dataProvider, outputDir, baseFileName
                )

                GenerationResult(
                    jobId = jobId,
                    filePath = filePath,
                    rowsProcessed = rowsProcessed,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }.onSuccess { result ->
                job.complete(result)
                listener?.onCompleted(jobId, result)
            }.onFailure { error ->
                job.completeExceptionally(error as Exception)
                listener?.onFailed(jobId, error)
            }
        }

        return job
    }

    // ========== 내부 데이터 클래스 ==========

    private data class SheetLayout(
        val columnWidths: Map<Int, Int>,
        val rowHeights: Map<Int, Short>
    )

    private data class WorkbookLayout(
        val sheetLayouts: Map<Int, SheetLayout>
    )

    private data class DataValidationInfo(
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

    private data class SheetDataValidations(
        val validations: List<DataValidationInfo>,
        val lastDataRow: Int
    )

    private data class WorkbookDataValidations(
        val sheetValidations: Map<Int, SheetDataValidations>
    )

    private data class ContextResult(
        val context: Context,
        val rowsProcessed: Int
    )

    // ========== 핵심 처리 로직 ==========

    private fun processTemplate(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        output: OutputStream
    ): Int {
        val (context, rowsProcessed) = createContext(dataProvider)
        val templateBytes = templatePreprocessor.preprocess(template).readBytes()

        // 전처리된 템플릿으로 워크북 처리
        val processedBytes = XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            workbook.sheets.forEach { sheet ->
                preprocessSheet(workbook, sheet, dataProvider)
            }
            workbook.toByteArray()
        }

        // 레이아웃 및 데이터 유효성 검사 백업
        val layout = if (config.preserveTemplateLayout) {
            backupLayout(ByteArrayInputStream(processedBytes))
        } else null
        val dataValidations = backupDataValidations(ByteArrayInputStream(processedBytes))

        // JXLS 처리
        val jxlsOutput = ByteArrayOutputStream().also { tempOutput ->
            ByteArrayInputStream(processedBytes).use { input ->
                processJxlsWithFormulaErrorDetection(input, tempOutput, context)
            }
        }

        // 후처리 (레이아웃 복원, 데이터 유효성 검사 확장, 피벗 테이블 새로고침 설정)
        val resultBytes = jxlsOutput.toByteArray()
            .let { bytes -> layout?.let { restoreLayout(bytes, it) } ?: bytes }
            .let { bytes -> expandDataValidations(bytes, dataValidations) }
            .let { bytes -> enablePivotTableRefreshOnLoad(bytes) }

        output.write(resultBytes)
        return rowsProcessed
    }

    // ========== 레이아웃 처리 ==========

    private fun backupLayout(template: InputStream) =
        XSSFWorkbook(template).use { workbook ->
            WorkbookLayout(
                workbook.sheets.mapIndexed { index, sheet ->
                    val lastColumn = sheet.lastColumnWithData
                    val lastRow = sheet.lastRowWithData

                    val columnWidths = (0..lastColumn + 10).associateWith { sheet.getColumnWidth(it) }
                    val rowHeights = (0..lastRow + 10).associateWith { rowIndex ->
                        sheet.getRow(rowIndex)?.height ?: sheet.defaultRowHeight
                    }

                    index to SheetLayout(columnWidths, rowHeights)
                }.toMap()
            )
        }

    private fun restoreLayout(outputBytes: ByteArray, layout: WorkbookLayout) =
        XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
            layout.sheetLayouts
                .filter { (index, _) -> index < workbook.numberOfSheets }
                .forEach { (index, sheetLayout) ->
                    val sheet = workbook.getSheetAt(index)
                    sheetLayout.columnWidths.forEach { (col, width) -> sheet.setColumnWidth(col, width) }
                    sheetLayout.rowHeights.forEach { (row, height) ->
                        sheet.getRow(row)?.height = height
                    }
                }
            workbook.toByteArray()
        }

    // ========== 데이터 유효성 검사 처리 ==========

    private fun backupDataValidations(template: InputStream) =
        XSSFWorkbook(template).use { workbook ->
            WorkbookDataValidations(
                workbook.sheets
                    .mapIndexedNotNull { index, sheet ->
                        (sheet as? XSSFSheet)?.let { xssfSheet ->
                            val validations = xssfSheet.dataValidations.orEmpty().mapNotNull { dv ->
                                val regions = dv.regions?.cellRangeAddresses?.toList().orEmpty()
                                regions.takeIf { it.isNotEmpty() }?.let {
                                    val constraint = dv.validationConstraint
                                    DataValidationInfo(
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
                            index to SheetDataValidations(validations, xssfSheet.lastRowWithData)
                        }
                    }.toMap()
            )
        }

    private fun expandDataValidations(outputBytes: ByteArray, backup: WorkbookDataValidations) =
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

    // ========== 피벗 테이블 처리 ==========

    private val logger = org.slf4j.LoggerFactory.getLogger(ExcelGenerator::class.java)

    /**
     * 피벗 캐시 정보를 담는 데이터 클래스
     */
    private data class PivotCacheInfo(
        val sheetName: String,
        val range: CellRangeAddress,
        val fieldNames: List<String>,
        val cacheDefPartName: String,
        val cacheRecordsPartName: String?
    )

    /**
     * 피벗 테이블의 데이터 소스 범위를 확장하고 캐시를 초기화합니다.
     * refreshOnLoad를 설정하여 파일을 열 때 Excel이 캐시를 재구성하도록 합니다.
     *
     * 이 방법은 캐시와 피벗 테이블의 아이템 참조를 모두 최소화하여
     * Excel이 새로고침 시 전체를 재구성하도록 합니다.
     */
    private fun enablePivotTableRefreshOnLoad(outputBytes: ByteArray): ByteArray {
        val tempFile = java.io.File.createTempFile("excel_pivot_", ".xlsx")
        try {
            tempFile.writeBytes(outputBytes)

            // 시트별 데이터 범위 계산 (특정 컬럼 범위 내에서)
            data class SheetDataInfo(
                val lastRow: Int,
                val dataRangeLastRows: MutableMap<String, Int> // "A:C" -> lastRow with all columns filled
            )

            val sheetDataInfo = mutableMapOf<String, SheetDataInfo>()
            XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
                workbook.sheets.forEach { sheet ->
                    val lastRow = sheet.lastRowWithData
                    sheetDataInfo[sheet.sheetName] = SheetDataInfo(lastRow, mutableMapOf())
                    logger.debug("시트 '${sheet.sheetName}' 데이터 범위: lastRow=$lastRow")
                }
            }

            // 피벗 캐시 정보 수집 및 실제 데이터로 재구성
            data class PivotCacheData(
                val sheetName: String,
                val originalRange: CellRangeAddress,
                val newRange: CellRangeAddress,
                val fieldData: List<List<Any?>> // 각 필드별 데이터 (헤더 제외)
            )

            val pivotCacheDataMap = mutableMapOf<String, PivotCacheData>() // partName -> data

            org.apache.poi.openxml4j.opc.OPCPackage.open(tempFile, org.apache.poi.openxml4j.opc.PackageAccess.READ_WRITE).use { pkg ->
                val pivotCacheDefPattern = Regex("/xl/pivotCache/pivotCacheDefinition(\\d+)\\.xml")
                val pivotCacheRecordsPattern = Regex("/xl/pivotCache/pivotCacheRecords(\\d+)\\.xml")
                val worksheetSourcePattern = Regex("""<worksheetSource\s+ref="([^"]+)"\s+sheet="([^"]+)"""")

                // 먼저 피벗 캐시의 소스 범위와 실제 데이터 수집
                pkg.parts.toList().forEach { part ->
                    val partName = part.partName.name
                    if (!pivotCacheDefPattern.matches(partName)) return@forEach

                    val xmlContent = part.inputStream.bufferedReader().readText()
                    val sourceMatch = worksheetSourcePattern.find(xmlContent) ?: return@forEach
                    val currentRef = sourceMatch.groupValues[1]
                    val sheetName = sourceMatch.groupValues[2]
                    val originalRange = runCatching { CellRangeAddress.valueOf(currentRef) }.getOrNull() ?: return@forEach

                    // 워크북에서 데이터 읽기
                    XSSFWorkbook(ByteArrayInputStream(outputBytes)).use { workbook ->
                        val sheet = workbook.getSheet(sheetName) ?: return@forEach
                        val dataLastRow = findLastRowWithAllColumns(sheet, originalRange)

                        val newRange = CellRangeAddress(
                            originalRange.firstRow,
                            dataLastRow,
                            originalRange.firstColumn,
                            originalRange.lastColumn
                        )

                        // 각 필드별 데이터 수집 (헤더 행 제외)
                        val fieldCount = originalRange.lastColumn - originalRange.firstColumn + 1
                        val fieldData = List(fieldCount) { mutableListOf<Any?>() }

                        for (rowNum in originalRange.firstRow + 1..dataLastRow) {
                            val row = sheet.getRow(rowNum) ?: continue
                            for (colIdx in 0 until fieldCount) {
                                val cell = row.getCell(originalRange.firstColumn + colIdx)
                                fieldData[colIdx].add(getCellValueAsAny(cell))
                            }
                        }

                        pivotCacheDataMap[partName] = PivotCacheData(sheetName, originalRange, newRange, fieldData)
                        logger.debug("피벗 소스 '$sheetName' 범위 ${originalRange.formatAsString()}: 데이터 마지막 행=$dataLastRow, 레코드 수=${fieldData[0].size}")
                    }
                }

                // 각 필드별 유니크 값 목록 계산
                data class CacheFieldInfo(
                    val cacheNum: String,
                    val uniqueValuesPerField: List<List<String>>
                )
                val cacheFieldInfoMap = mutableMapOf<String, CacheFieldInfo>()

                // 피벗 캐시 정의 및 레코드 수정
                pkg.parts.toList().forEach { part ->
                    val partName = part.partName.name
                    val cacheNumMatch = pivotCacheDefPattern.find(partName)

                    when {
                        cacheNumMatch != null -> {
                            val cacheData = pivotCacheDataMap[partName] ?: return@forEach
                            val cacheNum = cacheNumMatch.groupValues[1]

                            // 각 필드별 유니크 값 계산
                            val uniqueValuesPerField = cacheData.fieldData.map { field ->
                                field.mapNotNull { it?.toString() }.distinct()
                            }
                            cacheFieldInfoMap[cacheNum] = CacheFieldInfo(cacheNum, uniqueValuesPerField)

                            // 캐시 정의 수정 (sharedItems 채움)
                            val xmlContent = part.inputStream.bufferedReader().readText()
                            val modifiedXml = rebuildPivotCacheWithData(
                                xmlContent,
                                cacheData.newRange,
                                cacheData.fieldData
                            )
                            part.outputStream.use { it.write(modifiedXml.toByteArray(Charsets.UTF_8)) }
                            logger.debug("피벗 캐시 범위 확장: ${cacheData.originalRange.formatAsString()} -> ${cacheData.newRange.formatAsString()} (시트: ${cacheData.sheetName})")

                            // 캐시 레코드 업데이트 (인덱스 참조 사용)
                            val recordsPartName = "/xl/pivotCache/pivotCacheRecords$cacheNum.xml"
                            val recordsPart = runCatching {
                                pkg.getPart(org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName(recordsPartName))
                            }.getOrNull()

                            if (recordsPart != null) {
                                val originalRecordsXml = recordsPart.inputStream.bufferedReader().readText()
                                val recordsXml = buildPivotCacheRecordsWithIndices(cacheData.fieldData, uniqueValuesPerField, originalRecordsXml)
                                recordsPart.outputStream.use { it.write(recordsXml.toByteArray(Charsets.UTF_8)) }
                                logger.debug("피벗 캐시 레코드 재구성: ${cacheData.fieldData[0].size}건")
                            }
                        }
                    }
                }

                // 피벗 테이블 정의 수정 (items 업데이트)
                val pivotTablePattern = Regex("/xl/pivotTables/pivotTable(\\d+)\\.xml")
                pkg.parts.toList().forEach { part ->
                    val partName = part.partName.name
                    if (!pivotTablePattern.matches(partName)) return@forEach

                    val xmlContent = part.inputStream.bufferedReader().readText()

                    // pivotTable items는 업데이트하지 않음
                    // refreshOnLoad="true"가 설정되어 있으므로 Excel이 열 때 자동으로 재계산함
                    // items만 업데이트하고 rowItems/location을 업데이트하지 않으면 오류 발생
                    logger.debug("피벗 테이블은 refreshOnLoad로 자동 갱신됨")
                }

                pkg.flush()
            }

            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 피벗 소스 범위 내의 모든 컬럼에 데이터가 있는 마지막 행을 찾습니다.
     * 푸터 행(일부 컬럼만 데이터가 있는 행)을 제외하기 위함입니다.
     */
    private fun findLastRowWithAllColumns(sheet: org.apache.poi.ss.usermodel.Sheet, range: CellRangeAddress): Int {
        val headerRow = range.firstRow
        val colStart = range.firstColumn
        val colEnd = range.lastColumn
        val colCount = colEnd - colStart + 1

        var lastCompleteRow = headerRow // 최소 헤더 행

        for (rowNum in headerRow + 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowNum) ?: break

            // 해당 범위 내 모든 컬럼에 데이터가 있는지 확인
            var filledColumns = 0
            for (colIdx in colStart..colEnd) {
                val cell = row.getCell(colIdx)
                if (cell != null && cell.cellType != org.apache.poi.ss.usermodel.CellType.BLANK) {
                    val value = when (cell.cellType) {
                        org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue.trim()
                        org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell.numericCellValue.toString()
                        else -> ""
                    }
                    if (value.isNotEmpty()) {
                        filledColumns++
                    }
                }
            }

            // 모든 컬럼에 데이터가 있으면 이 행까지 포함
            if (filledColumns == colCount) {
                lastCompleteRow = rowNum
            } else if (filledColumns == 0) {
                // 완전히 빈 행이면 데이터 영역 끝
                break
            }
            // 일부 컬럼만 채워진 행(푸터)은 무시
        }

        return lastCompleteRow
    }

    /**
     * sharedItems를 빈 상태로 만들되, containsBlank 속성을 추가합니다.
     */
    private fun clearSharedItemsForRefresh(xml: String): String {
        val result = StringBuilder()
        var pos = 0

        while (pos < xml.length) {
            val sharedItemsStart = xml.indexOf("<sharedItems", pos)
            if (sharedItemsStart == -1) {
                result.append(xml.substring(pos))
                break
            }

            result.append(xml.substring(pos, sharedItemsStart))

            val tagEnd = xml.indexOf(">", sharedItemsStart)
            if (tagEnd == -1) {
                result.append(xml.substring(sharedItemsStart))
                break
            }

            if (xml[tagEnd - 1] == '/') {
                // self-closing
                result.append("""<sharedItems containsBlank="1"/>""")
                pos = tagEnd + 1
            } else {
                val closingTag = "</sharedItems>"
                val closingPos = xml.indexOf(closingTag, tagEnd)
                if (closingPos == -1) {
                    result.append(xml.substring(sharedItemsStart))
                    break
                }
                result.append("""<sharedItems containsBlank="1"/>""")
                pos = closingPos + closingTag.length
            }
        }

        return result.toString()
    }

    /**
     * 피벗 테이블의 아이템 참조를 리셋합니다.
     * 캐시가 비어있으므로 아이템 인덱스 참조(x="0")를 제거하고 missing으로 표시합니다.
     * 원본 구조는 최대한 보존하여 필드명 등이 유지되도록 합니다.
     */
    private fun resetPivotTableItems(xml: String): String {
        var result = xml

        // pivotField 내의 items에서 x="숫자" 참조를 m="1"(missing)으로 변경
        // 이렇게 하면 필드 구조는 유지되고 Excel이 refreshOnLoad 시 올바르게 재구성함
        result = result.replace(Regex("""<item\s+x="[^"]*"/>"""), """<item m="1"/>""")

        // rowItems의 <x v="숫자"/>도 처리
        result = result.replace(Regex("""<x\s+v="[^"]*"/>"""), """<x/>""")
        result = result.replace(Regex("""<x/>"""), """<x v="0"/>""")

        return result
    }

    /**
     * 셀 값을 Any?로 반환
     */
    private fun getCellValueAsAny(cell: org.apache.poi.ss.usermodel.Cell?): Any? {
        if (cell == null) return null
        return when (cell.cellType) {
            org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
            org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    cell.dateCellValue
                } else {
                    cell.numericCellValue
                }
            }
            org.apache.poi.ss.usermodel.CellType.BOOLEAN -> cell.booleanCellValue
            org.apache.poi.ss.usermodel.CellType.FORMULA -> {
                try {
                    cell.numericCellValue
                } catch (e: Exception) {
                    try {
                        cell.stringCellValue
                    } catch (e2: Exception) {
                        null
                    }
                }
            }
            else -> null
        }
    }

    /**
     * 피벗 캐시 정의 XML을 재구성합니다.
     */
    private fun rebuildPivotCacheDefinition(
        originalXml: String,
        newRange: CellRangeAddress,
        sheetName: String,
        uniqueValuesLists: List<List<String>>,
        recordCount: Int
    ): String {
        var xml = originalXml

        // worksheetSource ref 업데이트
        xml = xml.replace(
            Regex("""(<worksheetSource\s+ref=")[^"]+("\s+sheet="[^"]+")"""),
            "$1${newRange.formatAsString()}$2"
        )

        // refreshOnLoad 추가
        xml = if (xml.contains("refreshOnLoad=")) {
            xml.replace(Regex("""refreshOnLoad="[^"]*""""), """refreshOnLoad="true"""")
        } else {
            xml.replace("<pivotCacheDefinition ", """<pivotCacheDefinition refreshOnLoad="true" """)
        }

        // recordCount 업데이트
        xml = xml.replace(Regex("""recordCount="[^"]*""""), """recordCount="$recordCount"""")

        // 각 cacheField의 sharedItems 업데이트
        var fieldIndex = 0
        val cacheFieldPattern = Regex("""<cacheField\s+name="[^"]+"\s+numFmtId="[^"]+">.*?</cacheField>""", RegexOption.DOT_MATCHES_ALL)

        xml = cacheFieldPattern.replace(xml) { match ->
            val fieldXml = match.value
            if (fieldIndex < uniqueValuesLists.size) {
                val uniqueValues = uniqueValuesLists[fieldIndex]
                fieldIndex++
                rebuildCacheField(fieldXml, uniqueValues)
            } else {
                fieldIndex++
                fieldXml
            }
        }

        return xml
    }

    /**
     * 단일 cacheField의 sharedItems를 재구성합니다.
     */
    private fun rebuildCacheField(fieldXml: String, uniqueValues: List<String>): String {
        // cacheField 시작 태그 추출
        val startTagMatch = Regex("""<cacheField\s+name="[^"]+"\s+numFmtId="[^"]+">""").find(fieldXml)
            ?: return fieldXml
        val startTag = startTagMatch.value

        // sharedItems 구성
        val sharedItems = if (uniqueValues.isEmpty()) {
            "<sharedItems/>"
        } else {
            val items = uniqueValues.joinToString("") { value ->
                // 숫자인지 확인
                val numValue = value.toDoubleOrNull()
                if (numValue != null) {
                    """<n v="$numValue"/>"""
                } else {
                    """<s v="${escapeXml(value)}"/>"""
                }
            }
            """<sharedItems count="${uniqueValues.size}">$items</sharedItems>"""
        }

        return "$startTag$sharedItems</cacheField>"
    }

    /**
     * 피벗 캐시 레코드 XML을 구성합니다.
     */
    private fun buildPivotCacheRecords(
        dataRecords: List<List<Any?>>,
        uniqueValuesLists: List<List<String>>
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<pivotCacheRecords xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        sb.append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """)
        sb.append("""count="${dataRecords.size}">""")

        for (record in dataRecords) {
            sb.append("<r>")
            for ((colIdx, value) in record.withIndex()) {
                if (colIdx < uniqueValuesLists.size) {
                    val uniqueValues = uniqueValuesLists[colIdx]
                    val valueStr = value?.toString() ?: ""
                    val idx = uniqueValues.indexOf(valueStr)
                    if (idx >= 0) {
                        sb.append("""<x v="$idx"/>""")
                    } else {
                        // 값이 uniqueValues에 없으면 직접 값으로 저장
                        val numValue = valueStr.toDoubleOrNull()
                        if (numValue != null) {
                            sb.append("""<n v="$numValue"/>""")
                        } else {
                            sb.append("""<s v="${escapeXml(valueStr)}"/>""")
                        }
                    }
                }
            }
            sb.append("</r>")
        }

        sb.append("</pivotCacheRecords>")
        return sb.toString()
    }

    /**
     * XML 특수문자 이스케이프
     */
    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 실제 데이터로 피벗 캐시 정의 XML을 재구성합니다.
     */
    private fun rebuildPivotCacheWithData(
        originalXml: String,
        newRange: CellRangeAddress,
        fieldData: List<List<Any?>>
    ): String {
        var xml = originalXml

        // 1. worksheetSource ref 업데이트
        xml = xml.replace(
            Regex("""(<worksheetSource\s+ref=")[^"]+("\s+sheet="[^"]+")"""),
            "$1${newRange.formatAsString()}$2"
        )

        // 2. refreshOnLoad 추가/업데이트
        xml = if (xml.contains("refreshOnLoad=")) {
            xml.replace(Regex("""refreshOnLoad="[^"]*""""), """refreshOnLoad="true"""")
        } else {
            xml.replace("<pivotCacheDefinition ", """<pivotCacheDefinition refreshOnLoad="true" """)
        }

        // 3. recordCount 업데이트
        val recordCount = if (fieldData.isNotEmpty()) fieldData[0].size else 0
        xml = if (xml.contains("recordCount=")) {
            xml.replace(Regex("""recordCount="[^"]*""""), """recordCount="$recordCount"""")
        } else {
            xml.replace(
                Regex("""(<pivotCacheDefinition[^>]*)(>)"""),
                """$1 recordCount="$recordCount"$2"""
            )
        }

        // 4. 각 필드의 sharedItems 재구성
        val result = StringBuilder()
        var pos = 0
        var fieldIndex = 0
        val cacheFieldEndTag = "</cacheField>"

        while (pos < xml.length) {
            // <cacheField 뒤에 공백이나 >가 오는 경우만 매칭 (cacheFields 제외)
            val cacheFieldStart = findCacheFieldStart(xml, pos)
            if (cacheFieldStart == -1) {
                result.append(xml.substring(pos))
                break
            }

            // cacheField 앞부분 추가
            result.append(xml.substring(pos, cacheFieldStart))

            val cacheFieldEnd = xml.indexOf(cacheFieldEndTag, cacheFieldStart)
            if (cacheFieldEnd == -1) {
                result.append(xml.substring(cacheFieldStart))
                break
            }

            val cacheFieldXml = xml.substring(cacheFieldStart, cacheFieldEnd + cacheFieldEndTag.length)

            // 현재 필드의 데이터로 sharedItems 재구성
            if (fieldIndex < fieldData.size) {
                val uniqueValues = fieldData[fieldIndex]
                    .mapNotNull { it?.toString() }
                    .distinct()
                val rebuiltField = rebuildCacheFieldWithData(cacheFieldXml, uniqueValues)
                result.append(rebuiltField)
            } else {
                result.append(cacheFieldXml)
            }

            fieldIndex++
            pos = cacheFieldEnd + cacheFieldEndTag.length
        }

        return result.toString()
    }

    /**
     * 피벗 캐시의 범위만 업데이트하고 refreshOnLoad를 설정합니다.
     * sharedItems는 비워두고 Excel이 refreshOnLoad로 재구성하도록 합니다.
     */
    private fun updatePivotCacheRangeOnly(
        originalXml: String,
        newRange: CellRangeAddress,
        recordCount: Int
    ): String {
        var xml = originalXml

        // 1. worksheetSource ref 업데이트
        xml = xml.replace(
            Regex("""(<worksheetSource\s+ref=")[^"]+("\s+sheet="[^"]+")"""),
            "$1${newRange.formatAsString()}$2"
        )

        // 2. refreshOnLoad 추가/업데이트
        xml = if (xml.contains("refreshOnLoad=")) {
            xml.replace(Regex("""refreshOnLoad="[^"]*""""), """refreshOnLoad="true"""")
        } else {
            xml.replace("<pivotCacheDefinition ", """<pivotCacheDefinition refreshOnLoad="true" """)
        }

        // 3. recordCount 업데이트
        xml = if (xml.contains("recordCount=")) {
            xml.replace(Regex("""recordCount="[^"]*""""), """recordCount="$recordCount"""")
        } else {
            xml.replace(
                Regex("""(<pivotCacheDefinition[^>]*)(>)"""),
                """$1 recordCount="$recordCount"$2"""
            )
        }

        // 4. 모든 sharedItems를 비움 (Excel이 refreshOnLoad로 재구성)
        xml = clearAllSharedItems(xml)

        return xml
    }

    /**
     * 인덱스 참조를 사용하여 피벗 캐시 레코드 XML을 구성합니다.
     */
    private fun buildPivotCacheRecordsWithIndices(
        fieldData: List<List<Any?>>,
        uniqueValuesPerField: List<List<String>>,
        originalXml: String
    ): String {
        if (fieldData.isEmpty() || fieldData[0].isEmpty()) {
            return originalXml
        }

        val recordCount = fieldData[0].size
        val fieldCount = fieldData.size

        // 각 필드별 값 -> 인덱스 맵
        val valueToIndexMaps = uniqueValuesPerField.map { uniqueValues ->
            uniqueValues.mapIndexed { index, value -> value to index }.toMap()
        }

        // 원본 XML에서 네임스페이스 선언 추출
        val nsPattern = Regex("""<pivotCacheRecords\s+([^>]*)>""")
        val nsMatch = nsPattern.find(originalXml)
        val namespaceAttrs = nsMatch?.groupValues?.get(1)?.replace(Regex("""count="[^"]*""""), "")?.trim() ?: ""

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<pivotCacheRecords")
        if (namespaceAttrs.isNotEmpty()) {
            sb.append(" $namespaceAttrs")
        }
        sb.append(""" count="$recordCount">""")

        // 각 레코드 생성 (인덱스 참조 사용)
        for (rowIdx in 0 until recordCount) {
            sb.append("<r>")
            for (colIdx in 0 until fieldCount) {
                val value = fieldData[colIdx].getOrNull(rowIdx)?.toString() ?: ""
                val idx = valueToIndexMaps.getOrNull(colIdx)?.get(value)
                if (idx != null) {
                    sb.append("""<x v="$idx"/>""")
                } else {
                    sb.append("<m/>")
                }
            }
            sb.append("</r>")
        }

        sb.append("</pivotCacheRecords>")
        return sb.toString()
    }

    /**
     * 피벗 테이블의 items를 업데이트합니다.
     */
    private fun updatePivotTableItems(xml: String, uniqueValuesPerField: List<List<String>>): String {
        var result = xml
        var fieldIndex = 0

        // 각 pivotField의 items를 업데이트
        val pivotFieldPattern = Regex("""<pivotField([^>]*)>(.*?)</pivotField>""", RegexOption.DOT_MATCHES_ALL)

        result = pivotFieldPattern.replace(result) { match ->
            val attrs = match.groupValues[1]
            val content = match.groupValues[2]

            if (fieldIndex < uniqueValuesPerField.size) {
                val uniqueCount = uniqueValuesPerField[fieldIndex].size
                fieldIndex++

                // items가 있는 경우에만 업데이트
                if (content.contains("<items")) {
                    val newItems = buildPivotFieldItems(uniqueCount)
                    val newContent = content.replace(
                        Regex("""<items[^>]*>.*?</items>""", RegexOption.DOT_MATCHES_ALL),
                        newItems
                    )
                    "<pivotField$attrs>$newContent</pivotField>"
                } else {
                    match.value
                }
            } else {
                fieldIndex++
                match.value
            }
        }

        return result
    }

    /**
     * pivotField의 items 요소를 생성합니다.
     */
    private fun buildPivotFieldItems(uniqueCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<items count="${uniqueCount + 1}">""")
        for (i in 0 until uniqueCount) {
            sb.append("""<item x="$i"/>""")
        }
        sb.append("""<item t="default"/>""")
        sb.append("</items>")
        return sb.toString()
    }

    /**
     * 직접 값을 사용하여 피벗 캐시 레코드 XML을 구성합니다.
     * sharedItems가 비어있으므로 인덱스 참조 대신 직접 값을 사용합니다.
     */
    private fun buildPivotCacheRecordsWithDirectValues(fieldData: List<List<Any?>>, originalXml: String): String {
        if (fieldData.isEmpty() || fieldData[0].isEmpty()) {
            // 원본 XML 반환 (빈 데이터)
            return originalXml
        }

        val recordCount = fieldData[0].size
        val fieldCount = fieldData.size

        // 원본 XML에서 네임스페이스 선언 추출
        val nsPattern = Regex("""<pivotCacheRecords\s+([^>]*)>""")
        val nsMatch = nsPattern.find(originalXml)
        val namespaceAttrs = nsMatch?.groupValues?.get(1)?.replace(Regex("""count="[^"]*""""), "")?.trim() ?: ""

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<pivotCacheRecords")
        if (namespaceAttrs.isNotEmpty()) {
            sb.append(" $namespaceAttrs")
        }
        sb.append(""" count="$recordCount">""")

        // 각 레코드 생성 (직접 값 사용)
        for (rowIdx in 0 until recordCount) {
            sb.append("<r>")
            for (colIdx in 0 until fieldCount) {
                val value = fieldData[colIdx].getOrNull(rowIdx)
                when (value) {
                    null -> sb.append("<m/>")
                    is Number -> {
                        val numValue = value.toDouble()
                        val formatted = if (numValue == numValue.toLong().toDouble()) {
                            numValue.toLong().toString()
                        } else {
                            numValue.toString()
                        }
                        sb.append("""<n v="$formatted"/>""")
                    }
                    else -> sb.append("""<s v="${escapeXml(value.toString())}"/>""")
                }
            }
            sb.append("</r>")
        }

        sb.append("</pivotCacheRecords>")
        return sb.toString()
    }

    /**
     * 모든 sharedItems를 비웁니다.
     */
    private fun clearAllSharedItems(xml: String): String {
        val result = StringBuilder()
        var pos = 0

        while (pos < xml.length) {
            val sharedItemsStart = xml.indexOf("<sharedItems", pos)
            if (sharedItemsStart == -1) {
                result.append(xml.substring(pos))
                break
            }

            // sharedItems 태그 앞부분 추가
            result.append(xml.substring(pos, sharedItemsStart))

            // self-closing 태그인지 확인
            val tagEnd = xml.indexOf(">", sharedItemsStart)
            if (tagEnd == -1) {
                result.append(xml.substring(sharedItemsStart))
                break
            }

            if (xml[tagEnd - 1] == '/') {
                // self-closing: <sharedItems/>
                result.append("<sharedItems/>")
                pos = tagEnd + 1
            } else {
                // 닫는 태그 찾기
                val closingTag = "</sharedItems>"
                val closingPos = xml.indexOf(closingTag, tagEnd)
                if (closingPos == -1) {
                    result.append(xml.substring(sharedItemsStart))
                    break
                }
                // 빈 sharedItems로 대체
                result.append("<sharedItems/>")
                pos = closingPos + closingTag.length
            }
        }

        return result.toString()
    }

    /**
     * <cacheField 태그의 시작 위치를 찾습니다.
     * <cacheFields와 구분하기 위해 <cacheField 뒤에 공백이나 >가 오는 경우만 매칭합니다.
     */
    private fun findCacheFieldStart(xml: String, startPos: Int): Int {
        var pos = startPos
        while (pos < xml.length) {
            val idx = xml.indexOf("<cacheField", pos)
            if (idx == -1) return -1

            // <cacheField 뒤의 문자 확인
            val nextCharPos = idx + "<cacheField".length
            if (nextCharPos < xml.length) {
                val nextChar = xml[nextCharPos]
                // 공백이나 >가 오면 실제 <cacheField> 태그
                if (nextChar == ' ' || nextChar == '>') {
                    return idx
                }
            }
            // <cacheFields 등인 경우 다음 위치에서 계속 검색
            pos = idx + 1
        }
        return -1
    }

    /**
     * 단일 cacheField의 sharedItems를 실제 데이터로 재구성합니다.
     */
    private fun rebuildCacheFieldWithData(fieldXml: String, uniqueValues: List<String>): String {
        // cacheField 시작 태그 추출 (name과 numFmtId 속성 포함)
        val startTagEnd = fieldXml.indexOf(">")
        if (startTagEnd == -1) return fieldXml

        val startTag = fieldXml.substring(0, startTagEnd + 1)

        // 숫자와 문자열 값 분리
        // sharedItems 구성 - 모든 값을 문자열로 저장 (원본 템플릿과 동일한 방식)
        val sharedItems = if (uniqueValues.isEmpty()) {
            "<sharedItems/>"
        } else {
            val sb = StringBuilder()
            sb.append("""<sharedItems count="${uniqueValues.size}">""")

            // 모든 값을 문자열로 저장
            for (value in uniqueValues) {
                sb.append("""<s v="${escapeXml(value)}"/>""")
            }

            sb.append("</sharedItems>")
            sb.toString()
        }

        return "$startTag$sharedItems</cacheField>"
    }

    /**
     * 실제 데이터로 피벗 캐시 레코드 XML을 구성합니다.
     */
    private fun buildPivotCacheRecordsWithData(fieldData: List<List<Any?>>): String {
        if (fieldData.isEmpty() || fieldData[0].isEmpty()) {
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<pivotCacheRecords xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" count="0"/>"""
        }

        val recordCount = fieldData[0].size
        val fieldCount = fieldData.size

        // 각 필드별 유니크 값 인덱스 맵 구성
        val uniqueValueIndices = fieldData.map { field ->
            val uniqueValues = field.mapNotNull { it?.toString() }.distinct()
            uniqueValues.mapIndexed { index, value -> value to index }.toMap()
        }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<pivotCacheRecords xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        sb.append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """)
        sb.append("""count="$recordCount">""")

        // 각 레코드 생성
        for (rowIdx in 0 until recordCount) {
            sb.append("<r>")
            for (colIdx in 0 until fieldCount) {
                val value = fieldData[colIdx].getOrNull(rowIdx)
                val valueStr = value?.toString() ?: ""
                val idx = uniqueValueIndices[colIdx][valueStr]

                if (idx != null) {
                    sb.append("""<x v="$idx"/>""")
                } else {
                    // 값이 없는 경우 (빈 값)
                    sb.append("""<m/>""")
                }
            }
            sb.append("</r>")
        }

        sb.append("</pivotCacheRecords>")
        return sb.toString()
    }

    /**
     * cacheField의 sharedItems 내용을 초기화합니다.
     * 각 cacheField를 개별적으로 처리하여 정규식 매칭 오류를 방지합니다.
     */
    @Suppress("unused")
    private fun clearSharedItemsContent(xml: String): String {
        val result = StringBuilder()
        var pos = 0

        while (pos < xml.length) {
            val sharedItemsStart = xml.indexOf("<sharedItems", pos)
            if (sharedItemsStart == -1) {
                result.append(xml.substring(pos))
                break
            }

            // sharedItems 태그 앞부분 추가
            result.append(xml.substring(pos, sharedItemsStart))

            // self-closing 태그인지 확인
            val tagEnd = xml.indexOf(">", sharedItemsStart)
            if (tagEnd == -1) {
                result.append(xml.substring(sharedItemsStart))
                break
            }

            if (xml[tagEnd - 1] == '/') {
                // self-closing: <sharedItems/> 또는 <sharedItems attr="value"/>
                result.append("<sharedItems/>")
                pos = tagEnd + 1
            } else {
                // 닫는 태그 찾기
                val closingTag = "</sharedItems>"
                val closingPos = xml.indexOf(closingTag, tagEnd)
                if (closingPos == -1) {
                    result.append(xml.substring(sharedItemsStart))
                    break
                }
                // 빈 sharedItems로 대체
                result.append("<sharedItems/>")
                pos = closingPos + closingTag.length
            }
        }

        return result.toString()
    }

    private fun expandRangeIfNeeded(
        originalRange: CellRangeAddress,
        templateLastRow: Int,
        currentLastRow: Int
    ): CellRangeAddress {
        val isSingleRow = originalRange.firstRow == originalRange.lastRow
        val isInTemplateArea = originalRange.firstRow <= templateLastRow
        val rowExpansion = currentLastRow - templateLastRow

        return if (isSingleRow && isInTemplateArea && rowExpansion > 0) {
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
        helper: org.apache.poi.ss.usermodel.DataValidationHelper,
        info: DataValidationInfo,
        ranges: List<CellRangeAddress>
    ): org.apache.poi.ss.usermodel.DataValidation? {
        val constraint = when (info.validationType) {
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

            else -> return null
        }

        val addressList = CellRangeAddressList().apply {
            ranges.forEach { addCellRangeAddress(it) }
        }

        return helper.createValidation(constraint, addressList).apply {
            showErrorBox = info.showErrorBox
            showPromptBox = info.showPromptBox
            emptyCellAllowed = info.emptyCellAllowed

            if (info.errorTitle != null || info.errorText != null) {
                createErrorBox(info.errorTitle.orEmpty(), info.errorText.orEmpty())
            }
            if (info.promptTitle != null || info.promptText != null) {
                createPromptBox(info.promptTitle.orEmpty(), info.promptText.orEmpty())
            }
        }
    }

    // ========== JXLS 처리 ==========

    private fun processJxlsWithFormulaErrorDetection(
        input: InputStream,
        output: OutputStream,
        context: Context
    ) {
        val appender = FormulaErrorCapturingAppender().apply { start() }
        val logger = org.slf4j.LoggerFactory.getLogger("org.jxls.transform.poi.PoiTransformer")
            as ch.qos.logback.classic.Logger
        val originalLevel = logger.level

        try {
            logger.addAppender(appender)

            JxlsHelper.getInstance()
                .setUseFastFormulaProcessor(config.formulaProcessingEnabled)
                .processTemplate(input, output, context)

            appender.getCapturedError()?.let { error ->
                throw FormulaExpansionException(
                    sheetName = error.sheetName,
                    cellRef = error.cellRef,
                    formula = error.formula
                )
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = originalLevel
            appender.stop()
        }
    }

    // ========== 템플릿 전처리 ==========

    private fun preprocessSheet(
        workbook: XSSFWorkbook,
        sheet: Sheet,
        dataProvider: ExcelDataProvider
    ) {
        convertImagePlaceholders(workbook, sheet)
        completeImageCommands(workbook, sheet, dataProvider)
        ensureJxAreaComment(workbook, sheet)
    }

    private fun convertImagePlaceholders(workbook: XSSFWorkbook, sheet: Sheet) {
        val imagePattern = Regex("""\$\{image\.(\w+)}""")

        sheet.asSequence()
            .flatMap { it.asSequence() }
            .filter { it.cellType == CellType.STRING }
            .forEach { cell ->
                cell.stringCellValue?.let { value ->
                    imagePattern.find(value)?.let { match ->
                        val imageName = match.groupValues[1]
                        cell.setCellValue("")

                        if (cell.cellComment == null) {
                            cell.addJxImageComment(workbook, sheet, imageName)
                        }
                    }
                }
            }
    }

    private fun Cell.addJxImageComment(workbook: XSSFWorkbook, sheet: Sheet, imageName: String) {
        val drawing = sheet.createDrawingPatriarch()
        val anchor = XSSFClientAnchor(0, 0, 0, 0, columnIndex, rowIndex, columnIndex + 2, rowIndex + 2)
        val comment = drawing.createCellComment(anchor)
        comment.string = workbook.creationHelper.createRichTextString("jx:image(src=\"$imageName\")")
        cellComment = comment
    }

    private fun completeImageCommands(
        workbook: XSSFWorkbook,
        sheet: Sheet,
        dataProvider: ExcelDataProvider
    ) {
        sheet.asSequence()
            .flatMap { it.asSequence() }
            .filter { it.cellComment != null }
            .forEach { cell ->
                val comment = cell.cellComment
                val commentText = comment.string?.string ?: return@forEach

                if (commentText.contains("jx:image", ignoreCase = true)) {
                    val updatedCommand = completeImageCommand(commentText, cell, sheet, dataProvider)
                    if (updatedCommand != commentText) {
                        comment.string = workbook.creationHelper.createRichTextString(updatedCommand)
                    }
                }
            }
    }

    private fun completeImageCommand(
        command: String,
        cell: Cell,
        sheet: Sheet,
        dataProvider: ExcelDataProvider
    ): String {
        var result = command

        // lastCell 자동 추가
        if (!command.contains("lastCell", ignoreCase = true)) {
            val cellRef = sheet.findMergedRegion(cell.rowIndex, cell.columnIndex)
                ?.let { getCellReference(it.lastRow, it.lastColumn) }
                ?: getCellReference(cell.rowIndex, cell.columnIndex)
            result = result.replace(")", " lastCell=\"$cellRef\")")
        }

        // imageType 자동 감지
        if (!command.contains("imageType", ignoreCase = true)) {
            Regex("""src\s*=\s*"([^"]+)"""").find(command)
                ?.groupValues?.get(1)
                ?.let { srcName ->
                    dataProvider.getImage(srcName)?.let { imageBytes ->
                        val imageType = imageBytes.detectImageType()
                        result = result.replace(")", " imageType=\"$imageType\")")
                    }
                }
        }

        return result
    }

    private fun ensureJxAreaComment(workbook: XSSFWorkbook, sheet: Sheet) {
        val firstCell = sheet.getRow(0)?.getCell(0)
        if (firstCell.hasJxAreaComment()) return

        val lastRow = sheet.lastRowWithData
        val lastCol = sheet.lastColumnWithData

        if (lastRow >= 0 && lastCol >= 0) {
            addJxAreaComment(workbook, sheet, lastRow, lastCol)
        }
    }

    private fun addJxAreaComment(workbook: XSSFWorkbook, sheet: Sheet, lastRow: Int, lastCol: Int) {
        val row = sheet.getRow(0) ?: sheet.createRow(0)
        val cell = row.getCell(0) ?: row.createCell(0)

        val drawing = sheet.createDrawingPatriarch()
        val anchor = XSSFClientAnchor(0, 0, 0, 0, 0, 0, 2, 2)
        val comment = drawing.createCellComment(anchor)
        comment.string = workbook.creationHelper.createRichTextString(
            "jx:area(lastCell=\"${getCellReference(lastRow, lastCol)}\")"
        )
        cell.cellComment = comment
    }

    // ========== Context 생성 ==========

    private fun createContext(dataProvider: ExcelDataProvider): ContextResult {
        val context = Context()
        var totalRows = 0

        dataProvider.getAvailableNames().forEach { name ->
            dataProvider.getValue(name)?.let { context.putVar(name, it) }
            dataProvider.getItems(name)?.let { iterator ->
                val items = iterator.asSequence().toList()
                totalRows += items.size
                context.putVar(name, items)
            }
            dataProvider.getImage(name)?.let { context.putVar(name, it) }
        }

        return ContextResult(context, totalRows)
    }

    // ========== 유틸리티 ==========

    private fun generateFileName(baseFileName: String): String {
        val timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern(config.timestampFormat)
        )
        return "${baseFileName}_${timestamp}.xlsx"
    }

    private fun getCellReference(row: Int, col: Int) =
        "${col.toColumnLetter()}${row + 1}"

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }

    // ========== 확장 함수 및 프로퍼티 ==========

    private val XSSFWorkbook.sheets
        get() = (0 until numberOfSheets).asSequence().map { getSheetAt(it) }

    private val Sheet.lastRowWithData
        get() = asSequence()
            .flatMap { it.asSequence() }
            .filterNot { it.isEmpty }
            .maxOfOrNull { it.rowIndex } ?: -1

    private val Sheet.lastColumnWithData
        get() = asSequence()
            .flatMap { it.asSequence() }
            .filterNot { it.isEmpty }
            .maxOfOrNull { it.columnIndex } ?: -1

    private val Cell.isEmpty
        get() = cellComment == null && when (cellType) {
            CellType.BLANK -> true
            CellType.STRING -> stringCellValue.isNullOrBlank()
            else -> false
        }

    private fun Cell?.hasJxAreaComment() =
        this?.cellComment?.string?.string?.contains("jx:area", ignoreCase = true) == true

    private fun Sheet.findMergedRegion(rowIndex: Int, columnIndex: Int) =
        (0 until numMergedRegions)
            .map { getMergedRegion(it) }
            .firstOrNull { it.isInRange(rowIndex, columnIndex) }

    private fun Int.toColumnLetter() = buildString {
        var c = this@toColumnLetter
        while (c >= 0) {
            insert(0, ('A' + (c % 26)))
            c = c / 26 - 1
        }
    }

    private fun ByteArray.detectImageType() = when {
        size < 8 -> "PNG"
        isPng() -> "PNG"
        isJpeg() -> "JPEG"
        isGif() -> "PNG"  // GIF는 POI에서 PNG로 처리
        isBmp() -> "DIB"
        else -> "PNG"
    }

    private fun ByteArray.isPng() =
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()

    private fun ByteArray.isJpeg() =
        this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

    private fun ByteArray.isGif() =
        this[0] == 0x47.toByte() && this[1] == 0x49.toByte() &&
        this[2] == 0x46.toByte() && this[3] == 0x38.toByte()

    private fun ByteArray.isBmp() =
        this[0] == 0x42.toByte() && this[1] == 0x4D.toByte()

    private fun XSSFWorkbook.toByteArray() =
        ByteArrayOutputStream().also { write(it) }.toByteArray()
}
