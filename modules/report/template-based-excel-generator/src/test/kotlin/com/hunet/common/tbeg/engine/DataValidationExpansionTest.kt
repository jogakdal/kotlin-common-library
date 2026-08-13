package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.engine.rendering.TemplateRenderingEngine
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 데이터 유효성이 반복 확장 시 확장 행 전체로 전파되는지 검증하는 회귀 테스트.
 *
 * 이전에는 데이터 유효성이 코드에서 전혀 처리되지 않아, 반복 확장 시 원본 셀(예 B6)에만
 * 남고 확장 행에는 적용되지 않았다(조건부 서식은 확장되나 유효성만 누락되는 비대칭).
 * 이제 반복 영역과 겹치는 유효성의 sqref를 확장 범위로 넓히고, 겹치지 않는 유효성은 유지한다.
 */
class DataValidationExpansionTest {

    private fun employees(count: Int) = (1..count).map {
        mapOf("name" to "이름$it", "position" to "과장", "salary" to (5000 + it))
    }

    /** 시트의 데이터 유효성 중 지정 열(col)을 덮는 행 인덱스 집합(0-based) */
    private fun validatedRows(sheet: XSSFSheet, col: Int): Set<Int> =
        sheet.dataValidations
            .flatMap { it.regions.cellRangeAddresses.toList() }
            .filter { it.firstColumn <= col && it.lastColumn >= col }
            .flatMap { (it.firstRow..it.lastRow) }
            .toSet()

    @Test
    fun `실제 템플릿에서 데이터 유효성이 반복 확장 행 전체로 전파된다`() {
        // template.xlsx "세로 확장(기본형)" 시트: B6 직급 드롭다운 + A6:C6 employees repeat
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!
        val data = mapOf(
            "title" to "유효성 확장 테스트",
            "date" to "2026-01-20",
            "employees" to employees(3),
            "mergedEmployees" to employees(3)
        )

        val result = TemplateRenderingEngine().process(template, data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sheet = wb.getSheet("세로 확장(기본형)")
                ?: error("세로 확장(기본형) 시트를 찾을 수 없다")
            val rows = validatedRows(sheet, col = 1)
            // B6(행5) 원본 + 확장된 B7(행6)·B8(행7)까지 유효성이 덮어야 한다
            assertTrue(
                rows.containsAll(setOf(5, 6, 7)),
                "유효성이 확장 행(B6:B8, 0-based 5~7)을 덮지 못했다: $rows"
            )
        }

        // 수동 검증용 산출물 저장 (Excel에서 B6 드롭다운이 확장 행까지 걸리는지 확인)
        val samplesDir = java.nio.file.Path.of("build/samples/data-validation")
        java.nio.file.Files.createDirectories(samplesDir)
        samplesDir.resolve("dv_expansion.xlsx").toFile().writeBytes(result)
    }

    /**
     * ${title} + repeat(A3:B3) 템플릿에 유효성 2개를 심는다.
     * - B3(반복 행, col=1): 확장 대상
     * - D1(반복 밖, col=3): 유지 대상
     */
    private fun buildTemplateWithValidations(): ByteArray =
        XSSFWorkbook().use { wb ->
            val sh = wb.createSheet("Data")
            sh.createRow(0).createCell(0).setCellValue("\${title}")
            sh.createRow(1).createCell(0).setCellValue("\${repeat(employees, A3:B3, emp)}")
            sh.createRow(2).also { r ->
                r.createCell(0).setCellValue("\${emp.name}")
                r.createCell(1).setCellValue("\${emp.position}")
            }
            val helper = sh.dataValidationHelper
            val listConstraint = helper.createExplicitListConstraint(arrayOf("사원", "과장", "부장"))
            // B3: 반복 데이터 행(row2, col1) → 확장 대상
            sh.addValidationData(helper.createValidation(listConstraint, CellRangeAddressList(2, 2, 1, 1)))
            // D1: 반복 밖(row0, col3) → 유지 대상
            sh.addValidationData(helper.createValidation(listConstraint, CellRangeAddressList(0, 0, 3, 3)))
            ByteArrayOutputStream().apply { wb.write(this) }.toByteArray()
        }

    @Test
    fun `반복과 겹치는 유효성만 확장되고 겹치지 않는 유효성은 유지된다`() {
        val data = mapOf("title" to "혼합 유효성", "employees" to employees(3))

        val result = TemplateRenderingEngine().process(ByteArrayInputStream(buildTemplateWithValidations()), data)

        XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
            val sheet = wb.getSheetAt(0)
            // B3(row2)이 employees 3명으로 확장 → B3:B5(row2~4)
            assertTrue(
                validatedRows(sheet, col = 1).containsAll(setOf(2, 3, 4)),
                "반복과 겹친 유효성(B3)이 B3:B5로 확장되지 않았다: ${validatedRows(sheet, col = 1)}"
            )
            // D1(row0)은 반복 밖이므로 원본 그대로
            assertEquals(
                setOf(0), validatedRows(sheet, col = 3),
                "반복 밖 유효성(D1)이 예기치 않게 확장됐다"
            )
        }
    }
}
