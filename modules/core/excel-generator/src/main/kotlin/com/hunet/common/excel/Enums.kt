package com.hunet.common.excel

/**
 * Excel 생성 시 스트리밍 모드 설정.
 *
 * Apache POI의 SXSSF(Streaming Usermodel API) 사용 여부를 결정합니다.
 */
enum class StreamingMode {
    /**
     * 스트리밍 모드 비활성화.
     *
     * 항상 XSSFWorkbook을 사용합니다.
     * 모든 Excel 기능을 완전히 지원하며, 수식 참조가 자동으로 조정됩니다.
     * 아래 행 참조 수식이 있는 템플릿에서 사용하세요.
     */
    DISABLED,

    /**
     * 스트리밍 모드 활성화 (기본값).
     *
     * SXSSFWorkbook을 사용하여 대용량 데이터의 메모리 사용량을 줄입니다.
     *
     * 제한사항:
     * - 아래 행 참조 수식 사용 불가 (예: 1행에서 2행 이하 참조)
     */
    ENABLED
}

/**
 * 파일명 생성 모드.
 */
enum class FileNamingMode {
    /**
     * 기본 파일명만 사용 (suffix 없음).
     * 예: report.xlsx
     */
    NONE,

    /**
     * 타임스탬프를 suffix로 추가.
     * 예: report_20240107_143052.xlsx
     */
    TIMESTAMP
}

/**
 * 파일명 충돌 시 처리 정책.
 */
enum class FileConflictPolicy {
    /**
     * 동일 파일명이 존재하면 예외 발생.
     */
    ERROR,

    /**
     * 동일 파일명이 존재하면 시퀀스 번호를 추가.
     * 예: report.xlsx → report_1.xlsx → report_2.xlsx
     */
    SEQUENCE
}

/**
 * 템플릿에 정의된 변수/컬렉션/이미지에 대응하는 데이터가 없을 때의 동작.
 */
enum class MissingDataBehavior {
    /**
     * 무시하고 마커를 그대로 유지.
     *
     * 템플릿의 `${variableName}`, `${repeat(...)}`, `${image.name}` 등의
     * 마커가 치환되지 않고 원본 그대로 출력됩니다.
     */
    IGNORE,

    /**
     * 경고 로그를 출력하고 마커를 그대로 유지 (기본값).
     *
     * 누락된 데이터에 대해 WARNING 레벨 로그를 출력합니다.
     * 디버깅 및 데이터 누락 감지에 유용합니다.
     */
    WARN,

    /**
     * 예외를 발생시킴.
     *
     * 데이터 무결성이 중요한 경우 사용합니다.
     * [MissingTemplateDataException]이 발생합니다.
     */
    THROW
}
