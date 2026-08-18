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

    /** 같은 행에 병렬로 놓인 두 반복(확장량이 다름)과 그 아래 공통 named range */
    private fun buildParallelRepeatTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            // 병렬 반복: left(A2:B2, 마커 A1), right(D2:E2, 마커 D1) — 같은 행, 다른 열
            sh.createRow(0).also { r ->
                r.createCell(0).setCellValue("\${repeat(left, A2:B2, l)}")
                r.createCell(3).setCellValue("\${repeat(right, D2:E2, rt)}")
            }
            sh.createRow(1).also { r ->
                r.createCell(0).setCellValue("\${l.a}")
                r.createCell(1).setCellValue("\${l.b}")
                r.createCell(3).setCellValue("\${rt.a}")
                r.createCell(4).setCellValue("\${rt.b}")
            }
            // 병렬 반복 아래 공통 area (A10:E10) — 두 반복에 모두 걸침
            wb.createName().apply {
                nameName = "footer"
                refersToFormula = "'데이터'!\$A\$10:\$E\$10"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `병렬 반복 아래 공통 named range는 가장 많이 밀리는 영역 기준으로 조정된다`() {
        val data = mapOf(
            "left" to (1..3).map { mapOf("a" to "la$it", "b" to it) },
            "right" to (1..5).map { mapOf("a" to "ra$it", "b" to it) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildParallelRepeatTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("footer")?.refersToFormula
                ?: error("named range footer를 찾지 못했다")
            // left(+2)·right(+4) 병렬 → 공통 footer는 max(+4)로 직사각형을 통째 이동: A10:E10 → A14:E14.
            // 열별로 다른 오프셋을 적용하면 A12:E14 같은 비대칭이 되어 직사각형이 깨진다.
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("A14:E14"),
                "병렬 반복 아래 공통 area가 max offset(셀 병합 방식)으로 조정되지 않았다: $refers"
            )
        }
    }

    /** RIGHT(가로) 반복: 데이터 열(B2:B4)이 오른쪽으로 확장 */
    private fun buildRightRepeatTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            // RIGHT 반복: 마커 A1, 데이터 B2:B4 (1열 3행이 오른쪽으로 반복)
            sh.createRow(0).createCell(0).setCellValue("\${repeat(items, B2:B4, x, RIGHT)}")
            sh.createRow(1).createCell(1).setCellValue("\${x.a}")
            sh.createRow(2).createCell(1).setCellValue("\${x.b}")
            sh.createRow(3).createCell(1).setCellValue("\${x.c}")
            // named range = RIGHT 반복 데이터 열(B2:B4) — 오른쪽으로 열이 확대되어야 함
            wb.createName().apply {
                nameName = "rightData"
                refersToFormula = "'데이터'!\$B\$2:\$B\$4"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `RIGHT 반복 데이터 열을 가리키는 named range는 오른쪽으로 확대된다`() {
        val data = mapOf(
            "items" to (1..3).map { mapOf("a" to "a$it", "b" to "b$it", "c" to "c$it") }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildRightRepeatTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("rightData")?.refersToFormula
                ?: error("named range rightData를 찾지 못했다")
            // B2:B4가 items 3개로 오른쪽 확대 → B2:D4
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("B2:D4"),
                "RIGHT 반복 데이터 named range가 열 방향으로 확대되지 않았다: $refers"
            )
        }
    }

    /** 단일 셀 named range(범위 아님)와 다중 영역(union) named range를 함께 둔 템플릿 */
    private fun buildSingleAndMultiTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // 단일 셀(범위 아님), repeat 아래 → 시프트 대상
            wb.createName().apply {
                nameName = "single"
                refersToFormula = "'데이터'!\$A\$8"
            }
            // 다중 영역(union) → AreaReference 단일 파싱 실패로 안전하게 미조정(원본 유지)
            wb.createName().apply {
                nameName = "multi"
                refersToFormula = "'데이터'!\$A\$8,'데이터'!\$C\$8"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `단일 셀 named range는 시프트되고 다중 영역 named range는 원본을 유지한다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildSingleAndMultiTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            // 단일 셀 A8(row7)은 repeat 확장(+2)에 밀려 A10으로 시프트
            val single = wb.getName("single")?.refersToFormula ?: error("single을 찾지 못했다")
            assertTrue(
                single.replace("$", "").replace(" ", "").contains("A10"),
                "단일 셀 named range가 시프트되지 않았다: $single"
            )
            // 다중 영역은 조정 대상이 아니므로 원본(A8, C8) 유지 — 오조정하지 않는지 확인
            val multi = wb.getName("multi")?.refersToFormula ?: error("multi를 찾지 못했다")
            assertTrue(
                multi.replace("$", "").replace(" ", "").contains("A8") &&
                    multi.replace("$", "").replace(" ", "").contains("C8"),
                "다중 영역 named range가 예기치 않게 변경됐다: $multi"
            )
        }
    }

    /** repeat를 세로로 관통하는 named range(시작은 데이터 영역, 끝은 그 아래) */
    private fun buildStraddleTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // B3(데이터 영역) ~ B10(그 아래)을 한 범위로 묶은 관통 named range
            wb.createName().apply {
                nameName = "straddle"
                refersToFormula = "'데이터'!\$B\$3:\$B\$10"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `repeat를 관통하는 named range는 끝이 확장량만큼 밀린다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildStraddleTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val refers = wb.getName("straddle")?.refersToFormula
                ?: error("named range straddle을 찾지 못했다")
            // 이상적: 시작 B3 유지(데이터 영역), 끝 B10은 확장(+2)에 밀려 B12 → B3:B12
            assertTrue(
                refers.replace("$", "").replace(" ", "").contains("B3:B12"),
                "관통 named range의 끝이 확장량만큼 밀리지 않았다(실제): $refers"
            )
        }
    }
}
