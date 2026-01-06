package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import com.hunet.common.lib.YnFlag
import com.hunet.common.lib.isY
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary // 추가
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import org.springframework.data.domain.PageRequest
import org.springframework.boot.autoconfigure.domain.EntityScan

// 복합 ID 엔티티
@IdClass(CompositeEntityId::class)
@Entity
@Table(name = "composite_entity")
class CompositeIdEntity(
    @Id var part1: Long? = null,
    @Id var part2: String? = null,
    var desc: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
) : Serializable

data class CompositeEntityId(var part1: Long? = null, var part2: String? = null) : Serializable

interface CompositeIdEntityRepository : SoftDeleteJpaRepository<CompositeIdEntity, CompositeEntityId>

// YES/NO 문자열 마크 엔티티
@Entity
@Table(name = "flag_entity")
class FlagEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.YES, deletedMark = DeleteMarkValue.NO)
    @Column(name = "active")
    var active: String = YnFlag.Y,

    var label: String? = null
) : Serializable

interface FlagEntityRepository : SoftDeleteJpaRepository<FlagEntity, Long>

// NOT_NULL alive / NULL deleted
@Entity
@Table(name = "notnull_mark_entity")
class NotNullMarkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NOT_NULL, deletedMark = DeleteMarkValue.NULL)
    @Column(name = "status_ts")
    var statusTs: LocalDateTime? = LocalDateTime.now(),

    var info: String? = null
) : Serializable

interface NotNullMarkEntityRepository : SoftDeleteJpaRepository<NotNullMarkEntity, Long>

// 시퀀스 코드 자동 생성 엔티티
@Entity
@Table(name = "seq_entity")
class SeqEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @GenerateSequentialCode(prefixExpression = "'PX-'")
    var code: String? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
) : Serializable {
    companion object {
        private val localCounter = AtomicLong(0)
    }

    @PrePersist
    fun prePersist() {
        if (code.isNullOrBlank()) {
            code = "PX-" + localCounter.incrementAndGet()
        }
    }
}

interface SeqEntityRepository : SoftDeleteJpaRepository<SeqEntity, Long>

@DataJpaTest
@EntityScan(
    basePackageClasses = [CompositeIdEntity::class, FlagEntity::class, NotNullMarkEntity::class, SeqEntity::class]
)
@TestPropertySource(properties = [
    "softdelete.upsert-all.flush-interval=10",
    "logging.level.com.hunet.common.data.jpa.softdelete=DEBUG"
]) // 대량 upsert 테스트용
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    ExtendedSequenceGeneratorTestConfig::class,
    SpringContextHolder::class,
)
class SoftDeleteJpaRepositoryExtendedScenariosTest {
    @Autowired lateinit var compositeRepo: CompositeIdEntityRepository
    @Autowired lateinit var flagRepo: FlagEntityRepository
    @Autowired lateinit var notNullRepo: NotNullMarkEntityRepository
    @Autowired lateinit var seqRepo: SeqEntityRepository

    // 복합 ID upsert & findOne & softDelete
    @Test
    @Transactional
    fun compositeIdSoftDeleteAndFind() {
        val entity = CompositeIdEntity(part1 = 100L, part2 = "CK", desc = "composite")
        compositeRepo.upsert(entity)
        val found = compositeRepo.findOneById(CompositeEntityId(100L, "CK"))
        assertTrue(found.isPresent)
        // 직접 softDelete(entity) 호출 (복합 ID Map 요구로 softDeleteById 회피)
        val affected = compositeRepo.softDelete(found.get())
        assertEquals(1, affected)
        assertTrue(compositeRepo.findOneById(CompositeEntityId(100L, "CK")).isEmpty)
    }

    // 복합 ID updateById & rowLockById
    @Test
    @Transactional
    fun compositeIdUpdateAndRowLock() {
        compositeRepo.upsert(CompositeIdEntity(part1 = 7L, part2 = "X", desc = "before"))
        val cid = CompositeEntityId(7L, "X")
        compositeRepo.rowLockById(cid) { _: CompositeIdEntity ->
            compositeRepo.updateById(cid) { it.desc = "after" }
        }
        assertEquals("after", compositeRepo.findOneById(cid).orElse(null)?.desc)
    }

    // YES/NO 마크 엔티티 softDeleteFlow
    @Test
    @Transactional
    fun yesNoDeleteMarkFlow() {
        val e = flagRepo.upsert(FlagEntity(label = "flag1"))
        assertTrue(e.active.isY())
        val affected = flagRepo.softDeleteById(e.id!!)
        assertEquals(1, affected)
        assertTrue(flagRepo.findOneById(e.id!!).isEmpty, "active == 'N'이므로 alive predicate 불일치해야 함")
    }

    // NOT_NULL alive / NULL deleted softDeleteFlow
    @Test
    @Transactional
    fun notNullAliveDeleteFlow() {
        val e = notNullRepo.upsert(NotNullMarkEntity(info = "nn"))
        assertNotNull(e.statusTs)
        val affected = notNullRepo.softDeleteById(e.id!!)
        assertEquals(1, affected)
        assertTrue(notNullRepo.findOneById(e.id!!).isEmpty, "statusTs가 NULL로 설정되면 alive 조건 위배")
    }

    // 시퀀스 코드 생성 확인
    @Test
    @Transactional
    fun sequentialCodeGeneration() {
        val a = seqRepo.upsert(SeqEntity())
        val b = seqRepo.upsert(SeqEntity())
        val aLoaded = seqRepo.findOneById(a.id!!).orElse(null)
        val bLoaded = seqRepo.findOneById(b.id!!).orElse(null)
        assertNotNull(aLoaded?.code); assertTrue(aLoaded!!.code!!.startsWith("PX-"))
        assertNotNull(bLoaded?.code); assertTrue(bLoaded!!.code!!.startsWith("PX-"))
        assertNotEquals(aLoaded.code, bLoaded.code)
    }

    // rowLockByField
    @Test
    @Transactional
    fun rowLockByFieldWorks() {
        flagRepo.upsert(FlagEntity(label = "lock-field"))
        flagRepo.rowLockByField("label", "lock-field") { _: FlagEntity ->
            flagRepo.updateByField("label", "lock-field") { it.label = "changed" }
        }
        assertEquals("changed", flagRepo.findFirstByField("label", "changed").orElse(null)?.label)
    }

    // rowLockByFields
    @Test
    @Transactional
    fun rowLockByFieldsWorks() {
        notNullRepo.upsert(NotNullMarkEntity(info = "A"))
        notNullRepo.rowLockByFields(mapOf("info" to "A")) { _: NotNullMarkEntity ->
            notNullRepo.updateByFields(mapOf("info" to "A")) { it.info = "B" }
        }
        assertTrue(notNullRepo.findFirstByField("info", "A").isEmpty)
        assertTrue(notNullRepo.findFirstByField("info", "B").isPresent)
    }

    // rowLockByCondition
    @Test
    @Transactional
    fun rowLockByConditionWorks() {
        compositeRepo.upsert(CompositeIdEntity(part1 = 1L, part2 = "C1", desc = "init"))
        compositeRepo.rowLockByCondition("e.part1 = 1 AND e.part2 = 'C1'") { _: CompositeIdEntity ->
            compositeRepo.updateByFields(mapOf("part1" to 1L, "part2" to "C1")) { it.desc = "locked" }
        }
        assertEquals("locked", compositeRepo.findOneById(CompositeEntityId(1L, "C1")).orElse(null)?.desc)
    }

    // 대량 upsertAll flushInterval=10 확인
    @Test
    @Transactional
    fun bulkUpsertAll() {
        val list = (1..45).map { i ->
            FlagEntity(label = "BULK-$i")
        }
        val saved = flagRepo.upsertAll(list)
        assertEquals(45, saved.size)
        // 페이지네이션 검증
        val page1 = flagRepo.findAllByCondition("", PageRequest.of(0, 20))
        val page3 = flagRepo.findAllByCondition("", PageRequest.of(2, 20))
        assertEquals(20, page1.content.size)
        assertEquals(5, page3.content.size)
        assertEquals(45, page1.totalElements)
    }
}

@Configuration
class ExtendedSequenceGeneratorTestConfig {
    private val counter = AtomicLong(0)
    @Bean
    @Primary // 우선순위 부여
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?) = prefix + counter.incrementAndGet()
    }
}
