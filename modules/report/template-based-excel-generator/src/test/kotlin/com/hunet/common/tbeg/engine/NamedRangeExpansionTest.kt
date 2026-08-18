package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.engine.rendering.TemplateRenderingEngine
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * named range 정의 확장 e2e 회귀 테스트.
 *
 * 수식이 named range를 이름으로 참조(`=SUM(salaryData)`)할 때, 그 named range 정의가
 * 가리키는 범위가 repeat 영역과 겹치면 확장 후 범위로 정의(refersToFormula)가 갱신되는지 검증한다.
 * 수식 문자열은 건드리지 않고 정의만 재작성한다(문자열 리터럴 오조정 리스크 없음).
 */
class NamedRangeExpansionTest {

    private fun buildNamedRangeTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // named range 정의 (B3 = salary 데이터 영역, 절대 참조) — 수식보다 먼저 생성해야 파싱 가능
            wb.createName().apply {
                nameName = "salaryData"
                refersToFormula = "'데이터'!\$B\$3:\$B\$3"
            }
            // 요약 셀: named range를 이름으로 참조하는 수식
            sh.createRow(5).createCell(0).cellFormula = "SUM(salaryData)"
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `수식이 참조하는 named range 정의가 반복 확장에 맞춰 갱신된다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildNamedRangeTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("salaryData")?.refersToFormula
                ?: error("named range salaryData를 찾지 못했다")
            // B3 → 3명 확장으로 B3:B5. 정의 끝이 B5까지 넓어져야 한다
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("B3:B5"),
                "named range 정의가 확장되지 않았다: $refers"
            )
        }
    }
}
