package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.data.jpa.softdelete.internal.MYSQL_DATETIME_MIN
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
@Table(name = "bulk_three_parent")
class BulkThreeParent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "code") var code: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL], orphanRemoval = true)
    var children: MutableList<BulkThreeChild> = mutableListOf(),
): Serializable

@Entity
@Table(name = "bulk_three_child")
class BulkThreeChild(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "code") var childCode: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id")
    var parent: BulkThreeParent? = null,
    @OneToMany(mappedBy = "child", cascade = [CascadeType.ALL], orphanRemoval = true)
    var grandChildren: MutableList<BulkThreeGrandChild> = mutableListOf(),
): Serializable

@Entity
@Table(name = "bulk_three_grand")
class BulkThreeGrandChild(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "code") var grandCode: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = MYSQL_DATETIME_MIN,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "child_id")
    var child: BulkThreeChild? = null,
): Serializable

interface BulkThreeParentRepository : SoftDeleteJpaRepository<BulkThreeParent, Long>
interface BulkThreeChildRepository : SoftDeleteJpaRepository<BulkThreeChild, Long>
interface BulkThreeGrandChildRepository : SoftDeleteJpaRepository<BulkThreeGrandChild, Long>

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
class SoftDeleteBulkThreeLevelHierarchyTest {
    @Autowired lateinit var parentRepo: BulkThreeParentRepository
    @Autowired lateinit var childRepo: BulkThreeChildRepository
    @Autowired lateinit var grandRepo: BulkThreeGrandChildRepository
    @Autowired lateinit var registry: SoftDeleteRepositoryRegistry

    private fun buildHierarchy(code: String, childCount: Int, grandPerChild: Int): BulkThreeParent {
        val p = BulkThreeParent(code = code)
        repeat(childCount) { ci ->
            val c = BulkThreeChild(childCode = "$code-C$ci", parent = p)
            repeat(grandPerChild) { gi ->
                val g = BulkThreeGrandChild(grandCode = "$code-C$ci-G$gi", child = c)
                c.grandChildren += g
            }
            p.children += c
        }
        return p
    }

    @BeforeEach fun initRegistry() { registry.initialize() }

    @Test @Transactional
    fun bulkSoftDeleteThreeLevelHierarchy() {
        val saved = parentRepo.upsert(buildHierarchy("B3-100", 2, 3))
        val parentId = saved.id!!
        val affected = parentRepo.softDelete(saved)
        assertEquals(1, affected)
        assertTrue(parentRepo.findOneById(parentId).isEmpty)
        saved.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("childCode", c.childCode!!).isEmpty)
            c.grandChildren.forEach { g ->
                assertTrue(grandRepo.findFirstByField("grandCode", g.grandCode!!).isEmpty)
            }
        }
    }
}
