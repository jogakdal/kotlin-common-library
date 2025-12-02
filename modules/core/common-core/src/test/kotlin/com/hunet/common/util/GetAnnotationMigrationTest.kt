package com.hunet.common.util

import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation

/**
 * findAnnotation에서 getAnnotation으로 마이그레이션된 기능이 정상 동작하는지 검증하는 테스트
 */
class GetAnnotationMigrationTest {

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
    annotation class TestAnnotation(val value: String)

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY)
    annotation class PropertyOnlyAnnotation(val value: String)

    @MappedSuperclass
    open class BaseTestEntity {
        @TestAnnotation("base_field")
        var baseField: String = ""

        @PropertyOnlyAnnotation("base_property")
        var baseProperty: String = ""
    }

    @MappedSuperclass
    open class MiddleTestEntity : BaseTestEntity() {
        @TestAnnotation("middle_field")
        var middleField: String = ""
    }

    class ConcreteTestEntity : MiddleTestEntity() {
        @TestAnnotation("concrete_field")
        var concreteField: String = ""
    }

    data class ConstructorTestEntity(
        @param:TestAnnotation("constructor_param")
        val paramField: String
    )

    @Test
    fun `getAnnotation은 기존 findAnnotation과 동일하게 동작해야 한다`() {
        val prop = BaseTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "baseField" }

        // getAnnotation과 findAnnotation 결과가 동일해야 함
        val getResult = prop.getAnnotation<TestAnnotation>()
        val findResult = prop.findAnnotation<TestAnnotation>()

        assertNotNull(getResult)
        assertNotNull(findResult)
        assertEquals(findResult?.value, getResult?.value)
    }

    @Test
    fun `getAnnotation은 Java 필드 fallback을 제공한다`() {
        val prop = BaseTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "baseField" }

        // Java 필드에서도 annotation을 찾을 수 있어야 함
        val annotation = prop.getAnnotation<TestAnnotation>()
        assertNotNull(annotation)
        assertEquals("base_field", annotation?.value)
    }

    @Test
    fun `getAnnotation은 생성자 파라미터 fallback을 제공한다`() {
        val prop = ConstructorTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "paramField" }

        val annotation = prop.getAnnotation<TestAnnotation>()
        assertNotNull(annotation)
        assertEquals("constructor_param", annotation?.value)
    }

    @Test
    fun `annotatedFields는 getAnnotation을 사용하여 정상 동작한다`() {
        val fields = ConcreteTestEntity::class.annotatedFields<TestAnnotation>()

        // @MappedSuperclass 체인을 따라 수집되어야 함
        val fieldNames = fields.map { it.name }.sorted()
        assertEquals(listOf("baseField", "middleField"), fieldNames)

        // 각 필드의 annotation 값 확인
        fields.forEach { field ->
            val annotation = field.getAnnotation<TestAnnotation>()
            assertNotNull(annotation)
            assertTrue(annotation!!.value.contains("field"))
        }
    }

    @Test
    fun `isExistAnnotation은 getAnnotation 기반으로 정상 동작한다`() {
        val baseFieldProp = BaseTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "baseField" }

        val basePropProp = BaseTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "baseProperty" }

        assertTrue(baseFieldProp.isExistAnnotation<TestAnnotation>())
        assertFalse(baseFieldProp.isExistAnnotation<PropertyOnlyAnnotation>())

        assertTrue(basePropProp.isExistAnnotation<PropertyOnlyAnnotation>())
        assertFalse(basePropProp.isExistAnnotation<TestAnnotation>())
    }

    @Test
    fun `annotation이 없는 경우 null을 반환한다`() {
        val prop = BaseTestEntity::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<*, *>>()
            .first { it.name == "baseField" }

        // 존재하지 않는 annotation 타입 요청
        val result = prop.getAnnotation<PropertyOnlyAnnotation>()
        assertNull(result)
        assertFalse(prop.isExistAnnotation<PropertyOnlyAnnotation>())
    }

    @Test
    fun `KClass에 대한 getAnnotation도 정상 동작한다`() {
        val annotation = AnnotatedTestClass::class.getAnnotation<TestAnnotation>()
        assertNotNull(annotation)
        assertEquals("class_annotation", annotation?.value)
        assertTrue(AnnotatedTestClass::class.isExistAnnotation<TestAnnotation>())
    }
}

@GetAnnotationMigrationTest.TestAnnotation("class_annotation")
class AnnotatedTestClass

