package com.hunet.common.data.jpa.sequence

import com.hunet.common.util.getAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

class SequenceGeneratorMigrationTest {
    private val testSequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? {
            return "${prefix}001"
        }
    }

    data class TestEntity(
        @GenerateSequentialCode(prefixExpression = "'TEST_'")
        var code: String = "",

        @GenerateSequentialCode(prefixExpression = "'ORDER_'")
        var orderNumber: String = "",

        var name: String = "test"
    )

    @Test
    fun `getAnnotation을 사용한 annotation 검색이 정상 동작한다`() {
        val codeProperty = TestEntity::class.memberProperties.first { it.name == "code" }
        val nameProperty = TestEntity::class.memberProperties.first { it.name == "name" }

        val codeAnnotation = codeProperty.getAnnotation<GenerateSequentialCode>()
        val nameAnnotation = nameProperty.getAnnotation<GenerateSequentialCode>()

        assertNotNull(codeAnnotation)
        assertEquals("'TEST_'", codeAnnotation?.prefixExpression)

        assertNull(nameAnnotation)
    }

    @Test
    fun `applySequentialCode에서 getAnnotation 기반 필터링이 정상 동작한다`() {
        val entity = TestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("TEST_001", entity.code)
        assertEquals("ORDER_001", entity.orderNumber)
        assertEquals("test", entity.name, "annotation이 없으면 변경되지 않아야 한다.")
    }

    @Test
    fun `이미 값이 있는 필드는 건드리지 않는다`() {
        val entity = TestEntity(code = "EXISTING", orderNumber = "", name = "test")

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("EXISTING", entity.code, "이미 값이 있는 필드는 변경되지 않아야 한다.")
        assertEquals("ORDER_001", entity.orderNumber, "빈 값인 필드는 생성되어야 한다.")
    }

    @Test
    fun `prefix expression이 정상 처리된다`() {
        val entity = TestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("TEST_001", entity.code)
        assertEquals("ORDER_001", entity.orderNumber)
    }

    class JavaFieldTestEntity {
        @JvmField
        @GenerateSequentialCode(prefixExpression = "'JAVA_'")
        var javaFieldCode: String = ""

        var normalField: String = ""
    }

    @Test
    fun `Java 필드 annotation도 정상 처리된다`() {
        val entity = JavaFieldTestEntity()

        applySequentialCode(entity, testSequenceGenerator)

        assertEquals("JAVA_001", entity.javaFieldCode)
        assertEquals("", entity.normalField, "annotation이 없으면 변경되지 않아야 한다.")
    }

    @Test
    fun `GenerateSequentialCode annotation 속성들이 getAnnotation으로 정상 추출된다`() {
        val codeProperty = TestEntity::class.memberProperties.first { it.name == "code" }
        val annotation = codeProperty.getAnnotation<GenerateSequentialCode>()

        assertNotNull(annotation)
        assertEquals("'TEST_'", annotation!!.prefixExpression)
        assertEquals(DefaultPrefixProvider::class, annotation.prefixProvider)
    }
}
