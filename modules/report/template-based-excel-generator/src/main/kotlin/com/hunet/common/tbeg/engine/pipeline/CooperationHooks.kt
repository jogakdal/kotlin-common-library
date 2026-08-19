package com.hunet.common.tbeg.engine.pipeline

/**
 * 생성 과정의 협조 훅.
 *
 * 진행률 보고와 협조적 취소를 렌더링 루프까지 전달하기 위한 운반체다.
 * 공개 API가 아니며, 비동기 생성(`submit`/`submitToFile`)에서만 구성되어
 * `ProcessingContext` → `RenderingContext`를 통해 렌더 루프로 전달된다.
 * 동기 생성 경로에서는 기본값(no-op)이 사용된다.
 *
 * @property progressInterval `onProgress` 호출 간격(행 수). 이 간격마다 콜백을 발화한다.
 * @property onProgress 누적 처리 행 수를 받는 진행률 콜백
 * @property checkCancelled true를 반환하면 취소가 요청된 것으로 보고 렌더 루프가 중단한다
 */
internal class CooperationHooks(
    val progressInterval: Int = 100,
    val onProgress: (processedRows: Int) -> Unit = {},
    val checkCancelled: () -> Boolean = { false }
)
