package com.hunet.common.test.support

import com.hunet.common.support.DataFeed
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ActiveProfiles

/**
 * 외부 프로젝트에서 DataFeed 빈을 커스텀으로 정의하면서 entityManagerFactory 주입을 누락한 경우
 * 기존에는 조용히(skip) 되었으나 이제는 IllegalStateException 을 던져 조기 실패한다.
 */
@SpringBootTest(
    classes = [DataFeedMisconfigurationTest.Misconfig::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:misdb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "logging.level.com.hunet.common.support.DataFeed=DEBUG"
    ]
)
@ActiveProfiles("local")
class DataFeedMisconfigurationTest {
    @Configuration
    @EnableAutoConfiguration
    open class Misconfig {
        // 잘못된 구성: entityManagerFactory 를 세팅하지 않음 (CommonCoreAutoConfiguration 조건을 피하기 위해 직접 제공)
        @Bean open fun dataFeed(): DataFeed = DataFeed()
    }

    @Autowired lateinit var dataFeed: DataFeed
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `entityManagerFactory 미초기화 DataFeed 는 IllegalStateException 을 던진다`() {
        // 사전: 실제 JPA EntityManagerFactory 는 context 에 존재 (정상 JPA 환경 가정)
        entityManagerFactory.createEntityManager().use { em ->
            em.transaction.begin()
            em.createNativeQuery("CREATE TABLE IF NOT EXISTS mis_users (id BIGINT PRIMARY KEY, name VARCHAR(100))").executeUpdate()
            em.createNativeQuery("DELETE FROM mis_users").executeUpdate()
            em.transaction.commit()
        }
        // 잘못 초기화된 DataFeed 사용 -> 예외 발생해야 함
        assertThatThrownBy {
            dataFeed.executeUpsertSql("INSERT INTO mis_users(id, name) VALUES (1, 'ShouldFail')")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("entityManagerFactory not initialized")
    }
}
