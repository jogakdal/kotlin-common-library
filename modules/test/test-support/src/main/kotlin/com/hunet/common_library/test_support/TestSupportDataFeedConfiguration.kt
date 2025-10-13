package com.hunet.common_library.test_support

import com.hunet.common_library.autoconfigure.CommonCoreAutoConfiguration
import com.hunet.common_library.support.DataFeed
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration(after = [CommonCoreAutoConfiguration::class])
// EntityManagerFactory 와 DataFeed 둘 다 없을 때만 fallback 활성화 (JPA 미사용 + 사용자 정의 DataFeed 없음)
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
