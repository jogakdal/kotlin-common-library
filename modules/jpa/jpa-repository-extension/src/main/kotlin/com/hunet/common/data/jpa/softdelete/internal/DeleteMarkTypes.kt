package com.hunet.common.data.jpa.softdelete.internal

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Date
import java.sql.Timestamp
import java.time.*
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

val MYSQL_DATETIME_MIN: LocalDateTime = LocalDateTime.of(1000, 1, 1, 0, 0)

enum class DeleteMarkValue(
    val value: String,
    val valueFunc: (() -> Any?),
    val aliveParamName: String = "alive",
    val aliveParamType: Class<*> = Any::class.java,
) {
    NULL("NULL", { null }, aliveParamName = "aliveNull"),
    NOT_NULL("NOT NULL", { Any() }, aliveParamName = "aliveNotNull"),
    YES("Y", { "Y" }, aliveParamName = "aliveStr"),
    NO("N", { "N" }, aliveParamName = "aliveStr"),
    NOW("NOW", LocalDateTime::now, aliveParamName = "aliveDt", aliveParamType = LocalDateTime::class.java),
    DATE_TIME_MIN(
        "1000-01-01 00:00:00",
        { MYSQL_DATETIME_MIN },
        aliveParamName = "aliveDt",
        aliveParamType = LocalDateTime::class.java
    ),
    TRUE("TRUE", { true }, aliveParamName = "aliveBool", aliveParamType = Boolean::class.java),
    FALSE("FALSE", { false }, aliveParamName = "aliveBool", aliveParamType = Boolean::class.java),
    ZERO("0", { 0 }, aliveParamName = "aliveInt", aliveParamType = Int::class.java),
    MINUS_ONE("-1", { -1 }, aliveParamName = "aliveInt", aliveParamType = Int::class.java),
    INT_MAX("INT_MAX", { Int.MAX_VALUE }, aliveParamName = "aliveInt", aliveParamType = Int::class.java);

    companion object {
        fun getDefaultDeleteMarkValue(info: DeleteMarkInfo): Any? =
            info.field?.javaField?.type?.let { type ->
                when {
                    type == String::class.java -> ""
                    Number::class.java.isAssignableFrom(type) -> when (type) {
                        Integer::class.java, Int::class.java -> 0
                        Long::class.java -> 0L
                        Short::class.java -> 0.toShort()
                        Byte::class.java -> 0.toByte()
                        Double::class.java -> 0.0
                        Float::class.java -> 0.0f
                        BigInteger::class.java -> BigInteger.ZERO
                        BigDecimal::class.java -> BigDecimal.ZERO
                        else -> throw IllegalStateException("지원하지 않는 숫자 타입입니다: ${type.name}")
                    }
                    type == Boolean::class.java || type == java.lang.Boolean::class.java -> true
                    type == LocalDateTime::class.java -> MYSQL_DATETIME_MIN
                    type == LocalDate::class.java -> MYSQL_DATETIME_MIN.toLocalDate()
                    type == Date::class.java -> Date.valueOf(MYSQL_DATETIME_MIN.toLocalDate())
                    type == Timestamp::class.java -> Timestamp.valueOf(MYSQL_DATETIME_MIN)
                    type == java.util.Date::class.java ->
                        java.util.Date.from(MYSQL_DATETIME_MIN.atOffset(ZoneOffset.UTC).toInstant())
                    type == Instant::class.java -> MYSQL_DATETIME_MIN.atOffset(ZoneOffset.UTC).toInstant()
                    type == OffsetDateTime::class.java -> MYSQL_DATETIME_MIN.atOffset(ZoneOffset.UTC)
                    else -> throw IllegalStateException("지원하지 않는 타입입니다: ${type.name}")
                }
            } ?: throw IllegalStateException("DeleteMarkInfo가 없습니다")
    }
}

data class DeleteMarkInfo(
    val field: KProperty1<*, *>?,
    val dbColumnName: String,
    val fieldName: String,
    val deleteMark: DeleteMarkValue?,
    val aliveMark: DeleteMarkValue?,
) {
    val deleteMarkValue: Any? by lazy {
        deleteMark?.valueFunc() ?: throw IllegalStateException("DeleteMarkValue cannot be null for field $fieldName")
    }
    val aliveMarkValue: Any? by lazy {
        aliveMark?.valueFunc() ?: throw IllegalStateException("AliveMarkValue cannot be null for field $fieldName")
    }
}

