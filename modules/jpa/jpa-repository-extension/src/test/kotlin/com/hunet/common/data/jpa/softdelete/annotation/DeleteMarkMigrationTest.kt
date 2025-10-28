package com.hunet.common.data.jpa.softdelete.annotation

import com.hunet.common.util.getAnnotation
import jakarta.persistence.Column
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * DeleteMark annotation에서 findAnnotation → getAnnotation 마이그레이션 테스트
 */
class DeleteMarkMigrationTest {

    @Test
    fun `getAnnotation을 사용한 DeleteMark annotation 검색이 정상 동작한다`() {
        class TestEntity {
            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.TRUE
            )
            var deleted: Boolean = false

            var normalField: String = ""
        }

        val deletedProperty = TestEntity::class.memberProperties.first { it.name == "deleted" }
        val normalProperty = TestEntity::class.memberProperties.first { it.name == "normalField" }

        // getAnnotation을 사용한 annotation 검색
        val deleteMarkAnnotation = deletedProperty.getAnnotation<DeleteMark>()
        val normalAnnotation = normalProperty.getAnnotation<DeleteMark>()

        assertNotNull(deleteMarkAnnotation)
        assertEquals(
            com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
            deleteMarkAnnotation?.aliveMark
        )
        assertEquals(
            com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.TRUE,
            deleteMarkAnnotation?.deletedMark
        )

        assertNull(normalAnnotation)
    }

    @Test
    fun `deleteMarkInfo extension property가 getAnnotation을 사용하여 정상 동작한다`() {
        class TestEntity {
            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NULL,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NOT_NULL
            )
            @Column(name = "is_deleted")
            var deleted: String? = null

            var name: String = ""
        }

        // deleteMarkInfo extension을 통한 정보 추출
        val deleteMarkInfo = TestEntity::class.deleteMarkInfo

        assertNotNull(deleteMarkInfo)
        assertEquals("deleted", deleteMarkInfo?.fieldName)
        assertEquals("is_deleted", deleteMarkInfo?.dbColumnName)
        assertEquals(
            com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NULL,
            deleteMarkInfo?.aliveMark
        )
        assertEquals(
            com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NOT_NULL,
            deleteMarkInfo?.deleteMark
        )
    }

    @Test
    fun `Column annotation이 없는 경우 property name을 column name으로 사용한다`() {
        class TestEntity {
            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.TRUE
            )
            var isDeleted: Boolean = false
        }

        val deleteMarkInfo = TestEntity::class.deleteMarkInfo

        assertNotNull(deleteMarkInfo)
        assertEquals("isDeleted", deleteMarkInfo?.fieldName)
        assertEquals("isDeleted", deleteMarkInfo?.dbColumnName) // Column annotation이 없으므로 property name 사용
    }

    @Test
    fun `DeleteMark annotation이 없는 클래스는 null을 반환한다`() {
        class PlainEntity {
            var name: String = ""
            var age: Int = 0
        }

        val deleteMarkInfo = PlainEntity::class.deleteMarkInfo
        assertNull(deleteMarkInfo)
    }

    @Test
    fun `Column annotation과 getAnnotation fallback이 정상 동작한다`() {
        class TestEntity {
            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.TRUE
            )
            @Column(name = "del_flag")
            var deleted: Boolean = false
        }

        val deletedProperty = TestEntity::class.memberProperties.first { it.name == "deleted" }

        // getAnnotation이 Column annotation을 정상적으로 찾아야 함
        val columnAnnotation = deletedProperty.getAnnotation<Column>()
        assertNotNull(columnAnnotation)
        assertEquals("del_flag", columnAnnotation?.name)

        // deleteMarkInfo에서도 Column 정보를 정상적으로 사용해야 함
        val deleteMarkInfo = TestEntity::class.deleteMarkInfo
        assertNotNull(deleteMarkInfo)
        assertEquals("del_flag", deleteMarkInfo?.dbColumnName)
    }

    @Test
    fun `여러 속성 중 DeleteMark가 있는 첫 번째 속성만 선택된다`() {
        class MultiplePropertiesEntity {
            var name: String = ""

            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.TRUE
            )
            var deleted: Boolean = false

            @DeleteMark(
                aliveMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NULL,
                deletedMark = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.NOT_NULL
            )
            var removedAt: String? = null
        }

        val deleteMarkInfo = MultiplePropertiesEntity::class.deleteMarkInfo

        assertNotNull(deleteMarkInfo)
        // 첫 번째로 발견된 속성이 선택되어야 함
        assertEquals("deleted", deleteMarkInfo?.fieldName)
        assertEquals(
            com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue.FALSE,
            deleteMarkInfo?.aliveMark
        )
    }
}
