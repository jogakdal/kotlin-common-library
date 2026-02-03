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

/** MatchResult를 CellRef로 변환 */
private fun MatchResult.toCellRef() = CellRef(
    colAbs = groupValues[1],
    col = groupValues[2].uppercase(),
    rowAbs = groupValues[3],
    row = groupValues[4].toInt()
)

/**
 * 범위 참조 정보를 담는 데이터 클래스
 */
private data class RangeRef(val start: CellRef, val end: CellRef) {
    fun format(
        newStartRow: Int = start.row,
        newStartCol: String = start.col,
        newEndRow: Int = end.row,
        newEndCol: String = end.col
    ) = "${start.colAbs}$newStartCol${start.rowAbs}$newStartRow:${end.colAbs}$newEndCol${end.rowAbs}$newEndRow"
}

/** MatchResult를 RangeRef로 변환 (RANGE_CAPTURE_PATTERN용) */
private fun MatchResult.toRangeRef() = RangeRef(
    start = CellRef(groupValues[1], groupValues[2].uppercase(), groupValues[3], groupValues[4].toInt()),
    end = CellRef(groupValues[5], groupValues[6].uppercase(), groupValues[7], groupValues[8].toInt())
)

/**
 * 수식 참조 조정기 - 반복 처리로 인한 행 오프셋에 따라 수식 내 셀 참조 조정
 *
 * 스트리밍 모드에서는 POI의 shiftRows()를 사용할 수 없으므로
 * 수식 참조를 직접 조정해야 합니다.
 */
object FormulaAdjuster {
    /** 셀 참조 패턴: A1, $A$1, $A1, A$1 등 */
    private val CELL_REF_PATTERN = Regex("""(\$?)([A-Z]+)(\$?)(\d+)""", RegexOption.IGNORE_CASE)

    /** 범위 참조 패턴: A1:B10 */
    private val RANGE_PATTERN = Regex("""\$?[A-Z]+\$?\d+:\$?[A-Z]+\$?\d+""", RegexOption.IGNORE_CASE)

    /** 범위 참조 패턴 - 그룹 캡처 버전 (시작셀, 끝셀 분리) */
    private val RANGE_CAPTURE_PATTERN = Regex(
        """(\$?)([A-Z]+)(\$?)(\d+):(\$?)([A-Z]+)(\$?)(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /** 매치가 범위 내에 포함되는지 확인 */
    private fun isPartOfRange(match: MatchResult, ranges: List<IntRange>) =
        ranges.any { match.range.first >= it.first && match.range.last <= it.last }

    /** 수식에서 범위 참조 위치 목록 추출 */
    private fun findRangePositions(formula: String) = RANGE_PATTERN.findAll(formula).map { it.range }.toList()

    // ========== 순환 참조 방지 헬퍼 ==========

    /** 이동 후 최종 위치 계산 (0-based 인덱스 기준) */
    private fun calculateShiftedPosition(position: Int, shiftAmount: Int, shiftStart: Int) =
        if (shiftAmount > 0 && shiftStart >= 0 && position >= shiftStart) position + shiftAmount else position

    /** 순환 참조 여부 체크 (0-based 인덱스 기준) */
    private fun isCircularRef(row: Int, col: Int, formulaCellRow: Int, formulaCellCol: Int) =
        formulaCellRow >= 0 && formulaCellCol >= 0 && row == formulaCellRow && col == formulaCellCol

    /** 순환 참조 방지를 위한 최대 위치 계산 (0-based 인덱스 기준) */
    private fun calculateMaxPosition(formulaCellPos: Int, shiftAmount: Int, shiftStart: Int) =
        if (shiftAmount > 0 && shiftStart >= 0 && (formulaCellPos - shiftAmount) >= shiftStart)
            formulaCellPos - shiftAmount else formulaCellPos

    /**
     * 반복 처리로 인한 행 오프셋에 따라 수식 내 셀 참조 조정
     *
     * @param formula 원본 수식
     * @param repeatStartRow 반복 영역 시작 행 (0-based)
     * @param rowOffset 행 오프셋 (실제 데이터 수 - 템플릿 행 수)
     * @return 조정된 수식
     *
     * 예: =SUM(C6:C6), repeatStartRow=5, rowOffset=2 -> =SUM(C6:C8)
     */
    fun adjustForRowExpansion(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        rowOffset: Int
    ) = if (rowOffset == 0) formula
    else adjustSingleReferences(
        adjustRangeReferences(formula, repeatStartRow, repeatEndRow, rowOffset), repeatEndRow, rowOffset
    )

    /** 범위 참조 조정 (예: C6:C6 -> C6:C8) - 범위의 끝 셀이 반복 영역에 포함되면 확장 */
    private fun adjustRangeReferences(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        rowOffset: Int
    ) = RANGE_CAPTURE_PATTERN.replace(formula) { match ->
        val range = match.toRangeRef()

        // 끝 행이 반복 영역에 포함되고 절대 참조가 아닌 경우 확장
        val adjustedEndRow = if (!range.end.isRowAbsolute && (range.end.row - 1) in repeatStartRow..repeatEndRow) {
            range.end.row + rowOffset
        } else {
            range.end.row
        }

        // 시작 행도 반복 영역 이후면 조정
        val adjustedStartRow = if (!range.start.isRowAbsolute && (range.start.row - 1) > repeatEndRow) {
            range.start.row + rowOffset
        } else {
            range.start.row
        }

        range.format(newStartRow = adjustedStartRow, newEndRow = adjustedEndRow)
    }

    /** 단일 셀 참조 조정 (반복 영역 이후의 참조만) */
    private fun adjustSingleReferences(formula: String, repeatEndRow: Int, rowOffset: Int) =
        findRangePositions(formula).let { ranges ->
            CELL_REF_PATTERN.replace(formula) { match ->
                if (isPartOfRange(match, ranges)) match.value
                else match.toCellRef().let { ref ->
                    // 절대 행 참조($)는 조정하지 않음, 반복 영역 이후의 행만 조정
                    if (!ref.isRowAbsolute && (ref.row - 1) > repeatEndRow) ref.format(newRow = ref.row + rowOffset)
                    else match.value
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
    fun adjustForRepeatIndex(formula: String, repeatIndex: Int) =
        if (repeatIndex == 0) formula
        else CELL_REF_PATTERN.replace(formula) { match ->
            match.toCellRef().run {
                if (isRowAbsolute) match.value
                else format(newRow = row + repeatIndex)
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
     * 예: =SUM(B7), startCol=2 (C열), shiftAmount=4 -> =SUM(B7) (B는 1이므로 변경 없음)
     * 예: =SUM(F7), startCol=2 (C열), shiftAmount=4 -> =SUM(J7) (F=5 >= 2, 5+4=9 -> J)
     */
    fun adjustForColumnExpansion(formula: String, startCol: Int, shiftAmount: Int) =
        if (shiftAmount == 0) formula
        else CELL_REF_PATTERN.replace(formula) { match ->
            match.toCellRef().let { ref ->
                if (ref.isColAbsolute) match.value
                else {
                    val colIndex = toColumnIndex(ref.col)
                    if (colIndex >= startCol) ref.format(newCol = toColumnLetter(colIndex + shiftAmount))
                    else match.value
                }
            }
        }

    /**
     * 반복 영역 내 단일 셀 참조를 행 방향(DOWN)으로 범위 확장
     *
     * DOWN 방향 반복 영역 외부의 수식에서 반복 영역 내 셀을 참조하는 경우,
     * 해당 참조를 행 방향으로 확장된 범위로 변환합니다.
     *
     * @param formula 원본 수식
     * @param repeatStartRow 반복 영역 시작 행 (0-based)
     * @param repeatEndRow 반복 영역 끝 행 (0-based)
     * @param itemCount 반복 아이템 수
     * @param templateRowCount 템플릿 행 수 (repeatEndRow - repeatStartRow + 1)
     * @param formulaCellRow 수식이 위치한 셀의 행 인덱스 (0-based, 순환 참조 방지용)
     * @param formulaCellCol 수식이 위치한 셀의 열 인덱스 (0-based, 순환 참조 방지용)
     * @param rowShiftAmount 확장 후 adjustForRowExpansion에서 이동될 행 수 (순환 참조 방지용)
     * @param rowShiftStartRow 행 이동이 시작되는 행 인덱스 (0-based, repeatEndRow + 1)
     * @return 확장된 수식과 비연속 참조 여부
     *
     * 예 (1행 템플릿):
     *   =SUM(B8), repeatStartRow=7, itemCount=3, templateRowCount=1
     *   -> =SUM(B8:B10) (행 방향 연속 범위)
     *
     * 예 (2행 템플릿):
     *   =SUM(B8), repeatStartRow=6, repeatEndRow=7, itemCount=3, templateRowCount=2
     *   -> =SUM(B8,B10,B12) (행 방향 비연속 - B8은 템플릿 2번째 행, 각 아이템마다 +2)
     *
     * 순환 참조 방지:
     *   수식이 B15에 있고 =SUM(B5)를 =SUM(B5:B20)로 확장하면 B15가 범위에 포함됨
     *   -> formulaCellRow=14를 전달하면 =SUM(B5:B14)로 제한하여 순환 참조 방지
     */
    fun expandSingleRefToRowRange(
        formula: String,
        repeatStartRow: Int,
        repeatEndRow: Int,
        itemCount: Int,
        templateRowCount: Int,
        formulaCellRow: Int = -1,
        formulaCellCol: Int = -1,
        rowShiftAmount: Int = 0,
        rowShiftStartRow: Int = -1
    ): Pair<String, Boolean> {
        if (itemCount <= 1) return formula to false

        var isSeq = true
        val ranges = findRangePositions(formula)

        val result = CELL_REF_PATTERN.replace(formula) { match ->
            if (isPartOfRange(match, ranges)) {
                match.value
            } else {
                val ref = match.toCellRef()
                val rowIndex = ref.row - 1
                val colIndex = toColumnIndex(ref.col)

                if (rowIndex !in repeatStartRow..repeatEndRow || ref.isRowAbsolute) {
                    match.value
                } else if (templateRowCount == 1) {
                    // 1행 템플릿: 연속 범위로 확장
                    var endRowIndex = rowIndex + (itemCount - 1)

                    // 순환 참조 방지: 같은 열이고 참조가 수식 셀보다 위에 있는 경우
                    if (colIndex == formulaCellCol && rowIndex < formulaCellRow) {
                        val finalEndRowIndex = calculateShiftedPosition(endRowIndex, rowShiftAmount, rowShiftStartRow)
                        if (finalEndRowIndex >= formulaCellRow) {
                            val maxRowIndex = calculateMaxPosition(formulaCellRow, rowShiftAmount, rowShiftStartRow)
                            endRowIndex = minOf(endRowIndex, maxRowIndex - 1)
                        }
                    }

                    // 시작이 끝보다 크거나 같으면 확장 의미 없음
                    if (endRowIndex <= rowIndex) {
                        match.value
                    } else {
                        "${ref.colAbs}${ref.col}${ref.rowAbs}${ref.row}:${ref.colAbs}${ref.col}${ref.rowAbs}${endRowIndex + 1}"
                    }
                } else {
                    // 다중 행 템플릿: 비연속 셀 나열
                    isSeq = false
                    val cells = (0 until itemCount).mapNotNull { idx ->
                        val newRowIndex = rowIndex + (idx * templateRowCount)
                        val finalRowIndex = calculateShiftedPosition(newRowIndex, rowShiftAmount, rowShiftStartRow)

                        // 순환 참조 방지: 이동 후 수식 셀과 같은 위치면 제외
                        if (isCircularRef(finalRowIndex, colIndex, formulaCellRow, formulaCellCol)) null
                        else "${ref.colAbs}${ref.col}${ref.rowAbs}${newRowIndex + 1}"
                    }
                    if (cells.isEmpty()) match.value else cells.joinToString(",")
                }
            }
        }

        return result to isSeq
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
     *   -> =SUM(B7:D7) (연속 범위)
     *
     * 예 (2열 템플릿):
     *   =SUM(B7), repeatStartCol=1, repeatEndCol=2, itemCount=3, templateColCount=2
     *   -> =SUM(B7,D7,F7) (비연속 - B는 템플릿 1번째 열, 각 아이템마다 +2)
     *
     * 순환 참조 방지:
     *   수식이 F7에 있고 =SUM(B7)을 =SUM(B7:GR7)로 확장하면 F7이 범위에 포함됨
     *   -> formulaCellCol=5를 전달하면 =SUM(B7:E7)로 제한하여 순환 참조 방지
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

        var isSeq = true
        val ranges = findRangePositions(formula)

        val result = CELL_REF_PATTERN.replace(formula) { match ->
            if (isPartOfRange(match, ranges)) {
                match.value
            } else {
                val ref = match.toCellRef()
                val rowIndex = ref.row - 1
                val colIndex = toColumnIndex(ref.col)

                // 반복 영역 내의 셀인지 확인 (열과 행 모두 반복 영역 내에 있어야 함)
                if (colIndex !in repeatStartCol..repeatEndCol || rowIndex !in repeatStartRow..repeatEndRow) {
                    match.value
                } else if (ref.isColAbsolute) {
                    match.value
                } else if (templateColCount == 1) {
                    // 1열 템플릿: 연속 범위로 확장
                    var endColIndex = colIndex + (itemCount - 1)

                    // 순환 참조 방지: 같은 행이고 참조가 수식 셀보다 왼쪽에 있는 경우
                    if (rowIndex == formulaCellRow && colIndex < formulaCellCol) {
                        val finalEndColIndex = calculateShiftedPosition(endColIndex, colShiftAmount, colShiftStartCol)
                        if (finalEndColIndex >= formulaCellCol) {
                            val maxColIndex = calculateMaxPosition(formulaCellCol, colShiftAmount, colShiftStartCol)
                            endColIndex = minOf(endColIndex, maxColIndex - 1)
                        }
                    }

                    // 시작이 끝보다 크거나 같으면 확장 의미 없음
                    if (endColIndex <= colIndex) {
                        match.value
                    } else {
                        "${ref.colAbs}${ref.col}${ref.rowAbs}${ref.row}:${ref.colAbs}${toColumnLetter(endColIndex)}" +
                                "${ref.rowAbs}${ref.row}"
                    }
                } else {
                    // 다중 열 템플릿: 비연속 셀 나열
                    isSeq = false
                    val cells = (0 until itemCount).mapNotNull { idx ->
                        val newColIndex = colIndex + (idx * templateColCount)
                        val finalColIndex = calculateShiftedPosition(newColIndex, colShiftAmount, colShiftStartCol)

                        // 순환 참조 방지: 이동 후 수식 셀과 같은 위치면 제외
                        if (isCircularRef(rowIndex, finalColIndex, formulaCellRow, formulaCellCol)) null
                        else "${ref.colAbs}${toColumnLetter(newColIndex)}${ref.rowAbs}${ref.row}"
                    }
                    if (cells.isEmpty()) match.value else cells.joinToString(",")
                }
            }
        }

        return result to isSeq
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
     * @return 조정된 수식
     */
    fun adjustWithPositionCalculator(formula: String, calculator: PositionCalculator): String {
        // 범위 참조 먼저 처리
        var result = RANGE_CAPTURE_PATTERN.replace(formula) { match ->
            val range = match.toRangeRef()

            // 절대 참조 여부에 따라 위치 계산
            val (newStartRow, newStartCol) = if (range.start.isRowAbsolute && range.start.isColAbsolute) {
                range.start.row to toColumnIndex(range.start.col)
            } else {
                val (r, c) = calculator.getFinalPosition(range.start.row - 1, toColumnIndex(range.start.col))
                val finalRow = if (range.start.isRowAbsolute) range.start.row else r + 1
                val finalCol = if (range.start.isColAbsolute) toColumnIndex(range.start.col) else c
                finalRow to finalCol
            }

            val (newEndRow, newEndCol) = if (range.end.isRowAbsolute && range.end.isColAbsolute) {
                range.end.row to toColumnIndex(range.end.col)
            } else {
                val (r, c) = calculator.getFinalPosition(range.end.row - 1, toColumnIndex(range.end.col))
                val finalRow = if (range.end.isRowAbsolute) range.end.row else r + 1
                val finalCol = if (range.end.isColAbsolute) toColumnIndex(range.end.col) else c
                finalRow to finalCol
            }

            range.format(
                newStartRow = newStartRow,
                newStartCol = toColumnLetter(newStartCol),
                newEndRow = newEndRow,
                newEndCol = toColumnLetter(newEndCol)
            )
        }

        // 단일 셀 참조 처리 (범위 외부만)
        val newRanges = findRangePositions(result)

        result = CELL_REF_PATTERN.replace(result) { match ->
            if (isPartOfRange(match, newRanges)) {
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

        var isSeq = true
        val ranges = findRangePositions(formula)

        val result = CELL_REF_PATTERN.replace(formula) { match ->
            if (isPartOfRange(match, ranges)) {
                match.value
            } else {
                val ref = match.toCellRef()
                val rowIndex = ref.row - 1
                val colIndex = toColumnIndex(ref.col)

                // 반복 영역 내의 셀인지 확인
                if (rowIndex !in region.startRow..region.endRow || colIndex !in region.startCol..region.endCol) {
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
                                isSeq = false
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
                                isSeq = false
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

        return result to isSeq
    }
}
