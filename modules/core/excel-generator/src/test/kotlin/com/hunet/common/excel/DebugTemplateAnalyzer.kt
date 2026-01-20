package com.hunet.common.excel

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class DebugTemplateAnalyzerTest {

    data class Employee(val name: String, val position: String, val salary: Int)

    @Test
    fun `test simple engine with debug`() {
        val templateStream = javaClass.getResourceAsStream("/templates/template.xlsx")
            ?: throw IllegalStateException("템플릿 파일을 찾을 수 없습니다")

        val data = mapOf(
            "title" to "테스트 제목",
            "date" to "2026-01-19",
            "linkText" to "테스트 링크",
            "url" to "https://test.com",
            "employees" to listOf(
                Employee("홍길동", "부장", 8000),
                Employee("김철수", "과장", 6500),
                Employee("이영희", "대리", 4500),
                Employee("박민수", "사원", 3500)
            )
        )

        val output = StringBuilder()
        output.appendLine("=== Input Data ===")
        data.forEach { (k, v) ->
            output.appendLine("  $k: ${if (v is List<*>) "List(${v.size})" else v}")
        }
        output.appendLine()

        // 템플릿 원본 피벗 테이블 서식 분석
        output.appendLine("=== 템플릿 원본 피벗 테이블 서식 (I6:K8 영역) ===")
        val templateBytes = javaClass.getResourceAsStream("/templates/template.xlsx")!!.readBytes()
        XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { templateWorkbook ->
            val templateSheet = templateWorkbook.getSheetAt(0)
            for (rowIndex in 5..7) { // I6:K8 (0-based: row 5-7)
                val row = templateSheet.getRow(rowIndex) ?: continue
                for (colIndex in 8..10) { // I-K (0-based: col 8-10)
                    val cell = row.getCell(colIndex) ?: continue
                    val addr = "${('A' + colIndex)}${rowIndex + 1}"
                    val style = cell.cellStyle as? XSSFCellStyle
                    val font = style?.let { templateWorkbook.getFontAt(it.fontIndex) }
                    output.appendLine("  $addr: bold=${font?.bold}, align=${style?.alignment}, fill=${style?.fillPattern}, " +
                        "fillColor=${style?.fillForegroundXSSFColor?.argbHex}, border=${style?.borderBottom}")
                }
            }
        }
        output.appendLine()

        // ExcelGenerator를 통해 호출 (SimpleTemplateEngineSample과 동일한 경로)
        val config = ExcelGeneratorConfig(
            templateEngine = TemplateEngine.SIMPLE,
            streamingMode = StreamingMode.DISABLED
        )
        val dataProvider = SimpleDataProvider.of(data)

        output.appendLine("=== DataProvider Check ===")
        output.appendLine("  availableNames: ${dataProvider.getAvailableNames()}")
        output.appendLine("  getValue('title'): ${dataProvider.getValue("title")}")
        output.appendLine("  getItems('employees'): ${dataProvider.getItems("employees")?.asSequence()?.toList()?.size} items")
        output.appendLine()

        // ExcelGenerator를 통해 호출 (PivotTableProcessor 포함)
        val generator = ExcelGenerator(config)
        val resultBytes = generator.generate(templateStream, dataProvider)

        // 결과 파일 저장
        val outputPath = Path.of("/tmp/debug_output.xlsx")
        Files.write(outputPath, resultBytes)
        output.appendLine("Output file: $outputPath")

        // 결과 분석
        XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            output.appendLine("\n=== Generated Sheet: ${sheet.sheetName} ===")
            output.appendLine("Total rows: ${sheet.lastRowNum + 1}")

            // 피벗 테이블 영역 서식 분석 (I6:K11)
            output.appendLine("\n=== 결과 피벗 테이블 서식 (I6:K11 영역) ===")
            for (rowIndex in 5..10) {
                val row = sheet.getRow(rowIndex) ?: continue
                for (colIndex in 8..10) {
                    val cell = row.getCell(colIndex) ?: continue
                    val addr = "${('A' + colIndex)}${rowIndex + 1}"
                    val style = cell.cellStyle as? XSSFCellStyle
                    val font = style?.let { workbook.getFontAt(it.fontIndex) }
                    val value = when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        else -> ""
                    }
                    output.appendLine("  $addr [$value]: bold=${font?.bold}, align=${style?.alignment}, fill=${style?.fillPattern}, " +
                        "fillColor=${style?.fillForegroundXSSFColor?.argbHex}, border=${style?.borderBottom}")
                }
            }
            output.appendLine()

            for (rowIndex in 0..minOf(15, sheet.lastRowNum)) {
                val row = sheet.getRow(rowIndex) ?: continue
                output.appendLine("Row $rowIndex:")
                row.forEach { cell ->
                    val value = when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        CellType.FORMULA -> "FORMULA: ${cell.cellFormula}"
                        CellType.BLANK -> "(blank)"
                        else -> cell.toString()
                    }
                    if (value != "(blank)") {
                        output.appendLine("  ${cell.address}: $value")
                    }
                }
            }
        }

        File("/tmp/debug_output.txt").writeText(output.toString())
        println(output.toString())
    }
}
