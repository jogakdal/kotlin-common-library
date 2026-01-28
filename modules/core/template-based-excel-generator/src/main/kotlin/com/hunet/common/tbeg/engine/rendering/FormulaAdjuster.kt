package com.hunet.common.tbeg.engine.rendering

import com.hunet.common.tbeg.engine.core.toColumnIndex
import com.hunet.common.tbeg.engine.core.toColumnLetter

/**
 * 셀 참조 정보를 담는 데이터 클래스
 */
private data class CellRef(
    val colAbs: String,
    val col: String,
    val rowAbs: String,
    val row: Int
) {
    val isRowAbsolute get() = rowAbs == "$"
    val isColAbsolute get() = colAbs == "$"

    fun format(newRow: Int = row, newCol: String = col) = "$colAbs$newCol$rowAbs$newRow"
}

/**
 * MatchResult를 CellRef로 변환하는 확장 함수
 */
private fun MatchResult.toCellRef() = CellRef(
    colAbs = groupValues[1],
    col = groupValues[2].uppercase(),
    rowAbs = groupValues[3],
    row = groupValues[4].toInt()
)

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
     * 범위 참조 패턴 (A1:B10)
     */
    private val RANGE_PATTERN = Regex(
        """\$?[A-Z]+\$?\d+:\$?[A-Z]+\$?\d+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 범위 참조 패턴 - 그룹 캡처 버전 (시작셀, 끝셀 분리)
     */
    private val RANGE_CAPTURE_PATTERN = Regex(
        """(\$?)([A-Z]+)(\$?)(\d+):(\$?)([A-Z]+)(\$?)(\d+)""",
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
    ): String = RANGE_CAPTURE_PATTERN.replace(formula) { match ->
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
        val ranges = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

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
            match.toCellRef().run {
                if (isRowAbsolute) match.value
                else {
                    val newRefRow = row + rowDiff
                    if (newRefRow > 0) format(newRow = newRefRow)
                    else match.value  // 유효하지 않은 참조는 그대로 유지
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
            match.toCellRef().run {
                if (isRowAbsolute) match.value
                else format(newRow = row + repeatIndex)
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
                val colIndex = toColumnIndex(col)
                if (colIndex >= startCol) {
                    val newColIndex = colIndex + shiftAmount
                    val newColName = toColumnLetter(newColIndex)
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
        val ranges = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

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
                if (rowIndex in repeatStartRow..repeatEndRow) {
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
     * 반복 영역 내 단일 셀 참조를 열 방향으로 범위/목록으로 확장
     *
     * RIGHT 방향 반복 영역 외부의 수식에서 반복 영역 내 셀을 참조하는 경우,
     * 해당 참조를 확장된 범위로 변환합니다.
     *
     * @param formula 원본 수식
     * @param repeatStartCol 반복 영역 시작 열 (0-based)
     * @param repeatEndCol 반복 영역 끝 열 (0-based)
     * @param repeatStartRow 반복 영역 시작 행 (0-based)
     * @param repeatEndRow 반복 영역 끝 행 (0-based)
     * @param itemCount 반복 아이템 수
     * @param templateColCount 템플릿 열 수 (repeatEndCol - repeatStartCol + 1)
     * @param formulaCellCol 수식이 위치한 셀의 열 인덱스 (0-based, 순환 참조 방지용)
     * @param formulaCellRow 수식이 위치한 셀의 행 인덱스 (0-based, 순환 참조 방지용)
     * @param colShiftAmount 확장 후 adjustForColumnExpansion에서 이동될 열 수 (순환 참조 방지용)
     * @param colShiftStartCol 열 이동이 시작되는 열 인덱스 (0-based, repeatEndCol + 1)
     * @return 확장된 수식과 비연속 참조 여부
     *
     * 예 (1열 템플릿):
     *   =SUM(B7), repeatStartCol=1, itemCount=3, templateColCount=1
     *   → =SUM(B7:D7) (연속 범위)
     *
     * 예 (2열 템플릿):
     *   =SUM(B7), repeatStartCol=1, repeatEndCol=2, itemCount=3, templateColCount=2
     *   → =SUM(B7,D7,F7) (비연속 - B는 템플릿 1번째 열, 각 아이템마다 +2)
     *
     * 순환 참조 방지:
     *   수식이 F7에 있고 =SUM(B7)을 =SUM(B7:GR7)로 확장하면 F7이 범위에 포함됨
     *   → formulaCellCol=5를 전달하면 =SUM(B7:E7)로 제한하여 순환 참조 방지
     *
     * 열 이동 고려:
     *   확장된 범위의 끝 열이 colShiftStartCol 이상이면, colShiftAmount만큼 이동됨
     *   이동 후 범위가 수식 셀을 포함하면 범위를 제한함
     */
    fun expandSingleRefToColumnRange(
        formula: String,
        repeatStartCol: Int,
        repeatEndCol: Int,
        repeatStartRow: Int,
        repeatEndRow: Int,
        itemCount: Int,
        templateColCount: Int,
        formulaCellCol: Int = -1,
        formulaCellRow: Int = -1,
        colShiftAmount: Int = 0,
        colShiftStartCol: Int = -1
    ): Pair<String, Boolean> {
        if (itemCount <= 1) return formula to false

        var hasDiscontinuous = false

        // 이미 범위 참조인 것은 제외하기 위해 범위 위치 수집
        val ranges = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

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
                val colIndex = toColumnIndex(col)

                // 반복 영역 내의 셀인지 확인 (열과 행 모두 반복 영역 내에 있어야 함)
                if (colIndex in repeatStartCol..repeatEndCol && rowIndex in repeatStartRow..repeatEndRow) {
                    // 절대 열 참조는 확장하지 않음
                    if (colAbs == "$") {
                        match.value
                    } else if (templateColCount == 1) {
                        // 1열 템플릿: 연속 범위로 확장
                        var endColIndex = colIndex + (itemCount - 1)

                        // 순환 참조 방지: 열 이동 후 최종 위치 고려
                        if (formulaCellCol >= 0 && formulaCellRow >= 0 &&
                            rowIndex == formulaCellRow && colIndex < formulaCellCol) {

                            // 열 이동 후 끝 열의 최종 위치 계산
                            val finalEndColIndex = if (colShiftAmount > 0 &&
                                colShiftStartCol >= 0 &&
                                endColIndex >= colShiftStartCol) {
                                endColIndex + colShiftAmount
                            } else {
                                endColIndex
                            }

                            // 이동 후 범위가 수식 셀을 포함하면 범위 제한
                            if (finalEndColIndex >= formulaCellCol) {
                                // 이동 후 수식 셀 직전이 되도록 원래 끝 열 계산
                                // finalEndColIndex = endColIndex + colShiftAmount = formulaCellCol - 1
                                // endColIndex = formulaCellCol - 1 - colShiftAmount
                                val maxEndColIndex = if (colShiftAmount > 0 &&
                                    colShiftStartCol >= 0 &&
                                    (formulaCellCol - 1 - colShiftAmount) >= colShiftStartCol) {
                                    formulaCellCol - 1 - colShiftAmount
                                } else {
                                    formulaCellCol - 1
                                }
                                endColIndex = minOf(endColIndex, maxEndColIndex)
                            }
                        }

                        // 시작 열이 끝 열보다 크거나 같으면 확장 의미 없음
                        if (endColIndex <= colIndex) {
                            match.value
                        } else {
                            val endColName = toColumnLetter(endColIndex)
                            "$colAbs$col$rowAbs$row:$colAbs$endColName$rowAbs$row"
                        }
                    } else {
                        // 다중 열 템플릿: 비연속 셀 나열
                        hasDiscontinuous = true
                        val cells = (0 until itemCount).mapNotNull { idx ->
                            val newColIndex = colIndex + (idx * templateColCount)

                            // 이동 후 최종 위치 계산
                            val finalColIndex = if (colShiftAmount > 0 &&
                                colShiftStartCol >= 0 &&
                                newColIndex >= colShiftStartCol) {
                                newColIndex + colShiftAmount
                            } else {
                                newColIndex
                            }

                            // 순환 참조 방지: 이동 후 수식 셀과 같은 위치면 제외
                            if (formulaCellCol >= 0 && formulaCellRow >= 0 &&
                                finalColIndex == formulaCellCol && rowIndex == formulaCellRow) {
                                null
                            } else {
                                val newColName = toColumnLetter(newColIndex)
                                "$colAbs$newColName$rowAbs$row"
                            }
                        }
                        if (cells.isEmpty()) {
                            match.value
                        } else {
                            cells.joinToString(",")
                        }
                    }
                } else {
                    match.value
                }
            }
        }

        return result to hasDiscontinuous
    }

    // ========== PositionCalculator 연동 메서드 ==========

    /**
     * PositionCalculator를 사용하여 수식 내 셀 참조의 위치를 조정합니다.
     *
     * 모든 셀 참조가 PositionCalculator의 getFinalPosition()을 통해
     * 최종 위치로 변환됩니다.
     *
     * @param formula 원본 수식
     * @param calculator 위치 계산기
     * @param formulaRow 수식이 위치한 템플릿 행 (0-based)
     * @param formulaCol 수식이 위치한 템플릿 열 (0-based)
     * @return 조정된 수식
     */
    fun adjustWithPositionCalculator(
        formula: String,
        calculator: PositionCalculator,
        formulaRow: Int,
        formulaCol: Int
    ): String {
        // 범위 참조 위치 수집 (범위 내부 셀은 별도 처리 방지)
        val ranges = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

        // 범위 참조 먼저 처리
        var result = RANGE_CAPTURE_PATTERN.replace(formula) { match ->
            val startColAbs = match.groupValues[1]
            val startCol = match.groupValues[2].uppercase()
            val startRowAbs = match.groupValues[3]
            val startRow = match.groupValues[4].toInt()

            val endColAbs = match.groupValues[5]
            val endCol = match.groupValues[6].uppercase()
            val endRowAbs = match.groupValues[7]
            val endRow = match.groupValues[8].toInt()

            // 절대 참조 여부에 따라 위치 계산
            val (newStartRow, newStartCol) = if (startRowAbs == "$" && startColAbs == "$") {
                startRow to toColumnIndex(startCol)
            } else {
                val (r, c) = calculator.getFinalPosition(startRow - 1, toColumnIndex(startCol))
                val finalRow = if (startRowAbs == "$") startRow else r + 1
                val finalCol = if (startColAbs == "$") toColumnIndex(startCol) else c
                finalRow to finalCol
            }

            val (newEndRow, newEndCol) = if (endRowAbs == "$" && endColAbs == "$") {
                endRow to toColumnIndex(endCol)
            } else {
                val (r, c) = calculator.getFinalPosition(endRow - 1, toColumnIndex(endCol))
                val finalRow = if (endRowAbs == "$") endRow else r + 1
                val finalCol = if (endColAbs == "$") toColumnIndex(endCol) else c
                finalRow to finalCol
            }

            val newStartColName = toColumnLetter(newStartCol)
            val newEndColName = toColumnLetter(newEndCol)

            "$startColAbs$newStartColName$startRowAbs$newStartRow:$endColAbs$newEndColName$endRowAbs$newEndRow"
        }

        // 단일 셀 참조 처리 (범위 외부만)
        val newRanges = RANGE_PATTERN.findAll(result).map { it.range }.toList()

        result = CELL_REF_PATTERN.replace(result) { match ->
            // 이 매치가 범위의 일부인지 확인
            val isPartOfRange = newRanges.any { range ->
                match.range.first >= range.first && match.range.last <= range.last
            }

            if (isPartOfRange) {
                match.value
            } else {
                val ref = match.toCellRef()
                if (ref.isRowAbsolute && ref.isColAbsolute) {
                    match.value
                } else {
                    val (newRow, newCol) = calculator.getFinalPosition(ref.row - 1, toColumnIndex(ref.col))
                    val finalRow = if (ref.isRowAbsolute) ref.row else newRow + 1
                    val finalCol = if (ref.isColAbsolute) ref.col else toColumnLetter(newCol)
                    "${ref.colAbs}$finalCol${ref.rowAbs}$finalRow"
                }
            }
        }

        return result
    }

    /**
     * repeat 영역 내 셀 참조를 범위로 확장합니다 (PositionCalculator 사용).
     *
     * repeat 영역 외부의 수식에서 반복 영역 내 셀을 참조하는 경우,
     * 해당 참조를 확장된 범위로 변환합니다.
     *
     * @param formula 원본 수식
     * @param expansion 대상 repeat 확장 정보
     * @param itemCount 반복 아이템 수
     * @return 확장된 수식과 비연속 참조 여부
     */
    fun expandToRangeWithCalculator(
        formula: String,
        expansion: PositionCalculator.RepeatExpansion,
        itemCount: Int
    ): Pair<String, Boolean> {
        if (itemCount <= 1) return formula to false

        val region = expansion.region
        val templateRowCount = region.endRow - region.startRow + 1
        val templateColCount = region.endCol - region.startCol + 1

        var hasDiscontinuous = false
        val ranges = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

        val result = CELL_REF_PATTERN.replace(formula) { match ->
            val isPartOfRange = ranges.any { range ->
                match.range.first >= range.first && match.range.last <= range.last
            }

            if (isPartOfRange) {
                match.value
            } else {
                val ref = match.toCellRef()
                val rowIndex = ref.row - 1
                val colIndex = toColumnIndex(ref.col)

                // 반복 영역 내의 셀인지 확인
                val inRepeatRow = rowIndex in region.startRow..region.endRow
                val inRepeatCol = colIndex in region.startCol..region.endCol

                if (!inRepeatRow || !inRepeatCol) {
                    match.value
                } else if (ref.isRowAbsolute && region.direction == RepeatDirection.DOWN) {
                    match.value
                } else if (ref.isColAbsolute && region.direction == RepeatDirection.RIGHT) {
                    match.value
                } else {
                    when (region.direction) {
                        RepeatDirection.DOWN -> {
                            if (templateRowCount == 1) {
                                // 연속 범위
                                val startRow = expansion.finalStartRow + (rowIndex - region.startRow) + 1
                                val endRow = startRow + (itemCount - 1)
                                val col = toColumnLetter(colIndex)
                                "${ref.colAbs}$col${ref.rowAbs}$startRow:${ref.colAbs}$col${ref.rowAbs}$endRow"
                            } else {
                                // 비연속 셀 나열
                                hasDiscontinuous = true
                                val relativeRow = rowIndex - region.startRow
                                val cells = (0 until itemCount).map { idx ->
                                    val newRow = expansion.finalStartRow + (idx * templateRowCount) + relativeRow + 1
                                    val col = toColumnLetter(colIndex)
                                    "${ref.colAbs}$col${ref.rowAbs}$newRow"
                                }
                                cells.joinToString(",")
                            }
                        }
                        RepeatDirection.RIGHT -> {
                            if (templateColCount == 1) {
                                // 연속 범위
                                val startCol = expansion.finalStartCol + (colIndex - region.startCol)
                                val endCol = startCol + (itemCount - 1)
                                val row = ref.row
                                "${ref.colAbs}${toColumnLetter(startCol)}${ref.rowAbs}$row:${ref.colAbs}${toColumnLetter(endCol)}${ref.rowAbs}$row"
                            } else {
                                // 비연속 셀 나열
                                hasDiscontinuous = true
                                val relativeCol = colIndex - region.startCol
                                val cells = (0 until itemCount).map { idx ->
                                    val newCol = expansion.finalStartCol + (idx * templateColCount) + relativeCol
                                    "${ref.colAbs}${toColumnLetter(newCol)}${ref.rowAbs}${ref.row}"
                                }
                                cells.joinToString(",")
                            }
                        }
                    }
                }
            }
        }

        return result to hasDiscontinuous
    }
}
