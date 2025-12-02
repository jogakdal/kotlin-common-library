@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import com.hunet.common.util.getAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import kotlin.reflect.full.memberProperties
import java.util.concurrent.TimeUnit

@ExtendWith(MockitoExtension::class)
class StandardApiResponseAdviceMigrationTest {
    @Test
    fun `getAnnotation을 사용한 InjectDuration annotation 검색이 정상 동작한다`() {
        data class ResponseWithDuration(
            val data: String,

            @InjectDuration(TimeUnit.MILLISECONDS)
            var duration: Long = 0,

            @InjectDuration(TimeUnit.SECONDS)
            var durationSeconds: Int = 0,

            val message: String = "success"
        )

        val properties = ResponseWithDuration::class.memberProperties
        val durationProp = properties.first { it.name == "duration" }
        val durationSecondsProp = properties.first { it.name == "durationSeconds" }
        val dataProp = properties.first { it.name == "data" }
        val messageProp = properties.first { it.name == "message" }

        val durationAnnotation = durationProp.getAnnotation<InjectDuration>()
        val durationSecondsAnnotation = durationSecondsProp.getAnnotation<InjectDuration>()
        val dataAnnotation = dataProp.getAnnotation<InjectDuration>()
        val messageAnnotation = messageProp.getAnnotation<InjectDuration>()

        assertNotNull(durationAnnotation)
        assertEquals(TimeUnit.MILLISECONDS, durationAnnotation?.unit)

        assertNotNull(durationSecondsAnnotation)
        assertEquals(TimeUnit.SECONDS, durationSecondsAnnotation?.unit)

        assertNull(dataAnnotation)
        assertNull(messageAnnotation)
    }

    @Test
    fun `InjectDuration annotation 필터링이 getAnnotation으로 정상 동작한다`() {
        data class TestResponse(
            val result: String,

            @InjectDuration(TimeUnit.MILLISECONDS)
            var processingTime: Long = 0,

            @InjectDuration(TimeUnit.MICROSECONDS)
            var detailTime: Long = 0,

            var timestamp: Long = System.currentTimeMillis()
        )

        val allProps = TestResponse::class.memberProperties

        val targets = allProps.filter { it.getAnnotation<InjectDuration>() != null }

        assertEquals(2, targets.size)
        val targetNames = targets.map { it.name }.sorted()
        assertEquals(listOf("detailTime", "processingTime"), targetNames)
    }

    @Test
    fun `convertForProperty에서 getAnnotation 기반 처리가 정상 동작한다`() {
        data class DurationResponse(
            @InjectDuration(TimeUnit.MILLISECONDS)
            var millis: Long = 0,

            @InjectDuration(TimeUnit.SECONDS)
            var seconds: Int = 0,

            @InjectDuration(TimeUnit.MICROSECONDS)
            var micros: Double = 0.0,

            @InjectDuration(TimeUnit.NANOSECONDS)
            var nanos: String = ""
        )

        val properties = DurationResponse::class.memberProperties
        val millisProp = properties.first { it.name == "millis" }
        val secondsProp = properties.first { it.name == "seconds" }
        val microsProp = properties.first { it.name == "micros" }
        val nanosProp = properties.first { it.name == "nanos" }

        fun convertForProperty(prop: kotlin.reflect.KProperty1<DurationResponse, *>, elapsedNanos: Long): Any? {
            val ann = prop.getAnnotation<InjectDuration>() ?: return null
            val v = ann.unit.convert(elapsedNanos, TimeUnit.NANOSECONDS)
            return when (prop.returnType.classifier) {
                Long::class -> v
                Int::class -> v.toInt()
                Double::class -> v.toDouble()
                String::class -> v.toString()
                else -> null
            }
        }

        val testElapsedNanos = 1_500_000_000L

        val millisResult = convertForProperty(millisProp, testElapsedNanos)
        val secondsResult = convertForProperty(secondsProp, testElapsedNanos)
        val microsResult = convertForProperty(microsProp, testElapsedNanos)
        val nanosResult = convertForProperty(nanosProp, testElapsedNanos)

        assertEquals(1500L, millisResult)
        assertEquals(1, secondsResult)
        assertEquals(1500000.0, microsResult)
        assertEquals("1500000000", nanosResult)
    }

    @Test
    fun `data class copy 함수에서 getAnnotation 기반 처리가 정상 동작한다`() {
        data class CopyTestResponse(
            val id: String,

            @InjectDuration(TimeUnit.MILLISECONDS)
            var duration: Long = 0,

            @InjectDuration(TimeUnit.SECONDS)
            var seconds: Int = 0,

            val message: String
        )

        val originalResponse = CopyTestResponse(
            id = "test",
            duration = 0,
            seconds = 0,
            message = "original"
        )

        val allProps = CopyTestResponse::class.memberProperties
        val copyParameters = mutableMapOf<String, Any?>()

        allProps.forEach { prop ->
            copyParameters[prop.name] = prop.get(originalResponse)
        }

        allProps.forEach { prop ->
            if (prop.getAnnotation<InjectDuration>() != null) {
                when (prop.name) {
                    "duration" -> copyParameters["duration"] = 1500L
                    "seconds" -> copyParameters["seconds"] = 2
                }
            }
        }

        assertEquals("test", copyParameters["id"])
        assertEquals(1500L, copyParameters["duration"])
        assertEquals(2, copyParameters["seconds"])
        assertEquals("original", copyParameters["message"])
    }

    @Test
    fun `InjectDuration annotation이 없는 property는 null을 반환한다`() {
        data class NoAnnotationResponse(
            val data: String,
            var timestamp: Long = 0
        )

        val prop = NoAnnotationResponse::class.memberProperties.first { it.name == "timestamp" }
        val annotation = prop.getAnnotation<InjectDuration>()

        assertNull(annotation)
    }

    @Test
    fun `Java field annotation도 getAnnotation fallback으로 처리된다`() {
        class JavaFieldResponse {
            @JvmField
            @InjectDuration(TimeUnit.MILLISECONDS)
            var javaDuration: Long = 0

            var kotlinField: String = ""
        }

        val properties = JavaFieldResponse::class.memberProperties
        val javaDurationProp = properties.first { it.name == "javaDuration" }
        val kotlinFieldProp = properties.first { it.name == "kotlinField" }

        val javaAnnotation = javaDurationProp.getAnnotation<InjectDuration>()
        val kotlinAnnotation = kotlinFieldProp.getAnnotation<InjectDuration>()

        assertNotNull(javaAnnotation)
        assertEquals(TimeUnit.MILLISECONDS, javaAnnotation?.unit)

        assertNull(kotlinAnnotation)
    }
}
