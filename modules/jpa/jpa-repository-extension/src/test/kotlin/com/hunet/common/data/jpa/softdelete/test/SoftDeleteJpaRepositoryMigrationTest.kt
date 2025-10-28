package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.UpsertKey
import com.hunet.common.util.getAnnotation
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import java.time.LocalDateTime

/**
 * SoftDeleteJpaRepository에서 findAnnotation → getAnnotation 마이그레이션 테스트
 */
@ExtendWith(MockitoExtension::class)
class SoftDeleteJpaRepositoryMigrationTest {

    @Mock
    private lateinit var entityManager: EntityManager

    @Test
    fun `getAnnotation을 사용한 UpsertKey 검색이 정상 동작한다`() {
        data class TestEntity(
            @UpsertKey
            val uniqueCode: String = "",

            val name: String = "",

            @Id
            val id: Long? = null
        )

        val uniqueCodeProperty = TestEntity::class.memberProperties.first { it.name == "uniqueCode" }
        val nameProperty = TestEntity::class.memberProperties.first { it.name == "name" }

        // getAnnotation을 사용한 UpsertKey annotation 검색
        val upsertKeyAnnotation = uniqueCodeProperty.getAnnotation<UpsertKey>()
        val nameAnnotation = nameProperty.getAnnotation<UpsertKey>()

        assertNotNull(upsertKeyAnnotation)
        assertNull(nameAnnotation)
    }

    @Test
    fun `getAnnotation을 사용한 CreatedDate 및 LastModifiedDate 검색이 정상 동작한다`() {
        data class AuditableEntity(
            val name: String = "",

            @CreatedDate
            var createdAt: LocalDateTime? = null,

            @LastModifiedDate
            var updatedAt: LocalDateTime? = null,

            var description: String = ""
        )

        val properties = AuditableEntity::class.memberProperties
        val createdAtProp = properties.first { it.name == "createdAt" }
        val updatedAtProp = properties.first { it.name == "updatedAt" }
        val nameProp = properties.first { it.name == "name" }

        // getAnnotation을 사용한 annotation 검색
        assertTrue(createdAtProp.getAnnotation<CreatedDate>() != null)
        assertTrue(updatedAtProp.getAnnotation<LastModifiedDate>() != null)
        assertNull(nameProp.getAnnotation<CreatedDate>())
        assertNull(nameProp.getAnnotation<LastModifiedDate>())
    }

    @Test
    fun `copyAndMerge 로직에서 getAnnotation 기반 필터링이 정상 동작한다`() {
        data class TestEntity(
            var name: String = "",

            @CreatedDate
            var createdAt: LocalDateTime? = null,

            @LastModifiedDate
            var updatedAt: LocalDateTime? = null
        )

        val sourceEntity = TestEntity(
            name = "Updated Name",
            createdAt = LocalDateTime.of(2023, 1, 1, 10, 0),
            updatedAt = LocalDateTime.of(2023, 1, 1, 11, 0)
        )

        val targetEntity = TestEntity(
            name = "Original Name",
            createdAt = LocalDateTime.of(2022, 12, 1, 9, 0),
            updatedAt = LocalDateTime.of(2022, 12, 1, 10, 0)
        )

        // copyAndMerge 로직 시뮬레이션
        sourceEntity::class.memberProperties.forEach { prop ->
            if (prop is kotlin.reflect.KMutableProperty1<*, *>) {
                prop.isAccessible = true
                when {
                    prop.getAnnotation<CreatedDate>() != null -> {
                        // CreatedDate는 수정하지 않음
                    }
                    prop.getAnnotation<LastModifiedDate>() != null -> {
                        // LastModifiedDate는 현재 시간으로 설정
                        val currentTime = LocalDateTime.now()
                        @Suppress("UNCHECKED_CAST")
                        (prop as kotlin.reflect.KMutableProperty1<TestEntity, LocalDateTime?>)
                            .set(targetEntity, currentTime)
                    }
                    else -> {
                        // 일반 필드는 새 값으로 복사
                        prop.getter.call(sourceEntity)?.let { newValue ->
                            @Suppress("UNCHECKED_CAST")
                            (prop as kotlin.reflect.KMutableProperty1<TestEntity, Any?>)
                                .set(targetEntity, newValue)
                        }
                    }
                }
            }
        }

        // 검증
        assertEquals("Updated Name", targetEntity.name) // 일반 필드는 업데이트됨
        assertEquals(LocalDateTime.of(2022, 12, 1, 9, 0), targetEntity.createdAt) // CreatedDate는 유지됨
        assertTrue(targetEntity.updatedAt!!.isAfter(LocalDateTime.of(2023, 1, 1, 0, 0))) // LastModifiedDate는 현재 시간으로 갱신됨
    }

    @Test
    fun `applyUpdateEntity에서 getAnnotation 기반 속성 분류가 정상 동작한다`() {
        data class TestEntity(
            var name: String = "",

            @CreatedDate
            var createdAt: LocalDateTime? = null,

            @LastModifiedDate
            var updatedAt: LocalDateTime? = null,

            var description: String = ""
        )

        val entity = TestEntity(
            name = "Test",
            createdAt = LocalDateTime.of(2023, 1, 1, 10, 0),
            updatedAt = LocalDateTime.of(2023, 1, 1, 11, 0),
            description = "Original"
        )

        // applyUpdateEntity 로직 시뮬레이션
        val createdProps = entity::class.memberProperties
            .filterIsInstance<kotlin.reflect.KMutableProperty1<TestEntity, Any?>>()
            .filter { it.getAnnotation<CreatedDate>() != null }

        val lastModifiedProps = entity::class.memberProperties
            .filterIsInstance<kotlin.reflect.KMutableProperty1<TestEntity, Any?>>()
            .filter { it.getAnnotation<LastModifiedDate>() != null }

        // 검증
        assertEquals(1, createdProps.size)
        assertEquals("createdAt", createdProps[0].name)

        assertEquals(1, lastModifiedProps.size)
        assertEquals("updatedAt", lastModifiedProps[0].name)
    }

    @Test
    fun `OneToMany annotation 검색이 getAnnotation으로 정상 동작한다`() {
        class ParentEntity {
            @OneToMany(mappedBy = "parent")
            var children: List<ChildEntity> = emptyList()

            var name: String = ""
        }

        class ChildEntity {
            @ManyToOne
            var parent: ParentEntity? = null
        }

        val properties = ParentEntity::class.memberProperties
        val childrenProp = properties.first { it.name == "children" }
        val nameProp = properties.first { it.name == "name" }

        // getAnnotation을 사용한 OneToMany annotation 검색
        assertNotNull(childrenProp.getAnnotation<OneToMany>())
        assertNull(nameProp.getAnnotation<OneToMany>())
    }

    @Test
    fun `Java 필드 annotation도 getAnnotation fallback으로 처리된다`() {
        class JavaFieldEntity {
            @JvmField
            @UpsertKey
            var javaUpsertKey: String = ""

            var normalField: String = ""
        }

        val properties = JavaFieldEntity::class.memberProperties
        val javaKeyProp = properties.first { it.name == "javaUpsertKey" }
        val normalProp = properties.first { it.name == "normalField" }

        // Java 필드의 annotation도 getAnnotation으로 찾을 수 있어야 함
        assertNotNull(javaKeyProp.getAnnotation<UpsertKey>())
        assertNull(normalProp.getAnnotation<UpsertKey>())
    }
}
