@file:Suppress("NonAsciiCharacters")

package com.hunet.common.tbeg.engine

import com.hunet.common.tbeg.ExcelGenerator
import com.hunet.common.tbeg.simpleDataProvider
import com.hunet.common.tbeg.engine.rendering.parser.MarkerValidationException
import com.hunet.common.tbeg.exception.TemplateProcessingException
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 영역 겹침 검증기 회귀 테스트.
 *
 * 반복·묶음(bundle)·hideable 영역이 서로 부분적으로 걸치면 생성이 예외로 거부되어야 한다.
 * 이 검증은 확장 시 레이아웃이 어긋나거나 데이터가 조용히 누락되는 것을 막는 안전장치다.
 *
 * - repeat × repeat 겹침            -> PositionCalculator.validateNoOverlap (TemplateProcessingException)
 * - bundle × bundle 겹침            -> TemplateAnalyzer.validateBundleRegions (TemplateProcessingException)
 * - repeat이 bundle 경계 부분 걸침    -> TemplateAnalyzer.validateBundleRegions (TemplateProcessingException)
 * - hideable bundle이 병합 셀 부분 포함 -> HideValidator (MarkerValidationException)
 */
@DisplayName("영역 겹침 검증 회귀 테스트")
class AreaOverlapValidationTest {

    private val generator = ExcelGenerator()

    private fun template(block: XSSFWorkbook.() -> Unit): ByteArray =
        XSSFWorkbook().use { wb ->
            wb.block()
            ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
        }

    private fun generate(template: ByteArray, data: Map<String, Any>) =
        generator.generate(ByteArrayInputStream(template), data)

    @Test
    @DisplayName("repeat 영역이 서로 부분적으로 겹치면 예외")
    fun repeatOverlap() {
        val tpl = template {
            createSheet("Sheet1").apply {
                createRow(0).apply {
                    createCell(0).setCellValue("\${a}")   // A1 (repeat1 영역 안)
                    createCell(2).setCellValue("\${b}")   // C1 (repeat2 영역 안)
                }
                createRow(4).createCell(0).setCellValue("\${repeat(listA, A1:B1, a)}")  // 영역 A1:B1
                createRow(5).createCell(0).setCellValue("\${repeat(listB, B1:C1, b)}")  // 영역 B1:C1 (B1 겹침)
            }
        }
        val ex = assertThrows<TemplateProcessingException> {
            generate(tpl, mapOf("listA" to listOf("v1"), "listB" to listOf("v2")))
        }
        assertTrue(
            ex.message?.contains("overlap", ignoreCase = true) == true,
            "repeat 겹침 메시지여야 한다: ${ex.message}"
        )
    }

    @Test
    @DisplayName("bundle 영역이 서로 겹치면 예외")
    fun bundleOverlap() {
        val tpl = template {
            createSheet("Sheet1").apply {
                createRow(9).createCell(0).setCellValue("\${bundle(A1:B5)}")   // 영역 A1:B5
                createRow(10).createCell(0).setCellValue("\${bundle(B3:C8)}")  // 영역 B3:C8 (B3:B5 겹침)
            }
        }
        val ex = assertThrows<TemplateProcessingException> {
            generate(tpl, emptyMap())
        }
        assertTrue(
            ex.message?.contains("Bundle regions overlap") == true,
            "bundle 겹침 메시지여야 한다: ${ex.message}"
        )
    }

    @Test
    @DisplayName("repeat이 bundle 경계를 부분적으로 걸치면 예외")
    fun repeatStraddlesBundle() {
        val tpl = template {
            createSheet("Sheet1").apply {
                createRow(1).createCell(0).setCellValue("\${x}")  // A2 (repeat 영역 안)
                createRow(8).createCell(0).setCellValue("\${bundle(A1:B3)}")            // 영역 A1:B3
                createRow(9).createCell(0).setCellValue("\${repeat(list, A2:B4, x)}")   // 영역 A2:B4 (bundle 경계 걸침)
            }
        }
        val ex = assertThrows<TemplateProcessingException> {
            generate(tpl, mapOf("list" to listOf("v1")))
        }
        assertTrue(
            ex.message?.contains("partially overlaps a bundle boundary") == true,
            "repeat-bundle 부분 걸침 메시지여야 한다: ${ex.message}"
        )
    }

    @Test
    @DisplayName("hideable bundle이 병합 셀을 부분 포함하면 예외")
    fun hideableBundlePartiallyCoversMergedCell() {
        val tpl = template {
            createSheet("Sheet1").apply {
                addMergedRegion(CellRangeAddress(0, 0, 1, 2))  // B1:C1 병합
                createRow(1).apply {
                    createCell(0).setCellValue("\${x.name}")                                 // A2
                    createCell(2).setCellValue("\${hideable(value=x.salary, bundle=C1:C5)}")  // C2, bundle C1:C5가 B1:C1을 부분 포함
                }
                createRow(5).createCell(0).setCellValue("\${repeat(list, A2:E2, x)}")        // repeat A2:E2
            }
        }
        // hideable 검증은 hideFields가 지정되어야 동작한다(미지정 시 일반 필드로 처리)
        val provider = simpleDataProvider {
            items("list", listOf(mapOf("name" to "n1", "salary" to 100)))
            hideFields("list", "salary")
        }
        val ex = assertThrows<MarkerValidationException> {
            generator.generate(ByteArrayInputStream(tpl), provider)
        }
        assertTrue(
            ex.message?.contains("병합 셀") == true,
            "병합 셀 부분 포함 메시지여야 한다: ${ex.message}"
        )
    }
}
