package com.hunet.common.tbeg

/**
 * Excel 템플릿에 바인딩할 데이터를 제공하는 인터페이스.
 *
 * 지연 로딩 방식으로 데이터를 제공하여 대용량 처리 시 메모리 효율성을 높입니다.
 * - [getValue]: 단일 변수 값 제공 (예: title, date) - `${변수명}` 형식
 * - [getItems]: 컬렉션 데이터 제공 (예: employees) - `${repeat(컬렉션, 범위, 변수)}` 형식
 * - [getImage]: 이미지 데이터 제공 (예: logo) - `${image.이름}` 형식
 */
interface ExcelDataProvider {

    /**
     * 단일 변수 값을 반환합니다.
     *
     * 템플릿의 `${variableName}` 형태의 플레이스홀더에 바인딩됩니다.
     *
     * @param name 변수 이름
     * @return 변수 값, 존재하지 않으면 null
     */
    fun getValue(name: String): Any?

    /**
     * 컬렉션 데이터의 Iterator를 반환합니다.
     *
     * 템플릿의 `${repeat(name, A2:C2, item)}` 반복 영역에 사용됩니다.
     * Iterator를 반환하여 대용량 데이터도 스트리밍 방식으로 처리할 수 있습니다.
     *
     * @param name 컬렉션 이름
     * @return 컬렉션의 Iterator, 존재하지 않으면 null
     */
    fun getItems(name: String): Iterator<Any>?

    /**
     * 이미지 데이터를 반환합니다.
     *
     * 템플릿의 `${image.name}` 마커에 이미지를 삽입합니다.
     *
     * @param name 이미지 이름
     * @return 이미지 바이트 배열, 존재하지 않으면 null
     */
    fun getImage(name: String): ByteArray? = null

    /**
     * 문서 메타데이터를 반환합니다.
     *
     * Excel 파일의 문서 속성(제목, 작성자, 설명 등)을 설정합니다.
     * Excel에서 "파일 > 정보 > 속성"에서 확인할 수 있습니다.
     *
     * @return 문서 메타데이터, 설정하지 않으면 null
     */
    fun getMetadata(): DocumentMetadata? = null
}
