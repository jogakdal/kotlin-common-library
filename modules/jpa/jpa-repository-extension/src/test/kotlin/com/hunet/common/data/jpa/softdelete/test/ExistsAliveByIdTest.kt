@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration
import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.Transactional
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Column
import java.time.LocalDateTime
import org.springframework.beans.factory.annotation.Autowired
import jakarta.persistence.EntityManager
import java.sql.Timestamp

@Entity
@Table(name = "users")
data class UserEntity(
    @Id val id: Long,
    val email: String,
    @DeleteMark(
        aliveMark = DeleteMarkValue.DATE_TIME_MIN,
        deletedMark = DeleteMarkValue.NOW
    )
    @Column(name = "deletedAt")
    var deletedAt: LocalDateTime = LocalDateTime.parse("1000-01-01T00:00:00")
)

interface UserRepository : SoftDeleteJpaRepository<UserEntity, Long>

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:softdelete;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    // 스키마 초기화 활성화 (schema.sql 적용)
    "spring.sql.init.mode=always",
    "spring.sql.init.platform=h2",
    // Hibernate DDL 자동 생성 비활성화
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.show-sql=true",
    // 카멜케이스 네이밍 보존
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl"
])
@EnableJpaRepositories(basePackageClasses = [UserEntity::class], repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)
@EntityScan(basePackageClasses = [UserEntity::class])
@Import(SoftDeleteJpaRepositoryAutoConfiguration::class)
class ExistsAliveByIdTest {
    @Autowired
    lateinit var userRepository: UserRepository
    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    @Transactional
    fun `existsAliveById 삭제 복구 검증`() {
        val u1 = UserEntity(1L, "jogakdal@gmail.com")
        val u2 = UserEntity(2L, "jogakdal@naver.com")
        userRepository.upsert(u1)
        userRepository.upsert(u2)
        assertTrue(userRepository.existsAliveById(1L))
        assertTrue(userRepository.existsAliveById(2L))

        userRepository.softDeleteById(2L)

        assertTrue(userRepository.existsAliveById(1L))
        assertFalse(userRepository.existsAliveById(2L))

        entityManager.createNativeQuery("UPDATE users SET deletedAt = ? WHERE id = ?")
            .setParameter(1, Timestamp.valueOf("1000-01-01 00:00:00"))
            .setParameter(2, 2L)
            .executeUpdate()
        entityManager.flush()

        assertTrue(userRepository.existsAliveById(2L))
    }
}
