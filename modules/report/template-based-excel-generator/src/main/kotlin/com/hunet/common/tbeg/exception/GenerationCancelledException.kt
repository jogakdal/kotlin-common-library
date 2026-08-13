package com.hunet.common.tbeg.exception

/**
 * 생성 작업이 협조적 취소로 중단되었음을 나타내는 예외.
 *
 * 렌더링 루프가 취소 요청을 감지하면 이 예외를 던지고,
 * 비동기 생성 경로가 이를 구분해 리스너의 `onCancelled`로 라우팅한다.
 */
internal class GenerationCancelledException : RuntimeException("생성 작업이 취소되었습니다")
