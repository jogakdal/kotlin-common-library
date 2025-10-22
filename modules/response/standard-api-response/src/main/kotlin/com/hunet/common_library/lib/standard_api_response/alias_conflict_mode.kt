package com.hunet.common_library.lib.standard_api_response

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import jakarta.annotation.PostConstruct

/**
 * Alias 충돌 발생 시 동작 모드.
 * WARN: (기본) 경고 로그 후 최초 정의 우선 (기존 동작)
 * ERROR: 충돌 발견 즉시 IllegalStateException throw (직렬화/역직렬화 모두)
 */
enum class AliasConflictMode { WARN, ERROR }

/**
 * WARN 모드에서 canonical 충돌 시, 어떤 property 를 선택할지 결정하는 전략.
 * FIRST_WIN: (기본) 최초 발견된 property 유지
 * BEST_MATCH: 입력 JSON 실제 key 와 가장 일치도가 높은 property 선택
 */
enum class AliasConflictResolution { FIRST_WIN, BEST_MATCH }

internal object AliasConflictConfig {
    @Volatile var mode: AliasConflictMode = resolveInitialMode(); internal set
    @Volatile var resolution: AliasConflictResolution = resolveInitialResolution(); internal set

    private fun resolveInitialMode(): AliasConflictMode {
        val raw = System.getProperty(PROP_MODE) ?: System.getenv(ENV_MODE) ?: "WARN"
        return runCatching { AliasConflictMode.valueOf(raw.trim().uppercase()) }.getOrElse { AliasConflictMode.WARN }
    }
    private fun resolveInitialResolution(): AliasConflictResolution {
        val raw = System.getProperty(PROP_RESOLUTION) ?: System.getenv(ENV_RESOLUTION) ?: "FIRST_WIN"
        return runCatching {
            AliasConflictResolution.valueOf(raw.trim().uppercase())
        }.getOrElse { AliasConflictResolution.FIRST_WIN }
    }

    internal const val PROP_MODE = "standard-api-response.alias-conflict-mode"
    internal const val ENV_MODE = "STANDARD_API_RESPONSE_ALIAS_CONFLICT_MODE"
    internal const val PROP_RESOLUTION = "standard-api-response.alias-conflict-resolution"
    internal const val ENV_RESOLUTION = "STANDARD_API_RESPONSE_ALIAS_CONFLICT_RESOLUTION"
}

@Configuration
class AliasConflictModeConfiguration(
    @Value("\${standard-api-response.alias-conflict-mode:WARN}") private val configuredMode: String,
    @Value("\${standard-api-response.alias-conflict-resolution:FIRST_WIN}") private val configuredResolution: String
) {
    @PostConstruct
    fun init() {
        val resolvedMode = runCatching {
            AliasConflictMode.valueOf(configuredMode.trim().uppercase())
        }.getOrElse { AliasConflictMode.WARN }
        AliasConflictConfig.mode = resolvedMode

        val resolvedRes = runCatching {
            AliasConflictResolution.valueOf(configuredResolution.trim().uppercase())
        }.getOrElse { AliasConflictResolution.FIRST_WIN }
        AliasConflictConfig.resolution = resolvedRes
    }
}
