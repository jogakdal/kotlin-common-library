package com.hunet.common_library.test_support

import com.hunet.common_library.support.DataFeed
import jakarta.persistence.EntityManager
import org.mockito.Mockito
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

@Configuration
open class TestSupportDataFeedConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(DataFeed::class)
    open fun noOpDataFeed(): DataFeed {
        val mockEm = Mockito.mock(EntityManager::class.java)
        return object : DataFeed() {
            init {
                try {
                    val f = DataFeed::class.java.getDeclaredField("entityManager")
                    f.isAccessible = true
                    f.set(this, mockEm)
                } catch (_: Exception) { }
            }
            override fun executeScriptFromFile(scriptFilePath: String) { /* no-op */ }
            override fun executeUpsertSql(query: String) { /* no-op */ }
        }
    }
}
