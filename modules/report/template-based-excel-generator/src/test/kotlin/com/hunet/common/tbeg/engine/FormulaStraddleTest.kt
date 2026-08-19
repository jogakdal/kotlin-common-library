package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.engine.rendering.TemplateRenderingEngine
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 수식이 repeat를 세로로 관통하는 범위(시작=데이터 영역, 끝=그 아래)를 참조할 때의 동작 확인.
 * named range에서 발견한 관통 버그가 수식 조정에도 있는지 검증한다.
 */
class FormulaStraddleTest {

    private fun buildTemplate(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("S")
            sh.createRow(0).createCell(0).setCellValue("\${repeat(items, A3:B3, x)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${x.a}")
                r.createCell(1).setCellValue("\${x.b}")
            }
            // repeat 아래 static 수식: B3(데이터) ~ B10(아래)을 관통 참조
            sh.createRow(11).createCell(0).cellFormula = "SUM(B3:B10)"
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `수식이 repeat를 관통하는 범위를 참조하면 끝이 확장량만큼 밀린다`() {
        val data = mapOf("items" to (1..3).map { mapOf("a" to "a$it", "b" to it) })

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildTemplate()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sh = wb.getSheet("S")
            val formula = (0..sh.lastRowNum).firstNotNullOfOrNull { r ->
                sh.getRow(r)?.getCell(0)?.takeIf { it.cellType == CellType.FORMULA }?.cellFormula
            } ?: error("수식 셀을 찾지 못했다")
            // B3(데이터 시작 유지) ~ B10이 확장(+2)에 밀려 B12 → SUM(B3:B12)
            assertTrue(
                formula.replace(" ", "").contains("B3:B12"),
                "수식 관통 참조의 끝이 확장량만큼 밀리지 않았다: $formula"
            )
        }
    }
}
