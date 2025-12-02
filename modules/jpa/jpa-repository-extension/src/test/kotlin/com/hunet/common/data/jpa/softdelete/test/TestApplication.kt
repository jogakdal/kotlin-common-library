package com.hunet.common.data.jpa.softdelete.test

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import com.hunet.common.data.jpa.sequence.SequenceGenerator

@SpringBootApplication(scanBasePackages = ["com.hunet.common"]) // 라이브러리 전체 패키지 스캔
class TestApplication {
    @Bean
    fun sequenceGenerator(): SequenceGenerator = object : SequenceGenerator {
        override fun generateKey(prefix: String, entity: Any?): Any? = null // 테스트에서는 시퀀스 생성 불필요
    }
}

fun main(args: Array<String>) {
    runApplication<TestApplication>(*args)
}
