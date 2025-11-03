package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDateTime

// 복합 PK Parent/Child 재귀 softDelete 시 parent composite PK 각 필드 기반 JPQL 조건 생성 테스트

@IdClass(ParentCompId::class)
@Entity
@Table(name = "parent_comp")
class ParentComp(
    @Id var partA: Long? = null,
    @Id var partB: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,
) : Serializable {
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST, CascadeType.MERGE], orphanRemoval = false)
    var children: MutableList<ChildComp> = mutableListOf()
}

data class ParentCompId(var partA: Long? = null, var partB: String? = null) : Serializable

@Entity
@Table(name = "child_comp")
class ChildComp(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(
        value = [
            JoinColumn(name = "parent_part_a", referencedColumnName = "partA"),
            JoinColumn(name = "parent_part_b", referencedColumnName = "partB"),
        ]
    )
    var parent: ParentComp? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,
    var label: String? = null,
) : Serializable

interface ParentCompRepository : SoftDeleteJpaRepository<ParentComp, ParentCompId>
interface ChildCompRepository : SoftDeleteJpaRepository<ChildComp, Long>

@DataJpaTest
@TestPropertySource(properties = [
    "logging.level.com.hunet.common.data.jpa.softdelete=DEBUG"
])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SpringContextHolder::class
)
class SoftDeleteCompositeHierarchyTest {
    @Autowired lateinit var parentRepo: ParentCompRepository
    @Autowired lateinit var childRepo: ChildCompRepository
    @PersistenceContext lateinit var em: EntityManager

    private fun buildParent(partA: Long, partB: String, childCount: Int): ParentComp {
        val p = ParentComp(partA = partA, partB = partB)
        repeat(childCount) { idx ->
            val c = ChildComp(parent = p, label = "C-$idx")
            p.children += c
        }
        return p
    }

    @Test
    @Transactional
    fun compositePkRecursiveSoftDelete() {
        val parent = buildParent(77L, "AB", 3)
        val saved = parentRepo.upsert(parent)
        assertEquals(3, saved.children.size)

        // 삭제 수행
        val affected = parentRepo.softDelete(saved)
        assertEquals(1, affected)

        // alive 조회에서 parent/child 모두 제외되어야 함
        assertTrue(parentRepo.findOneById(ParentCompId(77L, "AB")).isEmpty)
        saved.children.forEach { c ->
            assertTrue(childRepo.findFirstByField("label", c.label!!).isEmpty, "child ${c.label}는 soft-delete되어야 함")
        }

        // 물리 행 유지 확인
        val parentCount = (em.createNativeQuery(
            "SELECT COUNT(*) FROM parent_comp WHERE parta = 77 AND partb = 'AB'"
        ).singleResult as Number).toInt()
        assertEquals(1, parentCount)
        val childCount = (em.createNativeQuery(
            "SELECT COUNT(*) FROM child_comp WHERE parent_part_a = 77 AND parent_part_b = 'AB'"
        ).singleResult as Number).toInt()
        assertEquals(3, childCount)
    }
}
