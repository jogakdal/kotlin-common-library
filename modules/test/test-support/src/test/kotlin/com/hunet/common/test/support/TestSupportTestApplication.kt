package com.hunet.common.test.support

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * 테스트용 부트스트랩 애플리케이션.
 * - 패키지: com.hunet.common.test.support (AbstractControllerTest 와 동일 루트)
 * - 목적: @SpringBootTest 가 올라갈 @SpringBootConfiguration 제공
 */
@SpringBootApplication
open class TestSupportTestApplication
