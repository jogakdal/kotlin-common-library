package com.hunet.common.tbeg

import com.hunet.common.tbeg.async.ExcelGenerationListener
import com.hunet.common.tbeg.async.GenerationJob
import com.hunet.common.tbeg.async.GenerationResult
import com.hunet.common.tbeg.async.ProgressInfo
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 비동기 생성의 진행률 콜백 발화와 협조적 취소 회귀 테스트.
 *
 * `onProgress`/`checkCancelled` 훅이 submit → ProcessingContext → RenderingContext →
 * 렌더 루프까지 배선되어
 * (1) 진행률이 interval 간격으로 실제 발화되고
 * (2) 생성 도중 취소가 렌더 루프를 실제로 중단시키는지 검증한다.
 */
class ProgressAndCancellationTest {

    private lateinit var generator: ExcelGenerator

    @BeforeEach
    fun setUp() {
        generator = ExcelGenerator()
    }

    @AfterEach
    fun tearDown() {
        generator.close()
    }

    /** ${title} + repeat 마커 템플릿을 프로그램적으로 생성 */
    private fun buildTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("Data")
            sh.createRow(0).createCell(0).setCellValue("\${title}")
            sh.createRow(1).createCell(0).setCellValue("\${repeat(employees, A3:C3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.position}")
                r.createCell(2).setCellValue("\${emp.salary}")
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    private fun sampleData(rows: Int): Map<String, Any> = mapOf(
        "title" to "직원 명부",
        "employees" to (0 until rows).map {
            mapOf("name" to "이름$it", "position" to "직급${it % 5}", "salary" to (3000 + it))
        }
    )

    @Test
    fun `submit 시 진행률 콜백이 interval 간격으로 발화된다`() {
        // 기본 progressReportInterval = 100
        val progresses = Collections.synchronizedList(mutableListOf<Int>())
        val doneLatch = CountDownLatch(1)

        generator.submit(
            template = ByteArrayInputStream(buildTemplate()),
            data = sampleData(1000),
            listener = object : ExcelGenerationListener {
                override fun onProgress(jobId: String, progress: ProgressInfo) {
                    progresses.add(progress.processedRows)
                }

                override fun onCompleted(jobId: String, result: GenerationResult) {
                    doneLatch.countDown()
                }

                override fun onFailed(jobId: String, error: Exception) {
                    doneLatch.countDown()
                }
            }
        )

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "생성이 완료되지 않았다")

        val snapshot = progresses.toList()
        assertTrue(snapshot.isNotEmpty(), "진행률 콜백이 한 번도 호출되지 않았다")
        assertTrue(snapshot.all { it % 100 == 0 }, "진행률이 interval(100) 배수가 아니다: $snapshot")
        assertEquals(snapshot.sorted(), snapshot, "진행률이 단조 증가하지 않는다: $snapshot")
        assertTrue(
            snapshot.last() >= 1000,
            "마지막 진행률이 전체 데이터 행 수(1000)에 도달하지 못했다: ${snapshot.last()}"
        )
    }

    @Test
    fun `submit 도중 취소하면 렌더링이 중단되고 onCancelled가 호출된다`() {
        val cancelledLatch = CountDownLatch(1)
        val completedCalled = AtomicBoolean(false)
        val jobRef = AtomicReference<GenerationJob?>()

        val job = generator.submit(
            template = ByteArrayInputStream(buildTemplate()),
            data = sampleData(5000),
            listener = object : ExcelGenerationListener {
                override fun onProgress(jobId: String, progress: ProgressInfo) {
                    // 렌더 루프 진입(첫 진행률 발화)을 확인한 뒤 취소를 요청한다
                    jobRef.get()?.cancel()
                }

                override fun onCancelled(jobId: String) {
                    cancelledLatch.countDown()
                }

                override fun onCompleted(jobId: String, result: GenerationResult) {
                    completedCalled.set(true)
                }
            }
        )
        jobRef.set(job)

        assertTrue(cancelledLatch.await(30, TimeUnit.SECONDS), "취소가 반영되지 않았다 (onCancelled 미호출)")
        assertTrue(job.isCancelled, "job이 취소 상태가 아니다")
        assertFalse(completedCalled.get(), "취소되었는데 onCompleted가 호출되었다")
    }
}
