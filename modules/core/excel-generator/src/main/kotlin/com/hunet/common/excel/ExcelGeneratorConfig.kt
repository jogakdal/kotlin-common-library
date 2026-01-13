package com.hunet.common.excel

/**
 * Excel 생성기 설정.
 *
 * @property streamingMode 스트리밍 모드 (기본: AUTO)
 * @property streamingRowThreshold AUTO 모드에서 SXSSF로 전환되는 행 수 기준 (기본: 1000)
 * @property formulaProcessingEnabled 수식 자동 계산 활성화 여부 (기본: true)
 * @property timestampFormat 파일명에 추가되는 타임스탬프 형식 (기본: yyyyMMdd_HHmmss)
 * @property progressReportInterval 진행률 콜백 호출 간격 (행 수, 기본: 100)
 * @property preserveTemplateLayout jx:each 확장 시 원본 템플릿의 열 폭과 행 높이를 보존할지 여부 (기본: true)
 * @property pivotIntegerFormatIndex 피벗 테이블 정수 필드에 적용할 Excel 내장 포맷 인덱스 (기본: 37)
 * @property pivotDecimalFormatIndex 피벗 테이블 소수점 필드에 적용할 Excel 내장 포맷 인덱스 (기본: 39)
 */
data class ExcelGeneratorConfig(
    val streamingMode: StreamingMode = StreamingMode.AUTO,
    val streamingRowThreshold: Int = 1000,
    val formulaProcessingEnabled: Boolean = true,
    val timestampFormat: String = "yyyyMMdd_HHmmss",
    val progressReportInterval: Int = 100,
    val preserveTemplateLayout: Boolean = true,
    val pivotIntegerFormatIndex: Short = 37,
    val pivotDecimalFormatIndex: Short = 39
) {
    companion object {
        /**
         * 기본 설정을 반환합니다.
         */
        @JvmStatic
        fun default(): ExcelGeneratorConfig = ExcelGeneratorConfig()

        /**
         * 대용량 처리에 최적화된 설정을 반환합니다.
         */
        @JvmStatic
        fun forLargeData(): ExcelGeneratorConfig = ExcelGeneratorConfig(
            streamingMode = StreamingMode.ENABLED,
            progressReportInterval = 500
        )

        /**
         * 소량 데이터 처리에 최적화된 설정을 반환합니다.
         */
        @JvmStatic
        fun forSmallData(): ExcelGeneratorConfig = ExcelGeneratorConfig(
            streamingMode = StreamingMode.DISABLED
        )

        /**
         * Builder를 반환합니다. (Java에서 사용하기 편리)
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Java용 빌더 클래스.
     */
    class Builder {
        private var streamingMode: StreamingMode = StreamingMode.AUTO
        private var streamingRowThreshold: Int = 1000
        private var formulaProcessingEnabled: Boolean = true
        private var timestampFormat: String = "yyyyMMdd_HHmmss"
        private var progressReportInterval: Int = 100
        private var preserveTemplateLayout: Boolean = true
        private var pivotIntegerFormatIndex: Short = 37
        private var pivotDecimalFormatIndex: Short = 39

        fun streamingMode(mode: StreamingMode): Builder {
            this.streamingMode = mode
            return this
        }

        fun streamingRowThreshold(threshold: Int): Builder {
            this.streamingRowThreshold = threshold
            return this
        }

        fun formulaProcessingEnabled(enabled: Boolean): Builder {
            this.formulaProcessingEnabled = enabled
            return this
        }

        fun timestampFormat(format: String): Builder {
            this.timestampFormat = format
            return this
        }

        fun progressReportInterval(interval: Int): Builder {
            this.progressReportInterval = interval
            return this
        }

        fun preserveTemplateLayout(preserve: Boolean): Builder {
            this.preserveTemplateLayout = preserve
            return this
        }

        fun pivotIntegerFormatIndex(index: Short): Builder {
            this.pivotIntegerFormatIndex = index
            return this
        }

        fun pivotDecimalFormatIndex(index: Short): Builder {
            this.pivotDecimalFormatIndex = index
            return this
        }

        fun build(): ExcelGeneratorConfig = ExcelGeneratorConfig(
            streamingMode = streamingMode,
            streamingRowThreshold = streamingRowThreshold,
            formulaProcessingEnabled = formulaProcessingEnabled,
            timestampFormat = timestampFormat,
            progressReportInterval = progressReportInterval,
            preserveTemplateLayout = preserveTemplateLayout,
            pivotIntegerFormatIndex = pivotIntegerFormatIndex,
            pivotDecimalFormatIndex = pivotDecimalFormatIndex
        )
    }

    /**
     * 설정을 수정한 새 인스턴스를 반환합니다.
     */
    fun withStreamingMode(mode: StreamingMode): ExcelGeneratorConfig =
        copy(streamingMode = mode)

    fun withStreamingRowThreshold(threshold: Int): ExcelGeneratorConfig =
        copy(streamingRowThreshold = threshold)

    fun withTimestampFormat(format: String): ExcelGeneratorConfig =
        copy(timestampFormat = format)

    fun withProgressReportInterval(interval: Int): ExcelGeneratorConfig =
        copy(progressReportInterval = interval)

    fun withPreserveTemplateLayout(preserve: Boolean): ExcelGeneratorConfig =
        copy(preserveTemplateLayout = preserve)

    fun withPivotIntegerFormatIndex(index: Short): ExcelGeneratorConfig =
        copy(pivotIntegerFormatIndex = index)

    fun withPivotDecimalFormatIndex(index: Short): ExcelGeneratorConfig =
        copy(pivotDecimalFormatIndex = index)
}
