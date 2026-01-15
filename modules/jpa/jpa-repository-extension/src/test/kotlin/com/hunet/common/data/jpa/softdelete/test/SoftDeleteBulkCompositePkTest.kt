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

@IdClass(BulkParentCompId::class)
@Entity
@Table(name = "bulk_parent_comp")
class BulkParentComp(
    @Id
    var partA: Long? = null,

    @Id
    var partB: String? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null,
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST])
    var children: MutableList<BulkChildComp> = mutableListOf()
): Serializable

data class BulkParentCompId(var partA: Long? = null, var partB: String? = null) : Serializable

@Entity
@Table(name = "bulk_child_comp")
class BulkChildComp(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = [
        JoinColumn(name = "parent_part_a", referencedColumnName = "partA"),
        JoinColumn(name = "parent_part_b", referencedColumnName = "partB"),
    ])
    var parent: BulkParentComp? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @Column(name = "label")
    var label: String? = null,
): Serializable

interface BulkParentCompRepository : SoftDeleteJpaRepository<BulkParentComp, BulkParentCompId>
interface BulkChildCompRepository : SoftDeleteJpaRepository<BulkChildComp, Long>

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
class SoftDeleteBulkCompositePkTest {
    @Autowired lateinit var parentRepo: BulkParentCompRepository
    @Autowired lateinit var childRepo: BulkChildCompRepository
    @PersistenceContext lateinit var em: EntityManager
    @Autowired lateinit var registry: SoftDeleteRepositoryRegistry

    private fun buildParent(partA: Long, partB: String, childCount: Int): BulkParentComp {
        val p = BulkParentComp(partA = partA, partB = partB)
        repeat(childCount) { idx ->
            val c = BulkChildComp(parent = p, label = "BC-$idx")
            p.children += c
        }
        return p
    }

    @BeforeEach fun initRegistry() {
        registry.initialize()
    }

    @Test @Transactional
    fun bulkSoftDeleteCompositePkChildren() {
        val parent = buildParent(501L, "XY", 4)
        val saved = parentRepo.upsert(parent)
        assertEquals(4, saved.children.size)

        val affected = parentRepo.softDelete(saved)
        assertEquals(1, affected)
        assertTrue(parentRepo.findOneById(BulkParentCompId(501L, "XY")).isEmpty)
        saved.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("label", c.label!!).isEmpty)
        }

        val physicalParent = (em.createNativeQuery(
            "SELECT COUNT(*) FROM bulk_parent_comp WHERE parta=501 AND partb='XY'"
        ).singleResult as Number).toInt()
        assertEquals(1, physicalParent)

        val physicalChildren = (em.createNativeQuery(
            "SELECT COUNT(*) FROM bulk_child_comp WHERE parent_part_a=501 AND parent_part_b='XY'"
        ).singleResult as Number).toInt()
        assertEquals(4, physicalChildren)
    }
}
