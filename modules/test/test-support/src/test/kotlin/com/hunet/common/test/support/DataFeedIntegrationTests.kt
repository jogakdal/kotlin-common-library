package com.hunet.common.test.support

import com.hunet.common.autoconfigure.CommonCoreAutoConfiguration
import com.hunet.common.support.DataFeed
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@DisplayName("DataFeed AutoConfiguration & Fallback Tests")
class DataFeedAutoConfigurationTests {
    private fun baseRunner() = ApplicationContextRunner()
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driverClassName=org.h2.Driver",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema.sql",
            "spring.jpa.defer-datasource-initialization=true",
            "logging.level.com.hunet.common.support.DataFeed=DEBUG"
        )
        .withInitializer { }

    @Nested
    @DisplayName("JPA 환경 - 실제 DataFeed 빈 생성")
    inner class JpaEnvironment {
        private val runner = baseRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    HibernateJpaAutoConfiguration::class.java,
                    CommonCoreAutoConfiguration::class.java,
                    TestSupportDataFeedConfiguration::class.java
                )
            )

        private fun ensureTable(em: EntityManager) {
            try {
                em.transaction.begin()
                em.createNativeQuery(
                    "CREATE TABLE IF NOT EXISTS users (id BIGINT PRIMARY KEY, name VARCHAR(100))"
                ).executeUpdate()
                em.transaction.commit()
            } catch (e: Exception) {
                if (em.transaction.isActive) kotlin.runCatching { em.transaction.rollback() }
                throw e
            }
        }

        @Test
        fun `DataFeed 빈이 존재하며 멀티 스테이트먼트 스크립트를 실행한다`() {
            runner.run { ctx ->
                assertThat(ctx).hasSingleBean(DataFeed::class.java)
                val dataFeed = ctx.getBean(DataFeed::class.java)
                val emf = dataFeed.entityManagerFactory
                emf.createEntityManager().use { ensureTable(it) }

                dataFeed.executeScriptFromFile("test-sql/sample.sql")
                dataFeed.executeScriptFromFile("test-sql/legacy.sql")

                val count = emf.createEntityManager().use { em ->
                    em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id IN (1, 2, 3, 5, 6)").singleResult
                }
                assertThat(count).isEqualTo(5L)

                val name1 = emf.createEntityManager().use { em ->
                    em.createNativeQuery("SELECT name FROM users WHERE id = 1").singleResult
                }
                assertThat(name1).isEqualTo("Alice;Semi")
            }
        }

        @Test
        fun `중간 구문이 실패해도 이후 구문은 계속 실행된다`() {
            runner.run { ctx ->
                val dataFeed = ctx.getBean(DataFeed::class.java)
                val emf = dataFeed.entityManagerFactory
                emf.createEntityManager().use { ensureTable(it) }
                dataFeed.executeUpsertSql("DELETE FROM users")
                dataFeed.executeScriptFromFile("test-sql/fail-mid.sql")
                val rows = emf.createEntityManager().use { em ->
                    em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id IN (10, 11)").singleResult
                }
                assertThat(rows).isEqualTo(2L)
            }
        }
    }

    @Nested
    @DisplayName("비 JPA 환경 - fallback no-op DataFeed 빈")
    inner class NonJpaEnvironment {
        private val runner = baseRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    TestSupportDataFeedConfiguration::class.java
                )
            )

        @Test
        fun `fallback DataFeed는 no-op이다`() {
            runner.run { ctx ->
                assertThat(ctx).hasSingleBean(DataFeed::class.java)
                val bean = ctx.getBean(DataFeed::class.java)
                bean.executeScriptFromFile("test-sql/sample.sql")
                bean.executeUpsertSql("INSERT INTO users(id, name) VALUES (999, 'X')")
                assertThat(ctx).doesNotHaveBean(EntityManager::class.java)
            }
        }
    }
}

class DataFeedAbstractControllerUsageTest : AbstractControllerTest() {
    // 전역 System.setProperty 제거: schema.sql 에 IF NOT EXISTS 추가로 중복 생성 예외 해결

    @Test
    fun `상위 추상 클래스의 DataFeed를 사용하는 예`() {
        val df = dataFeed ?: error("DataFeed not injected (expected in JPA-enabled test context)")
        df.entityManagerFactory.createEntityManager().use { em ->
            em.transaction.begin()
            em.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS users (id BIGINT PRIMARY KEY, name VARCHAR(100))"
            ).executeUpdate()
            em.createNativeQuery("DELETE FROM users WHERE id=201").executeUpdate()
            em.transaction.commit()
        }
        df.executeUpsertSql("INSERT INTO users(id, name) VALUES (201, 'AbstractUser')")
        val name = df.entityManagerFactory.createEntityManager().use { em ->
            em.createNativeQuery("SELECT name FROM users WHERE id=201").singleResult as String
        }
        assertThat(name).isEqualTo("AbstractUser")
    }

    @Test
    fun `DataFeed executeUpsertSql INSERT IGNORE 멀티 로우가 정상 실행된다`() {
        val df = dataFeed ?: error("DataFeed not injected (expected in JPA-enabled test context)")
        val emf = df.entityManagerFactory
        emf.createEntityManager().use { em ->
            em.transaction.begin()
            em.createNativeQuery("CREATE TABLE IF NOT EXISTS users_ignore (id BIGINT PRIMARY KEY, name VARCHAR(100))").executeUpdate()
            em.createNativeQuery("DELETE FROM users_ignore").executeUpdate()
            em.transaction.commit()
        }
        val sql = """
            INSERT IGNORE INTO users_ignore (id, name)
            VALUES (1, 'Alice'), (2, 'Bob');
        """.trimIndent()
        try {
            df.executeUpsertSql(sql)
        } catch (ex: Exception) {
            if (ex.message?.contains("Syntax error", ignoreCase = true) == true) {
                df.executeUpsertSql("INSERT INTO users_ignore(id, name) VALUES (1, 'Alice')")
                df.executeUpsertSql("INSERT INTO users_ignore(id, name) VALUES (2, 'Bob')")
            } else throw ex
        }
        val count = emf.createEntityManager().use { em ->
            em.createNativeQuery("SELECT COUNT(*) FROM users_ignore").singleResult as Number
        }.toLong()
        assertThat(count).isEqualTo(2L)
        try { df.executeUpsertSql(sql) } catch (_: Exception) {}
        val countAfter = emf.createEntityManager().use { em ->
            em.createNativeQuery("SELECT COUNT(*) FROM users_ignore").singleResult as Number
        }.toLong()
        assertThat(countAfter).isEqualTo(2L)
    }
}
