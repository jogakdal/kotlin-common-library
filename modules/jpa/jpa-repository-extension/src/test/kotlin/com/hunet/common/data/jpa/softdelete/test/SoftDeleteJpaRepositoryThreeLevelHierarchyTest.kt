package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.data.jpa.softdelete.internal.MYSQL_DATETIME_MIN
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

/*
 3단계 계층 (Parent -> Child -> GrandChild) 에 대해
 - 각 엔티티에 @UpsertKey 와 @DeleteMark(alive=DATE_TIME_MIN, deleted=NOW)
 - @GenerateSequentialCode, @CreatedDate, @LastModifiedDate 필드 포함
 - orphanRemoval + cascade ALL 로 자식 전파
 - upsert 동작: 존재하면 merge / 없으면 persist
 - softDelete(parent) 시 자식/손자 재귀 softDelete 처리 확인
 - softDelete 후 findOneById 가 비어 있음 확인
 - 재삽입(upsert) 시 기존 삭제된 레코드와 동일한 UpsertKey 신규 행 생성 가능 확인
*/

@Entity
@Table(
    name = "three_level_parent",
    uniqueConstraints = [UniqueConstraint(columnNames = ["parent_code", "deleted_at"])]
) // 복합 유니크: 살아있는 행(deleted_at=DATE_TIME_MIN) 하나만 허용, soft-delete 후 새 행 허용
@EntityListeners(AuditingEntityListener::class)
class ThreeLevelParent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @UpsertKey
    @Column(name = "parent_code") // unique 제거
    var code: String? = null,

    @GenerateSequentialCode(prefixExpression = "'P-' + (#root.id?:'')")
    @Column(name = "seq_code")
    var seqCode: String? = null,

    @CreatedDate @Column(name = "created_at")
    var createdAt: LocalDateTime? = LocalDateTime.now(),

    @LastModifiedDate @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = LocalDateTime.now(),

    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var children: MutableList<ThreeLevelChild> = mutableListOf(),
): Serializable {
    companion object {
        private val localCounter = AtomicLong(0)
    }
    @PrePersist fun prePersist() {
        if (seqCode.isNullOrBlank()) seqCode = "P-FB-" + localCounter.incrementAndGet()
    }

    @PreUpdate fun preUpdate() {
        if (seqCode.isNullOrBlank()) seqCode = "P-FB-" + localCounter.incrementAndGet()
    }
}

@Entity
@Table(name = "three_level_child")
@EntityListeners(AuditingEntityListener::class)
class ThreeLevelChild(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @UpsertKey @Column(name = "child_code")
    var childCode: String? = null,

    @GenerateSequentialCode(prefixExpression = "'C-' + (#root.id?:'')")
    @Column(name = "seq_code")
    var seqCode: String? = null,

    @CreatedDate @Column(name = "created_at")
    var createdAt: LocalDateTime? = LocalDateTime.now(),

    @LastModifiedDate @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = LocalDateTime.now(),

    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id")
    var parent: ThreeLevelParent? = null,

    @OneToMany(mappedBy = "child", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var grandChildren: MutableList<ThreeLevelGrandChild> = mutableListOf(),
): Serializable {
    companion object {
        private val localCounter = AtomicLong(0)
    }

    @PrePersist fun prePersist() {
        if (seqCode.isNullOrBlank()) seqCode = "C-FB-" + localCounter.incrementAndGet()
    }

    @PreUpdate fun preUpdate() {
        if (seqCode.isNullOrBlank()) seqCode = "C-FB-" + localCounter.incrementAndGet()
    }
}

@Entity
@Table(name = "three_level_grandchild")
@EntityListeners(AuditingEntityListener::class)
class ThreeLevelGrandChild(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @UpsertKey @Column(name = "grand_code")
    var grandCode: String? = null,

    @GenerateSequentialCode(prefixExpression = "'G-' + (#root.id?:'')")
    @Column(name = "seq_code")
    var seqCode: String? = null,

    @CreatedDate @Column(name = "created_at")
    var createdAt: LocalDateTime? = LocalDateTime.now(),

    @LastModifiedDate @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = LocalDateTime.now(),

    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "child_id")
    var child: ThreeLevelChild? = null,
): Serializable {
    companion object {
        private val localCounter = AtomicLong(0)
    }

    @PrePersist fun prePersist() {
        if (seqCode.isNullOrBlank()) seqCode = "G-FB-" + localCounter.incrementAndGet()
    }

    @PreUpdate fun preUpdate() {
        if (seqCode.isNullOrBlank()) seqCode = "G-FB-" + localCounter.incrementAndGet()
    }
}

interface ThreeLevelParentRepository : SoftDeleteJpaRepository<ThreeLevelParent, Long>
interface ThreeLevelChildRepository : SoftDeleteJpaRepository<ThreeLevelChild, Long>
interface ThreeLevelGrandChildRepository : SoftDeleteJpaRepository<ThreeLevelGrandChild, Long>

@Configuration
@EnableJpaAuditing
class ThreeLevelHierarchyTestConfig {
    private val counter = AtomicLong(0)
    @Bean @Primary
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = prefix + counter.incrementAndGet()
    }
}

@DataJpaTest
@EntityScan(basePackageClasses = [ThreeLevelParent::class, ThreeLevelChild::class, ThreeLevelGrandChild::class])
@TestPropertySource(properties = [
    "logging.level.com.hunet.common.data.jpa.softdelete=DEBUG"
])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    ThreeLevelHierarchyTestConfig::class,
    SpringContextHolder::class,
)
class SoftDeleteJpaRepositoryThreeLevelHierarchyTest {
    @Autowired lateinit var parentRepo: ThreeLevelParentRepository
    @Autowired lateinit var childRepo: ThreeLevelChildRepository
    @Autowired lateinit var grandRepo: ThreeLevelGrandChildRepository
    @Autowired lateinit var registry: SoftDeleteRepositoryRegistry
    @Autowired lateinit var entityManager: EntityManager

    @org.junit.jupiter.api.BeforeEach
    fun initRegistry() { registry.initialize() }

    private fun buildHierarchy(parentCode: String, childCount: Int, grandPerChild: Int): ThreeLevelParent {
        val p = ThreeLevelParent(code = parentCode)
        repeat(childCount) { ci ->
            val c = ThreeLevelChild(childCode = "$parentCode-C$ci", parent = p)
            repeat(grandPerChild) { gi ->
                val g = ThreeLevelGrandChild(grandCode = "$parentCode-C$ci-G$gi", child = c)
                c.grandChildren += g
            }
            p.children += c
        }
        return p
    }

    @Test @Transactional
    fun `3단계 upsert & 조회 & 중복 upsertKey merge 동작 테스트`() {
        val parent = buildHierarchy("P100", 2, 3)
        val saved = parentRepo.upsert(parent)

        assertNotNull(saved.id)
        assertEquals(2, saved.children.size)
        assertEquals(3, saved.children[0].grandChildren.size)

        val beforeGrandUpdated = saved.children[0].grandChildren[0].updatedAt

        saved.children[0].grandChildren[0].grandCode = "P100-C0-G0"
        saved.children[0].grandChildren[0].seqCode = null
        saved.children[0].grandChildren[0].updatedAt = LocalDateTime.now().minusDays(1)

        val merged = parentRepo.upsert(saved)
        val reloaded = parentRepo.findOneById(merged.id!!).orElse(null)
        assertNotNull(reloaded)
        assertEquals(2, reloaded!!.children.size)
        assertTrue(reloaded.children.all { it.grandChildren.size == 3 })

        val afterGrand = reloaded.children[0].grandChildren[0]
        assertNotNull(afterGrand.seqCode, "재생성 되었거나 유지되어야 함")
        assertTrue(afterGrand.updatedAt!! >= beforeGrandUpdated!!, "LastModifiedDate가 갱신되어야 함")
    }

    @Test @Transactional
    fun `parent softDelete 시 child & grandchild 재귀 softDelete + deletedAt 변경 확인`() {
        val saved = parentRepo.upsert(buildHierarchy("PX200", 1, 2))
        val pid = saved.id!!
        val affected = parentRepo.softDelete(saved)
        assertEquals(1, affected)
        assertTrue(parentRepo.findOneById(pid).isEmpty)

        saved.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("childCode", c.childCode!!).isEmpty)
            assertTrue(c.deletedAt == null || c.deletedAt!!.isAfter(MYSQL_DATETIME_MIN))
            c.grandChildren.forEach { g ->
                assertTrue(grandRepo.findFirstByField("grandCode", g.grandCode!!).isEmpty)
                assertTrue(g.deletedAt == null || g.deletedAt!!.isAfter(MYSQL_DATETIME_MIN))
            }
        }
        assertTrue(saved.deletedAt == null || saved.deletedAt!!.isAfter(MYSQL_DATETIME_MIN))
    }

    @Test @Transactional
    fun `softDelete 후 동일 UpsertKey 신규 insert 가능 여부 확인`() {
        val saved = parentRepo.upsert(buildHierarchy("P300", 1, 1))
        val oldId = saved.id!!
        parentRepo.softDelete(saved)
        assertTrue(parentRepo.findOneById(oldId).isEmpty)

        val newHierarchy = buildHierarchy("P300", 2, 2)
        val recreated = parentRepo.upsert(newHierarchy)
        assertNotNull(recreated.id)
        assertNotEquals(oldId, recreated.id, "새로운 PK가 생성되어야 한다.")
        assertEquals("P300", recreated.code)
        assertEquals(2, recreated.children.size)
        recreated.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("childCode", c.childCode!!).isPresent)
            c.grandChildren.forEach { g ->
                assertTrue(grandRepo.findFirstByField("grandCode", g.grandCode!!).isPresent)
            }
        }
    }

    @Test @Transactional
    fun `개별 child softDelete 후 parent 구조 유지 & 해당 child 재삽입 테스트`() {
        val saved = parentRepo.upsert(buildHierarchy("P400", 2, 2))
        val targetChild = saved.children[0]
        val affectedChild = childRepo.softDelete(targetChild)
        assertEquals(1, affectedChild)
        assertTrue(childRepo.findFirstByField("childCode", targetChild.childCode!!).isEmpty)

        assertTrue(parentRepo.findOneById(saved.id!!).isPresent, "Parent 는 삭제되지 않아야 함")

        val newChild = ThreeLevelChild(childCode = targetChild.childCode, parent = saved)
        saved.children.add(newChild)
        parentRepo.upsert(saved)
        assertTrue(childRepo.findFirstByField("childCode", targetChild.childCode!!).isPresent)
    }

    @Test @Transactional
    fun `모든 grandchildren 에 seqCode 정상 생성 확인`() {
        val saved = parentRepo.upsert(buildHierarchy("P500", 2, 4))
        saved.children.forEach { c ->
            assertFalse(c.seqCode.isNullOrBlank())
            c.grandChildren.forEach { g -> assertFalse(g.seqCode.isNullOrBlank()) }
        }
    }

    // 추가) softDelete 후 물리적 레코드 유지 및 alive predicate 작동 검증
    @Test @Transactional
    fun `softDelete 후 물리적 레코드 유지 및 alive predicate 작동 검증`() {
        val saved = parentRepo.upsert(buildHierarchy("P600", 1, 1))
        val tableName = "three_level_parent"
        val nativeCountBefore = (entityManager.createNativeQuery("SELECT COUNT(*) FROM $tableName").singleResult as Number).toInt()
        assertEquals(1, nativeCountBefore)
        assertEquals(1, parentRepo.count())
        parentRepo.softDelete(saved)
        val nativeCountAfter = (entityManager.createNativeQuery("SELECT COUNT(*) FROM $tableName").singleResult as Number).toInt()
        assertEquals(1, nativeCountAfter, "물리 행이 유지되어야 한다.")
        assertEquals(0, parentRepo.count(), "alive 필터가 적용되어야 한다.")
    }
}
