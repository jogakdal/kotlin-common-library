package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl
import com.hunet.common.data.jpa.softdelete.SoftDeleteRepositoryRegistry
import com.hunet.common.lib.SpringContextHolder
import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.annotation.DirtiesContext

@Entity
@Table(name = "upsert_merge_entity")
class UpsertMergeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var valueA: String? = null,

    var valueB: String? = null,

    @GenerateSequentialCode(prefixExpression = "'MP_'")
    var code: String? = null
)

interface UpsertMergeEntityRepository : SoftDeleteJpaRepository<UpsertMergeEntity, Long>

@Configuration
class MergePolicySequenceGeneratorConfig {
    private class TestSequenceGenerator : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?) = prefix + System.nanoTime()
    }
    @Bean(name = ["sequenceGenerator"]) // 기존 동일 타입 Bean 덮어쓰기
    @Primary
    fun sequenceGenerator(): SequenceGenerator = TestSequenceGenerator()
}
// IGNORE 정책 테스트
@DataJpaTest
@EntityScan(basePackageClasses = [UpsertMergeEntity::class])
@EnableJpaRepositories(
    repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class,
    basePackageClasses = [UpsertMergeEntityRepository::class]
)
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    MergePolicySequenceGeneratorConfig::class,
    SpringContextHolder::class
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SoftDeleteJpaRepositoryMergePolicyTest {
    @Autowired lateinit var repo: UpsertMergeEntityRepository

    @ParameterizedTest
    @ValueSource(strings = ["ignore", "overwrite"])
    fun testNullMergePolicyVariants(policy: String) {
        System.setProperty("softdelete.upsert.null-merge", policy)
        val saved = repo.upsert(UpsertMergeEntity(valueA = "A1", valueB = "B1"))
        val id = saved.id!!
        val updateEntity = when (policy) {
            "ignore" -> UpsertMergeEntity(id = id, valueA = null, valueB = "B2")
            else -> UpsertMergeEntity(id = id, valueA = null, valueB = null)
        }
        val merged = repo.upsert(updateEntity)
        if (policy == "ignore") {
            assertEquals("A1", merged.valueA)
            assertEquals("B2", merged.valueB)
        } else {
            assertNull(merged.valueA)
            assertNull(merged.valueB)
        }
    }

    @Test
    fun testSequentialCodeGeneratedOnce() {
        val e1 = repo.upsert(UpsertMergeEntity(valueA = "X", valueB = "Y"))
        val firstCode = e1.code
        // overwrite policy: 코드가 이미 있으면 유지
        System.setProperty("softdelete.upsert.null-merge", "overwrite")
        val e2 = repo.upsert(UpsertMergeEntity(id = e1.id, valueA = "X2", valueB = null))
        assertEquals(firstCode, e2.code)
    }
}
