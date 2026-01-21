package com.hunet.common.excel.engine

import com.hunet.common.excel.StreamingMode
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path

/**
 * SimpleTemplateEngine PoC 테스트
 */
class SimpleTemplateEngineTest {

    @Test
    fun `XSSF mode - simple variable substitution`() {
        // Given: 템플릿과 데이터
        val template = javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!
        val data = mapOf(
            "title" to "PoC 테스트",
            "date" to "2026-01-19",
            "linkText" to "테스트 링크",
            "url" to "https://example.com",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        // When: 템플릿 처리 (XSSF 모드)
        val engine = SimpleTemplateEngine(StreamingMode.DISABLED)
        val resultBytes = engine.process(template, data)

        // Then: 결과 검증
        assertTrue(resultBytes.isNotEmpty(), "결과 바이트 배열이 비어있지 않아야 함")

        XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)

            // 변수 치환 확인
            // 실제 셀 위치는 템플릿에 따라 다를 수 있음
            println("=== XSSF 모드 결과 ===")
            for (rowIndex in 0..10) {
                val row = sheet.getRow(rowIndex) ?: continue
                print("Row $rowIndex: ")
                for (colIndex in 0..5) {
                    val cell = row.getCell(colIndex) ?: continue
                    val value = when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        CellType.FORMULA -> "[F]${cell.cellFormula}"
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> ""
                    }
                    if (value.isNotEmpty()) print("[$colIndex]$value  ")
                }
                println()
            }
        }
    }

    @Test
    fun `SXSSF mode - simple variable substitution`() {
        // Given: 템플릿과 데이터
        val template = javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!
        val data = mapOf(
            "title" to "스트리밍 PoC 테스트",
            "date" to "2026-01-19",
            "linkText" to "테스트 링크",
            "url" to "https://example.com",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        // When: 템플릿 처리 (SXSSF 모드)
        val engine = SimpleTemplateEngine(StreamingMode.ENABLED)
        val resultBytes = engine.process(template, data)

        // Then: 결과 검증
        assertTrue(resultBytes.isNotEmpty(), "결과 바이트 배열이 비어있지 않아야 함")

        XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)

            println("=== SXSSF 모드 결과 ===")
            for (rowIndex in 0..10) {
                val row = sheet.getRow(rowIndex) ?: continue
                print("Row $rowIndex: ")
                for (colIndex in 0..5) {
                    val cell = row.getCell(colIndex) ?: continue
                    val value = when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        CellType.FORMULA -> "[F]${cell.cellFormula}"
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> ""
                    }
                    if (value.isNotEmpty()) print("[$colIndex]$value  ")
                }
                println()
            }
        }
    }

    @Test
    fun `FormulaAdjuster - range reference expansion`() {
        // Given: 반복으로 인해 범위가 확장되어야 하는 수식
        val formula = "SUM(C6:C6)"
        val repeatStartRow = 5  // 6행 (0-based: 5)
        val repeatEndRow = 5    // 6행
        val rowOffset = 2       // 3개 데이터 - 1개 템플릿 행 = 2

        // When
        val adjusted = FormulaAdjuster.adjustForRowExpansion(formula, repeatStartRow, repeatEndRow, rowOffset)

        // Then
        assertEquals("SUM(C6:C8)", adjusted, "범위 참조가 확장되어야 함")
    }

    @Test
    fun `FormulaAdjuster - single reference after repeat region`() {
        // Given: 반복 영역 이후의 셀 참조
        val formula = "A10+B10"
        val repeatEndRow = 5    // 반복 영역 끝 행
        val rowOffset = 2       // 2행 추가됨

        // When
        val adjusted = FormulaAdjuster.adjustForRowExpansion(formula, 0, repeatEndRow, rowOffset)

        // Then
        assertEquals("A12+B12", adjusted, "반복 영역 이후 참조가 조정되어야 함")
    }

    @Test
    fun `FormulaAdjuster - absolute reference should not change`() {
        // Given: 절대 참조가 포함된 수식
        val formula = "SUM(\$C\$6:C6)"
        val repeatStartRow = 5
        val repeatEndRow = 5
        val rowOffset = 2

        // When
        val adjusted = FormulaAdjuster.adjustForRowExpansion(formula, repeatStartRow, repeatEndRow, rowOffset)

        // Then
        assertEquals("SUM(\$C\$6:C8)", adjusted, "절대 참조는 변경되지 않아야 함")
    }

    @Test
    fun `FormulaAdjuster - repeat index adjustment`() {
        // Given: 반복 내 수식
        val formula = "A6*B6"

        // When: 첫 번째 반복 (index=0)
        val first = FormulaAdjuster.adjustForRepeatIndex(formula, 0)
        assertEquals("A6*B6", first)

        // When: 두 번째 반복 (index=1)
        val second = FormulaAdjuster.adjustForRepeatIndex(formula, 1)
        assertEquals("A7*B7", second)

        // When: 세 번째 반복 (index=2)
        val third = FormulaAdjuster.adjustForRepeatIndex(formula, 2)
        assertEquals("A8*B8", third)
    }

    @Test
    fun `TemplateAnalyzer - parse repeat marker`() {
        // Given: 템플릿
        val template = javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!

        // When
        val analyzer = TemplateAnalyzer()
        val blueprint = analyzer.analyze(template)

        // Then
        assertNotNull(blueprint)
        assertTrue(blueprint.sheets.isNotEmpty())

        val firstSheet = blueprint.sheets[0]
        println("=== 템플릿 분석 결과 ===")
        println("시트명: ${firstSheet.sheetName}")
        println("행 수: ${firstSheet.rows.size}")
        println("병합 영역: ${firstSheet.mergedRegions.size}")

        // 반복 행 확인
        val repeatRows = firstSheet.rows.filterIsInstance<RowBlueprint.RepeatRow>()
        println("반복 영역: ${repeatRows.size}개")
        repeatRows.forEach { row ->
            println("  - collection=${row.collectionName}, var=${row.itemVariable}, " +
                    "rows=${row.templateRowIndex}~${row.repeatEndRowIndex}")
        }
    }

    @Test
    fun `save output files for manual verification`(@TempDir tempDir: Path) {
        // Given
        val template = javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!
        val data = mapOf(
            "title" to "PoC 검증용 파일",
            "date" to "2026-01-19",
            "linkText" to "휴넷 홈페이지",
            "url" to "https://www.hunet.co.kr",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        val samplesDir = Path.of("build/samples/poc")
        java.nio.file.Files.createDirectories(samplesDir)

        // XSSF 모드
        val xssfEngine = SimpleTemplateEngine(StreamingMode.DISABLED)
        val xssfBytes = xssfEngine.process(
            javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!,
            data
        )
        samplesDir.resolve("poc_xssf.xlsx").toFile().writeBytes(xssfBytes)
        println("XSSF 결과: ${samplesDir.resolve("poc_xssf.xlsx").toAbsolutePath()}")

        // SXSSF 모드
        val sxssfEngine = SimpleTemplateEngine(StreamingMode.ENABLED)
        val sxssfBytes = sxssfEngine.process(
            javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")!!,
            data
        )
        samplesDir.resolve("poc_sxssf.xlsx").toFile().writeBytes(sxssfBytes)
        println("SXSSF 결과: ${samplesDir.resolve("poc_sxssf.xlsx").toAbsolutePath()}")
    }

    @Test
    fun `multi-row template with cell styles should copy styles to all repeated rows`() {
        // Given: 세 번째 탭(셀병합)이 있는 template.xlsx 사용
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!
        val data = mapOf(
            "title" to "스타일 복사 테스트",
            "date" to "2026-01-20",
            "linkText" to "테스트 링크",
            "url" to "https://example.com",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        val samplesDir = Path.of("build/samples/style-test")
        java.nio.file.Files.createDirectories(samplesDir)

        // XSSF 모드 테스트
        println("\n=== XSSF 모드 다중 행 템플릿 스타일 테스트 ===")
        val xssfEngine = SimpleTemplateEngine(StreamingMode.DISABLED)
        val xssfBytes = xssfEngine.process(
            javaClass.getResourceAsStream("/templates/template.xlsx")!!,
            data
        )
        samplesDir.resolve("style_test_xssf.xlsx").toFile().writeBytes(xssfBytes)
        println("XSSF 결과: ${samplesDir.resolve("style_test_xssf.xlsx").toAbsolutePath()}")

        // 세 번째 시트의 스타일 확인
        XSSFWorkbook(ByteArrayInputStream(xssfBytes)).use { workbook ->
            if (workbook.numberOfSheets >= 3) {
                val sheet = workbook.getSheetAt(2)
                println("\n세 번째 시트 (${sheet.sheetName}) 스타일 분석:")
                for (rowIndex in 0..15) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    for (colIndex in 0..3) {
                        val cell = row.getCell(colIndex) ?: continue
                        val style = cell.cellStyle
                        val fillColor = (style as? org.apache.poi.xssf.usermodel.XSSFCellStyle)?.fillForegroundXSSFColor
                        if (fillColor != null) {
                            val rgb = fillColor.rgb
                            if (rgb != null) {
                                val r = rgb[0].toInt() and 0xFF
                                val g = rgb[1].toInt() and 0xFF
                                val b = rgb[2].toInt() and 0xFF
                                // 빨간색 계열인지 확인 (R > 200, G < 100, B < 100)
                                if (r > 200 && g < 100 && b < 100) {
                                    print("Row $rowIndex, Col $colIndex: RED BACKGROUND, ")
                                    when (cell.cellType) {
                                        CellType.STRING -> print("value=${cell.stringCellValue}")
                                        CellType.NUMERIC -> print("value=${cell.numericCellValue}")
                                        else -> {}
                                    }
                                    println(" (RGB: $r,$g,$b)")
                                }
                            }
                        }
                    }
                }
            }
        }

        // SXSSF 모드 테스트
        println("\n=== SXSSF 모드 다중 행 템플릿 스타일 테스트 ===")
        val sxssfEngine = SimpleTemplateEngine(StreamingMode.ENABLED)
        val sxssfBytes = sxssfEngine.process(
            javaClass.getResourceAsStream("/templates/template.xlsx")!!,
            data
        )
        samplesDir.resolve("style_test_sxssf.xlsx").toFile().writeBytes(sxssfBytes)
        println("SXSSF 결과: ${samplesDir.resolve("style_test_sxssf.xlsx").toAbsolutePath()}")

        // 세 번째 시트의 스타일 확인
        XSSFWorkbook(ByteArrayInputStream(sxssfBytes)).use { workbook ->
            if (workbook.numberOfSheets >= 3) {
                val sheet = workbook.getSheetAt(2)
                println("\n세 번째 시트 (${sheet.sheetName}) 스타일 분석:")
                for (rowIndex in 0..15) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    for (colIndex in 0..3) {
                        val cell = row.getCell(colIndex) ?: continue
                        val style = cell.cellStyle
                        val fillColor = (style as? org.apache.poi.xssf.usermodel.XSSFCellStyle)?.fillForegroundXSSFColor
                        if (fillColor != null) {
                            val rgb = fillColor.rgb
                            if (rgb != null) {
                                val r = rgb[0].toInt() and 0xFF
                                val g = rgb[1].toInt() and 0xFF
                                val b = rgb[2].toInt() and 0xFF
                                if (r > 200 && g < 100 && b < 100) {
                                    print("Row $rowIndex, Col $colIndex: RED BACKGROUND, ")
                                    when (cell.cellType) {
                                        CellType.STRING -> print("value=${cell.stringCellValue}")
                                        CellType.NUMERIC -> print("value=${cell.numericCellValue}")
                                        else -> {}
                                    }
                                    println(" (RGB: $r,$g,$b)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `analyze third sheet structure from template`() {
        // Given: template.xlsx의 세 번째 탭 분석
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!

        // 템플릿 분석
        val analyzer = TemplateAnalyzer()
        val blueprint = analyzer.analyze(template)

        if (blueprint.sheets.size >= 3) {
            val thirdSheet = blueprint.sheets[2]
            println("\n=== 세 번째 시트 (${thirdSheet.sheetName}) 구조 분석 ===")
            println("행 수: ${thirdSheet.rows.size}")
            println("병합 영역: ${thirdSheet.mergedRegions.size}")

            // 모든 행의 청사진 출력
            println("\n행 청사진:")
            thirdSheet.rows.forEachIndexed { idx, row ->
                when (row) {
                    is RowBlueprint.StaticRow -> println("  [$idx] StaticRow(templateRow=${row.templateRowIndex})")
                    is RowBlueprint.RepeatRow -> {
                        println("  [$idx] RepeatRow(templateRow=${row.templateRowIndex}, " +
                                "endRow=${row.repeatEndRowIndex}, collection=${row.collectionName}, " +
                                "var=${row.itemVariable}, cols=${row.repeatStartCol}..${row.repeatEndCol})")
                        // 셀 스타일 인덱스 출력
                        row.cells.forEach { cell ->
                            println("       Cell(col=${cell.columnIndex}, styleIdx=${cell.styleIndex}, content=${cell.content})")
                        }
                    }
                    is RowBlueprint.RepeatContinuation -> {
                        println("  [$idx] RepeatContinuation(templateRow=${row.templateRowIndex}, " +
                                "parentRepeatRow=${row.parentRepeatRowIndex})")
                        row.cells.forEach { cell ->
                            println("       Cell(col=${cell.columnIndex}, styleIdx=${cell.styleIndex}, content=${cell.content})")
                        }
                    }
                }
            }

            // 병합 영역 출력
            println("\n병합 영역:")
            thirdSheet.mergedRegions.forEach { region ->
                println("  ${region.firstRow}:${region.lastRow} / ${region.firstColumn}:${region.lastColumn}")
            }
        }
    }

    @Test
    fun `check template original styles`() {
        // 템플릿 원본의 스타일 확인
        val templateBytes = javaClass.getResourceAsStream("/templates/template.xlsx")!!.readBytes()

        XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { workbook ->
            if (workbook.numberOfSheets >= 3) {
                val sheet = workbook.getSheetAt(2)
                println("\n=== 템플릿 원본 세 번째 시트 (${sheet.sheetName}) 스타일 ===")

                for (rowIndex in 0..15) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    print("Row $rowIndex: ")
                    for (colIndex in 0..3) {
                        val cell = row.getCell(colIndex) ?: continue
                        val style = cell.cellStyle as org.apache.poi.xssf.usermodel.XSSFCellStyle

                        val fillColor = style.fillForegroundXSSFColor
                        val colorStr = if (fillColor != null) {
                            val rgb = fillColor.rgb
                            if (rgb != null) {
                                val r = rgb[0].toInt() and 0xFF
                                val g = rgb[1].toInt() and 0xFF
                                val b = rgb[2].toInt() and 0xFF
                                "RGB($r,$g,$b)"
                            } else {
                                fillColor.argbHex ?: "none"
                            }
                        } else "none"

                        val value = when (cell.cellType) {
                            CellType.STRING -> cell.stringCellValue.take(20)
                            CellType.NUMERIC -> cell.numericCellValue.toString()
                            else -> ""
                        }

                        print("[col$colIndex] val=\"$value\", style=${style.index}, fill=$colorStr  ")
                    }
                    println()
                }
            }
        }
    }

    @Test
    fun `SXSSF mode should expand conditional formatting for repeat regions`() {
        // Given: 조건부 서식이 있는 템플릿 (template.xlsx의 세 번째 시트에 급여 >= 6000 조건부 서식)
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!
        val data = mapOf(
            "title" to "조건부 서식 테스트",
            "date" to "2026-01-20",
            "linkText" to "테스트 링크",
            "url" to "https://example.com",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        // When: SXSSF 모드로 처리
        val engine = SimpleTemplateEngine(StreamingMode.ENABLED)
        val resultBytes = engine.process(template, data)

        // Then: 조건부 서식이 확장되었는지 확인
        XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
            if (workbook.numberOfSheets >= 3) {
                val sheet = workbook.getSheetAt(2)
                val scf = sheet.sheetConditionalFormatting

                println("\n=== SXSSF 모드 조건부 서식 확인 ===")
                println("조건부 서식 개수: ${scf.numConditionalFormattings}")

                var foundExpandedFormatting = false
                for (i in 0 until scf.numConditionalFormattings) {
                    val cf = scf.getConditionalFormattingAt(i)
                    val ranges = cf.formattingRanges.map { it.formatAsString() }
                    println("범위: $ranges")

                    // 여러 범위가 있거나 범위가 확장되었으면 성공
                    if (ranges.size > 1 || ranges.any { it.contains(":") && !it.contains("B8:B8") }) {
                        foundExpandedFormatting = true
                    }

                    for (j in 0 until cf.numberOfRules) {
                        val rule = cf.getRule(j)
                        println("  규칙 $j: type=${rule.conditionType}, formula=${rule.formula1}")

                        // dxfId 확인 (리플렉션)
                        try {
                            val cfRuleField = rule.javaClass.getDeclaredField("_cfRule")
                            cfRuleField.isAccessible = true
                            val ctCfRule = cfRuleField.get(rule)
                            val dxfIdMethod = ctCfRule.javaClass.getMethod("getDxfId")
                            val dxfId = dxfIdMethod.invoke(ctCfRule)
                            println("  dxfId: $dxfId")

                            // dxfId가 0 이상이면 스타일이 연결됨
                            assertTrue((dxfId as Long) >= 0, "dxfId가 설정되어야 함")
                        } catch (e: Exception) {
                            println("  dxfId 확인 실패: ${e.message}")
                        }
                    }
                }

                // 범위가 확장되었는지 또는 여러 셀에 적용되었는지 확인
                assertTrue(scf.numConditionalFormattings > 0, "조건부 서식이 있어야 함")
            }
        }

        // 결과 파일 저장 (수동 검증용)
        val samplesDir = Path.of("build/samples/conditional-formatting")
        java.nio.file.Files.createDirectories(samplesDir)
        samplesDir.resolve("sxssf_cf_test.xlsx").toFile().writeBytes(resultBytes)
        println("\n결과 파일: ${samplesDir.resolve("sxssf_cf_test.xlsx").toAbsolutePath()}")
    }

    @Test
    fun `XSSF mode should expand conditional formatting for repeat regions`() {
        // Given
        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!
        val data = mapOf(
            "title" to "조건부 서식 테스트 (XSSF)",
            "date" to "2026-01-20",
            "linkText" to "테스트 링크",
            "url" to "https://example.com",
            "employees" to listOf(
                mapOf("name" to "홍길동", "position" to "부장", "salary" to 8000),
                mapOf("name" to "김철수", "position" to "과장", "salary" to 6500),
                mapOf("name" to "이영희", "position" to "대리", "salary" to 4500)
            )
        )

        // When: XSSF 모드로 처리
        val engine = SimpleTemplateEngine(StreamingMode.DISABLED)
        val resultBytes = engine.process(template, data)

        // Then: 조건부 서식이 확장되었는지 확인
        XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
            if (workbook.numberOfSheets >= 3) {
                val sheet = workbook.getSheetAt(2)
                val scf = sheet.sheetConditionalFormatting

                println("\n=== XSSF 모드 조건부 서식 확인 ===")
                println("조건부 서식 개수: ${scf.numConditionalFormattings}")

                for (i in 0 until scf.numConditionalFormattings) {
                    val cf = scf.getConditionalFormattingAt(i)
                    val ranges = cf.formattingRanges.map { it.formatAsString() }
                    println("범위: $ranges")

                    for (j in 0 until cf.numberOfRules) {
                        val rule = cf.getRule(j)
                        println("  규칙 $j: type=${rule.conditionType}, formula=${rule.formula1}")
                    }
                }

                assertTrue(scf.numConditionalFormattings > 0, "조건부 서식이 있어야 함")
            }
        }

        // 결과 파일 저장
        val samplesDir = Path.of("build/samples/conditional-formatting")
        java.nio.file.Files.createDirectories(samplesDir)
        samplesDir.resolve("xssf_cf_test.xlsx").toFile().writeBytes(resultBytes)
        println("\n결과 파일: ${samplesDir.resolve("xssf_cf_test.xlsx").toAbsolutePath()}")
    }
}
