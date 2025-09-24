package com.hunet.common_library.lib.standard_api_response

import com.hunet.common_library.lib.standard_api_response.KeyNormalizationUtil.canonical
import kotlinx.serialization.json.*

@PublishedApi
internal object KeyNormalizationUtil {
    @PublishedApi
    internal fun String.canonical() = this.filter { it.isLetterOrDigit() }.lowercase()

    @PublishedApi
    internal fun String.toCamelCase(): String {
        if (isEmpty()) return this
        val parts = split('_', '-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return this
        val first = parts.first().lowercase()
        val rest = parts.drop(1).joinToString("") { p ->
            p.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return first + rest
    }

    @PublishedApi
    internal fun normalizeKeysToCamel(element: JsonElement, skipKeys: Set<String> = emptySet()): JsonElement =
        when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (k, v) ->
                    val targetKey = if (k in skipKeys) k else k.toCamelCase()
                    put(targetKey, normalizeKeysToCamel(v, skipKeys))
                }
            }
            is JsonArray -> JsonArray(element.map { normalizeKeysToCamel(it, skipKeys) })
            else -> element
        }
}

@PublishedApi
internal fun JsonObject.getByCanonicalKey(target: String) = this.entries.firstOrNull {
    it.key.canonical() == target.canonical()
}?.value
