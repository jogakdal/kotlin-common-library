package com.hunet.common.tbeg

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * ZipStreamPost의 raw copy 재작성 회귀 테스트.
 *
 * SXSSF 산출물의 data-descriptor(size=0)는 `java.util.zip.ZipInputStream`이 거부한다.
 * ZipStreamPost가 `ZipFile` 기반으로 후처리 필요 엔트리만 재압축하고 나머지(특히 대용량 sheet)를
 * raw copy하면서, 그 과정에서 size를 정규화하므로 최종 산출물은 `java.util.zip`으로도 열려야 한다.
 *
 * 이 테스트는 피벗 숫자서식이 없는 일반 repeat 케이스(= sheet가 raw copy되는 경로)를 대상으로
 * java.util.zip 호환 + POI 무결성 + 데이터 보존을 검증한다. (sheet가 재압축되는 경로는
 * `StylesSchemaComplianceTest`가 실제 산출물을 java.util.zip으로 열어 이미 커버한다.)
 */
class ZipStreamPostRawCopyTest {

    private lateinit var generator: ExcelGenerator

    @BeforeEach
    fun setUp() {
        generator = ExcelGenerator()
    }

    @AfterEach
    fun tearDown() {
        generator.close()
    }

    /** ${title} + repeat 마커 템플릿을 프로그램적으로 생성 (ZipExp에서 검증된 배치) */
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

    /** java.util.zip으로 전 엔트리를 순회할 수 있는지 (raw copy의 size 정규화 검증) */
    private fun countJdkZipEntries(bytes: ByteArray): Int =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var count = 0
            while (true) {
                zis.nextEntry ?: break
                zis.copyTo(OutputStream.nullOutputStream())
                count++
            }
            count
        }

    @Test
    fun `일반 repeat 산출물이 java_util_zip으로 열리고 데이터가 보존된다`() {
        val result = generator.generate(
            template = ByteArrayInputStream(buildTemplate()),
            data = sampleData(5000)
        )

        // (a) size 정규화: java.util.zip 순회가 예외 없이 완료되어야 한다
        assertTrue(countJdkZipEntries(result) > 0, "java.util.zip으로 엔트리를 읽지 못했다")

        // (b) POI 무결성 + (c) 데이터 보존
        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sh = wb.getSheetAt(0)
            assertEquals("직원 명부", sh.getRow(0).getCell(0).stringCellValue, "title이 보존되지 않았다")
            val lastEmployeeFound = (0..sh.lastRowNum).any {
                sh.getRow(it)?.getCell(0)?.stringCellValue == "이름4999"
            }
            assertTrue(lastEmployeeFound, "마지막 직원 데이터(이름4999)가 확장되지 않았다")
        }
    }

    @Test
    fun `피벗이 없으면 자동 숫자 서식이 렌더링 시점에 적용된다`() {
        val result = generator.generate(
            template = ByteArrayInputStream(buildTemplate()),
            data = sampleData(100)
        )
        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sh = wb.getSheetAt(0)
            val empRow = (0..sh.lastRowNum).firstNotNullOf { r ->
                sh.getRow(r)?.takeIf { it.getCell(0)?.stringCellValue == "이름0" }
            }
            val salaryCell = empRow.getCell(2)
            assertEquals(3000.0, salaryCell.numericCellValue, 0.0)
            assertEquals(
                3, salaryCell.cellStyle.dataFormat.toInt(),
                "서식 없는 정수 셀에 자동 숫자 서식(#,##0, 인덱스 3)이 렌더링 시점에 적용되어야 한다"
            )
        }
    }
}
