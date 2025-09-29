package com.hunet.common_library.autoconfigure

import com.hunet.common_library.support.DataFeed
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [HibernateJpaAutoConfiguration::class])
@ConditionalOnClass(EntityManagerFactory::class)
@ConditionalOnBean(EntityManagerFactory::class)
class CommonCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataFeed::class)
    fun dataFeed(emf: EntityManagerFactory): DataFeed =
        DataFeed().apply { this.entityManagerFactory = emf }
}
