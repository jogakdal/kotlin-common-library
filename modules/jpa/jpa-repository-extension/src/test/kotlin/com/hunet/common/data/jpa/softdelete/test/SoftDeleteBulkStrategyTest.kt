package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDateTime

@Entity
@Table(name = "bulk_parent")
class BulkParent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "code")
    var code: String? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST], orphanRemoval = false, fetch = FetchType.LAZY)
    var children: MutableList<BulkChild> = mutableListOf()
): Serializable

@Entity
@Table(name = "bulk_child")
class BulkChild(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: BulkParent? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @Column(name = "label")
    var label: String? = null,
): Serializable

interface BulkParentRepository : SoftDeleteJpaRepository<BulkParent, Long>
interface BulkChildRepository : SoftDeleteJpaRepository<BulkChild, Long>

@DataJpaTest
@TestPropertySource(properties = [
    "softdelete.delete.strategy=bulk",
    "logging.level.com.hunet.common.data.jpa.softdelete=DEBUG"
])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SpringContextHolder::class,
)
class SoftDeleteBulkStrategyTest {
    @Autowired lateinit var parentRepo: BulkParentRepository
    @Autowired lateinit var childRepo: BulkChildRepository
    @PersistenceContext lateinit var em: EntityManager
    @Autowired lateinit var registry: SoftDeleteRepositoryRegistry

    @BeforeEach fun initRegistry() {
        registry.initialize()
    }

    private fun buildParent(code: String, childCount: Int): BulkParent {
        val p = BulkParent(code = code)
        repeat(childCount) { idx ->
            val c = BulkChild(parent = p, label = "B-$idx")
            p.children += c
        }
        return p
    }

    @Test @Transactional
    fun bulkSoftDeleteChildrenInSingleUpdate() {
        val parent = buildParent("BP-1", 5)
        val saved = parentRepo.upsert(parent)
        assertEquals(5, saved.children.size)

        val affected = parentRepo.softDelete(saved)
        assertEquals(1, affected)
        assertTrue(parentRepo.findFirstByField("code", "BP-1").isEmpty, "parent alive 조회 제외")
        saved.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("label", c.label!!).isEmpty, "child ${c.label} alive 조회 제외")
        }
        // 물리적 행 유지
        val parentCount = (em.createNativeQuery(
            "SELECT COUNT(*) FROM bulk_parent WHERE code='BP-1'"
        ).singleResult as Number).toInt()
        assertEquals(1, parentCount)

        val childCount = (em.createNativeQuery(
            "SELECT COUNT(*) FROM bulk_child WHERE parent_id=${saved.id}"
        ).singleResult as Number).toInt()
        assertEquals(5, childCount)
    }
}
