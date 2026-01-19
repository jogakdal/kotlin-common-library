package com.hunet.common.excel.spring

import com.hunet.common.excel.ExcelGeneratorConfig
import com.hunet.common.excel.FileConflictPolicy
import com.hunet.common.excel.FileNamingMode
import com.hunet.common.excel.StreamingMode
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Excel Generator 설정 프로퍼티.
 *
 * application.yml 예시:
 * ```yaml
 * hunet:
 *   excel:
 *     streaming-mode: auto            # auto, enabled, disabled
 *     streaming-row-threshold: 1000   # AUTO 모드에서 SXSSF 전환 기준 행 수
 *     file-naming-mode: timestamp     # none, timestamp
 *     timestamp-format: yyyyMMdd_HHmmss
 *     file-conflict-policy: sequence  # error, sequence
 *     progress-report-interval: 100   # 진행률 콜백 호출 간격
 *     preserve-template-layout: true  # 템플릿 레이아웃 보존
 *     pivot-integer-format-index: 37  # 피벗 테이블 정수 필드 포맷 인덱스
 *     pivot-decimal-format-index: 39  # 피벗 테이블 소수점 필드 포맷 인덱스
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
     * 파일명 생성 모드.
     * - none: suffix 없이 기본 파일명만 사용
     * - timestamp: 타임스탬프를 suffix로 추가 (기본값)
     */
    var fileNamingMode: FileNamingModeProperty = FileNamingModeProperty.TIMESTAMP,

    /**
     * 파일명에 추가되는 타임스탬프 형식.
     */
    var timestampFormat: String = "yyyyMMdd_HHmmss",

    /**
     * 파일명 충돌 시 처리 정책.
     * - error: 동일 파일명이 존재하면 예외 발생
     * - sequence: 동일 파일명이 존재하면 시퀀스 번호 추가 (기본값)
     */
    var fileConflictPolicy: FileConflictPolicyProperty = FileConflictPolicyProperty.SEQUENCE,

    /**
     * 진행률 콜백 호출 간격 (행 수).
     */
    var progressReportInterval: Int = 100,

    /**
     * jx:each 확장 시 원본 템플릿의 열 폭과 행 높이를 보존할지 여부.
     */
    var preserveTemplateLayout: Boolean = true,

    /**
     * 피벗 테이블 정수 필드에 적용할 Excel 내장 포맷 인덱스.
     */
    var pivotIntegerFormatIndex: Short = 37,

    /**
     * 피벗 테이블 소수점 필드에 적용할 Excel 내장 포맷 인덱스.
     */
    var pivotDecimalFormatIndex: Short = 39
) {
    /**
     * ExcelGeneratorConfig로 변환합니다.
     */
    fun toConfig(): ExcelGeneratorConfig = ExcelGeneratorConfig(
        streamingMode = streamingMode.toStreamingMode(),
        streamingRowThreshold = streamingRowThreshold,
        fileNamingMode = fileNamingMode.toFileNamingMode(),
        timestampFormat = timestampFormat,
        fileConflictPolicy = fileConflictPolicy.toFileConflictPolicy(),
        progressReportInterval = progressReportInterval,
        preserveTemplateLayout = preserveTemplateLayout,
        pivotIntegerFormatIndex = pivotIntegerFormatIndex,
        pivotDecimalFormatIndex = pivotDecimalFormatIndex
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

/**
 * 파일명 생성 모드 프로퍼티 (application.yml 바인딩용).
 */
enum class FileNamingModeProperty {
    NONE, TIMESTAMP;

    fun toFileNamingMode(): FileNamingMode = when (this) {
        NONE -> FileNamingMode.NONE
        TIMESTAMP -> FileNamingMode.TIMESTAMP
    }
}

/**
 * 파일 충돌 정책 프로퍼티 (application.yml 바인딩용).
 */
enum class FileConflictPolicyProperty {
    ERROR, SEQUENCE;

    fun toFileConflictPolicy(): FileConflictPolicy = when (this) {
        ERROR -> FileConflictPolicy.ERROR
        SEQUENCE -> FileConflictPolicy.SEQUENCE
    }
}
