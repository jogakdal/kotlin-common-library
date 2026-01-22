package com.hunet.common.excel

/**
 * Excel 생성기 설정.
 *
 * @property streamingMode 스트리밍 모드 (기본: ENABLED)
 * @property fileNamingMode 파일명 생성 모드 (기본: TIMESTAMP)
 * @property timestampFormat 파일명에 추가되는 타임스탬프 형식 (기본: yyyyMMdd_HHmmss)
 * @property fileConflictPolicy 파일명 충돌 시 처리 정책 (기본: SEQUENCE)
 * @property progressReportInterval 진행률 콜백 호출 간격 (행 수, 기본: 100)
 * @property preserveTemplateLayout 반복 영역 확장 시 원본 템플릿의 열 폭과 행 높이를 보존할지 여부 (기본: true)
 * @property pivotIntegerFormatIndex 피벗 테이블 정수 필드에 적용할 Excel 내장 포맷 인덱스 (기본: 37)
 * @property pivotDecimalFormatIndex 피벗 테이블 소수점 필드에 적용할 Excel 내장 포맷 인덱스 (기본: 39)
 * @property missingDataBehavior 템플릿에 정의된 데이터가 없을 때의 동작 (기본: WARN)
 */
data class ExcelGeneratorConfig(
    val streamingMode: StreamingMode = StreamingMode.ENABLED,
    val fileNamingMode: FileNamingMode = FileNamingMode.TIMESTAMP,
    val timestampFormat: String = "yyyyMMdd_HHmmss",
    val fileConflictPolicy: FileConflictPolicy = FileConflictPolicy.SEQUENCE,
    val progressReportInterval: Int = 100,
    val preserveTemplateLayout: Boolean = true,
    val pivotIntegerFormatIndex: Short = 37,
    val pivotDecimalFormatIndex: Short = 39,
    val missingDataBehavior: MissingDataBehavior = MissingDataBehavior.WARN
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
        private var streamingMode: StreamingMode = StreamingMode.ENABLED
        private var fileNamingMode: FileNamingMode = FileNamingMode.TIMESTAMP
        private var timestampFormat: String = "yyyyMMdd_HHmmss"
        private var fileConflictPolicy: FileConflictPolicy = FileConflictPolicy.SEQUENCE
        private var progressReportInterval: Int = 100
        private var preserveTemplateLayout: Boolean = true
        private var pivotIntegerFormatIndex: Short = 37
        private var pivotDecimalFormatIndex: Short = 39
        private var missingDataBehavior: MissingDataBehavior = MissingDataBehavior.WARN

        fun streamingMode(mode: StreamingMode) = apply { this.streamingMode = mode }
        fun fileNamingMode(mode: FileNamingMode) = apply { this.fileNamingMode = mode }
        fun timestampFormat(format: String) = apply { this.timestampFormat = format }
        fun fileConflictPolicy(policy: FileConflictPolicy) = apply { this.fileConflictPolicy = policy }
        fun progressReportInterval(interval: Int) = apply { this.progressReportInterval = interval }
        fun preserveTemplateLayout(preserve: Boolean) = apply { this.preserveTemplateLayout = preserve }
        fun pivotIntegerFormatIndex(index: Short) = apply { this.pivotIntegerFormatIndex = index }
        fun pivotDecimalFormatIndex(index: Short) = apply { this.pivotDecimalFormatIndex = index }
        fun missingDataBehavior(behavior: MissingDataBehavior) = apply { this.missingDataBehavior = behavior }

        fun build() = ExcelGeneratorConfig(
            streamingMode = streamingMode,
            fileNamingMode = fileNamingMode,
            timestampFormat = timestampFormat,
            fileConflictPolicy = fileConflictPolicy,
            progressReportInterval = progressReportInterval,
            preserveTemplateLayout = preserveTemplateLayout,
            pivotIntegerFormatIndex = pivotIntegerFormatIndex,
            pivotDecimalFormatIndex = pivotDecimalFormatIndex,
            missingDataBehavior = missingDataBehavior
        )
    }

    /**
     * 설정을 수정한 새 인스턴스를 반환합니다.
     */
    fun withStreamingMode(mode: StreamingMode): ExcelGeneratorConfig =
        copy(streamingMode = mode)

    fun withFileNamingMode(mode: FileNamingMode): ExcelGeneratorConfig =
        copy(fileNamingMode = mode)

    fun withTimestampFormat(format: String): ExcelGeneratorConfig =
        copy(timestampFormat = format)

    fun withFileConflictPolicy(policy: FileConflictPolicy): ExcelGeneratorConfig =
        copy(fileConflictPolicy = policy)

    fun withProgressReportInterval(interval: Int): ExcelGeneratorConfig =
        copy(progressReportInterval = interval)

    fun withPreserveTemplateLayout(preserve: Boolean): ExcelGeneratorConfig =
        copy(preserveTemplateLayout = preserve)

    fun withPivotIntegerFormatIndex(index: Short): ExcelGeneratorConfig =
        copy(pivotIntegerFormatIndex = index)

    fun withPivotDecimalFormatIndex(index: Short): ExcelGeneratorConfig =
        copy(pivotDecimalFormatIndex = index)

    fun withMissingDataBehavior(behavior: MissingDataBehavior): ExcelGeneratorConfig =
        copy(missingDataBehavior = behavior)
}
