package com.hunet.common.excel.engine

/**
 * 수식 참조 조정기 - 반복 처리로 인한 행 오프셋에 따라 수식 내 셀 참조 조정
 *
 * 스트리밍 모드에서는 POI의 shiftRows()를 사용할 수 없으므로
 * 수식 참조를 직접 조정해야 합니다.
 */
object FormulaAdjuster {

    /**
     * 셀 참조 패턴
     * - A1, B2, AA100 등
     * - $A$1, $A1, A$1 등 (절대/상대 참조)
     */
    private val CELL_REF_PATTERN = Regex(
        """(\$?)([A-Z]+)(\$?)(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 시트 참조 패턴 (Sheet1!A1)
     */
    private val SHEET_REF_PATTERN = Regex(
        """(['"]?[\w\s]+['"]?!)(\$?)([A-Z]+)(\$?)(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 반복 처리로 인한 행 오프셋에 따라 수식 내 셀 참조 조정
     *
     * @param formula 원본 수식
     * @param repeatStartRow 반복 영역 시작 행 (0-based)
     * @param rowOffset 행 오프셋 (실제 데이터 수 - 템플릿 행 수)
     * @return 조정된 수식
     *
     * 예: =SUM(C6:C6), repeatStartRow=5, rowOffset=2 → =SUM(C6:C8)
     */
    fun adjustForRowExpansion(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        rowOffset: Int
    ): String {
        if (rowOffset == 0) return formula

        var result = formula

        // 범위 참조 처리 (A1:B10 형태)
        result = adjustRangeReferences(result, repeatStartRow, repeatEndRow, rowOffset)

        // 단일 셀 참조 처리
        result = adjustSingleReferences(result, repeatEndRow, rowOffset)

        return result
    }

    /**
     * 범위 참조 조정 (예: C6:C6 → C6:C8)
     *
     * 범위의 끝 셀이 반복 영역에 포함되면 확장
     */
    private fun adjustRangeReferences(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        rowOffset: Int
    ): String {
        // 범위 패턴: A1:B10
        val rangePattern = Regex(
            """(\$?)([A-Z]+)(\$?)(\d+):(\$?)([A-Z]+)(\$?)(\d+)""",
            RegexOption.IGNORE_CASE
        )

        return rangePattern.replace(formula) { match ->
            val startColAbs = match.groupValues[1]
            val startCol = match.groupValues[2]
            val startRowAbs = match.groupValues[3]
            val startRow = match.groupValues[4].toInt()

            val endColAbs = match.groupValues[5]
            val endCol = match.groupValues[6]
            val endRowAbs = match.groupValues[7]
            val endRow = match.groupValues[8].toInt()

            // 끝 행이 반복 영역에 포함되고 절대 참조가 아닌 경우 확장
            val adjustedEndRow = if (endRowAbs != "$" &&
                (endRow - 1) >= repeatStartRow &&
                (endRow - 1) <= repeatEndRow) {
                endRow + rowOffset
            } else {
                endRow
            }

            // 시작 행도 반복 영역 이후면 조정
            val adjustedStartRow = if (startRowAbs != "$" && (startRow - 1) > repeatEndRow) {
                startRow + rowOffset
            } else {
                startRow
            }

            "$startColAbs$startCol$startRowAbs$adjustedStartRow:$endColAbs$endCol$endRowAbs$adjustedEndRow"
        }
    }

    /**
     * 단일 셀 참조 조정 (반복 영역 이후의 참조만)
     */
    private fun adjustSingleReferences(
        formula: String,
        repeatEndRow: Int,
        rowOffset: Int
    ): String {
        // 범위 참조는 이미 처리되었으므로, 범위가 아닌 단일 참조만 처리
        // 범위 참조 내의 셀은 건너뛰기 위해 현재 formula에서 범위 패턴 탐지
        // (adjustRangeReferences 이후의 결과를 받으므로 이미 조정된 범위 기준)

        val rangePattern = Regex("""\$?[A-Z]+\$?\d+:\$?[A-Z]+\$?\d+""", RegexOption.IGNORE_CASE)
        val ranges = rangePattern.findAll(formula).map { it.range }.toList()

        return CELL_REF_PATTERN.replace(formula) { match ->
            // 이 매치가 범위의 일부인지 확인
            val isPartOfRange = ranges.any { range ->
                match.range.first >= range.first && match.range.last <= range.last
            }

            if (isPartOfRange) {
                match.value
            } else {
                val colAbs = match.groupValues[1]
                val col = match.groupValues[2]
                val rowAbs = match.groupValues[3]
                val row = match.groupValues[4].toInt()

                // 절대 행 참조($)는 조정하지 않음
                // 반복 영역 이후의 행만 조정
                if (rowAbs != "$" && (row - 1) > repeatEndRow) {
                    "$colAbs$col$rowAbs${row + rowOffset}"
                } else {
                    match.value
                }
            }
        }
    }

    /**
     * 특정 위치의 수식을 새로운 행 위치에 맞게 조정
     *
     * 예: 원래 6행에 있던 =A5+B5 수식이 8행으로 이동하면 =A7+B7로 조정
     *
     * @param formula 원본 수식
     * @param originalRow 수식의 원래 행 (0-based)
     * @param newRow 수식의 새 행 (0-based)
     * @return 조정된 수식
     */
    fun adjustForRowMove(formula: String, originalRow: Int, newRow: Int): String {
        val rowDiff = newRow - originalRow
        if (rowDiff == 0) return formula

        return CELL_REF_PATTERN.replace(formula) { match ->
            val colAbs = match.groupValues[1]
            val col = match.groupValues[2]
            val rowAbs = match.groupValues[3]
            val row = match.groupValues[4].toInt()

            // 절대 행 참조($)는 조정하지 않음
            if (rowAbs == "$") {
                match.value
            } else {
                val newRefRow = row + rowDiff
                if (newRefRow > 0) {
                    "$colAbs$col$rowAbs$newRefRow"
                } else {
                    match.value // 유효하지 않은 참조는 그대로 유지
                }
            }
        }
    }

    /**
     * 현재 행 위치에서의 수식을 반복 인덱스에 맞게 조정
     *
     * 반복 영역 내 수식이 각 반복 항목에 맞게 조정됨
     *
     * @param formula 원본 수식
     * @param repeatIndex 반복 인덱스 (0부터 시작)
     * @return 조정된 수식
     */
    fun adjustForRepeatIndex(formula: String, repeatIndex: Int): String {
        if (repeatIndex == 0) return formula

        return CELL_REF_PATTERN.replace(formula) { match ->
            val colAbs = match.groupValues[1]
            val col = match.groupValues[2]
            val rowAbs = match.groupValues[3]
            val row = match.groupValues[4].toInt()

            // 절대 행 참조($)는 조정하지 않음
            if (rowAbs == "$") {
                match.value
            } else {
                "$colAbs$col$rowAbs${row + repeatIndex}"
            }
        }
    }

    /**
     * 열 확장으로 인한 수식 내 열 참조 조정
     *
     * 지정된 시작 열 이후의 열 참조를 shiftAmount만큼 오른쪽으로 이동
     *
     * @param formula 원본 수식
     * @param startCol 이동 시작 열 (0-based, 이 열 이상의 참조가 조정됨)
     * @param shiftAmount 이동할 열 수
     * @return 조정된 수식
     *
     * 예: =SUM(B7), startCol=2 (C열), shiftAmount=4 → =SUM(B7) (B는 1이므로 변경 없음)
     * 예: =SUM(F7), startCol=2 (C열), shiftAmount=4 → =SUM(J7) (F=5 >= 2, 5+4=9 → J)
     */
    fun adjustForColumnExpansion(formula: String, startCol: Int, shiftAmount: Int): String {
        if (shiftAmount == 0) return formula

        return CELL_REF_PATTERN.replace(formula) { match ->
            val colAbs = match.groupValues[1]
            val col = match.groupValues[2].uppercase()
            val rowAbs = match.groupValues[3]
            val row = match.groupValues[4]

            // 절대 열 참조($)는 조정하지 않음
            if (colAbs == "$") {
                match.value
            } else {
                val colIndex = columnNameToIndex(col)
                if (colIndex >= startCol) {
                    val newColIndex = colIndex + shiftAmount
                    val newColName = indexToColumnName(newColIndex)
                    "$colAbs$newColName$rowAbs$row"
                } else {
                    match.value
                }
            }
        }
    }

    /**
     * 반복 영역 내 단일 셀 참조를 범위로 확장
     *
     * 반복 영역 외부의 수식에서 반복 영역 내 셀을 참조하는 경우,
     * 해당 참조를 확장된 범위로 변환합니다.
     *
     * @param formula 원본 수식
     * @param repeatStartRow 반복 영역 시작 행 (0-based)
     * @param repeatEndRow 반복 영역 끝 행 (0-based)
     * @param itemCount 반복 아이템 수
     * @param templateRowCount 템플릿 행 수 (repeatEndRow - repeatStartRow + 1)
     * @return 확장된 수식과 비연속 참조 여부
     *
     * 예 (1행 템플릿):
     *   =SUM(B8), repeatStartRow=7, itemCount=3, templateRowCount=1
     *   → =SUM(B8:B10) (연속 범위)
     *
     * 예 (2행 템플릿):
     *   =SUM(B8), repeatStartRow=6, repeatEndRow=7, itemCount=3, templateRowCount=2
     *   → =SUM(B8,B10,B12) (비연속 - B8은 템플릿 2번째 행, 각 아이템마다 +2)
     */
    fun expandSingleRefToRange(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        itemCount: Int,
        templateRowCount: Int
    ): Pair<String, Boolean> {
        if (itemCount <= 1) return formula to false

        var hasDiscontinuous = false

        // 이미 범위 참조인 것은 제외하기 위해 범위 위치 수집
        val rangePattern = Regex("""\$?[A-Z]+\$?\d+:\$?[A-Z]+\$?\d+""", RegexOption.IGNORE_CASE)
        val ranges = rangePattern.findAll(formula).map { it.range }.toList()

        val result = CELL_REF_PATTERN.replace(formula) { match ->
            // 이 매치가 범위의 일부인지 확인
            val isPartOfRange = ranges.any { range ->
                match.range.first >= range.first && match.range.last <= range.last
            }

            if (isPartOfRange) {
                match.value
            } else {
                val colAbs = match.groupValues[1]
                val col = match.groupValues[2].uppercase()
                val rowAbs = match.groupValues[3]
                val row = match.groupValues[4].toInt()
                val rowIndex = row - 1  // 0-based

                // 반복 영역 내의 셀인지 확인
                if (rowIndex >= repeatStartRow && rowIndex <= repeatEndRow) {
                    // 절대 참조는 확장하지 않음
                    if (rowAbs == "$") {
                        match.value
                    } else if (templateRowCount == 1) {
                        // 1행 템플릿: 연속 범위로 확장
                        val endRow = row + (itemCount - 1)
                        "$colAbs$col$rowAbs$row:$colAbs$col$rowAbs$endRow"
                    } else {
                        // 다중 행 템플릿: 비연속 셀 나열
                        hasDiscontinuous = true
                        val cells = (0 until itemCount).map { idx ->
                            val newRow = row + (idx * templateRowCount)
                            "$colAbs$col$rowAbs$newRow"
                        }
                        cells.joinToString(",")
                    }
                } else {
                    match.value
                }
            }
        }

        return result to hasDiscontinuous
    }

    /**
     * 열 이름을 인덱스로 변환 (A=0, B=1, ..., Z=25, AA=26, ...)
     */
    private fun columnNameToIndex(colName: String): Int {
        return colName.uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    }

    /**
     * 인덱스를 열 이름으로 변환 (0=A, 1=B, ..., 25=Z, 26=AA, ...)
     */
    fun indexToColumnName(index: Int): String {
        val sb = StringBuilder()
        var n = index + 1
        while (n > 0) {
            n--
            sb.insert(0, ('A' + (n % 26)))
            n /= 26
        }
        return sb.toString()
    }
}
