package com.hunet.common.data.jpa.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import kotlin.reflect.full.companionObjectInstance

/**
 * Generic JPA [jakarta.persistence.AttributeConverter] for mapping enums that expose a value and a companion object lookup.
 *
 * Contract expected on the enum type [E]:
 * 1. A value accessor: either a Kotlin property `val value: V` (generates `getValue()`) or a `fun getValue(): V` method.
 * 2. Companion object function: `fun fromValue(value: V): E` that returns the matching enum constant.
 *
 * Usage:
 *  - Implement a concrete converter: `class StatusConverter : GenericEnumConverter<Status, String>(Status::class.java)`
 *  - Annotate with `@Converter(autoApply = true)` if you want automatic application to all matching fields.
 *
 * Error cases:
 *  - Missing value accessor -> IllegalStateException
 *  - Missing companion or fromValue method -> IllegalStateException
 */
@Converter(autoApply = false)
@Suppress("UNCHECKED_CAST")
abstract class GenericEnumConverter<E : Enum<E>, V : Any>(
    private val enumType: Class<E>,
    private val valueType: Class<V> = String::class.java as Class<V>
) : AttributeConverter<E, V> {
    override fun convertToDatabaseColumn(attribute: E?): V? {
        if (attribute == null) return null
        // 1) getValue() 메서드
        enumType.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }?.let { m ->
            return m.invoke(attribute) as V
        }
        // 2) value 필드
        enumType.declaredFields.firstOrNull { it.name == "value" }?.let { f ->
            f.isAccessible = true
            return f.get(attribute) as V
        }
        throw IllegalStateException("Enum ${enumType.simpleName} must declare getValue() or 'value' field")
    }

    override fun convertToEntityAttribute(dbData: V?): E? = dbData?.let { value ->
        // 1) Kotlin companion object 방식
        enumType.kotlin.companionObjectInstance?.let { companion ->
            val method = companion::class.java.methods.firstOrNull { m ->
                m.name == "fromValue" && m.parameterCount == 1 && valueType.isAssignableFrom(m.parameterTypes[0])
            }
            if (method != null) return method.invoke(companion, value) as E
        }
        // 2) Java static fromValue(valueType) 방식
        enumType.methods.firstOrNull { m ->
            m.name == "fromValue" && m.parameterCount == 1 && valueType.isAssignableFrom(m.parameterTypes[0])
        }?.let { static -> return static.invoke(null, value) as E }
        throw IllegalStateException(
            "Enum ${enumType.simpleName} must provide companion fromValue(${valueType.simpleName}) or static fromValue(${valueType.simpleName})"
        )
    }
}