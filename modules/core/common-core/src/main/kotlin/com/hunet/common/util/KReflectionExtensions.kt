package com.hunet.common.util

import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.isAccessible
import jakarta.persistence.MappedSuperclass

/**
 * 통합된 KAnnotatedElement.getAnnotation 확장 함수.
 * 우선순위:
 * 1. Kotlin 리플렉션 findAnnotation
 * 2. KProperty인 경우: backing field -> getter -> 주 생성자 파라미터
 * 3. KClass인 경우: Java 리플렉션 조회
 */
inline fun <reified A : Annotation> KAnnotatedElement.getAnnotation(): A? {
    findAnnotation<A>()?.let { return it }
    if (this is KProperty1<*, *>) {
        this.javaField?.getAnnotation(A::class.java)?.let { return it }
        this.javaGetter?.getAnnotation(A::class.java)?.let { return it }
        val ownerClass = (this.javaField?.declaringClass ?: this.javaGetter?.declaringClass)?.kotlin
        ownerClass?.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name == this.name }
            ?.findAnnotation<A>()
            ?.let { return it }
        return null
    }
    if (this is KClass<*>) {
        this.java.getAnnotation(A::class.java)?.let { return it }
    }
    return null
}

inline fun <reified A : Annotation> KAnnotatedElement.isExistAnnotation(): Boolean = getAnnotation<A>() != null

/**
 * @MappedSuperclass가 붙은 상위 클래스를 체인으로 타고 올라가며 지정 애노테이션을 가진 mutable property 목록 반환.
 */
inline fun <reified A : Annotation> KClass<*>.annotatedFields(): List<KMutableProperty1<*, *>> =
    generateSequence(this) { it.java.superclass?.kotlin }
        .filter { klass -> klass.findAnnotation<MappedSuperclass>() != null }
        .flatMap { klass -> klass.declaredMemberProperties.asSequence() }
        .filter { it.findAnnotation<A>() != null }
        .mapNotNull { prop -> (prop as? KMutableProperty1<*, *>)?.apply { isAccessible = true } }
        .toList()
