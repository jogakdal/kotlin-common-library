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

    /** repeat 아래(합계·요약 영역)를 가리키는 named range가 있는 템플릿 */
    private fun buildBelowRefTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // repeat 아래(A8)를 가리키는 named range — 확장에 밀려 위치가 이동해야 한다
            wb.createName().apply {
                nameName = "belowRef"
                refersToFormula = "'데이터'!\$A\$8:\$A\$8"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `repeat 아래를 가리키는 named range는 확장량만큼 시프트된다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildBelowRefTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("belowRef")?.refersToFormula
                ?: error("named range belowRef를 찾지 못했다")
            // A8(row7)은 1행 repeat가 3명으로 확장(+2)되며 A10으로 밀려야 한다
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("A10"),
                "repeat 아래 named range가 시프트되지 않았다: $refers"
            )
        }
    }

    /** 한 시트에 반복이 둘: 위쪽(first) 반복 아래에 아래쪽(second) 반복이 있고, named range가 second를 가리킴 */
    private fun buildMultiRepeatTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            // 위쪽 반복(first): 마커 A1, 데이터 A2:B2
            sh.createRow(0).createCell(0).setCellValue("\${repeat(first, A2:B2, f)}")
            sh.createRow(1).also { r ->
                r.createCell(0).setCellValue("\${f.x}")
                r.createCell(1).setCellValue("\${f.y}")
            }
            // 아래쪽 반복(second): 마커 A4, 데이터 A5:B5
            sh.createRow(3).createCell(0).setCellValue("\${repeat(second, A5:B5, s)}")
            sh.createRow(4).also { r ->
                r.createCell(0).setCellValue("\${s.x}")
                r.createCell(1).setCellValue("\${s.y}")
            }
            // named range = 아래쪽 반복(second)의 데이터 열 (B5, 절대)
            wb.createName().apply {
                nameName = "secondData"
                refersToFormula = "'데이터'!\$B\$5:\$B\$5"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `다중 반복에서 뒤쪽 반복의 named range는 앞 반복 확장까지 반영해 이동·확장된다`() {
        val data = mapOf(
            "first" to (1..3).map { mapOf("x" to "f$it", "y" to it) },
            "second" to (1..4).map { mapOf("x" to "s$it", "y" to it) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildMultiRepeatTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("secondData")?.refersToFormula
                ?: error("named range secondData를 찾지 못했다")
            // first(3개, +2)가 second를 아래로 밀어 second 데이터는 row6부터(B7), 4개 확장 → B7:B10.
            // 시작이 앞 반복 확장만큼 시프트되지 않으면 B5:B... 로 어긋난다.
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("B7:B10"),
                "다중 반복에서 named range 시작이 앞 반복 확장만큼 시프트되지 않았다: $refers"
            )
        }
    }
}
