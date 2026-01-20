package com.hunet.common.excel

/**
 * Excel 생성 시 스트리밍 모드 설정.
 *
 * JXLS 3.x의 SXSSF(Streaming Usermodel API) 사용 여부를 결정합니다.
 */
enum class StreamingMode {
    /**
     * 스트리밍 모드 비활성화.
     *
     * 항상 XSSFWorkbook을 사용합니다.
     * 모든 Excel 기능을 완전히 지원합니다.
     * 아래 행 참조 수식이 있는 템플릿에서 사용하세요.
     */
    DISABLED,

    /**
     * 스트리밍 모드 활성화 (기본값).
     *
     * JXLS 3.x의 SXSSF 스트리밍을 사용합니다.
     * 대용량 데이터에서 메모리 사용량 감소 및 처리 속도 향상됩니다.
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
