package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import org.springframework.data.domain.PageRequest
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.dao.InvalidDataAccessApiUsageException

@Entity
@Table(name = "upsert_key_entity")
class UpsertKeyEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @UpsertKey
    @Column(name = "code", unique = true, nullable = false)

    var code: String,

    var desc: String? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)

interface UpsertKeyEntityRepository : SoftDeleteJpaRepository<UpsertKeyEntity, Long>

@Entity
@Table(name = "parent_entity")
class ParentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var title: String? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var children: MutableList<ChildEntity> = mutableListOf()
)

@Entity
@Table(name = "child_entity")
class ChildEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var name: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: ParentEntity? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)

interface ParentEntityRepository : SoftDeleteJpaRepository<ParentEntity, Long>
interface ChildEntityRepository : SoftDeleteJpaRepository<ChildEntity, Long>

@DataJpaTest
@EntityScan(basePackageClasses = [UpsertKeyEntity::class, ParentEntity::class, ChildEntity::class])
@TestPropertySource(properties = ["softdelete.upsert-all.flush-interval=5"]) // flushInterval 테스트용
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SequenceGeneratorAdvancedTestConfig::class,
    SpringContextHolder::class
)
class SoftDeleteJpaRepositoryAdvancedTest {
    @Autowired lateinit var upsertKeyEntityRepository: UpsertKeyEntityRepository
    @Autowired lateinit var parentEntityRepository: ParentEntityRepository
    @Autowired lateinit var childEntityRepository: ChildEntityRepository
    @Autowired lateinit var entityManager: EntityManager // 추가

    // UpsertKey 머지 동작
    @Test
    @Transactional
    fun upsertKeyMergeUpdatesExisting() {
        val first = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "CODE-1", desc = "v1"))
        assertNotNull(first.id)
        val idBefore = first.id
        val second = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "CODE-1", desc = "v2")) // 같은 code -> merge
        assertEquals(idBefore, second.id, "동일 ID가 유지되어야 한다.")
        assertEquals("v2", second.desc)
        // 조회 확인
        val loaded = upsertKeyEntityRepository.findFirstByField("code", "CODE-1").orElse(null)
        assertEquals("v2", loaded?.desc)
    }

    // UpsertKey + ID 불일치 예외
    @Test
    @Transactional
    fun upsertKeyIdMismatchThrows() {
        val existing = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "CODE-X", desc = "base"))
        val wrong = UpsertKeyEntity(id = existing.id!! + 999, code = "CODE-X", desc = "other")
        val ex = assertThrows(InvalidDataAccessApiUsageException::class.java) { upsertKeyEntityRepository.upsert(wrong) }
        assertTrue(ex.cause is IllegalStateException)
        assertTrue(ex.cause?.message?.contains("업데이트 실패") == true)
    }

    // updateByField / updateByFields / updateByCondition
    @Test
    @Transactional
    fun updateByFieldAndFieldsAndCondition() {
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "U-A", desc = "A"))
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "U-B", desc = "B"))
        upsertKeyEntityRepository.updateByField("code", "U-A") { it.desc = "A2" }
        assertEquals("A2", upsertKeyEntityRepository.findFirstByField("code", "U-A").orElse(null)?.desc)
        upsertKeyEntityRepository.updateByFields(mapOf("code" to "U-B")) { it.desc = "B2" }
        assertEquals("B2", upsertKeyEntityRepository.findFirstByField("code", "U-B").orElse(null)?.desc)
        upsertKeyEntityRepository.updateByCondition("e.code = 'U-A'") { it.desc = "A3" }
        assertEquals("A3", upsertKeyEntityRepository.findFirstByField("code", "U-A").orElse(null)?.desc)
    }

    // updateById 단일 엔티티 업데이트
    @Test
    @Transactional
    fun updateByIdUpdatesEntity() {
        val saved = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "UPD-ID", desc = "first"))
        val id = saved.id!!
        upsertKeyEntityRepository.updateById(id) { it.desc = "second" }
        val reloaded = upsertKeyEntityRepository.findOneById(id).orElse(null)
        assertEquals("second", reloaded?.desc)
    }

    // rowLockById 사용
    @Test
    @Transactional
    fun rowLockByIdExecutesBlock() {
        val saved = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "LOCK-1", desc = "orig"))
        val id = saved.id!!
        val ret = upsertKeyEntityRepository.rowLockById(id) { _: UpsertKeyEntity ->
            upsertKeyEntityRepository.updateById(id) { it.desc = "locked" }
            "OK"
        }
        assertEquals("OK", ret)
        val after = upsertKeyEntityRepository.findOneById(id).orElse(null)
        assertEquals("locked", after?.desc)
    }

    // softDelete 다양한 방식 및 필터링
    @Test
    @Transactional
    fun softDeleteByFieldFieldsCondition() {
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "SD-1", desc = "d1"))
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "SD-2", desc = "d2"))
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "SD-3", desc = "d3"))
        val affectedField = upsertKeyEntityRepository.softDeleteByField("code", "SD-1")
        assertEquals(1, affectedField)
        assertTrue(upsertKeyEntityRepository.findFirstByField("code", "SD-1").isEmpty)
        val affectedFields = upsertKeyEntityRepository.softDeleteByFields(mapOf("code" to "SD-2"))
        assertEquals(1, affectedFields)
        assertTrue(upsertKeyEntityRepository.findFirstByField("code", "SD-2").isEmpty)
        val affectedCond = upsertKeyEntityRepository.softDeleteByCondition("e.code = 'SD-3'")
        assertEquals(1, affectedCond)
        assertTrue(upsertKeyEntityRepository.findFirstByField("code", "SD-3").isEmpty)

        assertEquals(0, upsertKeyEntityRepository.countByCondition(""), "모두 soft-deleted 되어야 함")
    }

    // 직접 softDelete(entity) 호출
    @Test
    @Transactional
    fun directSoftDeleteEntity() {
        val ent = upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "SD-DIRECT", desc = "x"))
        val affected = upsertKeyEntityRepository.softDelete(ent)
        assertEquals(1, affected)
        assertTrue(upsertKeyEntityRepository.findFirstByField("code", "SD-DIRECT").isEmpty)
    }

    // softDeleteById 존재하지 않는 ID
    @Test
    @Transactional
    fun softDeleteNonExistentIdReturnsZero() {
        val result = upsertKeyEntityRepository.softDeleteById(999999L)
        assertEquals(0, result)
    }

    // upsertAll flushInterval 동작 (단순 성공 여부 & 전체 저장)
    @Test
    @Transactional
    fun upsertAllFlushInterval() {
        val list = (1..12).map { i -> UpsertKeyEntity(code = "BATCH-$i", desc = "desc-$i") }
        val saved = upsertKeyEntityRepository.upsertAll(list)
        assertEquals(12, saved.size)
        assertEquals(12, upsertKeyEntityRepository.countByCondition(""))
    }

    // pagination & countByField
    @Test
    @Transactional
    fun paginationAndCount() {
        (1..15).forEach { i ->
            upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "PG-$i", desc = "d$i"))
        }
        val page1 = upsertKeyEntityRepository.findAllByCondition("", PageRequest.of(0, 10))
        assertEquals(10, page1.content.size)
        assertEquals(15, page1.totalElements)
        val page2 = upsertKeyEntityRepository.findAllByCondition("", PageRequest.of(1, 10))
        assertEquals(5, page2.content.size)
        assertEquals(15, page2.totalElements)
        // countByField 특정 코드 1개
        assertEquals(1, upsertKeyEntityRepository.countByField("code", "PG-1"))
    }

    // findFirstByFields 복합 조건 테스트
    @Test
    @Transactional
    fun findFirstByFieldsWorks() {
        upsertKeyEntityRepository.upsert(UpsertKeyEntity(code = "MULTI", desc = "X"))
        val found = upsertKeyEntityRepository.findFirstByFields(mapOf("code" to "MULTI", "desc" to "X"))
        assertTrue(found.isPresent)

        upsertKeyEntityRepository.softDeleteByField("code", "MULTI")
        assertTrue(
            upsertKeyEntityRepository.findFirstByFields(mapOf("code" to "MULTI", "desc" to "X")).isEmpty,
            "soft-delete 후 다시 조회하면 빈 값이어야 함"
        )
    }

    // 캐스케이드 softDelete (Parent -> Children)
    @Test
    @Transactional
    fun cascadeSoftDeleteParentChildren() {
        val parent = ParentEntity(title = "parent")
        val c1 = ChildEntity(name = "c1", parent = parent)
        val c2 = ChildEntity(name = "c2", parent = parent)
        parent.children.addAll(listOf(c1, c2))
        val persistedParent = parentEntityRepository.upsert(parent)
        assertEquals(2, childEntityRepository.findAllByField("parent", persistedParent, null).content.size)
        val affected = parentEntityRepository.softDeleteById(persistedParent.id!!)
        assertEquals(1, affected)
        entityManager.clear() // 네이티브 업데이트 후 1차 캐시 클리어
        assertTrue(parentEntityRepository.findOneById(persistedParent.id!!).isEmpty)
        assertEquals(0, childEntityRepository.findAllByField("parent", persistedParent, null).content.size)
    }
}

@Configuration
class SequenceGeneratorAdvancedTestConfig {
    @Bean
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = null
    }
}
