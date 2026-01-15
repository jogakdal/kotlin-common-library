@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.*
import com.hunet.common.lib.SpringContextHolder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.test.context.TestPropertySource
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.boot.test.context.TestConfiguration

@DataJpaTest
@EntityScan(basePackageClasses = [
    AliveYesEntity::class,
    StrictTestEntity::class,
    NotNullAliveEntity::class,
    WhereDuplicatedEntity::class
])
@EnableJpaRepositories(
    repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class,
    basePackageClasses = [
        AliveYesEntityRepository::class,
        StrictTestEntityRepository::class,
        NotNullAliveEntityRepository::class,
        WhereDuplicatedEntityRepository::class
    ]
)
@Import(
    SoftDeleteJpaRepositoryAutoConfiguration::class,
    SoftDeleteRepositoryRegistry::class,
    SequenceGeneratorBasicConfig::class,
    SpringContextHolder::class
)
@TestPropertySource(properties = ["softdelete.query.strict=true"]) // 기본 strict 보장
/* 기본 기능 통합 테스트 */
class SoftDeleteJpaRepositoryBasicTest {
    @Autowired lateinit var aliveYesEntityRepository: AliveYesEntityRepository
    @Autowired lateinit var strictTestEntityRepository: StrictTestEntityRepository
    @Autowired lateinit var notNullAliveEntityRepository: NotNullAliveEntityRepository
    @Autowired lateinit var whereDuplicatedEntityRepository: WhereDuplicatedEntityRepository

    @Test
    @Transactional
    fun `whereClause가 비어 있을 때 True 적용 및 alive predicate(Y) 검증`() {
        aliveYesEntityRepository.upsert(AliveYesEntity(name = "A", status = "Y"))
        aliveYesEntityRepository.upsert(AliveYesEntity(name = "B", status = "Y"))
        aliveYesEntityRepository.upsert(AliveYesEntity(name = "C", status = "N"))
        val allAlive = aliveYesEntityRepository.findAllByCondition("", null)
        assertEquals(2, allAlive.totalElements)
        assertTrue(allAlive.content.all { it.status == "Y" })
    }

    @Test
    @Transactional
    fun `커스텀 aliveMark YES 값 바인딩 검증`() {
        val e = aliveYesEntityRepository.upsert(AliveYesEntity(name = "Alive", status = "Y"))
        aliveYesEntityRepository.upsert(AliveYesEntity(name = "Deleted", status = "N"))
        val found = aliveYesEntityRepository.findFirstByField("name", "Alive")
        assertTrue(found.isPresent)
        assertEquals(e.id, found.get().id)
        assertTrue(aliveYesEntityRepository.findFirstByField("name", "Deleted").isEmpty)
    }

    @Test
    @Transactional
    fun `NULL 또는 NOW soft delete 후 alive 필터 제거`() {
        val saved = strictTestEntityRepository.upsert(StrictTestEntity(value = "V1"))
        assertTrue(strictTestEntityRepository.findFirstByField("value", "V1").isPresent)
        strictTestEntityRepository.softDeleteById(saved.id!!)
        assertTrue(strictTestEntityRepository.findFirstByField("value", "V1").isEmpty)
    }

    @Test
    @Transactional
    fun `strict 모드 시 잘못된 단일 필드명 예외`() {
        assertThrows<InvalidDataAccessApiUsageException> {
            aliveYesEntityRepository.findAllByField("unknownField", "X", null)
        }
    }

    @Test
    @Transactional
    fun `strict 모드 다중 필드 중 오류 발생 시 예외`() {
        assertThrows<InvalidDataAccessApiUsageException> {
            aliveYesEntityRepository.findAllByFields(mapOf("name" to "A", "wrong" to "B"), null)
        }
    }

    @Test
    @Transactional
    fun `NOT_NULL aliveMark 엔티티 softDelete 후 필터링`() {
        val e = notNullAliveEntityRepository.upsert(NotNullAliveEntity(info = "I1"))
        assertTrue(notNullAliveEntityRepository.findFirstByField("info", "I1").isPresent)
        notNullAliveEntityRepository.softDeleteById(e.id!!)
        assertTrue(notNullAliveEntityRepository.findFirstByField("info", "I1").isEmpty)
    }

    @Test
    @Transactional
    fun `@Where + deleteMarkInfo 중복 alive 필터 경고 동작 기능적 결과 검증`() {
        val first = whereDuplicatedEntityRepository.upsert(WhereDuplicatedEntity(label = "L1"))
        assertNotNull(first.id)
        whereDuplicatedEntityRepository.softDeleteById(first.id!!)
        assertTrue(whereDuplicatedEntityRepository.findFirstByField("label", "L1").isEmpty)
    }

    @Test
    @Transactional
    fun `selectAll 빈 조건 TRUE 처리`() {
        (1..3).forEach { i ->
            notNullAliveEntityRepository.upsert(NotNullAliveEntity(info = "INFO-$i"))
        }
        val page = notNullAliveEntityRepository.findAllByCondition("", null)
        assertEquals(3, page.totalElements)
    }

    @Test
    @Transactional
    fun `strict false 모드 WARN 시나리오 검증`() {
        System.setProperty("softdelete.query.strict", "false")
        val ex = assertThrows<InvalidDataAccessApiUsageException> {
            notNullAliveEntityRepository.findAllByField("wrong_field", "X", null)
        }
        assertTrue(ex.cause is IllegalArgumentException)
        System.setProperty("softdelete.query.strict", "true")
    }
}

@TestConfiguration
class SequenceGeneratorBasicConfig {
    @Bean
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = null
    }
}
