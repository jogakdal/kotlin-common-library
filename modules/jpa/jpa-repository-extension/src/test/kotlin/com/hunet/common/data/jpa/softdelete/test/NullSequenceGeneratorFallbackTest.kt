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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "fallback_seq_entity")
class FallbackSeqEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @GenerateSequentialCode(prefixExpression = "'FB-'")
    var code: String? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: java.time.LocalDateTime? = null,
) : java.io.Serializable

interface FallbackSeqEntityRepository : SoftDeleteJpaRepository<FallbackSeqEntity, Long>

@Configuration
class NullSequenceGeneratorConfig {
    @Bean
    @Primary
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = null // 항상 null 반환 -> fallback 유도
    }
}

@DataJpaTest
@EntityScan(basePackageClasses = [FallbackSeqEntity::class])
@TestPropertySource(properties = [
    "logging.level.com.hunet.common.data.jpa.softdelete=DEBUG"
])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    NullSequenceGeneratorConfig::class,
    SpringContextHolder::class,
)
class NullSequenceGeneratorFallbackTest {
    @Autowired lateinit var repo: FallbackSeqEntityRepository

    @Test
    @Transactional
    fun fallbackGenerationWhenSequenceGeneratorReturnsNull() {
        val e = repo.upsert(FallbackSeqEntity())
        assertNotNull(e.code, "fallback 코드가 생성되어야 합니다")
        assertTrue(e.code!!.startsWith("FB-"), "prefix FB- 를 가져야 합니다")
        // 숫자 증가 여부 간단 확인
        val numPart = e.code!!.removePrefix("FB-")
        assertTrue(numPart.toLongOrNull() != null && numPart.toLong() >= 1, "FB- 뒤에 숫자가 와야 합니다: ${e.code}")
        // 두번째 엔티티 생성 시 증가 확인
        val e2 = repo.upsert(FallbackSeqEntity())
        assertNotEquals(e.code, e2.code, "서로 다른 fallback 코드여야 합니다")
    }
}

