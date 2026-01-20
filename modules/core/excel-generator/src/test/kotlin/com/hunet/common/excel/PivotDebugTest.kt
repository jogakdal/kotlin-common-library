package com.hunet.common.excel

import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class PivotDebugTest {

    data class Employee(val name: String, val position: String, val salary: Int)

    @Test
    fun `피벗 테이블 스타일 디버그`() {
        val outputDir = Path.of("build/samples/pivot-debug")
        Files.createDirectories(outputDir)

        val config = ExcelGeneratorConfig(
            templateEngine = TemplateEngine.SIMPLE,
            streamingMode = StreamingMode.ENABLED
        )

        val dataProvider = simpleDataProvider {
            value("title", "피벗 디버그")
            value("date", LocalDate.now().toString())
            value("linkText", "테스트")
            value("url", "https://test.com")
            image("logo", loadImage("hunet_logo.png") ?: byteArrayOf())
            image("ci", loadImage("hunet_ci.png") ?: byteArrayOf())

            items("employees") {
                listOf(
                    Employee("홍길동", "부장", 8000),
                    Employee("김철수", "과장", 6500),
                    Employee("이영희", "대리", 4500)
                ).iterator()
            }
        }

        val template = javaClass.getResourceAsStream("/templates/template.xlsx")!!

        ExcelGenerator(config).use { generator ->
            val resultPath = generator.generateToFile(template, dataProvider, outputDir, "pivot_debug")
            println("결과 파일: $resultPath")

            // 결과 파일의 피벗 테이블 스타일 분석
            val resultBytes = Files.readAllBytes(resultPath)
            XSSFWorkbook(ByteArrayInputStream(resultBytes)).use { workbook ->
                workbook.sheetIterator().asSequence()
                    .filterIsInstance<XSSFSheet>()
                    .forEach { sheet ->
                        sheet.pivotTables.orEmpty().forEach { pivotTable ->
                            val name = pivotTable.ctPivotTableDefinition?.name ?: "Unknown"
                            val location = pivotTable.ctPivotTableDefinition?.location?.ref ?: "Unknown"
                            val styleInfo = pivotTable.ctPivotTableDefinition?.pivotTableStyleInfo

                            println("\n=== 피벗 테이블: $name ===")
                            println("위치: $location")
                            println("스타일 이름: ${styleInfo?.name}")

                            val range = CellRangeAddress.valueOf(location)

                            // 헤더 행
                            println("\n[헤더 행 (${range.firstRow})]")
                            sheet.getRow(range.firstRow)?.let { row ->
                                (range.firstColumn..range.lastColumn).forEach { colIdx ->
                                    row.getCell(colIdx)?.let { cell ->
                                        val style = cell.cellStyle as? XSSFCellStyle
                                        println("  열 $colIdx: Bold=${style?.font?.bold}, " +
                                            "FillColor=${style?.fillForegroundXSSFColor?.argbHex}, " +
                                            "FillPattern=${style?.fillPattern}, " +
                                            "BorderBottom=${style?.borderBottom}")
                                    }
                                }
                            }

                            // Grand Total 행
                            println("\n[Grand Total 행 (${range.lastRow})]")
                            sheet.getRow(range.lastRow)?.let { row ->
                                (range.firstColumn..range.lastColumn).forEach { colIdx ->
                                    row.getCell(colIdx)?.let { cell ->
                                        val style = cell.cellStyle as? XSSFCellStyle
                                        println("  열 $colIdx: Bold=${style?.font?.bold}, " +
                                            "FillColor=${style?.fillForegroundXSSFColor?.argbHex}, " +
                                            "BorderTop=${style?.borderTop}, " +
                                            "BorderBottom=${style?.borderBottom}")
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun loadImage(fileName: String): ByteArray? =
        javaClass.getResourceAsStream("/$fileName")?.use { it.readBytes() }
}
