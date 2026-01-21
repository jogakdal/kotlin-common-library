package com.hunet.common.excel

import com.hunet.common.excel.async.DefaultGenerationJob
import com.hunet.common.excel.async.ExcelGenerationListener
import com.hunet.common.excel.async.GenerationJob
import com.hunet.common.excel.async.GenerationResult
import com.hunet.common.excel.engine.TemplateRenderingEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
 * .xlsx 템플릿에 데이터를 바인딩하고 Excel 파일을 생성합니다.
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

    // 프로세서들
    private val pivotTableProcessor = PivotTableProcessor(config)
    private val xmlVariableProcessor = XmlVariableProcessor()
    private val chartProcessor = ChartProcessor()

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

    private data class GenerationContext(
        val data: Map<String, Any>,
        val rowsProcessed: Int
    )

    // ========== 핵심 처리 로직 ==========

    /**
     * 템플릿 처리
     *
     * 피벗 테이블/차트 처리 흐름:
     * 1. 스트리밍 모드에서 차트 추출 및 제거 (SXSSF에서 차트 손실 방지)
     * 2. 피벗 테이블 정보 추출 및 템플릿에서 제거
     * 3. TemplateRenderingEngine으로 반복 데이터 처리 (스트리밍 가능)
     * 4. 확장된 데이터 소스로 피벗 테이블 재생성
     * 5. 스트리밍 모드에서 차트 복원
     */
    private fun processTemplate(
        template: InputStream,
        dataProvider: ExcelDataProvider,
        output: OutputStream
    ): Int {
        val (_, rowsProcessed) = createContext(dataProvider)
        var workingBytes = template.readBytes()

        // 1. 스트리밍 모드에서 차트 추출 (SXSSF에서 차트 손실 방지)
        val chartInfo = if (config.streamingMode == StreamingMode.ENABLED) {
            val (info, bytesWithoutChart) = chartProcessor.extractAndRemove(workingBytes)
            workingBytes = bytesWithoutChart
            info
        } else null

        // 2. 피벗 테이블 추출 및 제거
        val (pivotTableInfos, bytesWithoutPivot) = pivotTableProcessor.extractAndRemove(workingBytes)

        // 3. TemplateRenderingEngine으로 처리 (스트리밍 모드 유지)
        val engine = TemplateRenderingEngine(config.streamingMode)
        var resultBytes = engine.process(ByteArrayInputStream(bytesWithoutPivot), dataProvider)

        // 4. 숫자 서식 자동 적용
        resultBytes = applyNumberFormatToNumericCells(resultBytes)

        // 5. XML 변수 치환 (수식 내 변수 등)
        resultBytes = xmlVariableProcessor.processVariables(resultBytes, dataProvider)

        // 6. 피벗 테이블 재생성 (확장된 데이터 소스 기반)
        if (pivotTableInfos.isNotEmpty()) {
            resultBytes = pivotTableProcessor.recreate(resultBytes, pivotTableInfos)
        }

        // 7. 차트 복원 (스트리밍 모드) - 변수 치환 포함
        if (chartInfo != null) {
            val variableResolver = xmlVariableProcessor.createVariableResolver(dataProvider)
            resultBytes = chartProcessor.restore(resultBytes, chartInfo, variableResolver)
        }

        // 8. 메타데이터 적용
        resultBytes = applyMetadata(resultBytes, dataProvider.getMetadata())

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

    // ========== 숫자 서식 자동 적용 ==========

    // 숫자 서식 스타일 캐시
    private val numberStyleCache = mutableMapOf<XSSFWorkbook, MutableMap<String, XSSFCellStyle>>()

    /**
     * 숫자 값이 있는 셀 중 표시 형식이 "일반"인 경우 숫자 서식을 자동 적용합니다.
     * 조건부 서식 등 다른 서식은 그대로 유지됩니다.
     */
    private fun applyNumberFormatToNumericCells(bytes: ByteArray): ByteArray =
        transformWorkbook(bytes) { workbook ->
            applyNumberFormatToNumericCellsInPlace(workbook)
        }

    private fun applyNumberFormatToNumericCellsInPlace(workbook: XSSFWorkbook) {
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

    // ========== 데이터 맵 생성 ==========

    private fun createContext(dataProvider: ExcelDataProvider): GenerationContext {
        val data = mutableMapOf<String, Any>()
        var totalRows = 0

        dataProvider.getAvailableNames().forEach { name ->
            dataProvider.getValue(name)?.let { data[name] = it }
            dataProvider.getItems(name)?.let { iterator ->
                val items = iterator.asSequence().toList()
                totalRows += items.size
                data[name] = items
            }
            dataProvider.getImage(name)?.let { data[name] = it }
        }

        return GenerationContext(data, totalRows)
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

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }

}
