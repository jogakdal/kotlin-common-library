package com.hunet.common.lib.std_api_documentation

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import java.util.EnumSet
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EnumConstant

class DescriptiveEnumSerializer : JsonSerializer<DescriptiveEnum>() {
    override fun serialize(value: DescriptiveEnum, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.value)
    }
}

class DescriptiveEnumDeserializer(
    private val targetType: JavaType? = null
) : JsonDeserializer<Any>(), ContextualDeserializer {
    private fun defaultEnum(raw: Class<*>): Any? {
        runCatching {
            return raw.getMethod("fromValue", String::class.java).invoke(null, "")
        }
        return raw.enumConstants?.firstOrNull { (it as DescriptiveEnum).value.isEmpty() }
    }

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
        val t = property?.type ?: ctxt.contextualType
        val target: JavaType? = when {
            t == null -> null
            EnumSet::class.java.isAssignableFrom(t.rawClass) ->
                t.contentType?:property?.type?.contentType?:ctxt.contextualType?.contentType
            t.isArrayType -> t.contentType
            t.isCollectionLikeType -> t.contentType
            t.isMapLikeType -> t.contentType
            else -> t
        }
        return DescriptiveEnumDeserializer(target)
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any {
        val text: String? = if (p.currentToken == JsonToken.VALUE_NULL) null else p.valueAsString
        val javaType = targetType ?: ctxt.contextualType ?: throw InvalidFormatException(
            p, "대상 enum 타입 결정 불가.", text, Any::class.java
        )
        val raw = javaType.rawClass
        if (!raw.isEnum) throw InvalidFormatException(p, "대상 ${'$'}{raw.name}: enum 타입 아님.", text, raw)
        if (text.isNullOrBlank()) defaultEnum(raw)?.let { return it }
        val byFactory = runCatching { raw.getMethod("fromValue", String::class.java).invoke(null, text) }.getOrNull()
        if (byFactory != null) return byFactory
        return raw.enumConstants?.firstOrNull {
            (it as DescriptiveEnum).value.equals(text, true) || (it as Enum<*>).name.equals(text, true)
        } ?: throw InvalidFormatException(p, "유효하지 않은 ${'$'}{raw.simpleName} 값: '${'$'}text'", text, raw)
    }

    override fun getNullValue(ctxt: DeserializationContext): Any? =
        (targetType ?: ctxt.contextualType)?.rawClass?.let { defaultEnum(it) }

    override fun getAbsentValue(ctxt: DeserializationContext): Any? = getNullValue(ctxt)
}

@JsonSerialize(using = DescriptiveEnumSerializer::class)
@JsonDeserialize(using = DescriptiveEnumDeserializer::class)
interface DescriptiveEnum {
    val value: String
    val description: String
    val describable: Boolean
    fun toText() = description
    fun toDescription() = "'${'$'}value': ${'$'}description"
    companion object {
        const val DESCRIPTION_MARKER = "{$}DESCRIPTION{$}"
        fun toStringList(list: List<DescriptiveEnum>) = list.map { it.value }
        fun toDescription(list: Array<out DescriptiveEnum>) =
            list.filter { it.describable }.joinToString { it.toDescription() }

        fun replaceDescription(description: String, type: KClass<out DescriptiveEnum>) =
            description.replace(DESCRIPTION_MARKER, toDescription(type.java.enumConstants))
    }
}

interface ExceptionCode : DescriptiveEnum {
    val code: String
    val message: String
}
