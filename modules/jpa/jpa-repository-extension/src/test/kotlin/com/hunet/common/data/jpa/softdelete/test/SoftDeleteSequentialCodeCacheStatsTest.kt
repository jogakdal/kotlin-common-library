@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.annotation.DirtiesContext

@Entity
@Table(name = "seq_cache_entity")
class SeqCacheEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @GenerateSequentialCode(prefixExpression = "'SC_'")
    var code: String? = null
)

interface SeqCacheEntityRepository : SoftDeleteJpaRepository<SeqCacheEntity, Long>

@DataJpaTest
@EntityScan(basePackageClasses = [SeqCacheEntity::class])
@EnableJpaRepositories(
    repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class,
    basePackageClasses = [SeqCacheEntityRepository::class]
)
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SpringContextHolder::class
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SoftDeleteSequentialCodeCacheStatsTest {

    @Autowired
    lateinit var repo: SeqCacheEntityRepository

    @Test
    fun `시퀀스 코드 캐시 최초 MISS 이후 동일 클래스 재사용 시 missCount 증가 없음`() {
        val (initialMiss, initialSize) = repo.sequentialCodeCacheStats()

        val saved1 = repo.upsert(SeqCacheEntity())
        assertNotNull(saved1.code)
        val (afterFirstMiss, afterFirstSize) = repo.sequentialCodeCacheStats()
        assertTrue(
            afterFirstMiss >= initialMiss,
            "첫 upsert 이후 missCount 감소 불가: initial=$initialMiss, after=$afterFirstMiss"
        )
        assertTrue(
            afterFirstSize >= initialSize,
            "첫 upsert 이후 cacheSize 감소 불가: initial=$initialSize, after=$afterFirstSize"
        )

        val saved2 = repo.upsert(SeqCacheEntity())
        assertNotNull(saved2.code)
        val (afterSecondMiss, afterSecondSize) = repo.sequentialCodeCacheStats()
        assertEquals(afterFirstMiss, afterSecondMiss, "동일 클래스 재사용 시 missCount 증가하면 안됨")
        assertEquals(afterFirstSize, afterSecondSize, "동일 클래스 재사용 시 cacheSize 증가하면 안됨")
    }
}
