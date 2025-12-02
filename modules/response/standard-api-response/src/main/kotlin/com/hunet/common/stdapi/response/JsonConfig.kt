package com.hunet.common.stdapi.response

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString(), formatter)
    }
}

object InstantSerializer : KSerializer<Instant> {
    private val formatter = DateTimeFormatter.ISO_INSTANT

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(formatter.format(value)) // ISO-8601 형식으로 Z 포함
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

object AnyValueSerializer : JsonContentPolymorphicSerializer<Any>(Any::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Any> =
        when {
            element is JsonPrimitive && element.isString -> String.serializer()
            element is JsonPrimitive && element.booleanOrNull != null -> Boolean.serializer()
            element is JsonPrimitive && element.longOrNull != null -> Long.serializer()
            element is JsonPrimitive && element.intOrNull != null -> Int.serializer()
            element is JsonPrimitive && element.floatOrNull != null -> Float.serializer()
            element is JsonPrimitive && element.doubleOrNull != null -> Double.serializer()
            element is JsonArray -> ListSerializer(AnyValueSerializer)
            element is JsonObject -> MapSerializer(String.serializer(), AnyValueSerializer)
            else -> AnyValueSerializer
        }
}

object JsonConfig {
    val json: Json = Json {
        serializersModule = SerializersModule {
            contextual(LocalDateTime::class, LocalDateTimeSerializer)
            contextual(Instant::class, InstantSerializer)
            contextual(Any::class, AnyValueSerializer)
            contextual(Map::class) { serializers ->
                @Suppress("UNCHECKED_CAST")
                MapSerializer(
                    serializers[0] as KSerializer<String>,
                    serializers[1] as KSerializer<JsonElement>
                )
            }
        }
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
