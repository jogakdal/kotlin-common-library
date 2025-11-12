package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "seq_root")
class SeqRoot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL])
    var children: MutableList<SeqChild> = mutableListOf(),
    @GenerateSequentialCode(prefixExpression = "'PARENT_'")
    var parentCode: String? = null
)

@Entity
@Table(name = "seq_child")
class SeqChild(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var parent: SeqRoot? = null,
    @OneToOne(mappedBy = "child", cascade = [CascadeType.ALL])
    var grand: SeqGrand? = null,
    @GenerateSequentialCode(prefixExpression = "'CHILD_'")
    var childCode: String? = null
)

@Entity
@Table(name = "seq_grand")
class SeqGrand(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToOne
    var child: SeqChild? = null,
    @GenerateSequentialCode(prefixExpression = "'GRAND_'")
    var grandCode: String? = null
)

@Entity
@Table(name = "seq_circular_a")
class SeqCircularA(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToOne(mappedBy = "a", cascade = [CascadeType.ALL])
    var b: SeqCircularB? = null,
    @GenerateSequentialCode(prefixExpression = "'A_'")
    var codeA: String? = null
)

@Entity
@Table(name = "seq_circular_b")
class SeqCircularB(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToOne
    var a: SeqCircularA? = null,
    @GenerateSequentialCode(prefixExpression = "'B_'")
    var codeB: String? = null
)

interface SeqRootRepository: SoftDeleteJpaRepository<SeqRoot, Long>
interface SeqChildRepository: SoftDeleteJpaRepository<SeqChild, Long>
interface SeqGrandRepository: SoftDeleteJpaRepository<SeqGrand, Long>
interface SeqCircularARepository: SoftDeleteJpaRepository<SeqCircularA, Long>
interface SeqCircularBRepository: SoftDeleteJpaRepository<SeqCircularB, Long>

@SpringBootTest(classes = [SoftDeleteJpaRepositorySequentialCodeTest.SeqTestConfig::class], properties = [
    "softdelete.query.strict=false",
    "softdelete.upsert.null-merge=ignore",
    "softdelete.delete.strategy=recursive"
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SoftDeleteJpaRepositorySequentialCodeTest {
    @Autowired lateinit var rootRepo: SeqRootRepository
    @Autowired lateinit var childRepo: SeqChildRepository
    @Autowired lateinit var grandRepo: SeqGrandRepository
    @Autowired lateinit var circularARepo: SeqCircularARepository
    @Autowired lateinit var circularBRepo: SeqCircularBRepository

    @Test
    @Transactional
    fun testRecursiveSequentialCodePopulation() {
        val root = SeqRoot()
        val child1 = SeqChild(parent = root)
        val child2 = SeqChild(parent = root)
        val grand1 = SeqGrand(child = child1)
        val grand2 = SeqGrand(child = child2)
        child1.grand = grand1
        child2.grand = grand2
        root.children += child1
        root.children += child2
        val persisted = rootRepo.upsert(root)
        assertTrue(!persisted.parentCode.isNullOrBlank())
        persisted.children.forEach { c ->
            assertTrue(!c.childCode.isNullOrBlank())
            assertTrue(!c.grand!!.grandCode.isNullOrBlank())
        }
    }

    @Test
    @Transactional
    fun testCircularReferenceGuard() {
        val a = SeqCircularA()
        val b = SeqCircularB(a = a)
        a.b = b
        val savedA = circularARepo.upsert(a)
        val savedB = circularBRepo.upsert(b)
        assertTrue(!savedA.codeA.isNullOrBlank())
        assertTrue(!savedB.codeB.isNullOrBlank())
    }

    @Test
    @Transactional
    fun testSequentialCodeFieldCache() {
        val root = SeqRoot()
        rootRepo.upsert(root)
        val missAfterFirst = SoftDeleteJpaRepositoryImpl.seqCacheMissCounter.get()
        assertTrue(missAfterFirst >= 1)
        val root2 = SeqRoot()
        rootRepo.upsert(root2)
        val missAfterSecond = SoftDeleteJpaRepositoryImpl.seqCacheMissCounter.get()
        assertEquals(missAfterFirst, missAfterSecond)
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = ["com.hunet.common.data.jpa.softdelete", "com.hunet.common.data.jpa.sequence"])
    @EnableJpaRepositories(
        repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class,
        basePackageClasses = [
            SeqRootRepository::class,
            SeqChildRepository::class,
            SeqGrandRepository::class,
            SeqCircularARepository::class,
            SeqCircularBRepository::class
        ]
    )
    @EntityScan(
        basePackageClasses = [
            SeqRoot::class,
            SeqChild::class,
            SeqGrand::class,
            SeqCircularA::class,
            SeqCircularB::class
        ]
    )
    class SeqTestConfig
}
