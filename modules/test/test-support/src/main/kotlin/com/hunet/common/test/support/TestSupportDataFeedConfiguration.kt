package com.hunet.common.test.support

import com.hunet.common.autoconfigure.CommonCoreAutoConfiguration
import com.hunet.common.support.DataFeed
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration(after = [CommonCoreAutoConfiguration::class])
@ConditionalOnMissingBean(value = [EntityManagerFactory::class, DataFeed::class])
@Configuration(proxyBeanMethods = false)
open class TestSupportDataFeedConfiguration {
    open class TestSupportNoOpDataFeed : DataFeed() {
        override fun executeScriptFromFile(scriptFilePath: String) { /* no-op */ }
        override fun executeUpsertSql(query: String) { /* no-op */ }
        override fun executeStatement(sql: String) { /* no-op */ }
    }

    @Bean
    @ConditionalOnMissingBean(DataFeed::class)
    open fun noOpDataFeed(): DataFeed = TestSupportNoOpDataFeed()
}
