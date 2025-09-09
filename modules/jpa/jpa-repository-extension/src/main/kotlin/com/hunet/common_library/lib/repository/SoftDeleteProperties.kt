package com.hunet.common_library.lib.repository

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "softdelete.upsert-all")
data class SoftDeleteProperties(var flushInterval: Int = 50) {
    init { require(flushInterval >= 1) { "flush-interval must be at least 1" } }
}

