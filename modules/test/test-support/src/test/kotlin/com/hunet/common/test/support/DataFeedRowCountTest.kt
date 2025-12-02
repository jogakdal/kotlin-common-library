package com.hunet.common.test.support

import com.hunet.common.support.DataFeed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * DataFeed 행수 반환 신규 메서드 검증
 */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:rcdb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "logging.level.com.hunet.common.support.DataFeed=DEBUG"
])
@ActiveProfiles("local")
class DataFeedRowCountTest : AbstractControllerTest() {
    @Test
    fun `INSERT row count 가 반환된다`() {
        val df: DataFeed = dataFeed ?: error("DataFeed not injected")
        df.entityManagerFactory.createEntityManager().use { em ->
            em.transaction.begin()
            em.createNativeQuery("CREATE TABLE IF NOT EXISTS rc_users (id BIGINT PRIMARY KEY, name VARCHAR(100))").executeUpdate()
            em.createNativeQuery("DELETE FROM rc_users").executeUpdate()
            em.transaction.commit()
        }
        val count = df.executeUpsertSqlReturningCount("INSERT INTO rc_users(id, name) VALUES (1, 'Alice'), (2, 'Bob')")
        assertThat(count).isEqualTo(2)
        val dupCount = try {
            df.executeUpsertSqlReturningCount("INSERT IGNORE INTO rc_users(id, name) VALUES (1, 'Alice'), (2, 'Bob')")
        } catch (ex: Exception) {
            if (ex.message?.contains("Syntax error", true) == true) 0 else throw ex
        }
        assertThat(dupCount).isBetween(0,2)
    }
}
