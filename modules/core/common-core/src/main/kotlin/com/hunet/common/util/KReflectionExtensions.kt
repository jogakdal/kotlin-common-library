package com.hunet.common.util

import jakarta.persistence.MappedSuperclass
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * 어노테이션 조회/체크 통합 확장.
 */
inline fun <reified A : Annotation> KAnnotatedElement.getAnnotation() = annotationLookup(this, A::class.java)
inline fun <reified A : Annotation> KAnnotatedElement.hasAnnotation() = getAnnotation<A>() != null

inline fun <reified A : Annotation> AnnotatedElement.getAnnotation() = annotationLookup(this, A::class.java)
inline fun <reified A : Annotation> AnnotatedElement.hasAnnotation() = getAnnotation<A>() != null

inline fun <reified A : Annotation> Field.getAnnotation() = (this as AnnotatedElement).getAnnotation<A>()
inline fun <reified A : Annotation> Field.hasAnnotation() = (this as AnnotatedElement).hasAnnotation<A>()

inline fun <reified A : Annotation> KClass<*>.annotatedFields() = getAnnotatedFieldsCached(this, A::class.java)
inline fun <reified A : Annotation> Class<*>.annotatedFields() = this.kotlin.annotatedFields<A>()

@PublishedApi internal data class PropertyMaps(
    val properties: List<KProperty1<*, *>>,
    val fieldByJava: Map<Field, KProperty1<*, *>>,
    val getterByJava: Map<Method, KProperty1<*, *>>,
    val byName: Map<String, KProperty1<*, *>>
)

@PublishedApi internal val classPropertyCache = ConcurrentHashMap<Class<*>, PropertyMaps>()
@PublishedApi internal fun getPropertyMaps(clazz: Class<*>) = classPropertyCache.computeIfAbsent(clazz) { c ->
    val props = c.kotlin.memberProperties.toList()
    val fieldMap = mutableMapOf<Field, KProperty1<*, *>>()
    val getterMap = mutableMapOf<Method, KProperty1<*, *>>()
    val nameMap = mutableMapOf<String, KProperty1<*, *>>()
    props.forEach { p ->
        nameMap[p.name] = p
        p.javaField?.let { fieldMap[it] = p }
        p.javaGetter?.let { getterMap[it] = p }
    }
    PropertyMaps(props, fieldMap, getterMap, nameMap)
}

@PublishedApi internal data class AnnotatedFieldsKey(val root: KClass<*>, val annotationClassName: String)
@PublishedApi internal val annotatedFieldsCache = ConcurrentHashMap<AnnotatedFieldsKey, List<KMutableProperty1<*, *>>>()

@Suppress("UNCHECKED_CAST")
@PublishedApi internal fun getAnnotatedFieldsCached(root: KClass<*>, annClass: Class<out Annotation>) =
    annotatedFieldsCache.computeIfAbsent(AnnotatedFieldsKey(root, annClass.name)) {
        generateSequence(root) { it.java.superclass?.kotlin }
            .filter { klass -> klass.findAnnotation<MappedSuperclass>() != null }
            .flatMap { klass -> klass.declaredMemberProperties.asSequence() }
            .filter { prop -> prop.annotations.any { ann -> annClass.isInstance(ann) } }
            .mapNotNull { prop -> (prop as? KMutableProperty1<*, *>)?.apply { isAccessible = true } }
            .toList()
    }

// ===== Java interoperability overloads (non-inline, non-reified) =====
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> KAnnotatedElement.getAnnotation(annClass: Class<A>) = annotationLookup(this, annClass)
fun <A: Annotation> KAnnotatedElement.hasAnnotation(annClass: Class<A>) = getAnnotation(annClass) != null
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> AnnotatedElement.getAnnotation(annClass: Class<A>) = annotationLookup(this, annClass)
fun <A: Annotation> AnnotatedElement.hasAnnotation(annClass: Class<A>) = getAnnotation(annClass) != null
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> KClass<*>.getAnnotation(annClass: Class<A>) = annotationLookup(this, annClass)
fun <A: Annotation> KClass<*>.hasAnnotation(annClass: Class<A>) = getAnnotation(annClass) != null
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> Class<*>.getAnnotation(annClass: Class<A>) = annotationLookup(this, annClass)
fun <A: Annotation> Class<*>.hasAnnotation(annClass: Class<A>) = getAnnotation(annClass) != null

// annotatedFields Java용 오버로드
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> KClass<*>.annotatedFields(annClass: Class<A>) = getAnnotatedFieldsCached(this, annClass)
fun <A: Annotation> Class<*>.annotatedFields(annClass: Class<A>) = this.kotlin.annotatedFields(annClass)

/**
 * 리플렉션 캐시(Class property 매핑 및 annotatedFields 결과)를 모두 초기화한다.
 * 테스트나 핫 리로드/재기동 환경에서 캐시된 메타데이터를 비워야 할 때 사용.
 */
fun clearReflectionAnnotationCaches() {
    classPropertyCache.clear()
    annotatedFieldsCache.clear()
}

@PublishedApi internal fun <A: Annotation> annotationLookup(target: Any, annClass: Class<A>): A? {
    if (target is KAnnotatedElement) {
        target.annotations.firstOrNull { annClass.isInstance(it) }?.let { return annClass.cast(it) }
        if (target is KProperty1<*, *>) {
            target.javaField?.getAnnotation(annClass)?.let { return it }
            target.javaGetter?.getAnnotation(annClass)?.let { return it }
            val ownerK = (target.javaField?.declaringClass ?: target.javaGetter?.declaringClass)?.kotlin
            ownerK?.primaryConstructor?.parameters
                ?.firstOrNull { it.name == target.name }
                ?.annotations
                ?.firstOrNull { annClass.isInstance(it) }
                ?.let { return annClass.cast(it) }
            return null
        }
        if (target is KClass<*>) {
            target.java.getAnnotation(annClass)?.let { return it }
            return null
        }
    }
    // Java AnnotatedElement path (Field / Method / Class)
    if (target is AnnotatedElement) {
        when (target) {
            is Class<*> -> {
                target.kotlin.annotations.firstOrNull { annClass.isInstance(it) }?.let { return annClass.cast(it) }
                target.getAnnotation(annClass)?.let { return it }
                return null
            }
            is Field -> {
                target.getAnnotation(annClass)?.let { return it }
                val maps = getPropertyMaps(target.declaringClass)
                val kProp = maps.fieldByJava[target] ?: maps.byName[target.name]
                if (kProp != null) {
                    annotationLookup(kProp, annClass)?.let { return it }
                    kProp.javaGetter?.getAnnotation(annClass)?.let { return it }
                }
                return null
            }
            is Method -> {
                target.getAnnotation(annClass)?.let { return it }
                val maps = getPropertyMaps(target.declaringClass)
                val kProp = maps.getterByJava[target]
                if (kProp != null) {
                    annotationLookup(kProp, annClass)?.let { return it }
                }
                return null
            }
            else -> {
                target.getAnnotation(annClass)?.let { return it }
                return null
            }
        }
    }
    return null
}

/**
 * ===== Direct annotation access (no cross Kotlin/Java traversal) =====
 * 목적:
 *  - 기존 getAnnotation/hasAnnotation 은 Kotlin Property ↔ Java Field/Getter/Constructor Parameter 간 확장 탐색을 수행.
 *  - 아래 direct 계열은 해당 요소 자체(declaration site)에 붙은 어노테이션만 빠르게 확인.
 * 활용 시나리오:
 *  - 성능 미세 최적화 (대량 루프에서 불필요한 매핑 탐색 회피)
 *  - 의미적으로 "직접 선언" 여부만 구분해야 하는 필드 기반 로직(@Id 필드 스캔 등)
 *  - 넓은 탐색으로 인해 경고/충돌 로그가 증가하는 것을 피하고자 할 때
 * 주의:
 *  - Kotlin Property에 대해 constructor parameter / javaField / javaGetter 등에 붙은 어노테이션은 무시된다.
 *  - Class<*> direct는 Java getAnnotation(annClass)에 해당하며 Kotlin/Java 중복 구분 없음.
 */
inline fun <reified A: Annotation> KAnnotatedElement.getDirectAnnotation() =
    this.annotations.firstOrNull { it is A } as? A
inline fun <reified A: Annotation> KAnnotatedElement.hasDirectAnnotation() = getDirectAnnotation<A>() != null

inline fun <reified A: Annotation> AnnotatedElement.getDirectAnnotation(): A? = this.getAnnotation(A::class.java)
inline fun <reified A: Annotation> AnnotatedElement.hasDirectAnnotation() = getDirectAnnotation<A>() != null

inline fun <reified A: Annotation> Field.getDirectAnnotation() = (this as AnnotatedElement).getDirectAnnotation<A>()
inline fun <reified A: Annotation> Field.hasDirectAnnotation() = (this as AnnotatedElement).hasDirectAnnotation<A>()

inline fun <reified A: Annotation> KClass<*>.getDirectAnnotation() = this.annotations.firstOrNull { it is A } as? A
inline fun <reified A: Annotation> KClass<*>.hasDirectAnnotation() = getDirectAnnotation<A>() != null

inline fun <reified A: Annotation> Class<*>.getDirectAnnotation(): A? = this.getAnnotation(A::class.java)
inline fun <reified A: Annotation> Class<*>.hasDirectAnnotation() = getDirectAnnotation<A>() != null

// Non-reified overloads (Java 호출용)
@Suppress("UNCHECKED_CAST")
fun <A: Annotation> KAnnotatedElement.getDirectAnnotation(annClass: Class<A>) =
    this.annotations.firstOrNull { annClass.isInstance(it) }?.let { annClass.cast(it) }
fun <A: Annotation> KAnnotatedElement.hasDirectAnnotation(annClass: Class<A>) = getDirectAnnotation(annClass) != null

@Suppress("UNCHECKED_CAST")
fun <A: Annotation> AnnotatedElement.getDirectAnnotation(annClass: Class<A>): A? = this.getAnnotation(annClass)
fun <A: Annotation> AnnotatedElement.hasDirectAnnotation(annClass: Class<A>) = getDirectAnnotation(annClass) != null

@Suppress("UNCHECKED_CAST")
fun <A: Annotation> KClass<*>.getDirectAnnotation(annClass: Class<A>) =
    this.annotations.firstOrNull { annClass.isInstance(it) }?.let { annClass.cast(it) }
fun <A: Annotation> KClass<*>.hasDirectAnnotation(annClass: Class<A>) = getDirectAnnotation(annClass) != null

@Suppress("UNCHECKED_CAST")
fun <A: Annotation> Class<*>.getDirectAnnotation(annClass: Class<A>): A? = this.getAnnotation(annClass)
fun <A: Annotation> Class<*>.hasDirectAnnotation(annClass: Class<A>) = getDirectAnnotation(annClass) != null

/**
 * 클래스의 Kotlin memberProperties 및 Java declaredFields에 존재하는 특정 어노테이션(A)을 탐색하여
 * valueExtractor로부터 추출된 값들을 수집한다. Java/Kotlin 구분 없이 동작하며 null/blank 값은 호출 측에서 필터 가능.
 * - 중복은 제거(distinct=true) 기본, distinct=false 로 원래 순서 유지 가능.
 * - valueExtractor가 null 반환 시 해당 항목은 제외.
 */
inline fun <reified A: Annotation, R> Class<*>.collectAnnotatedMemberValues(
    distinct: Boolean = true,
    crossinline valueExtractor: (A) -> R?
): List<R> {
    val results = ArrayList<R>()
    // Kotlin properties
    this.kotlin.memberProperties.forEach { prop ->
        prop.getAnnotation<A>()?.let { ann -> valueExtractor(ann)?.let { results += it } }
    }
    // Java declared fields (직접 필드 어노테이션)
    this.declaredFields.forEach { field ->
        field.getAnnotation(A::class.java)?.let { ann -> valueExtractor(ann)?.let { results += it } }
    }
    return if (distinct) results.distinct() else results
}
inline fun <reified A: Annotation, R> KClass<*>.collectAnnotatedMemberValues(
    distinct: Boolean = true,
    crossinline valueExtractor: (A) -> R?
) = this.java.collectAnnotatedMemberValues(distinct, valueExtractor)

// Generic collection (non-reified) overloads
@Suppress("UNCHECKED_CAST")
fun <A: Annotation, R> Class<*>.collectAnnotatedMemberValues(
    annClass: Class<A>,
    distinct: Boolean = true,
    valueExtractor: (A) -> R?
): List<R> {
    val results = ArrayList<R>()
    // Kotlin properties
    this.kotlin.memberProperties.forEach { prop ->
        prop.getAnnotation(annClass)?.let { ann -> valueExtractor(ann)?.let { results += it } }
    }
    // Java declared fields
    this.declaredFields.forEach { field ->
        field.getAnnotation(annClass)?.let { ann -> valueExtractor(ann)?.let { results += it } }
    }
    return if (distinct) results.distinct() else results
}
@Suppress("UNCHECKED_CAST")
fun <A: Annotation, R> KClass<*>.collectAnnotatedMemberValues(
    annClass: Class<A>,
    distinct: Boolean = true,
    valueExtractor: (A) -> R?
) = this.java.collectAnnotatedMemberValues(annClass, distinct, valueExtractor)

/**
 * 주어진 Annotation 타입의 특정 attribute(메서드) 값을 수집 (String 변환).
 * - attributeName 메서드가 존재하지 않으면 무시
 * - 반환 타입이 String이면 그대로, 배열이면 join, 그 외 toString()
 * - skipBlank=true 시 blank 문자열 제외
 */
fun <A: Annotation> Class<*>.collectAnnotationAttributeValues(
    annClass: Class<A>,
    attributeName: String,
    distinct: Boolean = true,
    skipBlank: Boolean = true
) = collectAnnotatedMemberValues(annClass, distinct) { ann ->
    try {
        val m = annClass.getDeclaredMethod(attributeName).apply { isAccessible = true }
        val raw = m.invoke(ann)
        val str = when (raw) {
            null -> null
            is String -> raw
            is Array<*> -> raw.joinToString(",")
            else -> raw.toString()
        }
        if (skipBlank && str.isNullOrBlank()) null else str
    } catch (_: Exception) { null }
}
fun <A: Annotation> KClass<*>.collectAnnotationAttributeValues(
    annClass: Class<A>,
    attributeName: String,
    distinct: Boolean = true,
    skipBlank: Boolean = true
) = this.java.collectAnnotationAttributeValues(annClass, attributeName, distinct, skipBlank)

/** reified 오버로드: Kotlin 사용 시 annClass 전달 생략 */
inline fun <reified A: Annotation> Class<*>.collectAnnotationAttributeValues(
    attributeName: String,
    distinct: Boolean = true,
    skipBlank: Boolean = true
) = collectAnnotationAttributeValues(A::class.java, attributeName, distinct, skipBlank)

inline fun <reified A: Annotation> KClass<*>.collectAnnotationAttributeValues(
    attributeName: String,
    distinct: Boolean = true,
    skipBlank: Boolean = true
) = this.java.collectAnnotationAttributeValues(A::class.java, attributeName, distinct, skipBlank)
