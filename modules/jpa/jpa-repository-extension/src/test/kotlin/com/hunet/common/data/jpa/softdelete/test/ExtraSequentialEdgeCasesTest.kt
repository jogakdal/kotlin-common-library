package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
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
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import org.springframework.test.context.TestPropertySource

@Entity
@Table(name = "edge_seq_entity")
class EdgeSeqEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    // prefixExpression 다른 필드 참조 (없으면 fallback provider 에러 발생하므로 명시적으로 사용)
    @GenerateSequentialCode(prefixExpression = "'ED-' + (id == null ? 'NEW-' : 'EXIST-')")
    var code: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: java.time.LocalDateTime? = null,
) : java.io.Serializable

interface EdgeSeqEntityRepository : SoftDeleteJpaRepository<EdgeSeqEntity, Long>

@Configuration
class EdgeSeqSequenceGeneratorConfig {
    @Bean
    @Primary
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        private var c = 0L
        override fun generateKey(prefix: String, entity: Any?): Any? = prefix + (++c)
    }
}

@DataJpaTest
@EntityScan(basePackageClasses = [EdgeSeqEntity::class])
@TestPropertySource(properties = [
    "logging.level.com.hunet.common.data.jpa.softdelete=INFO"
])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    EdgeSeqSequenceGeneratorConfig::class,
    SpringContextHolder::class,
)
class ExtraSequentialEdgeCasesTest {
    @Autowired lateinit var repo: EdgeSeqEntityRepository

    @Test
    @Transactional
    fun generateForNewEntityUsesNEWPrefix() {
        val e = repo.upsert(EdgeSeqEntity())
        assertNotNull(e.code)
        assertTrue(e.code!!.startsWith("ED-NEW-"), "신규 엔티티 prefix 표현식 적용 확인: ${e.code}")
    }

    @Test
    @Transactional
    fun existingEntityPreservesCode() {
        var e = repo.upsert(EdgeSeqEntity())
        val originalCode = e.code
        // code 수동 변경 후 재 upsert -> 이미 값 존재하므로 변경되지 않아야 함
        e.code = "CUSTOM-CODE" // 직접 설정
        e = repo.upsert(e)
        assertEquals("CUSTOM-CODE", e.code, "사용자 설정 코드가 유지되어야 합니다")
        assertNotEquals(originalCode, e.code)
    }

    @Test
    @Transactional
    fun mergeDoesNotRegenerateIfCodeAlreadySet() {
        val e1 = repo.upsert(EdgeSeqEntity())
        val loaded = repo.findOneById(e1.id!!).orElseThrow()
        val prior = loaded.code
        // code 유지한 채 다른 필드 변화 (없지만 시뮬레이션)
        val merged = repo.upsert(loaded) // 기존 코드가 blank 아님 -> regenerate 금지
        assertEquals(prior, merged.code)
    }
}

