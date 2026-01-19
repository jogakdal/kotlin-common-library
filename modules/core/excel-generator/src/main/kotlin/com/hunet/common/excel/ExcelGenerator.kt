package com.hunet.common.excel

import com.hunet.common.excel.async.DefaultGenerationJob
import com.hunet.common.excel.async.ExcelGenerationListener
import com.hunet.common.excel.async.GenerationJob
import com.hunet.common.excel.async.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ch.qos.logback.classic.Logger
import org.jxls.common.Context
import org.jxls.util.JxlsHelper
import org.slf4j.LoggerFactory
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

    // 프로세서들
    private val layoutProcessor = LayoutProcessor()
    private val dataValidationProcessor = DataValidationProcessor()
    private val pivotTableProcessor = PivotTableProcessor(config)
    private val xmlVariableProcessor = XmlVariableProcessor()

    // ========== 동기 API ==========

    /**
     * 템플릿과 데이터 맵으로 Excel을 생성합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param data 바인딩할 데이터 맵
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 Excel 파일의 바이트 배열
     */
    @JvmOverloads
    fun generate(template: InputStream, data: Map<String, Any>, password: String? = null): ByteArray =
        generate(template, SimpleDataProvider.of(data), password)

    /**
     * 템플릿과 DataProvider로 Excel을 생성합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 Excel 파일의 바이트 배열
     */
    @JvmOverloads
    fun generate(template: InputStream, dataProvider: ExcelDataProvider, password: String? = null): ByteArray =
        ByteArrayOutputStream().use { output ->
            processTemplate(template, dataProvider, output)
            output.toByteArray()
        }.let { bytes ->
            password.takeUnless { it.isNullOrBlank() }?.let { bytes.encryptExcel(it) } ?: bytes
        }

    /**
     * 템플릿 파일과 DataProvider로 Excel을 생성합니다.
     *
     * @param template 템플릿 파일
     * @param dataProvider 데이터 제공자
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 Excel 파일의 바이트 배열
     */
    @JvmOverloads
    fun generate(template: File, dataProvider: ExcelDataProvider, password: String? = null): ByteArray =
        template.inputStream().use { generate(it, dataProvider, password) }

    /**
     * Excel을 생성하여 파일로 저장합니다.
     *
     * 파일명에 타임스탬프가 자동으로 추가됩니다.
     * 예: "report" → "report_20240106_143052.xlsx"
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param outputDir 출력 디렉토리 경로
     * @param baseFileName 기본 파일명 (확장자 제외)
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 파일의 경로
     */
    @JvmOverloads
    fun generateToFile(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        password: String? = null
    ): Path = generateToFileInternal(template, dataProvider, outputDir, baseFileName, password).first

    /**
     * 파일 생성 후 경로와 처리된 행 수를 함께 반환합니다.
     * 예외 발생 시 생성된 파일을 삭제합니다.
     */
    private fun generateToFileInternal(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        password: String? = null
    ): Pair<Path, Int> {
        Files.createDirectories(outputDir)
        val outputPath = resolveOutputPath(outputDir, baseFileName)

        return runCatching {
            val effectivePassword = password.takeUnless { it.isNullOrBlank() }
            val rowsProcessed = if (effectivePassword != null) {
                // 암호화가 필요한 경우 메모리에서 처리 후 암호화
                ByteArrayOutputStream().use { tempOutput ->
                    val rows = processTemplate(template, dataProvider, tempOutput)
                    Files.newOutputStream(outputPath).use { fileOutput ->
                        tempOutput.toByteArray().encryptExcelTo(effectivePassword, fileOutput)
                    }
                    rows
                }
            } else {
                Files.newOutputStream(outputPath).use { output ->
                    processTemplate(template, dataProvider, output)
                }
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
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 Excel 파일의 바이트 배열
     */
    suspend fun generateAsync(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        password: String? = null
    ): ByteArray = withContext(dispatcher) { generate(template, dataProvider, password) }

    /**
     * 비동기로 Excel을 생성하여 파일로 저장합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param outputDir 출력 디렉토리 경로
     * @param baseFileName 기본 파일명 (확장자 제외)
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 파일의 경로
     */
    suspend fun generateToFileAsync(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        password: String? = null
    ): Path = withContext(dispatcher) {
        generateToFile(template, dataProvider, outputDir, baseFileName, password)
    }

    // ========== 비동기 API (Java CompletableFuture) ==========

    /**
     * CompletableFuture로 Excel을 생성합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 Excel 파일의 바이트 배열을 담은 CompletableFuture
     */
    @JvmOverloads
    fun generateFuture(template: InputStream, dataProvider: ExcelDataProvider, password: String? = null) =
        scope.future { generate(template, dataProvider, password) }

    /**
     * CompletableFuture로 Excel을 생성하여 파일로 저장합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param outputDir 출력 디렉토리 경로
     * @param baseFileName 기본 파일명 (확장자 제외)
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @return 생성된 파일의 경로를 담은 CompletableFuture
     */
    @JvmOverloads
    fun generateToFileFuture(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        password: String? = null
    ) = scope.future { generateToFile(template, dataProvider, outputDir, baseFileName, password) }

    // ========== 비동기 API (작업 관리) ==========

    /**
     * 비동기 작업을 제출하고 작업 핸들을 반환합니다.
     * API 서버에서 즉시 응답 후 백그라운드 처리에 적합합니다.
     *
     * @param template 템플릿 입력 스트림
     * @param dataProvider 데이터 제공자
     * @param outputDir 출력 디렉토리 경로
     * @param baseFileName 기본 파일명 (확장자 제외)
     * @param password 파일 열기 암호 (null 또는 빈 문자열이면 암호 없음)
     * @param listener 작업 진행 상태를 받을 리스너 (선택)
     * @return 작업 핸들 (취소, 완료 대기 등에 사용)
     */
    @JvmOverloads
    fun submit(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        outputDir: Path,
        baseFileName: String,
        password: String? = null,
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
                    template, dataProvider, outputDir, baseFileName, password
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

        // 수식 내 ${변수}를 포함하는 셀 추출 (JXLS가 처리하지 않도록)
        val (formulaExtractedBytes, formulaInfos) = extractFormulaVariables(processedBytes)

        // 레이아웃 및 데이터 유효성 검사 백업
        val layout = if (config.preserveTemplateLayout) {
            layoutProcessor.backup(ByteArrayInputStream(formulaExtractedBytes))
        } else null
        val dataValidations = dataValidationProcessor.backup(ByteArrayInputStream(formulaExtractedBytes))

        // 피벗 테이블 정보 추출 및 삭제 (JXLS 처리 전)
        val (pivotTableInfos, bytesWithoutPivot) = pivotTableProcessor.extractAndRemove(formulaExtractedBytes)

        // JXLS 처리 (피벗 테이블 없이)
        val jxlsOutput = ByteArrayOutputStream().also { tempOutput ->
            ByteArrayInputStream(bytesWithoutPivot).use { input ->
                processJxlsWithFormulaErrorDetection(input, tempOutput, context)
            }
        }

        // 저장해둔 수식 복원 (${변수} 패턴 유지)
        val formulaRestoredBytes = restoreFormulaVariables(jxlsOutput.toByteArray(), formulaInfos)

        // 후처리 (숫자 서식 적용, 레이아웃 복원, 데이터 유효성 검사 확장, 피벗 테이블 재생성, 변수 치환, 메타데이터 적용)
        val resultBytes = applyNumberFormatToNumericCells(formulaRestoredBytes)
            .let { bytes -> layout?.let { layoutProcessor.restore(bytes, it) } ?: bytes }
            .let { bytes -> dataValidationProcessor.expand(bytes, dataValidations) }
            .let { bytes -> pivotTableProcessor.recreate(bytes, pivotTableInfos) }
            .let { bytes -> xmlVariableProcessor.processVariables(bytes, dataProvider) }
            .let { bytes -> applyMetadata(bytes, dataProvider.getMetadata()) }

        output.write(resultBytes)
        return rowsProcessed
    }

    // ========== 문서 메타데이터 적용 ==========

    /**
     * 문서 메타데이터를 워크북에 적용합니다.
     */
    private fun applyMetadata(bytes: ByteArray, metadata: DocumentMetadata?): ByteArray {
        if (metadata == null || metadata.isEmpty()) return bytes

        return transformWorkbook(bytes) { workbook ->
            val props = workbook.properties
            val coreProps = props.coreProperties
            val extProps = props.extendedProperties.underlyingProperties

            metadata.title?.let { coreProps.setTitle(it) }
            metadata.author?.let { coreProps.setCreator(it) }
            metadata.subject?.let { coreProps.setSubjectProperty(it) }
            metadata.keywords?.let { coreProps.setKeywords(it.joinToString(", ")) }
            metadata.description?.let { coreProps.setDescription(it) }
            metadata.category?.let { coreProps.setCategory(it) }
            metadata.company?.let { extProps.company = it }
            metadata.manager?.let { extProps.manager = it }
            metadata.created?.let {
                coreProps.setCreated(Optional.of(Date.from(it.atZone(java.time.ZoneId.systemDefault()).toInstant())))
            }
        }
    }

    // ========== 수식 변수 보호 ==========

    // JXLS가 수식 내 ${변수}를 처리하지 않도록 수식을 별도 저장 후 JXLS 처리 후 복원
    private data class FormulaInfo(
        val sheetName: String,
        val rowIndex: Int,
        val columnIndex: Int,
        val formula: String,
        val style: Short
    )

    /**
     * ${변수} 패턴을 포함하는 수식 셀을 추출하고 빈 셀로 변환합니다.
     * 반환된 리스트는 JXLS 처리 후 restoreFormulaVariables에서 복원됩니다.
     */
    private fun extractFormulaVariables(bytes: ByteArray): Pair<ByteArray, List<FormulaInfo>> {
        val formulas = mutableListOf<FormulaInfo>()
        val newBytes = transformWorkbook(bytes) { workbook ->
            workbook.forEach { sheet ->
                sheet.forEach { row ->
                    row.forEach { cell ->
                        if (cell.cellType == CellType.FORMULA) {
                            val formula = cell.cellFormula
                            if ("\${" in formula) {
                                formulas.add(FormulaInfo(
                                    sheetName = sheet.sheetName,
                                    rowIndex = cell.rowIndex,
                                    columnIndex = cell.columnIndex,
                                    formula = formula,
                                    style = cell.cellStyle.index
                                ))
                                // 수식 셀을 빈 셀로 변환 (JXLS가 처리하지 않도록)
                                val style = cell.cellStyle
                                cell.setBlank()
                                cell.cellStyle = style
                            }
                        }
                    }
                }
            }
        }
        return newBytes to formulas
    }

    /**
     * JXLS 처리 후 저장해둔 수식을 다시 설정합니다.
     * 이후 XmlVariableProcessor가 변수를 실제 값으로 치환합니다.
     */
    private fun restoreFormulaVariables(bytes: ByteArray, formulas: List<FormulaInfo>): ByteArray {
        if (formulas.isEmpty()) return bytes

        return transformWorkbook(bytes) { workbook ->
            formulas.forEach { info ->
                val sheet = workbook.getSheet(info.sheetName) ?: return@forEach
                val row = sheet.getRow(info.rowIndex) ?: sheet.createRow(info.rowIndex)
                val cell = row.getCell(info.columnIndex) ?: row.createCell(info.columnIndex)
                cell.cellFormula = info.formula
                if (info.style >= 0) {
                    cell.cellStyle = workbook.getCellStyleAt(info.style.toInt())
                }
            }
        }
    }

    // ========== 숫자 서식 자동 적용 ==========

    // 숫자 서식 스타일 캐시
    private val numberStyleCache = mutableMapOf<XSSFWorkbook, MutableMap<String, XSSFCellStyle>>()

    /**
     * 숫자 값이 있는 셀 중 표시 형식이 "일반"인 경우 숫자 서식을 자동 적용합니다.
     * 조건부 서식 등 다른 서식은 그대로 유지됩니다.
     */
    private fun applyNumberFormatToNumericCells(bytes: ByteArray): ByteArray =
        transformWorkbook(bytes) { workbook ->
            workbook.forEach { sheet ->
                sheet.forEach { row ->
                    row.forEach { cell ->
                        if (cell.cellType == CellType.NUMERIC) {
                            val currentStyle = cell.cellStyle
                            // 표시 형식이 "일반"(0)인 경우에만 숫자 서식 적용
                            if (currentStyle.dataFormat.toInt() == 0) {
                                val value = cell.numericCellValue
                                val isInteger = value == value.toLong().toDouble()
                                cell.cellStyle = getOrCreateNumberStyle(
                                    workbook, currentStyle.index, isInteger
                                )
                            }
                        }
                    }
                }
            }
        }

    /**
     * 숫자 서식이 적용된 스타일을 생성하거나 캐시에서 반환합니다.
     */
    private fun getOrCreateNumberStyle(
        workbook: XSSFWorkbook,
        templateStyleIdx: Short?,
        isInteger: Boolean
    ): XSSFCellStyle {
        val cacheKey = "${templateStyleIdx ?: "none"}_${if (isInteger) "int" else "dec"}"
        val workbookCache = numberStyleCache.getOrPut(workbook) { mutableMapOf() }

        return workbookCache.getOrPut(cacheKey) {
            workbook.createCellStyle().apply {
                if (templateStyleIdx != null) {
                    cloneStyleFrom(workbook.getCellStyleAt(templateStyleIdx.toInt()))
                }
                dataFormat = if (isInteger) {
                    config.pivotIntegerFormatIndex
                } else {
                    config.pivotDecimalFormatIndex
                }
            }
        }
    }

    /**
     * ByteArray를 XSSFWorkbook으로 변환하고 처리 후 다시 ByteArray로 반환합니다.
     */
    private inline fun transformWorkbook(bytes: ByteArray, block: (XSSFWorkbook) -> Unit): ByteArray =
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            block(workbook)
            workbook.toByteArray()
        }

    // ========== JXLS 처리 ==========

    private fun processJxlsWithFormulaErrorDetection(
        input: InputStream,
        output: OutputStream,
        context: Context
    ) {
        val appender = FormulaErrorCapturingAppender().apply { start() }
        val logger = LoggerFactory.getLogger("org.jxls.transform.poi.PoiTransformer") as Logger
        val originalLevel = logger.level

        try {
            logger.addAppender(appender)

            JxlsHelper.getInstance()
                .setUseFastFormulaProcessor(true)
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
        sheet.cellSequence()
            .filter { it.cellType == CellType.STRING }
            .forEach { cell ->
                IMAGE_PLACEHOLDER_REGEX.find(cell.stringCellValue.orEmpty())?.let { match ->
                    val imageName = match.groupValues[1]
                    cell.setCellValue("")
                    if (cell.cellComment == null) {
                        cell.addJxImageComment(workbook, sheet, imageName)
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
        sheet.cellSequence()
            .filter { it.cellComment != null }
            .forEach { cell ->
                val comment = cell.cellComment
                val commentText = comment.string?.string ?: return@forEach

                if ("jx:image" in commentText.lowercase()) {
                    completeImageCommand(commentText, cell, sheet, dataProvider)
                        .takeIf { it != commentText }
                        ?.let { comment.string = workbook.creationHelper.createRichTextString(it) }
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
                        val imageType = imageBytes.detectImageTypeForPoi()
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

    /**
     * 출력 파일 경로를 결정합니다.
     * fileNamingMode에 따라 파일명을 생성하고, fileConflictPolicy에 따라 충돌을 처리합니다.
     */
    private fun resolveOutputPath(outputDir: Path, baseFileName: String): Path {
        val basePath = outputDir.resolve(generateFileName(baseFileName))

        return basePath.takeUnless { Files.exists(it) }
            ?: when (config.fileConflictPolicy) {
                FileConflictPolicy.ERROR -> throw FileAlreadyExistsException(
                    basePath.toFile(), null, "파일이 이미 존재합니다: $basePath"
                )
                FileConflictPolicy.SEQUENCE -> findAvailablePathWithSequence(outputDir, baseFileName)
            }
    }

    /**
     * 시퀀스 번호를 붙여 사용 가능한 파일 경로를 찾습니다.
     */
    private fun findAvailablePathWithSequence(outputDir: Path, baseFileName: String): Path {
        val baseNameWithSuffix = when (config.fileNamingMode) {
            FileNamingMode.NONE -> baseFileName
            FileNamingMode.TIMESTAMP -> "${baseFileName}_${currentTimestamp()}"
        }

        return generateSequence(1) { it + 1 }
            .take(10000)
            .map { outputDir.resolve("${baseNameWithSuffix}_$it.xlsx") }
            .firstOrNull { !Files.exists(it) }
            ?: throw IllegalStateException("시퀀스 번호가 한계(10000)를 초과했습니다: $baseNameWithSuffix")
    }

    private fun generateFileName(baseFileName: String) = when (config.fileNamingMode) {
        FileNamingMode.NONE -> "$baseFileName.xlsx"
        FileNamingMode.TIMESTAMP -> "${baseFileName}_${currentTimestamp()}.xlsx"
    }

    private fun currentTimestamp() =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern(config.timestampFormat))

    private fun getCellReference(row: Int, col: Int) =
        "${col.toColumnLetter()}${row + 1}"

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }

    // ========== 내부 유틸리티 ==========

    private fun Cell?.hasJxAreaComment() =
        this?.cellComment?.string?.string?.contains("jx:area", ignoreCase = true) == true

    companion object {
        private val IMAGE_PLACEHOLDER_REGEX = Regex("""\$\{image\.(\w+)}""")
    }
}
