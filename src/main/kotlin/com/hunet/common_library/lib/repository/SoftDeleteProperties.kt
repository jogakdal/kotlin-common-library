// src/main/kotlin/com/hunet/common_library/lib/repository/SoftDeleteProperties.kt
package com.hunet.common_library.lib.repository

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * upsertAll 배치 처리 시 flush/clear 간격을 정의합니다.
 * application.yml에 값이 없으면 기본 50을 사용하며,
 * 1 미만의 값이 들어오면 애플리케이션 구동 시 예외가 발생합니다.
 */
@Component
@ConfigurationProperties(prefix = "softdelete.upsert-all")
data class SoftDeleteProperties(var flushInterval: Int = 50) {
    init {
        require(flushInterval >= 1) { "flush-interval must be at least 1" }
    }
}
