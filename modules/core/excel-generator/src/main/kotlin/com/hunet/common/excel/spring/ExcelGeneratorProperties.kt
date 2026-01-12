package com.hunet.common.excel.spring

import com.hunet.common.excel.ExcelGeneratorConfig
import com.hunet.common.excel.StreamingMode
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Excel Generator 설정 프로퍼티.
 *
 * application.yml 예시:
 * ```yaml
 * hunet:
 *   excel:
 *     streaming-mode: auto          # auto, enabled, disabled
 *     streaming-row-threshold: 1000 # AUTO 모드에서 SXSSF 전환 기준 행 수
 *     formula-processing: true      # 수식 자동 계산
 *     timestamp-format: yyyyMMdd_HHmmss
 *     progress-report-interval: 100 # 진행률 콜백 호출 간격
 * ```
 */
@ConfigurationProperties(prefix = "hunet.excel")
data class ExcelGeneratorProperties(
    /**
     * 스트리밍 모드 설정.
     * - auto: 데이터 크기에 따라 자동 결정 (기본값)
     * - enabled: 항상 SXSSF 사용 (대용량)
     * - disabled: 항상 XSSF 사용 (소량)
     */
    var streamingMode: StreamingModeProperty = StreamingModeProperty.AUTO,

    /**
     * AUTO 모드에서 SXSSF로 전환되는 행 수 기준.
     */
    var streamingRowThreshold: Int = 1000,

    /**
     * 수식 자동 계산 활성화 여부.
     */
    var formulaProcessing: Boolean = true,

    /**
     * 파일명에 추가되는 타임스탬프 형식.
     */
    var timestampFormat: String = "yyyyMMdd_HHmmss",

    /**
     * 진행률 콜백 호출 간격 (행 수).
     */
    var progressReportInterval: Int = 100
) {
    /**
     * ExcelGeneratorConfig로 변환합니다.
     */
    fun toConfig(): ExcelGeneratorConfig = ExcelGeneratorConfig(
        streamingMode = streamingMode.toStreamingMode(),
        streamingRowThreshold = streamingRowThreshold,
        formulaProcessingEnabled = formulaProcessing,
        timestampFormat = timestampFormat,
        progressReportInterval = progressReportInterval
    )
}

/**
 * 스트리밍 모드 프로퍼티 (application.yml 바인딩용).
 */
enum class StreamingModeProperty {
    AUTO, ENABLED, DISABLED;

    fun toStreamingMode(): StreamingMode = when (this) {
        AUTO -> StreamingMode.AUTO
        ENABLED -> StreamingMode.ENABLED
        DISABLED -> StreamingMode.DISABLED
    }
}
