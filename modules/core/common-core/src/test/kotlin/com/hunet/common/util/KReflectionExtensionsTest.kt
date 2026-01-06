package com.hunet.common.util

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaGetter

@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CLASS
)
annotation class Marker(val value: String)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY)
annotation class AnotherMarker(val tag: String)

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
        assertEquals("property", ann!!.value, "property 우선순위가 지켜져야 합니다.")
    }

    @Test
    fun `property 애노테이션 - field 전용 애노테이션 fallback 동작`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "fieldOnly" }
        val ann = prop.getAnnotation<Marker>()
        assertNotNull(ann, "Kotlin findAnnotation 실패 후 javaField 경로로 조회되어야 합니다.")
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
        assertTrue(ClassAnnotationSample::class.hasAnnotation<Marker>())
    }

    @Test
    fun `hasAnnotation false 케이스`() {
        val prop = BaseEntity::class.declaredMemberProperties.first { it.name == "nonAnnotated" }
        assertFalse(prop.hasAnnotation<Marker>())
    }

    @Test
    fun `annotatedFields - @MappedSuperclass 체인을 타고 mutable + 애노테이션 있는 필드만 수집`() {
        val fields = ConcreteEntity::class.annotatedFields<Marker>()
        val names = fields.map { it.name }.sorted()
        assertEquals(listOf("createdAt", "id"), names, "ConcreteEntity 자신의 필드나 immutable 필드는 제외되어야 합니다.")
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

    @Test
    fun `getAnnotation과 findAnnotation 결과는 동일해야 한다`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "value" }
        val viaGet = prop.getAnnotation<Marker>()
        val viaFind = prop.findAnnotation<Marker>()
        assertNotNull(viaGet)
        assertNotNull(viaFind)
        assertEquals(viaFind!!.value, viaGet!!.value)
    }

    @Test
    fun `다른 타입 annotation 요청 시 null 반환 및 hasAnnotation false`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "value" }
        val wrong = prop.getAnnotation<AnotherMarker>()
        assertNull(wrong)
        assertFalse(prop.hasAnnotation<AnotherMarker>())
    }

    @Test
    fun `non-reified 프로퍼티 애노테이션 조회`() {
        val prop = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first { it.name == "value" }
        val ann = prop.getAnnotation(Marker::class.java)
        assertNotNull(ann)
        assertEquals("property", ann!!.value)
    }

    @Test
    fun `Field 비 reified hasAnnotation 동작`() {
        val fieldOnlyField = PropertyAnnotationPrioritySample::class.java.getDeclaredField("fieldOnly")
        assertTrue(fieldOnlyField.hasAnnotation(Marker::class.java))
        assertFalse(fieldOnlyField.hasAnnotation(AnotherMarker::class.java))
    }

    @Test
    fun `getter 메서드 애노테이션 비 reified 조회`() {
        val getterProp = PropertyAnnotationPrioritySample::class.declaredMemberProperties.first {
            it.name == "getterOnly"
        }
        val getterMethod = getterProp.javaGetter!!
        assertTrue(getterMethod.hasAnnotation(Marker::class.java))
    }

    @Test
    fun `KClass 비 reified getAnnotation 조회`() {
        assertTrue(ClassAnnotationSample::class.hasAnnotation(Marker::class.java))
        val ann = ClassAnnotationSample::class.getAnnotation(Marker::class.java)
        assertNotNull(ann)
        assertEquals("onClass", ann!!.value)
        assertFalse(ClassAnnotationSample::class.hasAnnotation(AnotherMarker::class.java))
    }

    @Test
    fun `annotatedFields 비 reified 조회`() {
        val fields = ConcreteEntity::class.annotatedFields(Marker::class.java)
        val names = fields.map { it.name }.sorted()
        assertEquals(listOf("createdAt", "id"), names)
    }

    @Test
    fun `캐시 초기화 후 annotatedFields 결과 일관성 유지`() {
        val first = ConcreteEntity::class.annotatedFields<Marker>().map { it.name }.sorted()
        clearReflectionAnnotationCaches()
        val second = ConcreteEntity::class.annotatedFields<Marker>().map { it.name }.sorted()
        assertEquals(first, second, "캐시 초기화 후 annotatedFields 결과가 달라지면 안 됩니다.")
    }

    @Test
    fun `collectColumnNames - 기본 수집 및 blank 제외`() {
        class ColumnSample(
            @Column(name = "col_a") var a: String? = null,
            @field:Column(name = "col_b") var b: String? = null,
            @get:Column(name = "col_c") var c: String? = null,
            @Column(name = "") var ignoredBlank: String? = null,
        )
        val names = ColumnSample::class.collectAnnotationAttributeValues<Column>("name").sorted()
        assertEquals(listOf("col_a", "col_b", "col_c"), names)
    }

    @Test
    fun `collectColumnNames - 중복 발생 시 distinct=true로 1회만 유지`() {
        class DupColumnSample {
            @field:Column(name = "dup")
            @get:Column(name = "dup")
            var x: String? = null
        }
        val distinctNames = DupColumnSample::class.collectAnnotationAttributeValues<Column>("name", distinct = true)
        assertEquals(listOf("dup"), distinctNames)
        val allNames = DupColumnSample::class.collectAnnotationAttributeValues<Column>("name", distinct = false)
        assertEquals(2, allNames.size)
        assertTrue(allNames.all { it == "dup" })
    }

    @Test
    fun `collectColumnNames - field only annotation 수집`() {
        class FieldOnlySample {
            @field:Column(name = "only_field")
            var f: String? = null
        }
        val names = FieldOnlySample::class.collectAnnotationAttributeValues<Column>("name")
        assertEquals(listOf("only_field"), names)
    }

    @Test
    fun `collectColumnNames - getter only annotation 수집`() {
        class GetterOnlySample {
            @get:Column(name = "only_getter")
            var g: String? = null
        }
        val names = GetterOnlySample::class.collectAnnotationAttributeValues<Column>("name")
        assertEquals(listOf("only_getter"), names)
    }
}
