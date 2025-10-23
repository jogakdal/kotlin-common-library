package com.hunet.common.lib.repository

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import kotlin.reflect.full.companionObjectInstance

@Converter(autoApply = false)
@Suppress("UNCHECKED_CAST")
abstract class GenericEnumConverter<E : Enum<E>, V : Any>(
    private val enumType: Class<E>,
    private val valueType: Class<V> = String::class.java as Class<V>
) : AttributeConverter<E, V> {
    override fun convertToDatabaseColumn(attribute: E?): V? = attribute?.let {
        try {
            val method = enumType.getMethod("getValue")
            method.invoke(it) as V
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("Enum ${enumType.simpleName} must have value property or getValue()", e)
        }
    }

    override fun convertToEntityAttribute(dbData: V?): E? = dbData?.let { value ->
        try {
            val companion = enumType.kotlin.companionObjectInstance
                ?: throw IllegalStateException("Enum ${enumType.simpleName} has no companion object")
            val method = companion::class.java.getMethod("fromValue", valueType)
            method.invoke(companion, value) as E
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException(
                "Companion of ${enumType.simpleName} must have fromValue(${valueType.simpleName})", e
            )
        }
    }
}

