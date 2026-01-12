package com.hunet.common.excel

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * 템플릿 구조 확인용 유틸리티
 */
object TemplateInspector {
    @JvmStatic
    fun main(args: Array<String>) {
        val templateStream = TemplateInspector::class.java.getResourceAsStream("/templates/template.xlsx")
            ?: throw IllegalStateException("템플릿을 찾을 수 없습니다")

        templateStream.use { stream ->
            val workbook = XSSFWorkbook(stream)

            println("총 시트 수: ${workbook.numberOfSheets}")
            println()

            for (sheetIndex in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIndex)
                println("=== 시트 ${sheetIndex + 1}: ${sheet.sheetName} ===")

                // 병합 셀 정보
                if (sheet.numMergedRegions > 0) {
                    println("병합 영역:")
                    for (i in 0 until sheet.numMergedRegions) {
                        println("  - ${sheet.getMergedRegion(i).formatAsString()}")
                    }
                }

                // 셀 내용과 코멘트 출력
                println("셀 내용:")
                for (row in sheet) {
                    for (cell in row) {
                        val colLetter = ('A' + cell.columnIndex).toString()
                        val cellRef = "$colLetter${cell.rowIndex + 1}"
                        val value = when (cell.cellType) {
                            CellType.STRING -> cell.stringCellValue
                            CellType.NUMERIC -> cell.numericCellValue.toString()
                            CellType.FORMULA -> cell.cellFormula
                            else -> ""
                        }
                        val comment = cell.cellComment?.string?.string

                        if (value.isNotBlank() || comment != null) {
                            print("  $cellRef: ")
                            if (value.isNotBlank()) print("\"$value\" ")
                            if (comment != null) print("[코멘트: $comment]")
                            println()
                        }
                    }
                }
                println()
            }
        }
    }
}
