package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.engine.rendering.TemplateRenderingEngine
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 크로스시트 수식 조정 e2e 회귀 테스트.
 *
 * "요약" 시트(반복 없음)가 "데이터" 시트의 반복 확장 영역을 SUM으로 참조할 때,
 * 데이터 확장에 맞춰 수식 범위가 조정되는지 검증한다(forward 참조: 앞 시트가 뒤 시트 참조).
 * 이전에는 SheetExpansionInfo가 프로덕션 경로에서 생성되지 않아 조정되지 않았다.
 */
class CrossSheetFormulaTest {

    private fun buildCrossSheetTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val summary = wb.createSheet("요약")
            val data = wb.createSheet("데이터")
            // 데이터 시트: employees repeat (A3:B3, B열에 salary)
            data.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            data.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // 요약 시트: 데이터 시트 B3:B3 합계 (단일 → 확장 기대). '데이터' 시트가 이미 생성된 뒤 설정.
            summary.createRow(0).also { r ->
                r.createCell(0).setCellValue("급여 합계")
                r.createCell(1).cellFormula = "SUM('데이터'!B3:B3)"
            }
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `크로스시트 수식이 다른 시트의 반복 확장에 맞춰 조정된다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildCrossSheetTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val formula = wb.getSheet("요약").getRow(0).getCell(1).cellFormula
            // 데이터 B3 → 3명 확장으로 B3:B5. 요약 수식도 B3:B5를 덮어야 한다
            assertTrue(
                formula.replace(" ", "").contains("B3:B5"),
                "크로스시트 수식이 확장되지 않았다: $formula"
            )
        }
    }

    /** 단일 시트에 repeat과 "자기 시트 접두사 참조" static 수식을 함께 둔 템플릿 */
    private fun buildSelfReferenceTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("데이터")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.salary}")
            }
            // repeat 아래의 static 요약: 자기 시트를 접두사로 명시한 참조
            sh.createRow(5).createCell(0).cellFormula = "SUM('데이터'!B3:B3)"
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `자기 시트를 접두사로 명시한 참조도 반복 확장에 맞춰 조정된다`() {
        val data = mapOf(
            "employees" to (1..3).map { mapOf("name" to "이름$it", "salary" to (3000 + it)) }
        )

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildSelfReferenceTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sheet = wb.getSheet("데이터")
            // 확장으로 static 행 위치가 밀렸을 수 있으므로 수식 셀을 순회로 찾는다
            val formula = (0..sheet.lastRowNum).firstNotNullOfOrNull { r ->
                sheet.getRow(r)?.getCell(0)?.takeIf { it.cellType == CellType.FORMULA }?.cellFormula
            } ?: error("자기 시트 참조 수식 셀을 찾지 못했다")
            assertTrue(
                formula.replace(" ", "").contains("B3:B5"),
                "자기 시트 접두사 참조가 확장되지 않았다: $formula"
            )
        }
    }
}
