package com.hunet.common.excel

import com.hunet.common.excel.async.ExcelGenerationListener
import com.hunet.common.excel.async.GenerationResult
import kotlinx.coroutines.runBlocking
import org.apache.poi.openxml4j.opc.OPCPackage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import kotlin.io.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExcelGeneratorTest {

    private lateinit var generator: ExcelGenerator

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        generator = ExcelGenerator()
    }

    @AfterEach
    fun tearDown() {
        generator.close()
    }

    @Test
    fun `SimpleDataProvider of() should classify values and collections correctly`() {
        val data = mapOf(
            "title" to "테스트 보고서",
            "count" to 42,
            "items" to listOf("a", "b", "c")
        )

        val provider = SimpleDataProvider.of(data)

        assertEquals("테스트 보고서", provider.getValue("title"))
        assertEquals(42, provider.getValue("count"))
        assertNull(provider.getValue("items")) // List는 컬렉션으로 분류

        val items = provider.getItems("items")
        assertNotNull(items)
        assertEquals(listOf("a", "b", "c"), items!!.asSequence().toList())
    }

    @Test
    fun `SimpleDataProvider Builder should work correctly`() {
        val provider = SimpleDataProvider.Builder()
            .value("name", "황용호")
            .value("age", 30)
            .items("tags", listOf("kotlin", "java"))
            .build()

        assertEquals("황용호", provider.getValue("name"))
        assertEquals(30, provider.getValue("age"))
        assertEquals(listOf("kotlin", "java"), provider.getItems("tags")?.asSequence()?.toList())
    }

    @Test
    fun `simpleDataProvider DSL should work correctly`() {
        val provider = simpleDataProvider {
            value("title", "DSL 테스트")
            items("numbers") { listOf(1, 2, 3).iterator() }
            image("logo", byteArrayOf(1, 2, 3))
        }

        assertEquals("DSL 테스트", provider.getValue("title"))
        assertEquals(listOf(1, 2, 3), provider.getItems("numbers")?.asSequence()?.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), provider.getImage("logo"))
    }

    @Test
    fun `ExcelGeneratorConfig default should have expected values`() {
        val config = ExcelGeneratorConfig.default()

        assertEquals(StreamingMode.AUTO, config.streamingMode)
        assertEquals(1000, config.streamingRowThreshold)
        assertEquals("yyyyMMdd_HHmmss", config.timestampFormat)
    }

    @Test
    fun `ExcelGeneratorConfig forLargeData should use streaming mode`() {
        val config = ExcelGeneratorConfig.forLargeData()

        assertEquals(StreamingMode.ENABLED, config.streamingMode)
    }

    @Test
    fun `submit should return job with valid jobId`() {
        val latch = CountDownLatch(1)
        var completedJobId: String? = null

        // 빈 템플릿 스트림 (실제 JXLS 템플릿이 없으므로 실패할 것임)
        val templateStream = "".byteInputStream()
        val provider = SimpleDataProvider.empty()

        val job = generator.submit(
            template = templateStream,
            dataProvider = provider,
            outputDir = tempDir,
            baseFileName = "test",
            listener = object : ExcelGenerationListener {
                override fun onCompleted(jobId: String, result: GenerationResult) {
                    completedJobId = jobId
                    latch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    completedJobId = jobId
                    latch.countDown()
                }
            }
        )

        assertNotNull(job.jobId)
        assertTrue(job.jobId.isNotBlank())

        // 작업 완료 대기 (실패하더라도 리스너가 호출되어야 함)
        latch.await(5, TimeUnit.SECONDS)
        assertEquals(job.jobId, completedJobId)
    }

    @Test
    fun `generateAsync should work with coroutines`() = runBlocking {
        // 실제 JXLS 템플릿이 없으므로 예외가 발생할 것임
        // 이 테스트는 코루틴 통합이 올바르게 되었는지 확인
        val provider = SimpleDataProvider.of(mapOf("test" to "value"))

        var exceptionThrown = false
        try {
            generator.generateAsync("".byteInputStream(), provider)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown, "빈 템플릿에서는 예외가 발생해야 합니다")
    }

    @Test
    fun `cancel should stop the job`() {
        val templateStream = "".byteInputStream()
        val provider = SimpleDataProvider.empty()
        var cancelled = false

        val job = generator.submit(
            template = templateStream,
            dataProvider = provider,
            outputDir = tempDir,
            baseFileName = "cancel-test",
            listener = object : ExcelGenerationListener {
                override fun onCancelled(jobId: String) {
                    cancelled = true
                }
            }
        )

        // 즉시 취소
        val cancelResult = job.cancel()
        assertTrue(cancelResult)
        assertTrue(job.isCancelled)
    }

    // ==================== 실제 템플릿 기반 테스트 ====================

    private fun loadTemplate() =
        javaClass.getResourceAsStream("/templates/template.xlsx")
            ?: throw IllegalStateException("템플릿을 찾을 수 없습니다: /templates/template.xlsx")

    private fun loadImage(fileName: String): ByteArray? =
        javaClass.getResourceAsStream("/$fileName")?.readBytes()

    data class Employee(val name: String, val position: String, val salary: Int)

    private fun createTestData(employeeCount: Int = 3): Map<String, Any> {
        val employees = (1..employeeCount).map { i ->
            Employee("직원$i", listOf("사원", "대리", "과장")[i % 3], 3000 + i * 500)
        }

        return mutableMapOf(
            "title" to "테스트 보고서",
            "date" to "2024-01-07",
            "employees" to employees
        ).also { data ->
            loadImage("hunet_logo.png")?.let { data["logo"] = it }
            loadImage("hunet_ci.png")?.let { data["ci"] = it }
        }
    }

    @Test
    fun `generate should return valid excel bytes`() {
        val template = loadTemplate()
        val data = createTestData()

        val bytes = generator.generate(template, data)

        assertTrue(bytes.isNotEmpty())
        // XLSX 파일 시그니처 확인 (PK - ZIP 형식)
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `generateToFile should create file with timestamp`() {
        val template = loadTemplate()
        val data = createTestData()

        val resultPath = generator.generateToFile(
            template = template,
            dataProvider = SimpleDataProvider.of(data),
            outputDir = tempDir,
            baseFileName = "test_report"
        )

        assertTrue(Files.exists(resultPath))
        assertTrue(Files.size(resultPath) > 0)
        assertTrue(resultPath.fileName.toString().startsWith("test_report_"))
        assertTrue(resultPath.fileName.toString().endsWith(".xlsx"))
    }

    @Test
    fun `submit should return rowsProcessed in GenerationResult`() {
        val latch = CountDownLatch(1)
        var result: GenerationResult? = null
        val employeeCount = 5

        val template = loadTemplate()
        val data = createTestData(employeeCount)

        generator.submit(
            template = template,
            dataProvider = SimpleDataProvider.of(data),
            outputDir = tempDir,
            baseFileName = "rows_test",
            listener = object : ExcelGenerationListener {
                override fun onCompleted(jobId: String, generationResult: GenerationResult) {
                    result = generationResult
                    latch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    latch.countDown()
                }
            }
        )

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertNotNull(result)
        assertEquals(employeeCount, result!!.rowsProcessed)
        assertNotNull(result!!.filePath)
        assertTrue(result!!.durationMs > 0)
    }

    @Test
    fun `submit should call onStarted listener`() {
        val startedLatch = CountDownLatch(1)
        val completedLatch = CountDownLatch(1)
        var startedJobId: String? = null

        val template = loadTemplate()
        val data = createTestData()

        val job = generator.submit(
            template = template,
            dataProvider = SimpleDataProvider.of(data),
            outputDir = tempDir,
            baseFileName = "started_test",
            listener = object : ExcelGenerationListener {
                override fun onStarted(jobId: String) {
                    startedJobId = jobId
                    startedLatch.countDown()
                }

                override fun onCompleted(jobId: String, result: GenerationResult) {
                    completedLatch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    completedLatch.countDown()
                }
            }
        )

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(job.jobId, startedJobId)
        completedLatch.await(10, TimeUnit.SECONDS)
    }

    @Test
    fun `generateFuture should return CompletableFuture with valid bytes`() {
        val template = loadTemplate()
        val provider = SimpleDataProvider.of(createTestData())

        val future = generator.generateFuture(template, provider)
        val bytes = future.get(10, TimeUnit.SECONDS)

        assertTrue(bytes.isNotEmpty())
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `generateToFileFuture should create file asynchronously`() {
        val template = loadTemplate()
        val provider = SimpleDataProvider.of(createTestData())

        val future = generator.generateToFileFuture(template, provider, tempDir, "future_test")
        val resultPath = future.get(10, TimeUnit.SECONDS)

        assertTrue(Files.exists(resultPath))
        assertTrue(Files.size(resultPath) > 0)
    }

    @Test
    fun `generateAsync should return valid bytes with real template`() = runBlocking {
        val template = loadTemplate()
        val provider = SimpleDataProvider.of(createTestData())

        val bytes = generator.generateAsync(template, provider)

        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `generateToFileAsync should create file with coroutines`() = runBlocking {
        val template = loadTemplate()
        val provider = SimpleDataProvider.of(createTestData())

        val resultPath = generator.generateToFileAsync(template, provider, tempDir, "async_test")

        assertTrue(Files.exists(resultPath))
        assertTrue(Files.size(resultPath) > 0)
    }

    @Test
    fun `ExcelGenerator should be usable with use block`() {
        val template = loadTemplate()
        val data = createTestData()

        val resultPath = ExcelGenerator().use { gen ->
            gen.generateToFile(
                template = template,
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "use_block_test"
            )
        }

        assertTrue(Files.exists(resultPath))
    }

    @Test
    fun `large data should report correct rowsProcessed`() {
        val latch = CountDownLatch(1)
        var result: GenerationResult? = null
        val largeCount = 100

        val template = loadTemplate()
        val provider = simpleDataProvider {
            value("title", "대용량 테스트")
            value("date", "2024-01-07")
            items("employees") {
                (1..largeCount).map { i ->
                    Employee("직원$i", "직급", 3000)
                }.iterator()
            }
        }

        generator.submit(
            template = template,
            dataProvider = provider,
            outputDir = tempDir,
            baseFileName = "large_rows_test",
            listener = object : ExcelGenerationListener {
                override fun onCompleted(jobId: String, generationResult: GenerationResult) {
                    result = generationResult
                    latch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    error.printStackTrace()
                    latch.countDown()
                }
            }
        )

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        assertNotNull(result)
        assertEquals(largeCount, result!!.rowsProcessed)
    }

    // ==================== ChartProcessor 변수 치환 테스트 ====================

    @Test
    fun `chart title variable should be replaced`() {
        val template = loadTemplate()
        val data = createTestData()

        val bytes = generator.generate(template, data)

        // 생성된 Excel에서 chart1.xml 내용 추출
        OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg ->
            val chartPart = pkg.parts.find { it.partName.name == "/xl/charts/chart1.xml" }
            assertNotNull(chartPart, "차트가 존재해야 합니다")

            val chartXml = chartPart!!.inputStream.bufferedReader().readText()
            // ${title} 변수가 "테스트 보고서"로 치환되었는지 확인
            assertFalse(chartXml.contains("\${title}"), "차트에서 \${title} 변수가 치환되어야 합니다")
            assertTrue(chartXml.contains("테스트 보고서"), "차트에 '테스트 보고서' 텍스트가 있어야 합니다")
        }
    }

    @Test
    fun `drawing shape variable should be replaced`() {
        val template = loadTemplate()
        val data = createTestData()

        val bytes = generator.generate(template, data)

        // 생성된 Excel에서 drawing1.xml 내용 추출
        OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg ->
            val drawingPart = pkg.parts.find { it.partName.name == "/xl/drawings/drawing1.xml" }
            assertNotNull(drawingPart, "도형이 존재해야 합니다")

            val drawingXml = drawingPart!!.inputStream.bufferedReader().readText()
            // ${title} 변수가 치환되었는지 확인
            assertFalse(drawingXml.contains("\${title}"), "도형에서 \${title} 변수가 치환되어야 합니다")
            assertTrue(drawingXml.contains("테스트 보고서"), "도형에 '테스트 보고서' 텍스트가 있어야 합니다")
            // ${date} 변수도 치환되었는지 확인
            assertFalse(drawingXml.contains("\${date}"), "도형에서 \${date} 변수가 치환되어야 합니다")
        }
    }

    @Test
    fun `all xml files with variables should be processed`() {
        val template = loadTemplate()
        val data = createTestData()

        val bytes = generator.generate(template, data)

        // 생성된 Excel의 모든 XML 파일에서 ${...} 패턴 검색
        OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg ->
            val variablePattern = Regex("""\$\{(title|date)}""")
            val partsWithUnreplacedVars = mutableListOf<String>()

            // XML 파일만 필터링하여 순회
            for (part in pkg.parts) {
                if (part.partName.name.endsWith(".xml")) {
                    try {
                        val content = part.inputStream.bufferedReader().readText()
                        if (variablePattern.containsMatchIn(content)) {
                            partsWithUnreplacedVars.add(part.partName.name)
                        }
                    } catch (e: Exception) {
                        // 일부 스트림 읽기 실패는 무시
                    }
                }
            }

            assertTrue(
                partsWithUnreplacedVars.isEmpty(),
                "다음 파일에 치환되지 않은 변수가 있습니다: $partsWithUnreplacedVars"
            )
        }
    }

    // ==================== FileNamingMode / FileConflictPolicy 테스트 ====================

    @Test
    fun `FileNamingMode NONE should create file without timestamp`() {
        val config = ExcelGeneratorConfig(fileNamingMode = FileNamingMode.NONE)
        ExcelGenerator(config).use { gen ->
            val template = loadTemplate()
            val data = createTestData()

            val resultPath = gen.generateToFile(
                template = template,
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "no_timestamp_test"
            )

            assertEquals("no_timestamp_test.xlsx", resultPath.fileName.toString())
            assertTrue(Files.exists(resultPath))
        }
    }

    @Test
    fun `FileNamingMode TIMESTAMP should create file with timestamp`() {
        val config = ExcelGeneratorConfig(fileNamingMode = FileNamingMode.TIMESTAMP)
        ExcelGenerator(config).use { gen ->
            val template = loadTemplate()
            val data = createTestData()

            val resultPath = gen.generateToFile(
                template = template,
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "timestamp_test"
            )

            assertTrue(resultPath.fileName.toString().startsWith("timestamp_test_"))
            assertTrue(resultPath.fileName.toString().endsWith(".xlsx"))
            assertTrue(Files.exists(resultPath))
        }
    }

    @Test
    fun `FileConflictPolicy ERROR should throw exception when file exists`() {
        val config = ExcelGeneratorConfig(
            fileNamingMode = FileNamingMode.NONE,
            fileConflictPolicy = FileConflictPolicy.ERROR
        )

        // 먼저 파일 생성
        val existingFile = tempDir.resolve("conflict_test.xlsx")
        Files.createFile(existingFile)

        ExcelGenerator(config).use { gen ->
            val template = loadTemplate()
            val data = createTestData()

            assertThrows(FileAlreadyExistsException::class.java) {
                gen.generateToFile(
                    template = template,
                    dataProvider = SimpleDataProvider.of(data),
                    outputDir = tempDir,
                    baseFileName = "conflict_test"
                )
            }
        }
    }

    @Test
    fun `FileConflictPolicy SEQUENCE should add sequence number when file exists`() {
        val config = ExcelGeneratorConfig(
            fileNamingMode = FileNamingMode.NONE,
            fileConflictPolicy = FileConflictPolicy.SEQUENCE
        )

        ExcelGenerator(config).use { gen ->
            val template = loadTemplate()
            val data = createTestData()

            // 첫 번째 파일 생성
            val firstPath = gen.generateToFile(
                template = template,
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "sequence_test"
            )
            assertEquals("sequence_test.xlsx", firstPath.fileName.toString())

            // 두 번째 파일 생성 (충돌 발생 -> 시퀀스 추가)
            val secondPath = gen.generateToFile(
                template = loadTemplate(),
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "sequence_test"
            )
            assertEquals("sequence_test_1.xlsx", secondPath.fileName.toString())

            // 세 번째 파일 생성
            val thirdPath = gen.generateToFile(
                template = loadTemplate(),
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "sequence_test"
            )
            assertEquals("sequence_test_2.xlsx", thirdPath.fileName.toString())

            assertTrue(Files.exists(firstPath))
            assertTrue(Files.exists(secondPath))
            assertTrue(Files.exists(thirdPath))
        }
    }

    @Test
    fun `FileConflictPolicy SEQUENCE with TIMESTAMP should add sequence after timestamp`() {
        val config = ExcelGeneratorConfig(
            fileNamingMode = FileNamingMode.TIMESTAMP,
            fileConflictPolicy = FileConflictPolicy.SEQUENCE,
            timestampFormat = "yyyyMMdd"  // 날짜만 사용하여 충돌 유발
        )

        ExcelGenerator(config).use { gen ->
            val template = loadTemplate()
            val data = createTestData()

            // 첫 번째 파일 생성
            val firstPath = gen.generateToFile(
                template = template,
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "ts_seq_test"
            )
            assertTrue(firstPath.fileName.toString().matches(Regex("ts_seq_test_\\d{8}\\.xlsx")))

            // 두 번째 파일 생성 (같은 날짜면 충돌 -> 시퀀스 추가)
            val secondPath = gen.generateToFile(
                template = loadTemplate(),
                dataProvider = SimpleDataProvider.of(data),
                outputDir = tempDir,
                baseFileName = "ts_seq_test"
            )
            assertTrue(secondPath.fileName.toString().matches(Regex("ts_seq_test_\\d{8}_1\\.xlsx")))

            assertTrue(Files.exists(firstPath))
            assertTrue(Files.exists(secondPath))
        }
    }
}
