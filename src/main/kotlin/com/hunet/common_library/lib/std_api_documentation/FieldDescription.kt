package com.hunet.common_library.lib.std_api_documentation

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SwaggerDescribable

/**
 * DTO 클래스의 프로퍼티에 대한 설명을 지정하기 위한 어노테이션
 *
 * name: 프로퍼티의 이름 (null string이면 DTO 프로퍼티의 이름을 그대로 사용)
 * description: 프로퍼티의 설명.
 *      프로퍼티가 DescriptiveEnum을 상속받은 enum 클래스 타입인 경우
 *      DescriptiveEnum.DESCRIPTION_MARKER 를 사용하면 enum class의 describable 한 모든 요소들을 값과 설명 형태로 만들어 준다.
 * optional: 프로퍼티가 optional 인지 여부
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequestDescription(val name: String = "", val description: String = "", val optional: Boolean = false)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SwaggerDescription(val description: String = "", val optional: Boolean = false)

/**
 * DTO 클래스의 프로퍼티 순서를 지정하기 위한 어노테이션
 * KClass.declaredMemberProperties는 프로퍼티의 순서를 기본적으로 알파벳 순으로만 정렬하기 때문에 별도로 순서를 지정해 주어야 한다.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sequence(val value: Int)
