package com.hunet.common.util

import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.KMutableProperty1

@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CLASS
)
annotation class Marker(val value: String)

class PropertyAnnotationPrioritySample {
    @get:Marker("getter")
    @field:Marker("field")
    @property:Marker("property")
    var value: String = ""

    @field:Marker("fieldOnly")
    var fieldOnly: String = ""

    @get:Marker("getterOnly")
    var getterOnly: String = ""
}

data class ConstructorParamSample(
    @param:Marker("paramAnnotated")
    val paramOnly: String,
)

@MappedSuperclass
open class BaseEntity {
    @Marker("id")
    var id: Long? = null
    var nonAnnotated: String? = null
}

@MappedSuperclass
open class AuditableEntity : BaseEntity() {
    @Marker("created")
    var createdAt: Long? = null
    val immutableValue: String = "immu" // immutable -> 제외되어야 함
}

class ConcreteEntity : AuditableEntity() {
    @Marker("extra")
    var extra: String? = null // ConcreteEntity 자체엔 @MappedSuperclass 없음 -> 제외
}

@Marker("onClass")
class ClassAnnotationSample

class KReflectionExtensionsTest {

    @Test
    fun `property 애노테이션 우선순위 - property target이 가장 먼저 선택된다`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "value" }
        val ann = prop.getAnnotation<Marker>()
        assertNotNull(ann)
        assertEquals("property", ann!!.value, "property 우선순위가 지켜져야 합니다")
    }

    @Test
    fun `property 애노테이션 - field 전용 애노테이션 fallback 동작`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "fieldOnly" }
        // Kotlin findAnnotation 실패 후 javaField 경로로 조회되어야 함
        val ann = prop.getAnnotation<Marker>()
        assertNotNull(ann)
        assertEquals("fieldOnly", ann!!.value)
    }

    @Test
    fun `property 애노테이션 - getter 전용 애노테이션 fallback 동작`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "getterOnly" }
        val ann = prop.getAnnotation<Marker>()
        assertNotNull(ann)
        assertEquals("getterOnly", ann!!.value)
    }

    @Test
    fun `생성자 파라미터 애노테이션 fallback 동작`() {
        val prop = ConstructorParamSample::class.declaredMemberProperties.first { it.name == "paramOnly" }
        val ann = prop.getAnnotation<Marker>()
        assertNotNull(ann)
        assertEquals("paramAnnotated", ann!!.value)
    }

    @Test
    fun `KClass 대상 애노테이션 조회`() {
        val ann = ClassAnnotationSample::class.getAnnotation<Marker>()
        assertNotNull(ann)
        assertEquals("onClass", ann!!.value)
        assertTrue(ClassAnnotationSample::class.isExistAnnotation<Marker>())
    }

    @Test
    fun `isExistAnnotation false 케이스`() {
        val prop = BaseEntity::class.declaredMemberProperties.first { it.name == "nonAnnotated" }
        assertFalse(prop.isExistAnnotation<Marker>())
    }

    @Test
    fun `annotatedFields - @MappedSuperclass 체인을 타고 mutable + 애노테이션 있는 필드만 수집`() {
        val fields = ConcreteEntity::class.annotatedFields<Marker>()
        val names = fields.map { it.name }.sorted()
        assertEquals(listOf("createdAt", "id"), names, "ConcreteEntity 자신의 필드나 immutable 필드는 제외되어야 합니다")
    }

    @Test
    fun `annotatedFields - 클래스 체인에 @MappedSuperclass 없으면 빈 목록`() {
        class PlainA { @Marker("x") var x: String = "" }
        val collected = PlainA::class.annotatedFields<Marker>()
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `annotatedFields - 접근 가능성 검증 (isAccessible=true 설정)`() {
        val entity = ConcreteEntity().apply { id = 1L; createdAt = 100L }
        val fields = ConcreteEntity::class.annotatedFields<Marker>()
        @Suppress("UNCHECKED_CAST")
        val values = fields.associate { f ->
            val typed = f as KMutableProperty1<ConcreteEntity, *>
            f.name to typed.get(entity)
        }
        assertEquals(1L, values["id"])
        assertEquals(100L, values["createdAt"])
    }
}
