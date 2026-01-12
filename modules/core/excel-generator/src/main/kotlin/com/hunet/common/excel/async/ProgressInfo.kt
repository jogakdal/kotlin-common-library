package com.hunet.common.excel.async

/**
 * Excel 생성 작업의 진행률 정보.
 *
 * @property currentRow 현재 처리된 행 수
 * @property totalRows 전체 행 수 (알 수 없는 경우 null)
 * @property percentage 진행률 (0-100, 알 수 없으면 null)
 */
data class ProgressInfo(
    val currentRow: Int,
    val totalRows: Int? = null,
    val percentage: Int? = null
) {
    companion object {
        /**
         * 전체 행 수를 알고 있는 경우 ProgressInfo를 생성합니다.
         */
        @JvmStatic
        fun of(currentRow: Int, totalRows: Int): ProgressInfo {
            val percentage = if (totalRows > 0) (currentRow * 100 / totalRows) else 0
            return ProgressInfo(currentRow, totalRows, percentage)
        }

        /**
         * 전체 행 수를 모르는 경우 ProgressInfo를 생성합니다.
         */
        @JvmStatic
        fun unknown(currentRow: Int): ProgressInfo = ProgressInfo(currentRow, null, null)
    }
}
