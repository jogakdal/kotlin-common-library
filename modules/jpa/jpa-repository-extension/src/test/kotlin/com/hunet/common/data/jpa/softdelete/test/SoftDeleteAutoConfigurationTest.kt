package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration
import com.hunet.common.data.jpa.softdelete.SoftDeleteProperties
import com.hunet.common.data.jpa.softdelete.SoftDeleteRepositoryRegistry
import com.hunet.common.lib.SpringContextHolder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@TestPropertySource(properties = ["softdelete.upsert-all.flush-interval=5"])
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SequenceGeneratorTestConfig::class,
    SpringContextHolder::class,
)
class SoftDeleteAutoConfigurationTest {
    @Autowired lateinit var testEntityRepository: TestEntityRepository
    @Autowired lateinit var softDeleteRepositoryRegistry: SoftDeleteRepositoryRegistry
    @Autowired lateinit var softDeleteProperties: SoftDeleteProperties

    @Test
    fun contextLoadsBeans() {
        assertNotNull(testEntityRepository)
        assertNotNull(softDeleteRepositoryRegistry)
        assertTrue(softDeleteProperties.flushInterval >= 1)
        assertNotNull(softDeleteRepositoryRegistry.getRepositoryFor(TestEntity(name = "x")))
    }

    @Test
    @Transactional
    fun softDeleteFlow() {
        val created = testEntityRepository.upsert(TestEntity(name = "hello"))
        assertNotNull(created.id)
        val id = created.id!!
        val affected = testEntityRepository.softDeleteById(id)
        assertEquals(1, affected)
        val after = testEntityRepository.findOneById(id)
        assertTrue(after.isEmpty)
    }
}

@Configuration
class SequenceGeneratorTestConfig {
    @Bean
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = null
    }
}
