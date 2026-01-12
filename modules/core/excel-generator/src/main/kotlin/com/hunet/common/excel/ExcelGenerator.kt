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

        // 피벗 테이블 정보 추출 및 삭제 (JXLS 처리 전)
        val (pivotTableInfos, bytesWithoutPivot) = extractAndRemovePivotTables(processedBytes)

        // JXLS 처리 (피벗 테이블 없이)
        val jxlsOutput = ByteArrayOutputStream().also { tempOutput ->
            ByteArrayInputStream(bytesWithoutPivot).use { input ->
                processJxlsWithFormulaErrorDetection(input, tempOutput, context)
            }
        }

        // 후처리 (레이아웃 복원, 데이터 유효성 검사 확장, 피벗 테이블 재생성)
        val resultBytes = jxlsOutput.toByteArray()
            .let { bytes -> layout?.let { restoreLayout(bytes, it) } ?: bytes }
            .let { bytes -> expandDataValidations(bytes, dataValidations) }
            .let { bytes -> recreatePivotTables(bytes, pivotTableInfos) }

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
     * 피벗 테이블 설정 정보
     */
    private data class PivotTableInfo(
        val pivotTableSheetName: String,       // 피벗 테이블이 위치한 시트
        val pivotTableLocation: String,        // 피벗 테이블 위치 (예: "I6")
        val sourceSheetName: String,           // 데이터 소스 시트
        val sourceRange: CellRangeAddress,     // 원본 데이터 범위
        val rowLabelFields: List<Int>,         // 행 레이블 필드 인덱스
        val dataFields: List<DataFieldInfo>,   // 값 필드 정보
        val pivotTableName: String,            // 피벗 테이블 이름
        val rowHeaderCaption: String?,         // 행 레이블 헤더 캡션 (예: "직급")
        val pivotTableXml: String,             // 원본 피벗 테이블 XML
        val pivotCacheDefXml: String,          // 원본 피벗 캐시 정의 XML
        val pivotCacheRecordsXml: String?      // 원본 피벗 캐시 레코드 XML
    )

    private data class DataFieldInfo(
        val fieldIndex: Int,
        val function: org.apache.poi.ss.usermodel.DataConsolidateFunction,
        val name: String?
    )

    /**
     * 피벗 테이블 정보를 추출하고 워크북에서 제거합니다.
     * @return Pair<피벗 테이블 정보 목록, 피벗 테이블이 제거된 바이트 배열>
     */
    private fun extractAndRemovePivotTables(inputBytes: ByteArray): Pair<List<PivotTableInfo>, ByteArray> {
        val pivotTableInfos = mutableListOf<PivotTableInfo>()

        // 피벗 테이블이 없으면 원본 반환
        val hasPivotTable = XSSFWorkbook(ByteArrayInputStream(inputBytes)).use { workbook ->
            workbook.sheets.any { sheet ->
                (sheet as? XSSFSheet)?.pivotTables?.isNotEmpty() == true
            }
        }

        if (!hasPivotTable) {
            logger.debug("피벗 테이블이 없음, 원본 반환")
            return Pair(emptyList(), inputBytes)
        }

        // 피벗 테이블 정보 수집 (XML 직접 파싱)
        org.apache.poi.openxml4j.opc.OPCPackage.open(ByteArrayInputStream(inputBytes)).use { pkg ->
                // 피벗 캐시 정의에서 소스 정보 추출
                val cacheSourceMap = mutableMapOf<String, Pair<String, String>>() // cacheId -> (sheet, ref)

                pkg.parts.filter { it.partName.name.contains("/pivotCache/pivotCacheDefinition") }.forEach { part ->
                    val xml = part.inputStream.bufferedReader().readText()

                    // worksheetSource 파싱: <worksheetSource ref="A5:C6" sheet="Report(세로)"/>
                    val sheetMatch = Regex("""sheet="([^"]+)"""").find(xml)
                    val refMatch = Regex("""<worksheetSource[^>]*ref="([^"]+)"""").find(xml)

                    if (sheetMatch != null && refMatch != null) {
                        val cacheId = part.partName.name // 캐시 식별용
                        cacheSourceMap[cacheId] = Pair(sheetMatch.groupValues[1], refMatch.groupValues[1])
                        logger.debug("피벗 캐시 발견: $cacheId -> ${sheetMatch.groupValues[1]}!${refMatch.groupValues[1]}")
                    }
                }

                // 피벗 테이블에서 정보 추출
                pkg.parts.filter { it.partName.name.contains("/pivotTables/pivotTable") }.forEach { part ->
                    val xml = part.inputStream.bufferedReader().readText()

                    // 피벗 테이블 이름
                    val nameMatch = Regex("""name="([^"]+)"""").find(xml)
                    val pivotTableName = nameMatch?.groupValues?.get(1) ?: "PivotTable"

                    // 위치 정보: <location ref="I6:J8"
                    val locationMatch = Regex("""<location[^>]*ref="([^"]+)"""").find(xml)
                    val location = locationMatch?.groupValues?.get(1)?.split(":")?.firstOrNull() ?: "A1"

                    // 행 레이블 필드: <rowFields count="1"><field x="1"/></rowFields>
                    val rowLabelFields = mutableListOf<Int>()
                    Regex("""<rowFields[^>]*>(.+?)</rowFields>""").find(xml)?.let { rowFieldsMatch ->
                        Regex("""<field x="(\d+)"""").findAll(rowFieldsMatch.groupValues[1]).forEach {
                            rowLabelFields.add(it.groupValues[1].toInt())
                        }
                    }

                    // 데이터 필드: <dataField name="평균 : 급여" fld="2" subtotal="average"
                    val dataFields = mutableListOf<DataFieldInfo>()
                    Regex("""<dataField[^>]+>""").findAll(xml).forEach { match ->
                        val dataFieldXml = match.value
                        val fldMatch = Regex("""fld="(\d+)"""").find(dataFieldXml)
                        val subtotalMatch = Regex("""subtotal="([^"]*)"""").find(dataFieldXml)
                        val nameAttrMatch = Regex("""name="([^"]+)"""").find(dataFieldXml)

                        val fld = fldMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                        val subtotal = subtotalMatch?.groupValues?.get(1) ?: "sum"
                        val name = nameAttrMatch?.groupValues?.get(1)

                        val function = when (subtotal) {
                            "count" -> org.apache.poi.ss.usermodel.DataConsolidateFunction.COUNT
                            "average" -> org.apache.poi.ss.usermodel.DataConsolidateFunction.AVERAGE
                            "max" -> org.apache.poi.ss.usermodel.DataConsolidateFunction.MAX
                            "min" -> org.apache.poi.ss.usermodel.DataConsolidateFunction.MIN
                            else -> org.apache.poi.ss.usermodel.DataConsolidateFunction.SUM
                        }

                        dataFields.add(DataFieldInfo(fld, function, name))
                    }

                    // rowHeaderCaption 추출 (행 레이블 헤더 캡션, 예: "직급")
                    val rowHeaderCaptionMatch = Regex("""rowHeaderCaption="([^"]+)"""").find(xml)
                    val rowHeaderCaption = rowHeaderCaptionMatch?.groupValues?.get(1)

                    // 소스 정보는 첫 번째 캐시에서 가져옴 (단순화)
                    val (sourceSheet, sourceRef) = cacheSourceMap.values.firstOrNull() ?: run {
                        logger.warn("피벗 테이블 '$pivotTableName' 캐시 소스 정보 없음")
                        return@forEach
                    }

                    // 피벗 테이블이 어느 시트에 있는지 확인
                    val pivotTablePartName = part.partName.name
                    // /xl/pivotTables/pivotTable1.xml -> 시트 관계에서 찾아야 함
                    val pivotTableSheetName = findPivotTableSheetName(pkg, pivotTablePartName) ?: sourceSheet

                    pivotTableInfos.add(PivotTableInfo(
                        pivotTableSheetName = pivotTableSheetName,
                        pivotTableLocation = location,
                        sourceSheetName = sourceSheet,
                        sourceRange = CellRangeAddress.valueOf(sourceRef),
                        rowLabelFields = rowLabelFields,
                        dataFields = dataFields,
                        pivotTableName = pivotTableName,
                        rowHeaderCaption = rowHeaderCaption,
                        pivotTableXml = "",
                        pivotCacheDefXml = "",
                        pivotCacheRecordsXml = null
                    ))

                    logger.debug("피벗 테이블 발견: '$pivotTableName' (위치: $pivotTableSheetName!$location, 소스: $sourceSheet!$sourceRef)")
                }
            }

        // ZIP 파일로 피벗 관련 파트 및 참조 완전 제거
        val cleanedBytes = removePivotReferencesFromZip(inputBytes)

        return Pair(pivotTableInfos, cleanedBytes)
    }

    /**
     * 피벗 테이블을 재생성합니다.
     */
    private fun recreatePivotTables(inputBytes: ByteArray, pivotTableInfos: List<PivotTableInfo>): ByteArray {
        if (pivotTableInfos.isEmpty()) {
            return inputBytes
        }

        val bytesWithPivotTable = XSSFWorkbook(ByteArrayInputStream(inputBytes)).use { workbook ->
            pivotTableInfos.forEach { info ->
                val pivotSheet = workbook.getSheet(info.pivotTableSheetName) as? XSSFSheet
                val sourceSheet = workbook.getSheet(info.sourceSheetName) as? XSSFSheet

                if (pivotSheet == null || sourceSheet == null) {
                    logger.warn("피벗 테이블 재생성 실패: 시트를 찾을 수 없음 (pivot=${info.pivotTableSheetName}, source=${info.sourceSheetName})")
                    return@forEach
                }

                // 확장된 데이터 범위 계산
                val newLastRow = findLastRowWithData(sourceSheet, info.sourceRange)
                val newSourceRange = CellRangeAddress(
                    info.sourceRange.firstRow,
                    newLastRow,
                    info.sourceRange.firstColumn,
                    info.sourceRange.lastColumn
                )

                logger.debug("피벗 테이블 재생성: ${info.pivotTableName} (범위: ${info.sourceRange.formatAsString()} -> ${newSourceRange.formatAsString()})")

                try {
                    // 피벗 테이블 위치 파싱
                    val pivotLocation = org.apache.poi.ss.util.CellReference(info.pivotTableLocation)

                    // 피벗 테이블 영역 정리 (기존 피벗 테이블 잔여물 제거)
                    // 피벗 테이블은 보통 10x10 정도 영역을 차지하므로 여유있게 정리
                    clearPivotTableArea(pivotSheet, pivotLocation.row, pivotLocation.col.toInt(), 20, 10)

                    // 피벗 테이블 생성
                    val areaReference = org.apache.poi.ss.util.AreaReference(
                        "${info.sourceSheetName}!${newSourceRange.formatAsString()}",
                        workbook.spreadsheetVersion
                    )

                    val pivotTable = pivotSheet.createPivotTable(areaReference, pivotLocation, sourceSheet)

                    // 행 레이블 필드 추가
                    info.rowLabelFields.forEach { fieldIdx ->
                        pivotTable.addRowLabel(fieldIdx)
                    }

                    // 데이터 필드 추가
                    info.dataFields.forEach { dataField ->
                        pivotTable.addColumnLabel(dataField.function, dataField.fieldIndex, dataField.name)
                    }

                    // rowHeaderCaption 적용 (행 레이블 헤더 캡션)
                    info.rowHeaderCaption?.let { caption ->
                        pivotTable.ctPivotTableDefinition.rowHeaderCaption = caption
                        logger.debug("rowHeaderCaption 적용: '$caption'")
                    }

                    logger.debug("피벗 테이블 재생성 완료: ${info.pivotTableName}")
                } catch (e: Exception) {
                    throw IllegalStateException("피벗 테이블 재생성 실패: ${info.pivotTableName}", e)
                }
            }

            workbook.toByteArray()
        }

        return bytesWithPivotTable
    }

    /**
     * 지정된 범위 내에서 데이터가 있는 마지막 행을 찾습니다.
     */
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

            if (hasData) {
                lastRow = rowNum
            } else {
                // 빈 행이면 데이터 영역 끝
                break
            }
        }

        return lastRow
    }

    /**
     * ZIP 파일에서 피벗 관련 참조를 완전히 제거합니다.
     */
    private fun removePivotReferencesFromZip(inputBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        java.util.zip.ZipInputStream(ByteArrayInputStream(inputBytes)).use { zis ->
            java.util.zip.ZipOutputStream(output).use { zos ->
                var entry = zis.nextEntry

                while (entry != null) {
                    val entryName = entry.name

                    // 피벗 관련 파일 완전히 스킵
                    if (entryName.contains("pivotCache") || entryName.contains("pivotTables")) {
                        logger.debug("피벗 파트 제거: $entryName")
                        entry = zis.nextEntry
                        continue
                    }

                    val content = zis.readBytes()
                    var modifiedContent = content

                    // [Content_Types].xml에서 피벗 관련 타입 제거
                    if (entryName == "[Content_Types].xml") {
                        val xml = String(content, Charsets.UTF_8)
                        // Override 요소만 제거 (한 줄 형태 및 여러 줄 형태 모두 처리)
                        val cleanedXml = xml
                            .replace(Regex("""<Override[^>]*pivotCache[^>]*/>\s*"""), "")
                            .replace(Regex("""<Override[^>]*pivotTable[^>]*/>\s*"""), "")
                        modifiedContent = cleanedXml.toByteArray(Charsets.UTF_8)
                        logger.debug("Content_Types에서 피벗 참조 제거")
                    }

                    // 워크시트 관계에서 피벗 참조 제거
                    if (entryName.contains("worksheets/_rels/") && entryName.endsWith(".rels")) {
                        val xml = String(content, Charsets.UTF_8)
                        if (xml.contains("pivotTable")) {
                            val cleanedXml = xml.replace(Regex("""<Relationship[^>]*pivotTable[^>]*/>\s*"""), "")
                            // 관계가 모두 제거되면 유효한 빈 rels 파일로 대체
                            val finalXml = if (!cleanedXml.contains("<Relationship")) {
                                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
                            } else {
                                cleanedXml
                            }
                            modifiedContent = finalXml.toByteArray(Charsets.UTF_8)
                            logger.debug("워크시트 관계에서 피벗 참조 제거: $entryName")
                        }
                    }

                    // workbook.xml에서 pivotCaches 제거
                    if (entryName == "xl/workbook.xml") {
                        val xml = String(content, Charsets.UTF_8)
                        if (xml.contains("<pivotCaches")) {
                            val cleanedXml = xml
                                .replace(Regex("""<pivotCaches>.*?</pivotCaches>""", RegexOption.DOT_MATCHES_ALL), "")
                                .replace(Regex("""<pivotCaches/>"""), "")
                            modifiedContent = cleanedXml.toByteArray(Charsets.UTF_8)
                            logger.debug("워크북에서 pivotCaches 제거")
                        }
                    }

                    // workbook.xml.rels에서 피벗 캐시 관계 제거
                    if (entryName == "xl/_rels/workbook.xml.rels") {
                        val xml = String(content, Charsets.UTF_8)
                        if (xml.contains("pivotCache")) {
                            val cleanedXml = xml.replace(Regex("""<Relationship[^>]*pivotCache[^>]*/>\s*"""), "")
                            modifiedContent = cleanedXml.toByteArray(Charsets.UTF_8)
                            logger.debug("워크북 관계에서 피벗 캐시 참조 제거")
                        }
                    }

                    // 새 엔트리 작성
                    val newEntry = java.util.zip.ZipEntry(entryName)
                    zos.putNextEntry(newEntry)
                    zos.write(modifiedContent)
                    zos.closeEntry()

                    entry = zis.nextEntry
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * 피벗 테이블 영역의 셀 내용을 정리합니다.
     */
    private fun clearPivotTableArea(sheet: XSSFSheet, startRow: Int, startCol: Int, rows: Int, cols: Int) {
        for (rowIdx in startRow until startRow + rows) {
            val row = sheet.getRow(rowIdx) ?: continue
            for (colIdx in startCol until startCol + cols) {
                val cell = row.getCell(colIdx)
                cell?.setBlank()
            }
        }
    }

    /**
     * 피벗 테이블이 위치한 시트 이름을 찾습니다.
     */
    private fun findPivotTableSheetName(pkg: org.apache.poi.openxml4j.opc.OPCPackage, pivotTablePartName: String): String? {
        // 워크북에서 시트 이름 목록 추출
        val workbookPart = pkg.parts.find { it.partName.name == "/xl/workbook.xml" }
        val sheetNames = mutableListOf<String>()

        workbookPart?.let {
            val xml = it.inputStream.bufferedReader().readText()
            Regex("""<sheet[^>]*name="([^"]+)"[^>]*r:id="(rId\d+)"""").findAll(xml).forEach { match ->
                sheetNames.add(match.groupValues[1])
            }
        }

        // 각 시트의 관계에서 피벗 테이블 참조 찾기
        for ((index, sheetName) in sheetNames.withIndex()) {
            val sheetRelsPartName = "/xl/worksheets/_rels/sheet${index + 1}.xml.rels"
            val relsPart = pkg.parts.find { it.partName.name == sheetRelsPartName }

            relsPart?.let {
                val relsXml = it.inputStream.bufferedReader().readText()
                // pivotTablePartName: /xl/pivotTables/pivotTable1.xml
                // Target in rels: ../pivotTables/pivotTable1.xml
                val pivotFileName = pivotTablePartName.substringAfterLast("/")
                if (relsXml.contains(pivotFileName)) {
                    return sheetName
                }
            }
        }

        return null
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
