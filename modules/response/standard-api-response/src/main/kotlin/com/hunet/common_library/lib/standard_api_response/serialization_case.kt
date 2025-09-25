package com.hunet.common_library.lib.standard_api_response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonAlias
import com.hunet.common_library.lib.standard_api_response.KeyNormalizationUtil.canonical
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("StandardApiSerialization")

// 특정 필드 직렬화 케이스 변환 제외
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoCaseTransform

/** 직렬화 시 사용할 케이스 컨벤션 */
enum class CaseConvention { IDENTITY, SNAKE_CASE, SCREAMING_SNAKE_CASE, KEBAB_CASE, CAMEL_CASE, PASCAL_CASE }

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ResponseCase(val value: CaseConvention)

private object CaseKeyCache {
    private val cache: MutableMap<CaseConvention, ConcurrentHashMap<String, String>> =
        CaseConvention.entries.associateWith { ConcurrentHashMap<String, String>() }.toMutableMap()

    fun get(case: CaseConvention, key: String, computer: () -> String) =
        cache[case]!!.computeIfAbsent(key) { computer() }
}

private fun splitTokens(original: String): List<String> =
    if (original.isEmpty()) emptyList()
    else Regex("[A-Z]+(?=[A-Z][a-z0-9])|[A-Z]?[a-z0-9]+|[A-Z]+|[0-9]+")
        .findAll(original.replace('-', ' ').replace('_', ' '))
        .map { it.value.lowercase() }
        .toList()

private fun convertKey(key: String, case: CaseConvention): String =
    CaseKeyCache.get(case, key) {
        when (case) {
            CaseConvention.IDENTITY -> key
            CaseConvention.SNAKE_CASE -> splitTokens(key).joinToString("_")
            CaseConvention.SCREAMING_SNAKE_CASE -> splitTokens(key).joinToString("_").uppercase()
            CaseConvention.KEBAB_CASE -> splitTokens(key).joinToString("-")
            CaseConvention.CAMEL_CASE ->
                splitTokens(key).let { toks ->
                    if (toks.isEmpty()) key
                    else toks.first() + toks.drop(1).joinToString("") { it.replaceFirstChar { c -> c.titlecase() } }
                }
            CaseConvention.PASCAL_CASE ->
                splitTokens(key).joinToString("") { it.replaceFirstChar { c -> c.titlecase() } }
        }
    }

private fun transform(node: JsonNode, case: CaseConvention, skip: Set<String>): JsonNode =
    if (case == CaseConvention.IDENTITY) node
    else when (node) {
        is ObjectNode -> {
            val newObj = Jackson.json.createObjectNode()
            val fields = node.fields()
            while (fields.hasNext()) {
                val (k, v) = fields.next().let { it.key to it.value }
                val targetKey = if (k in skip) k else convertKey(k, case)
                newObj.set<JsonNode>(targetKey, transform(v, case, skip))
            }
            newObj
        }
        is ArrayNode -> {
            val newArr = Jackson.json.createArrayNode()
            node.forEach { newArr.add(transform(it, case, skip)) }
            newArr
        }
        else -> node
    }

@PublishedApi
internal fun transformAllKeys(node: JsonNode, case: CaseConvention) = transform(node, case, emptySet())

private object AliasCache {
    private val cache = ConcurrentHashMap<KClass<*>, GlobalAliasMaps>()
    fun get(kc: KClass<*>): GlobalAliasMaps? = cache[kc]
    fun put(kc: KClass<*>, maps: GlobalAliasMaps) { cache.putIfAbsent(kc, maps) }
    fun clear() = cache.clear()
}

fun clearAliasCaches() = AliasCache.clear()

@PublishedApi
internal fun collectGlobalAliasMaps(root: KClass<*>): GlobalAliasMaps {
    AliasCache.get(root)?.let { return it }

    val serialization = mutableMapOf<String, String>()
    val canonical = mutableMapOf<String, String>()
    val skipCase = mutableSetOf<String>()
    val visited = mutableSetOf<KClass<*>>()

    fun register(propertyName: String, alias: String?) {
        if (!alias.isNullOrBlank() && alias != propertyName) {
            val existing = serialization[propertyName]
            if (existing != null && existing != alias)
                LOG.warn("Alias conflict (serialization): property=$propertyName old=$existing new=$alias keeping-old")
            else if (existing == null) serialization[propertyName] = alias
        }
    }

    fun registerCanonical(candidate: String, propertyName: String) {
        val key = candidate.canonical()
        val existing = canonical[key]
        if (existing != null && existing != propertyName)
            LOG.warn("Alias canonical conflict: key=$key first=$existing second=$propertyName ignoring-second")
        else if (existing == null) canonical[key] = propertyName
    }

    fun handleAliasVariants(str: String, propertyName: String) {
        if (str.contains('_')) registerCanonical(str.replace('_','-'), propertyName)
        if (str.contains('-')) registerCanonical(str.replace('-','_'), propertyName)
    }

    fun recordSkipKeys(primary: String, aliasValues: List<String>) {
        skipCase += primary
        aliasValues.forEach { skipCase += it }
        if (primary.contains('_')) skipCase += primary.replace('_','-')
        if (primary.contains('-')) skipCase += primary.replace('-','_')
    }

    fun recurse(kClass: KClass<*>) {
        if (!visited.add(kClass)) return
        val properties = (kClass.memberProperties + kClass.declaredMemberProperties).distinctBy { it.name }
        properties.forEach { property ->
            val jsonProperty = property.findAnnotation<JsonProperty>()?.value?.takeUnless { it.isBlank() }
            val aliases = property.findAnnotation<JsonAlias>()?.value?.filterNot { it.isBlank() } ?: emptyList()
            val primary = jsonProperty ?: property.name
            if (property.findAnnotation<NoCaseTransform>() != null) recordSkipKeys(primary, aliases)

            register(property.name, jsonProperty)
            registerCanonical(primary, property.name)
            registerCanonical(property.name, property.name)

            aliases.forEach { alias ->
                registerCanonical(alias, property.name)
                handleAliasVariants(alias, property.name)
            }

            handleAliasVariants(primary, property.name)

            fun inspectKType(kType: KType) {
                val classifier = kType.classifier
                if (classifier is KClass<*>) {
                    when {
                        classifier.isSubclassOf(BasePayload::class) -> recurse(classifier)

                        classifier.isSubclassOf(Collection::class) ->
                            kType.arguments.firstOrNull()?.type?.let { inner -> inspectKType(inner) }

                        classifier.isSubclassOf(Map::class) ->
                            kType.arguments.getOrNull(1)?.type?.let { vType -> inspectKType(vType) }
                    }
                }
            }

            inspectKType(property.returnType)
        }

        kClass.java.declaredFields.forEach { field ->
            val jsonProperty = field.getAnnotation(JsonProperty::class.java)?.value?.takeUnless { it.isBlank() }
            val aliases = field.getAnnotation(JsonAlias::class.java)?.value?.filterNot { it.isBlank() } ?: emptyList()
            val primary = jsonProperty ?: field.name
            if (field.isAnnotationPresent(NoCaseTransform::class.java)) recordSkipKeys(primary, aliases)

            register(field.name, jsonProperty)
            registerCanonical(primary, field.name)
            registerCanonical(field.name, field.name)
            aliases.forEach { alias ->
                registerCanonical(alias, field.name)
                handleAliasVariants(alias, field.name)
            }
            handleAliasVariants(primary, field.name)
        }

        kClass.primaryConstructor?.parameters?.forEach { param ->
            val paramName = param.name ?: return@forEach
            val jsonProperty = param.findAnnotation<JsonProperty>()?.value?.takeUnless { it.isBlank() }
            val aliases = param.findAnnotation<JsonAlias>()?.value?.filterNot { it.isBlank() } ?: emptyList()
            val primary = jsonProperty ?: paramName
            if (param.findAnnotation<NoCaseTransform>() != null) recordSkipKeys(primary, aliases)
            register(paramName, jsonProperty)
            registerCanonical(primary, paramName)
            registerCanonical(paramName, paramName)
            aliases.forEach { alias ->
                registerCanonical(alias, paramName)
                handleAliasVariants(alias, paramName)
            }
            handleAliasVariants(primary, paramName)
        }
    }

    recurse(root)
    val maps = GlobalAliasMaps(serialization.toMap(), canonical.toMap(), skipCase.toSet())
    AliasCache.put(root, maps)

    return maps
}

private fun applyAliases(node: JsonNode, aliasMap: Map<String, String>): JsonNode =
    if (aliasMap.isEmpty()) node
    else when (node) {
        is ObjectNode -> {
            val newObj = Jackson.json.createObjectNode()
            node.fields().forEachRemaining { (k, v) ->
                val alias = aliasMap[k] ?: k
                newObj.set<JsonNode>(alias, applyAliases(v, aliasMap))
            }
            newObj
        }
        is ArrayNode -> {
            val newArr = Jackson.json.createArrayNode()
            node.forEach { newArr.add(applyAliases(it, aliasMap)) }
            newArr
        }
        else -> node
    }

@PublishedApi
internal data class GlobalAliasMaps(
    val serializationMap: Map<String, String>, // propertyName -> alias(for serialization)
    val canonicalAliasToProp: Map<String, String>, // canonical(alias or alias variants) -> propertyName
    val skipCaseKeys: Set<String> // 최종 변환에서 케이스 변경 제외될 키 모음 (alias 및 variants 포함)
)

fun <T: BasePayload> StandardResponse<T>.toJson(case: CaseConvention? = null, pretty: Boolean = false): String {
    val payloadAnn = this.payload::class.java.getAnnotation(ResponseCase::class.java)
    val effective = case ?: payloadAnn?.value ?: CaseConvention.IDENTITY
    val mapper = Jackson.json
    val root = mapper.valueToTree<JsonNode>(this)
    val global = collectGlobalAliasMaps(this.payload::class)
    val aliased = applyAliases(root, global.serializationMap)
    val transformed = transform(aliased, effective, global.skipCaseKeys)

    return if (pretty) mapper.writerWithDefaultPrettyPrinter().writeValueAsString(transformed)
    else mapper.writeValueAsString(transformed)
}
