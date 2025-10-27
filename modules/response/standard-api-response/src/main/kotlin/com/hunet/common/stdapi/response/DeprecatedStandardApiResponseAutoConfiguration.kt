package com.hunet.common.stdapi.response

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Deprecated: 기존 prefix (standard-api-response.*) 호환 유지용. 1 release 후 제거 예정.
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnProperty(
    prefix = "standard-api-response.auto-duration-calculation",
    name = ["active"],
    havingValue = "true",
    matchIfMissing = false
)
@Deprecated("Use StandardApiResponseAutoConfiguration with prefix 'stdapi.response.*'")
class DeprecatedStandardApiResponseAutoConfiguration {
    @Bean fun deprecatedRequestTimingFilter() = RequestTimingFilter()

    @Bean
    fun deprecatedRequestTimingFilterRegistration(
        filter: RequestTimingFilter
    ): FilterRegistrationBean<RequestTimingFilter> = FilterRegistrationBean(filter).apply { this.order = Int.MIN_VALUE }
}
