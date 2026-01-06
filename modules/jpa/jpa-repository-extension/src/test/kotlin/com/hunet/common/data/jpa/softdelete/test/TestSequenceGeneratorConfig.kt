package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.SequenceGenerator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.atomic.AtomicLong

/**
 * 통합 테스트용 단일 SequenceGenerator Bean.
 * prefix + 증가 카운터 (결정적) 형태로 키 생성.
 */
@TestConfiguration
class TestSequenceGeneratorConfig {
    @Bean
    @Primary
    fun sequenceGenerator(): SequenceGenerator {
        val counters = mutableMapOf<String, AtomicLong>()
        return object : SequenceGenerator {
            override fun generateKey(prefix: String, entity: Any?) = counters.computeIfAbsent(prefix) {
                AtomicLong(0) }.let { cnt -> prefix + cnt.incrementAndGet() }
        }
    }
}
