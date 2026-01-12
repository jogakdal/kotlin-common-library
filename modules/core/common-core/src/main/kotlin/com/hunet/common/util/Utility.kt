package com.hunet.common.util

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

val String.isDigit: Boolean get() {
    if (length == 0) return false

    forEachIndexed { index, it ->
        if (index == 0) {
            if (it != '+' && it != '-' && !it.isDigit()) return false
        }
        else if (!it.isDigit()) return false
    }

    return true
}

fun getDiffRate(curr: Long, before: Long, infinityValue: Float? = null): Float? {
    return if (before == 0L) infinityValue
    else (curr - before) / before.toFloat() * 100
}

fun isEmptyOrNull(str: String?): Boolean {
    return str == null || str.isEmpty()
}

fun isNotEmpty(str: String?): Boolean {
    return !isEmptyOrNull(str)
}

fun Any.smartCopyTo(target: Any): Any {
    val targetPropsByName = target::class.memberProperties
        .filterIsInstance<KMutableProperty1<Any, Any?>>()
        .associateBy { it.name }

    this::class.memberProperties.forEach { sp ->
        targetPropsByName[sp.name]?.let { tp ->
            tp.isAccessible = true
            sp.isAccessible = true

            val value = sp.getter.call(this)
            require(value != null || tp.returnType.isMarkedNullable) {
                "null 값을 non-null 프로퍼티 '${sp.name}'에 대입할 수 없습니다."
            }

            if (sp.returnType.classifier == tp.returnType.classifier &&
                sp.returnType.arguments == tp.returnType.arguments) {
                tp.set(target, value)
            }
        }
    }
    return target
}

fun Any.smartCopyFrom(source: Any): Any = source.smartCopyTo(this)

/**
 * 문자열에서 앞뒤 따옴표(쌍따옴표 또는 홑따옴표)를 제거합니다.
 *
 * 예시:
 * - `"hello"` → `hello`
 * - `'world'` → `world`
 * - `hello` → `hello` (변화 없음)
 */
fun String.unquote(): String {
    val trimmed = this.trim()
    return when {
        trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
            trimmed.drop(1).dropLast(1)
        trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") ->
            trimmed.drop(1).dropLast(1)
        else -> trimmed
    }
}
