package com.hunet.common.support

import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import javax.sql.DataSource

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:extdb;DB_CLOSE_DELAY=-1;MODE=LEGACY",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.show-sql=false"
])
class DataFeedExternalUsageExampleTest {

    @Autowired
    lateinit var feed: DataFeed

    @Autowired
    lateinit var emf: EntityManagerFactory

    @Test
    fun `외부 프로젝트 사용 예제 - 스크립트 실행 후 행 검증`() {
        feed.executeScriptFromFile("db/datafeed_external_usage.sql")
        val em = emf.createEntityManager()
        em.transaction.begin()
        val count = (em.createNativeQuery("SELECT COUNT(*) FROM ext_users").singleResult as Number).toInt()
        val activeCount = (em.createNativeQuery("SELECT COUNT(*) FROM ext_users WHERE active = TRUE").singleResult as Number).toInt()
        em.transaction.commit()
        em.close()
        assertEquals(3, count)
        assertEquals(2, activeCount)
    }

    @SpringBootApplication
    class TestBootApp

    @Configuration
    class DataFeedConfig {
        @Bean
        fun dataFeed(emf: EntityManagerFactory): DataFeed = DataFeed().apply { this.entityManagerFactory = emf }

        @Bean
        fun entityManagerFactory(dataSource: DataSource): EntityManagerFactory {
            val factoryBean = LocalContainerEntityManagerFactoryBean().apply {
                this.dataSource = dataSource
                this.persistenceUnitName = "ext-unit"
                this.jpaVendorAdapter = HibernateJpaVendorAdapter()
                setPackagesToScan("com.hunet.none")
                setJpaPropertyMap(
                    mapOf(
                        "hibernate.dialect" to "org.hibernate.dialect.H2Dialect",
                        "hibernate.hbm2ddl.auto" to "none",
                        "hibernate.id.new_generator_mappings" to "true"
                    )
                )
            }
            factoryBean.afterPropertiesSet()
            return factoryBean.nativeEntityManagerFactory!!
        }

        @Bean
        fun dataSource(): DataSource = org.springframework.jdbc.datasource.DriverManagerDataSource().apply {
            setDriverClassName("org.h2.Driver")
            url = "jdbc:h2:mem:extdb;DB_CLOSE_DELAY=-1;MODE=LEGACY"
            username = "sa"
            password = ""
        }
    }
}
