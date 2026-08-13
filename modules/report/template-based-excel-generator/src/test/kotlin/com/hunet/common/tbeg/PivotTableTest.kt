package com.hunet.common.tbeg

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 피벗 테이블 기능 테스트
 */
class PivotTableTest {

    @Test
    fun `rowHeaderCaption should be preserved from template`() {
        // Given: 템플릿과 데이터 준비
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")
            ?: throw IllegalStateException("Template not found")

        val data = mapOf(
            "title" to "테스트",
            "date" to "2024-01-06",
            "employees" to listOf(
                mapOf("name" to "황용호", "position" to "부장", "salary" to 8000),
                mapOf("name" to "홍용호", "position" to "과장", "salary" to 6500),
                mapOf("name" to "한용호", "position" to "대리", "salary" to 4500)
            )
        )

        // When: Excel 생성
        val generator = ExcelGenerator()
        val bytes = generator.generate(template, data)

        // Then: 피벗 테이블의 rowHeaderCaption 확인
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            var foundCaption: String? = null
            var foundPivotTableName: String? = null

            for (sheetIndex in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIndex) as? org.apache.poi.xssf.usermodel.XSSFSheet
                val pivotTables = sheet?.pivotTables ?: continue

                for (pt in pivotTables) {
                    val def = pt.ctPivotTableDefinition
                    // rowHeaderCaption이 있는 피벗 테이블을 찾음
                    if (def.rowHeaderCaption != null) {
                        foundCaption = def.rowHeaderCaption
                        foundPivotTableName = def.name
                    }
                }
            }
            assertNotNull(foundCaption, "rowHeaderCaption이 설정되어야 합니다")
            assertEquals("직급", foundCaption, "rowHeaderCaption이 '직급'이어야 합니다")
        }

        generator.close()
    }

    @Test
    fun `피벗 refreshedDate는 Excel serial date로 기록되어야 한다`() {
        // Given: 피벗을 포함한 템플릿과 데이터
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")
            ?: throw IllegalStateException("Template not found")

        val data = mapOf(
            "title" to "테스트",
            "date" to "2024-01-06",
            "employees" to listOf(
                mapOf("name" to "황용호", "position" to "부장", "salary" to 8000),
                mapOf("name" to "홍용호", "position" to "과장", "salary" to 6500),
                mapOf("name" to "한용호", "position" to "대리", "salary" to 4500)
            )
        )

        // When: Excel 생성
        val generator = ExcelGenerator()
        val bytes = generator.generate(template, data)
        generator.close()

        // Then: POI는 refreshedDate에 밀리초 타임스탬프(약 1.7e12)를 기록하므로,
        // TBEG이 Excel serial date(정수부 4~5만대)로 역변환해야 한다.
        // POI 버전 업그레이드 시 이 역변환이 깨지면 피벗 날짜가 조용히 틀어지므로 회귀 방지로 검증한다.
        val cacheXml = readZipEntry(bytes, Regex("xl/pivotCache/pivotCacheDefinition\\d*\\.xml"))
            ?: throw AssertionError("pivotCacheDefinition.xml이 산출물에 존재해야 합니다")
        val refreshedDate = Regex("""refreshedDate="([\d.]+)"""")
            .find(cacheXml)?.groupValues?.get(1)?.toDouble()
        assertNotNull(refreshedDate, "refreshedDate 속성이 존재해야 합니다")
        assertTrue(
            refreshedDate!! in 1.0..1_000_000.0,
            "refreshedDate는 Excel serial date여야 하며 밀리초 타임스탬프가 아니어야 합니다: $refreshedDate"
        )
    }

    private fun readZipEntry(bytes: ByteArray, pattern: Regex): String? =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (pattern.matches(entry.name)) return zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
            null
        }
}
