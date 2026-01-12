package com.hunet.common.excel

/**
 * Excel 생성 시 스트리밍 모드 설정.
 *
 * 대용량 데이터 처리 시 메모리 효율성을 위해 SXSSF(Streaming Usermodel API)를
 * 사용할지 여부를 결정합니다.
 */
enum class StreamingMode {
    /**
     * 스트리밍 모드 비활성화.
     *
     * 항상 XSSFWorkbook을 사용합니다.
     * 소량 데이터에 적합하며, 모든 기능을 지원합니다.
     */
    DISABLED,

    /**
     * 스트리밍 모드 활성화.
     *
     * 항상 SXSSFWorkbook을 사용합니다.
     * 대용량 데이터에 적합하며, 메모리 사용량을 최소화합니다.
     * 일부 기능(예: 셀 읽기)이 제한될 수 있습니다.
     */
    ENABLED,

    /**
     * 자동 모드.
     *
     * 데이터 크기에 따라 자동으로 모드를 결정합니다.
     * [ExcelGeneratorConfig.streamingRowThreshold]를 기준으로 전환됩니다.
     */
    AUTO
}
