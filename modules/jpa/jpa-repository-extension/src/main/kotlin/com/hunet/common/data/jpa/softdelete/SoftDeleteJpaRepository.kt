package com.hunet.common.data.jpa.softdelete

import com.hunet.common.data.jpa.resolvedTableName
import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.sequence.spelExpressionParser
import com.hunet.common.data.jpa.softdelete.annotation.deleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.AlivePredicateBuilder
import com.hunet.common.data.jpa.softdelete.internal.DeletePredicateBuilder
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import com.hunet.common.logging.commonLogger
import com.hunet.common.util.*
import jakarta.persistence.*
import jakarta.persistence.criteria.Predicate
import org.hibernate.annotations.SQLRestriction
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.lang.reflect.Field
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class UpsertKey

enum class NullMergePolicy { IGNORE, OVERWRITE }
enum class DeleteStrategy { RECURSIVE, BULK }

@Suppress("unused")
@NoRepositoryBean
interface SoftDeleteJpaRepository<E, ID: Serializable> : JpaRepository<E, ID> {
    fun upsert(entity: E): E
    fun upsertAll(entities: List<E>): List<E>
    fun updateById(id: ID, copyFunc: (E) -> Unit): E
    fun updateByField(fieldName: String, fieldValue: Any, copyFunc: (E) -> Unit): List<E>
    fun updateByFields(fields: Map<String, Any>, copyFunc: (E) -> Unit): List<E>
    fun updateByCondition(condition: String, copyFunc: (E) -> Unit): List<E>
    fun softDelete(entity: E): Int
    fun softDelete(condition: String) = softDeleteByCondition(condition)
    fun softDelete(fieldName: String, fieldValue: Any) = softDeleteByField(fieldName, fieldValue)
    fun softDelete(fields: Map<String, Any>) = softDeleteByFields(fields)
    fun softDeleteById(id: ID): Int
    fun softDeleteByField(fieldName: String, fieldValue: Any): Int
    fun softDeleteByFields(fields: Map<String, Any>): Int
    fun softDeleteByCondition(condition: String): Int
    fun refresh(entity: E): E
    fun selectAll(pageable: Pageable? = null): Page<E> = findAllByCondition("", pageable)
    fun selectAll(fieldName: String, fieldValue: Any, pageable: Pageable? = null): Page<E> =
        findAllByField(fieldName, fieldValue, pageable)
    fun selectAll(fields: Map<String, Any>, pageable: Pageable? = null): Page<E> = findAllByFields(fields, pageable)
    fun selectAll(condition: String = "", pageable: Pageable? = null): Page<E> = findAllByCondition(condition, pageable)
    fun findAllByField(fieldName: String, fieldValue: Any, pageable: Pageable? = null): Page<E>
    fun findAllByFields(fields: Map<String, Any>, pageable: Pageable? = null): Page<E>
    fun findAllByCondition(condition: String = "", pageable: Pageable? = null): Page<E>
    fun countByField(fieldName: String, fieldValue: Any): Long
    fun countByFields(fields: Map<String, Any>): Long
    fun countByCondition(condition: String = ""): Long
    fun findOne(id: ID): Optional<E> = findOneById(id)
    fun findOneById(id: ID): Optional<E>

    fun findFirstByField(fieldName: String, fieldValue: Any): Optional<E>
    fun findFirstByFields(fields: Map<String, Any>): Optional<E>
    fun findFirstByCondition(condition: String = ""): Optional<E>

    fun existsAliveById(id: ID): Boolean

    fun <R> rowLockById(id: ID, block: (E) -> R): R
    fun <R> rowLockByField(fieldName: String, fieldValue: Any, block: (E) -> R): R
    fun <R> rowLockByFields(fields: Map<String, Any>, block: (E) -> R): R
    fun <R> rowLockByCondition(condition: String, block: (E) -> R): R
    fun getEntityClass(): Class<E>
    // 캐시 missCount 및 cacheSize (테스트/모니터링 용)
    fun sequentialCodeCacheStats(): Pair<Long, Int>
}

@NoRepositoryBean
class SoftDeleteJpaRepositoryImpl<E: Any, ID: Serializable>(
    val entityInformation: JpaEntityInformation<E, *>,
    val entityManager: EntityManager
) : SimpleJpaRepository<E, ID>(entityInformation, entityManager), SoftDeleteJpaRepository<E, ID> {
    private val registry: SoftDeleteRepositoryRegistry by lazy { SpringContextHolder.getBean() }
    private val sequenceGenerator: SequenceGenerator by lazy { SpringContextHolder.getBean() }
    val entityType by lazy { entityInformation.javaType }
    val entityName: String? by lazy { entityManager.metamodel.entity(entityType).name }
    val tableName: String by lazy { entityInformation.javaType.resolvedTableName }
    private val autoModify: String by lazy {
        entityType.annotatedFields<LastModifiedDate>().mapNotNull { it.getAnnotation<Column>()?.name }
            .filter { it.isNotEmpty() }.joinToString(separator = "") { name -> ", `$name` = NOW()" }
    }
    private val deleteMarkInfo: DeleteMarkInfo? = entityType.deleteMarkInfo
    private val flushInterval: Int by lazy {
        SpringContextHolder.getProperty("softdelete.upsert-all.flush-interval", 50)
    }
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private val deleteDepthTL = ThreadLocal.withInitial { 0 }
    private val duplicateAliveFilterWarned = AtomicBoolean(false)
    // 캐시/통계: 순차 코드 필드 탐색 결과 및 미스 카운터
    private fun newIdentitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private data class SeqFields(
        val kotlinProps: List<KMutableProperty1<Any, Any?>>, val javaFields: List<Field>
    )

    companion object {
        val LOG by commonLogger()
        private val sequentialCodeFieldCache = ConcurrentHashMap<KClass<*>, SeqFields>()
        internal val seqCacheMissCounter = AtomicLong(0)
    }
    // Alive predicate 통합 데이터 클래스 및 헬퍼 (단일 정의)
    private data class AlivePredicateUnified(val fragment: String, val params: Map<String, Any?>)
    private fun buildAlivePredicateUnified(
        info: DeleteMarkInfo?,
        alias: String = "e",
        paramName: String = "aliveMarkValue"
    ): AlivePredicateUnified {
        if (info == null) return AlivePredicateUnified("", emptyMap())
        return when (info.aliveMark) {
            DeleteMarkValue.NULL -> AlivePredicateUnified(" AND $alias.${info.fieldName} IS NULL", emptyMap())
            DeleteMarkValue.NOT_NULL -> AlivePredicateUnified(" AND $alias.${info.fieldName} IS NOT NULL", emptyMap())
            else -> AlivePredicateUnified(
                " AND $alias.${info.fieldName} = :$paramName",
                mapOf(paramName to info.aliveMarkValue)
            )
        }
    }
    private fun computeDeleteMarkValue(info: DeleteMarkInfo): Any? = when (info.deleteMark) {
        DeleteMarkValue.NULL -> null
        DeleteMarkValue.NOW -> LocalDateTime.now()
        DeleteMarkValue.NOT_NULL -> DeleteMarkValue.getDefaultDeleteMarkValue(info)
        else -> info.deleteMarkValue
    }
    private val strictQueryValidation: Boolean by lazy {
        try {
            SpringContextHolder.getProperty("softdelete.query.strict", false)
        }
        catch (_: Exception) { false }
    }
    // 허용 필드 캐시 (Kotlin/Java property + @Column name)
    private val allowedFieldNames: Set<String> by lazy {
        (entityType.kotlin.memberProperties.map { it.name } + entityType.declaredFields.map { it.name } +
                entityType.collectAnnotationAttributeValues<Column>("name")).toSet()
    }
    private val nullMergePolicy: NullMergePolicy by lazy {
        val raw = try {
            SpringContextHolder.getProperty("softdelete.upsert.null-merge", "ignore")
        } catch (_: Exception) { "ignore" }

        when (raw.lowercase()) {
            "ignore" -> NullMergePolicy.IGNORE
            "overwrite" -> NullMergePolicy.OVERWRITE
            else -> {
                LOG.warn(
                    "[SoftDeleteJpaRepository] Invalid value '$raw' for softdelete.upsert.null-merge. Using IGNORE."
                )
                NullMergePolicy.IGNORE
            }
        }
    }
    private val deleteStrategy: DeleteStrategy by lazy {
        val raw = try {
            SpringContextHolder.getProperty("softdelete.delete.strategy", "recursive")
        } catch (_: Exception) { "recursive" }
        when (raw.lowercase()) {
            "bulk" -> DeleteStrategy.BULK
            else -> DeleteStrategy.RECURSIVE
        }
    }
    private val usePredicateBuilder: Boolean by lazy {
        try {
            SpringContextHolder.getProperty("softdelete.alive-predicate.enabled", false)
        } catch (_: Exception) { false }
    }

    override fun sequentialCodeCacheStats() = seqCacheMissCounter.get() to sequentialCodeFieldCache.size
    override fun getEntityClass(): Class<E> = entityType

    private fun findByUpsertKey(entity: E): E? {
        val upsertKeyProp = entityType.kotlin.memberProperties.firstOrNull { it.hasAnnotation<UpsertKey>() }
        if (upsertKeyProp != null) {
            upsertKeyProp.isAccessible = true
            val cb = entityManager.criteriaBuilder
            val cq = cb.createQuery(entityType)
            val root = cq.from(entityType)
            val predicates = mutableListOf<Predicate>(
                cb.equal(root.get<Any>(upsertKeyProp.name), upsertKeyProp.getter.call(entity))
            )
            deleteMarkInfo?.let { info ->
                val built = buildAlivePredicateUnified(info, alias = root.alias ?: "e", paramName = "_aliveMark")
                if (built.fragment.isNotBlank()) {
                    val deletePath = root.get<Any>(info.fieldName)
                    val alivePredicate = when (info.aliveMark) {
                        DeleteMarkValue.NULL -> cb.isNull(deletePath)
                        DeleteMarkValue.NOT_NULL -> cb.isNotNull(deletePath)
                        else -> cb.equal(deletePath, info.aliveMarkValue)
                    }
                    predicates += alivePredicate
                }
            }
            cq.where(*predicates.toTypedArray())
            return entityManager.createQuery(cq).resultList.firstOrNull()
        }

        val upsertKeyField = entityType.declaredFields.firstOrNull { it.hasAnnotation<UpsertKey>() } ?: return null
        upsertKeyField.isAccessible = true
        val fieldValue = upsertKeyField.get(entity)
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(entityType)
        val root = cq.from(entityType)
        val predicates = mutableListOf<Predicate>(
            cb.equal(root.get<Any>(upsertKeyField.name), fieldValue)
        )
        deleteMarkInfo?.let { info ->
            val deletePath = root.get<Any>(info.fieldName)
            val alivePredicate = when (info.aliveMark) {
                DeleteMarkValue.NULL -> cb.isNull(deletePath)
                DeleteMarkValue.NOT_NULL -> cb.isNotNull(deletePath)
                else -> cb.equal(deletePath, info.aliveMarkValue)
            }
            predicates += alivePredicate
        }
        cq.where(*predicates.toTypedArray())
        return entityManager.createQuery(cq).resultList.firstOrNull()
    }

    private fun copyAndMerge(entity: E, existing: E): E {
        val changedFields = mutableListOf<String>()
        entity::class.memberProperties.forEach { p ->
            if (p is KMutableProperty1<*, *>) {
                p.isAccessible = true
                when {
                    p.hasAnnotation<CreatedDate>() -> {}
                    p.hasAnnotation<LastModifiedDate>() -> p.setter.call(existing, LocalDateTime.now())
                    else -> {
                        val newValue = p.getter.call(entity)
                        val isSeq = p.hasAnnotation<GenerateSequentialCode>()
                        val assign = if (isSeq) {
                            // Sequential code 필드는 null 입력 시 기존 값 유지
                            newValue != null
                        } else {
                            newValue != null || nullMergePolicy == NullMergePolicy.OVERWRITE
                        }
                        if (assign) {
                            val oldValue = try { p.getter.call(existing) } catch (_: Exception) { null }
                            p.setter.call(existing, newValue)
                            changedFields += "${p.name}:$oldValue -> $newValue".take(256)
                        } else LOG.debug(
                            "[SoftDeleteJpaRepository] Skip field '{}' (value=null, seq={}, policy={})",
                            p.name,
                            isSeq,
                            nullMergePolicy
                        )
                    }
                }
            }
        }
        if (changedFields.isNotEmpty() && LOG.isDebugEnabled) {
            LOG.debug(
                "[SoftDeleteJpaRepository] " +
                        "Changed fields for ${entityType.simpleName}: ${changedFields.joinToString(", ")}"
            )
        }

        applySequentialCodes(existing, newIdentitySet())
        return entityManager.merge(existing)
    }

    // 시퀀스 코드 계산 헬퍼
    private fun computeSequentialCode(holder: Any, ann: GenerateSequentialCode): String {
        val prefix = if (ann.prefixExpression.isNotBlank()) {
            spelExpressionParser.parseExpression(ann.prefixExpression)
                .getValue(StandardEvaluationContext(holder)) as String
        } else ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(holder)

        return (sequenceGenerator.generateKey(prefix, holder) as? String)?.takeIf {
            it.isNotBlank()
        } ?: (prefix + seqCounters.computeIfAbsent(prefix) { AtomicLong(0) }.incrementAndGet())
    }

    // 통합 재귀 순차 코드 적용 (캐싱 + 순환참조 방지)
    private fun applySequentialCodes(entity: Any, visited: MutableSet<Any>) {
        if (!visited.add(entity)) {
            LOG.warn("[SoftDeleteJpaRepository] Circular reference detected: ${entity.javaClass.simpleName}.")
            return
        }

        val kClass = entity::class
        val seqFields = sequentialCodeFieldCache.computeIfAbsent(kClass) {
            val props = kClass.memberProperties
                .filter { it is KMutableProperty1<*, *> && it.hasAnnotation<GenerateSequentialCode>() }
                .map { @Suppress("UNCHECKED_CAST") (it as KMutableProperty1<Any, Any?>) }
            val jFields = entity.javaClass.declaredFields.filter { it.hasDirectAnnotation<GenerateSequentialCode>() }
            seqCacheMissCounter.incrementAndGet()
            LOG.debug(
                "[SoftDeleteJpaRepository] sequentialCodeCache MISS class=${kClass.simpleName} props=${props.size} " +
                        "fields=${jFields.size}"
            )
            SeqFields(props, jFields)
        }
        LOG.debug("[SoftDeleteJpaRepository] sequentialCodeCache HIT class=${kClass.simpleName}")

        seqFields.kotlinProps.forEach { prop ->
            prop.isAccessible = true
            val current = (prop.get(entity) as? String).orEmpty()
            if (current.isBlank()) {
                val ann = prop.getAnnotation<GenerateSequentialCode>() ?: return@forEach
                val finalCode = computeSequentialCode(entity, ann)
                try { prop.setter.call(entity, finalCode) } catch (_: Exception) {}
            }
        }

        seqFields.javaFields.forEach { field ->
            field.isAccessible = true
            val current = (field.get(entity) as? String).orEmpty()
            if (current.isBlank()) {
                val ann = field.getAnnotation<GenerateSequentialCode>() ?: return@forEach
                val finalCode = computeSequentialCode(entity, ann)
                try { field.set(entity, finalCode) } catch (_: Exception) {}
            }
        }

        entity::class.memberProperties.forEach { prop ->
            try {
                prop.isAccessible = true
                when (val value = prop.getter.call(entity)) {
                    is Collection<*> -> value.filterNotNull().forEach { child ->
                        val recurse = child.javaClass.hasAnnotation<Entity>() || isJpaRelation(prop)
                        if (recurse) applySequentialCodes(child, visited)
                    }
                    else -> if (value != null) {
                        val recurse = value.javaClass.hasAnnotation<Entity>() || isJpaRelation(prop)
                        if (recurse) applySequentialCodes(value, visited)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun isJpaRelation(prop: KProperty1<*, *>) = prop.annotations.any {
        it is OneToMany || it is OneToOne || it is ManyToOne || it is ManyToMany
    }

    private fun applyUpdateEntity(entity: E, copyFunc: (E) -> Unit): E {
        val createdProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.hasAnnotation<CreatedDate>()
        }
        val lastModifiedProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.hasAnnotation<LastModifiedDate>()
        }
        @Suppress("UNCHECKED_CAST")
        val deleteProp = deleteMarkInfo?.field as? KMutableProperty1<E, Any?>
        val originalCreated = createdProps.associateWith { it.get(entity) }
        val originalDelete = deleteProp?.get(entity)
        copyFunc(entity)
        originalCreated.forEach { (prop, value) ->
            prop.set(entity, value)
        }
        deleteProp?.set(entity, originalDelete)
        lastModifiedProps.forEach { prop ->
            prop.set(entity, LocalDateTime.now())
        }
        return entity
    }

    @Transactional
    @Modifying
    override fun softDeleteByField(fieldName: String, fieldValue: Any) =
        findAllByField(fieldName, fieldValue).content.sumOf { softDelete(it) }

    @Transactional
    @Modifying
    override fun softDeleteByCondition(condition: String) =
        findAllByCondition(condition.ifEmpty { "" }).content.sumOf { softDelete(it) }

    @Transactional
    @Modifying
    override fun softDeleteByFields(fields: Map<String, Any>) = findAllByFields(fields).content.sumOf {
        softDelete(it)
    }

    @Transactional
    @Modifying
    override fun softDeleteById(id: ID) = findOneById(id).orElse(null)?.let(this::softDelete) ?: 0

    // Alive 존재 확인 구현: 자기호출 회피를 위해 독립 쿼리로 조회(read-only)
    @Transactional(readOnly = true)
    override fun existsAliveById(id: ID): Boolean {
        val idAttrs = entityInformation.idAttributeNames.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Entity ${entityType.simpleName} must have at least one @Id attribute")
        val whereClause = idAttrs.joinToString(" AND ") { attr -> "e.$attr = :$attr" }
        val cnt = executeCount(whereClause) {
            if (idAttrs.size == 1) setParameter(idAttrs.first(), id) else {
                val paramMap = extractIdParamMap(id as Any, idAttrs)
                paramMap.forEach { (name, value) -> setParameter(name, value) }
            }
        }
        return cnt > 0
    }

    @Transactional
    override fun <R> rowLockById(id: ID, block: (E) -> R): R {
        val idAttrs = entityInformation.idAttributeNames.toList()
        val whereClause = when {
            idAttrs.size == 1 -> "e.${idAttrs[0]} = :${idAttrs[0]}"
            else -> idAttrs.joinToString(" AND ") { "e.$it = :$it" }
        }
        val locked = applyPessimisticLock(whereClause) {
            if (idAttrs.size == 1) setParameter(idAttrs[0], id) else {
                val paramMap = extractIdParamMap(id as Any, idAttrs)
                paramMap.forEach { (name, value) ->
                    setParameter(name, value ?: throw IllegalArgumentException("'$name' 값이 없습니다"))
                }
            }
        }.firstOrNull() ?: throw NoSuchElementException("Row lock 대상(${entityType.simpleName})이 존재하지 않습니다: id=$id")
        return block(locked)
    }

    @Transactional
    override fun <R> rowLockByField(fieldName: String, fieldValue: Any, block: (E) -> R): R {
        val locked = applyPessimisticLock("e.$fieldName = :fieldValue") {
            setParameter("fieldValue", fieldValue)
        }.firstOrNull() ?: throw NoSuchElementException(
            "Row lock 대상(${entityType.simpleName})이 존재하지 않습니다: $fieldName=$fieldValue"
        )
        return block(locked)
    }

    @Transactional
    override fun <R> rowLockByFields(fields: Map<String, Any>, block: (E) -> R): R {
        val where = fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" }
        val locked = applyPessimisticLock(where) {
            fields.forEach { (k, v) ->
                setParameter(k, v)
            }
        }.firstOrNull() ?: throw NoSuchElementException(
            "Row lock 대상(${entityType.simpleName})이 존재하지 않습니다: ${fields.entries.joinToString()}"
        )
        return block(locked)
    }

    @Transactional
    override fun <R> rowLockByCondition(condition: String, block: (E) -> R): R {
        val locked = applyPessimisticLock(condition.ifEmpty { "TRUE" }) {
        }.firstOrNull() ?: throw NoSuchElementException(
            "Row lock 대상(${entityType.simpleName})이 존재하지 않습니다: condition='${condition.ifEmpty { "TRUE" }}'"
        )
        return block(locked)
    }

    override fun refresh(entity: E): E {
        entityManager.refresh(entity)
        return entity
    }

    @Transactional
    override fun upsertAll(entities: List<E>): List<E> {
        val result = mutableListOf<E>()
        entities.forEachIndexed { idx, entity ->
            result += upsert(entity)
            if (idx > 0 && idx % flushInterval == 0) {
                entityManager.flush()
                entityManager.clear()
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    override fun upsert(entity: E): E {
        // UpsertKey 기반 기존 엔티티 탐색 -> 머지
        findByUpsertKey(entity)?.let { found ->
            val inputId = entityInformation.getId(entity) as ID?
            val foundId = entityInformation.getId(found) as ID?
            if (inputId != null && foundId != null && inputId != foundId) {
                throw IllegalStateException("업데이트 실패: 입력 엔티티 ID($inputId)와 DB 엔티티 ID($foundId)가 다릅니다.")
            }
            return copyAndMerge(entity, found)
        }
        // ID 기반 기존 엔티티 조회 -> 삭제 상태면 새로 persist (이후 코드 생성), 아니면 머지
        val entityId = entityInformation.getId(entity) as ID?
        if (entityId != null && existsById(entityId)) {
            val existing = findById(entityId).orElse(null)
            deleteMarkInfo?.let { info ->
                val curr = info.field?.getter?.call(existing)
                val isDeleted = when (info.deleteMark) {
                    DeleteMarkValue.NULL -> curr == null
                    DeleteMarkValue.NOT_NULL -> curr != null
                    else -> curr == info.deleteMarkValue
                }
                if (isDeleted) {
                    // prefixExpression에서 id == null 분기 처리를 위해 persist 이전에 코드 생성 수행
                    applySequentialCodes(entity as Any, newIdentitySet())
                    entityManager.persist(entity)
                    entityManager.flush()
                    return entity
                }
            }
            existing?.let { return copyAndMerge(entity, it) }
        }
        // 완전히 신규 엔티티 -> persist 전에 코드 생성
        applySequentialCodes(entity as Any, newIdentitySet())
        entityManager.persist(entity)
        entityManager.flush()
        return entity
    }

    @Transactional
    override fun updateById(id: ID, copyFunc: (E) -> Unit): E {
        val existing = findById(id).orElseThrow { NoSuchElementException("Entity with ID $id not found") }
        applyUpdateEntity(existing, copyFunc)
        return entityManager.merge(existing)
    }

    @Transactional
    override fun updateByField(fieldName: String, fieldValue: Any, copyFunc: (E) -> Unit) =
        findAllByField(fieldName, fieldValue).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }

    @Transactional
    override fun updateByFields(fields: Map<String, Any>, copyFunc: (E) -> Unit) =
        findAllByFields(fields).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }

    @Transactional
    override fun updateByCondition(condition: String, copyFunc: (E) -> Unit) =
        findAllByCondition(condition.ifEmpty { "" }).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }

    private fun <R> prepareQuery(
        selectClause: String, whereClause: String, setParams: Query.() -> Unit, resultClass: Class<R>
    ): TypedQuery<R> {
        val baseCondition = whereClause.ifBlank { "TRUE" }
        val (aliveFragment, aliveParams) = if (usePredicateBuilder) {
            val built = AlivePredicateBuilder.build(deleteMarkInfo, alias = "e", paramName = "aliveMarkValue")
            if (built.params.isNotEmpty()) LOG.debug("[SoftDeleteJpaRepository] alive params: {}", built.params.keys)
            built.fragment to built.params
        } else {
            val unified = buildAlivePredicateUnified(deleteMarkInfo)
            unified.fragment to unified.params
        }
        val finalCondition = "WHERE $baseCondition$aliveFragment".trim()
        logDuplicateAliveFilterIfNeeded(entityType, deleteMarkInfo)
        return entityManager.createQuery(
            "$selectClause FROM $entityName e $finalCondition", resultClass
        ).apply {
            setParams()
            aliveParams.forEach { (k, v) ->
                setParameter(k, v)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeFind(whereClause: String, setParams: Query.() -> Unit, pageable: Pageable?): Page<E> {
        val query = prepareQuery("SELECT e", whereClause, setParams, entityType)
        if (pageable == null || pageable.isUnpaged) {
            val results = query.resultList as List<E>
            return PageImpl(results, Pageable.unpaged(pageable?.sort ?: Sort.unsorted()), results.size.toLong())
        }
        val total = executeCount(whereClause, setParams)
        val retPageable = PageRequest.of(pageable.pageNumber.coerceAtLeast(0), pageable.pageSize, pageable.sort)
        if (total == 0L) return PageImpl(emptyList(), retPageable, 0)
        query.firstResult = pageable.offset.toInt().coerceAtLeast(0)
        query.maxResults = pageable.pageSize
        return PageImpl(query.resultList as List<E>, retPageable, total)
    }

    private fun executeCount(whereClause: String, setParams: Query.() -> Unit) =
        prepareQuery("SELECT COUNT(e)", whereClause, setParams, Long::class.java).singleResult as Long

    private fun applyPessimisticLock(whereClause: String, setParams: Query.() -> Unit): List<E> =
        prepareQuery("SELECT e", whereClause, setParams, entityType).run {
            lockMode = LockModeType.PESSIMISTIC_WRITE
            // 드라이버에 락 대기 시간 힌트 제공 (기본 2000ms). 일부 DB/H2 환경에서 대기 측정을 안정화
            try {
                setHint("javax.persistence.lock.timeout", 2000)
            }
            catch (_: Exception) { }
            resultList
        }

    @Transactional(readOnly = true)
    override fun findAllByField(fieldName: String, fieldValue: Any, pageable: Pageable?): Page<E> {
        validateFieldNames(listOf(fieldName))
        return executeFind("e.$fieldName = :fieldValue", { setParameter("fieldValue", fieldValue) }, pageable)
    }

    @Transactional(readOnly = true)
    override fun findAllByFields(fields: Map<String, Any>, pageable: Pageable?): Page<E> {
        validateFieldNames(fields.keys)
        return executeFind(
            fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" },
            { fields.forEach { (k, v) -> setParameter(k, v) } },
            pageable
        )
    }

    @Transactional(readOnly = true)
    override fun findAllByCondition(condition: String, pageable: Pageable?) =
        executeFind(if (isNotEmpty(condition)) condition else "TRUE", { }, pageable)

    @Transactional(readOnly = true)
    override fun findOneById(id: ID): Optional<E> {
        val idAttrs = entityInformation.idAttributeNames.takeIf {
            it.isNotEmpty()
        } ?: throw IllegalStateException("Entity ${entityType.simpleName} must have at least one @Id attribute")
        val whereClause = idAttrs.joinToString(" AND ") { attr -> "e.$attr = :$attr" }
        return executeFindFirst(whereClause) {
            if (idAttrs.size == 1) setParameter(idAttrs.first(), id) else {
                val paramMap = extractIdParamMap(id as Any, idAttrs)
                paramMap.forEach { (name, value) -> setParameter(name, value) }
            }
        }
    }

    private fun executeFindFirst(whereClause: String, setParams: Query.() -> Unit): Optional<E> {
        val baseCondition = whereClause.ifBlank { "TRUE" }
        val (aliveFragment, aliveParams) = if (usePredicateBuilder) {
            val built = AlivePredicateBuilder.build(deleteMarkInfo, alias = "e", paramName = "aliveMarkValue")
            built.fragment to built.params
        } else {
            val unified = buildAlivePredicateUnified(deleteMarkInfo)
            unified.fragment to unified.params
        }
        logDuplicateAliveFilterIfNeeded(entityType, deleteMarkInfo)
        val query = entityManager.createQuery(
            "SELECT e FROM $entityName e WHERE $baseCondition$aliveFragment",
            entityType
        ).apply {
            setParams()
            aliveParams.forEach { (k, v) ->
                setParameter(k, v)
            }
            maxResults = 1
        }
        return Optional.ofNullable(query.resultList.firstOrNull())
    }

    @Transactional(readOnly = true)
    override fun findFirstByField(fieldName: String, fieldValue: Any): Optional<E> =
        executeFindFirst("e.$fieldName = :fieldValue") { setParameter("fieldValue", fieldValue) }

    @Transactional(readOnly = true)
    override fun findFirstByFields(fields: Map<String, Any>) =
        executeFindFirst(fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" }) {
            fields.forEach { (k, v) ->
                setParameter(k, v)
            }
        }

    @Transactional(readOnly = true)
    override fun findFirstByCondition(condition: String) = executeFindFirst(condition.ifEmpty { "TRUE" }) { }

    @Transactional(readOnly = true)
    override fun countByCondition(condition: String) = executeCount(condition.ifEmpty { "TRUE" }) { }

    @Transactional(readOnly = true)
    override fun countByField(fieldName: String, fieldValue: Any) =
        executeCount("e.$fieldName = :fieldValue") { setParameter("fieldValue", fieldValue) }

    @Transactional(readOnly = true)
    override fun countByFields(fields: Map<String, Any>) =
        executeCount(fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" }) {
            fields.forEach { (k, v) -> setParameter(k, v) }
        }

    @Transactional(readOnly = true)
    override fun count(): Long = executeCount("TRUE") { }

    @Transactional
    @Modifying
    override fun softDelete(entity: E): Int {
        deleteDepthTL.set(deleteDepthTL.get() + 1)
        try {
            warnDuplicateAliveFilterIfNeeded()
            if (deleteStrategy == DeleteStrategy.BULK) softDeleteChildrenBulk(entity)
            else processChildrenRecursive(entity)

            applyParentDeleteMark(entity)
            val affected = executeDeleteOrSoftMark(entity)
            if (deleteDepthTL.get() == 1) entityManager.clear()
            return affected
        } finally {
            deleteDepthTL.set(deleteDepthTL.get() - 1)
            if (deleteDepthTL.get() <= 0) deleteDepthTL.remove()
        }
    }
    // 중복 alive 필터 경고
    private fun warnDuplicateAliveFilterIfNeeded() {
        if (deleteMarkInfo != null && !duplicateAliveFilterWarned.get()) {
            if ((entityType.hasAnnotation<SQLRestriction>()) &&
                duplicateAliveFilterWarned.compareAndSet(false, true)) {
                LOG.warn(
                    "[SoftDeleteJpaRepository] 엔티티 '${entityType.simpleName}'에 @SQLRestriction과 " +
                            "deleteMarkInfo가 함께 적용되어 있어, 소프트 삭제 이후 조회 시 중복 필터링으로 문제가 발생할 수 있습니다. " +
                            "한쪽을 제거하거나 향후 벌크 전략 사용을 고려하세요."
                )
            }
        }
    }

    private fun softDeleteChildrenBulk(entity: E) {
        entity::class.memberProperties.filter { it.hasAnnotation<OneToMany>() }.forEach { p ->
            p.isAccessible = true
            val ann = p.getAnnotation<OneToMany>() ?: return@forEach
            val mappedBy = ann.mappedBy
            if (mappedBy.isBlank()) return@forEach
            val childKClass = p.returnType.arguments.firstOrNull()?.type?.classifier as? KClass<*>
            val childJava = childKClass?.java ?: return@forEach
            val childEntityName = try {
                entityManager.metamodel.entity(childJava).name
            } catch (_: Exception) { childJava.simpleName }
            val childInfo = childKClass.deleteMarkInfo
            logDuplicateAliveFilterIfNeeded(childJava, childInfo)
            val aliveUnified = buildAlivePredicateUnified(childInfo, alias = "c", paramName = "_aliveChildMark")
            val aliveFragment = aliveUnified.fragment
            @Suppress("UNCHECKED_CAST")
            val parentIdVal = try { entityInformation.getId(entity) as Any } catch (_: Exception) { null }
            val idAttrs = entityInformation.idAttributeNames
            val canSingle = parentIdVal != null && idAttrs.size == 1
            val canComposite = parentIdVal != null && idAttrs.size > 1
            val selectJpql = when {
                canSingle ->
                    "SELECT c FROM $childEntityName c WHERE c.$mappedBy.${idAttrs.first()} = :_parentId$aliveFragment"
                canComposite -> {
                    val comp = idAttrs.joinToString(" AND ") { pk ->
                        "c.$mappedBy.$pk = :_parent_${pk}"
                    }
                    "SELECT c FROM $childEntityName c WHERE $comp$aliveFragment"
                }
                else -> "SELECT c FROM $childEntityName c WHERE c.$mappedBy = :_parent$aliveFragment"
            }
            val q = entityManager.createQuery(selectJpql, childJava).apply {
                when {
                    canSingle -> setParameter("_parentId", parentIdVal)
                    canComposite -> {
                        val paramMap = extractIdParamMap(parentIdVal, idAttrs)
                        paramMap.forEach { (k, v) ->
                            setParameter("_parent_$k", v ?: throw IllegalArgumentException("'_parent_$k' 값이 없습니다."))
                        }
                    }
                    else -> setParameter("_parent", entity)
                }
                aliveUnified.params.forEach { (k, v) ->
                    setParameter(k, v)
                }
            }
            val children = q.resultList
            if (children.isEmpty()) return@forEach
            val idFields = childJava.declaredFields.filter { it.hasDirectAnnotation<Id>() }
            val markValue = childInfo?.let { computeDeleteMarkValue(it) }
            if (idFields.isEmpty()) {
                children.forEach { c -> registry.getRepositoryFor(c)?.softDelete(c) }
                return@forEach
            }
            if (idFields.size == 1) {
                val idName = idFields.first().name
                val ids = children.mapNotNull { c ->
                    try {
                        childJava.getDeclaredField(idName).apply { isAccessible = true }.get(c)
                    } catch (_: Exception) { null }
                }
                if (ids.isNotEmpty()) {
                    val jpql = if (childInfo != null) {
                        "UPDATE $childEntityName c SET c.${childInfo.fieldName} = :_deleteMark WHERE c.$idName IN :_ids"
                    } else "DELETE FROM $childEntityName c WHERE c.$idName IN :_ids"
                    entityManager.createQuery(jpql).apply {
                        if (childInfo != null) setParameter("_deleteMark", markValue)
                        setParameter("_ids", ids)
                    }.executeUpdate()
                }
            } else {
                val whereParts = mutableListOf<String>()
                val params = mutableMapOf<String, Any?>()
                children.forEachIndexed { idx, c ->
                    val conditions = mutableListOf<String>()
                    idFields.forEach { f ->
                        f.isAccessible = true
                        val v = try {
                            f.get(c)
                        } catch (_: Exception) { null }
                        if (v != null) {
                            val pname = "_pk_${f.name}_$idx"
                            conditions += "c.${f.name} = :$pname"
                            params[pname] = v
                        }
                    }
                    if (conditions.isNotEmpty()) whereParts += "(" + conditions.joinToString(" AND ") + ")"
                }
                if (whereParts.isNotEmpty()) {
                    val combined = whereParts.joinToString(" OR ")
                    val jpql = if (childInfo != null) {
                        "UPDATE $childEntityName c SET c.${childInfo.fieldName} = :_deleteMark WHERE $combined"
                    } else "DELETE FROM $childEntityName c WHERE $combined"
                    entityManager.createQuery(jpql).apply {
                        if (childInfo != null) setParameter("_deleteMark", markValue)
                        params.forEach { (k, v) -> setParameter(k, v) }
                    }.executeUpdate()
                }
            }
            // 손자 재귀 처리
            children.forEach { gc ->
                registry.getRepositoryFor(gc)?.softDelete(gc)
            }
        }
    }
    private fun processChildrenRecursive(entity: E) {
        entity::class.memberProperties.filter { it.hasAnnotation<OneToMany>() }.forEach { p ->
            p.isAccessible = true
            val ann = p.getAnnotation<OneToMany>() ?: return@forEach
            val mappedBy = ann.mappedBy
            if (mappedBy.isBlank()) return@forEach
            val childKClass = p.returnType.arguments.firstOrNull()?.type?.classifier as? KClass<*>
            val childJava = childKClass?.java ?: return@forEach
            val childName = try {
                entityManager.metamodel.entity(childJava).name
            } catch (_: Exception) { childJava.simpleName }
            val childInfo = childKClass.deleteMarkInfo
            val aliveFrag = childInfo?.let { info ->
                when (info.aliveMark) {
                    DeleteMarkValue.NULL -> " AND c.${info.fieldName} IS NULL"
                    DeleteMarkValue.NOT_NULL -> " AND c.${info.fieldName} IS NOT NULL"
                    else -> " AND c.${info.fieldName} = :_aliveChildMark"
                }
            } ?: ""
            @Suppress("UNCHECKED_CAST")
            val parentIdVal = try {
                entityInformation.getId(entity) as Any
            } catch (_: Exception) { null }
            val idAttrs = entityInformation.idAttributeNames
            val parentIdAttr = idAttrs.firstOrNull()
            val canSingle = parentIdVal != null && idAttrs.size == 1 && parentIdAttr != null
            val canComposite = parentIdVal != null && idAttrs.size > 1
            val selectJpql = when {
                canSingle -> "SELECT c FROM $childName c WHERE c.$mappedBy.$parentIdAttr = :_parentId$aliveFrag"
                canComposite -> {
                    val comp = idAttrs.joinToString(" AND ") { pk ->
                        "c.$mappedBy.$pk = :_parent_${pk}"
                    }
                    "SELECT c FROM $childName c WHERE $comp$aliveFrag"
                }
                else -> "SELECT c FROM $childName c WHERE c.$mappedBy = :_parent$aliveFrag"
            }
            entityManager.createQuery(selectJpql, childJava).apply {
                when {
                    canSingle -> setParameter("_parentId", parentIdVal)
                    canComposite -> {
                        extractIdParamMap(parentIdVal, idAttrs).forEach { (k, v) ->
                            setParameter("_parent_$k", v ?: throw IllegalArgumentException("'_parent_$k' 값이 없습니다."))
                        }
                    }
                    else -> setParameter("_parent", entity)
                }
                if (childInfo != null &&
                    childInfo.aliveMark !in listOf(DeleteMarkValue.NULL, DeleteMarkValue.NOT_NULL)) {
                    setParameter("_aliveChildMark", childInfo.aliveMarkValue)
                }
            }.resultList.forEach { c ->
                registry.getRepositoryFor(c)?.softDelete(c)
            }
        }
    }
    private fun applyParentDeleteMark(entity: E) {
        deleteMarkInfo?.let { info ->
            val f = info.field
            if (f is KMutableProperty1<*, *>) {
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f as KMutableProperty1<E, Any?>).set(entity, computeDeleteMarkValue(info))
            }
        }
    }

    private fun validateFieldNames(fields: Collection<String>) {
        if (fields.isEmpty()) return
        val invalid = fields.filterNot { allowedFieldNames.contains(it) }
        if (invalid.isEmpty()) return
        if (strictQueryValidation) {
            throw IllegalArgumentException(
                "Invalid field name(s) for ${entityType.simpleName}: ${invalid.joinToString()} " +
                        "(allowed: ${allowedFieldNames.joinToString()})"
            )
        } else {
            LOG.warn(
                "[SoftDeleteJpaRepository] Invalid field name(s) ignored for ${entityType.simpleName}: " +
                        "${invalid.joinToString()} (allowed: ${allowedFieldNames.joinToString()})"
            )
        }
    }

    private fun logDuplicateAliveFilterIfNeeded(entityType: Class<*>, info: DeleteMarkInfo?) {
        if (info == null) return
        if (!duplicateAliveFilterWarned.get() &&
            (entityType.hasAnnotation<SQLRestriction>()) &&
            duplicateAliveFilterWarned.compareAndSet(false, true)) {
            LOG.warn(
                "[SoftDeleteJpaRepository] Entity '${entityType.simpleName}' uses @SQLRestriction along with " +
                        "deleteMarkInfo. Duplicate alive filtering may occur."
            )
        }
    }

    private fun extractIdParamMap(id: Any, idAttrs: Collection<String>): Map<String, Any?> =
        if (id is Map<*, *>) {
            id.entries.associate { (k, v) ->
                require(k is String) { "Compound ID map key must be String but was ${k?.javaClass?.name}" }
                require(k in idAttrs) { "Unexpected compound id key: $k" }
                k to v
            }
        } else idAttrs.associateWith { attr ->
            val prop = id::class.memberProperties.firstOrNull { it.name == attr }
                ?: throw IllegalArgumentException("Class ${id::class.simpleName} has no property '$attr'")
            prop.isAccessible = true
            prop.getter.call(id)
        }

    private fun executeDeleteOrSoftMark(entity: E): Int {
        @Suppress("UNCHECKED_CAST")
        val idVal = entityInformation.getId(entity) as ID
        val idAttrs = entityInformation.idAttributeNames.also {
            if (it.isEmpty()) throw IllegalStateException("ID 필드가 없습니다.")
        }
        val plan = buildNativeDeletePlan(idVal as Any, deleteMarkInfo, idAttrs)
        val query = entityManager.createNativeQuery(plan.sql).apply {
            plan.markParams.forEach { (k, v) -> setParameter(k, v) }
            plan.idParams.forEach { (k, v) ->
                setParameter(k, v ?: throw IllegalArgumentException("'$k' 값이 없습니다."))
            }
        }
        val affected = query.executeUpdate()
        entityManager.flush()
        return affected
    }

    private data class NativeDeletePlan(
        val sql: String,
        val markParams: Map<String, Any?>,
        val idParams: Map<String, Any?>
    )

    private fun buildIdParamMap(idVal: Any, idAttrs: Collection<String>): Map<String, Any?> {
        return if (idAttrs.size == 1) {
            mapOf(idAttrs.first() to idVal)
        } else {
            extractIdParamMap(idVal, idAttrs)
        }
    }

    private fun buildNativeDeletePlan(
        idVal: Any,
        info: DeleteMarkInfo?,
        idAttrs: Collection<String>
    ): NativeDeletePlan {
        val condition = idAttrs.joinToString(" AND ") { "`${it}` = :$it" }
        if (info == null)
            return NativeDeletePlan(
                "DELETE FROM $tableName WHERE $condition",
                emptyMap(),
                buildIdParamMap(idVal, idAttrs)
            )
        val quotedCol = "`${info.dbColumnName}`"
        val (setClause, markParams) = if (usePredicateBuilder) {
            val built = DeletePredicateBuilder.buildSetClause(info, quotedCol, "deleteMark")
            built.fragment to built.params
        } else {
            val valuePart = if (info.deleteMark == DeleteMarkValue.NULL) "NULL" else ":deleteMark"
            val params = if (info.deleteMark == DeleteMarkValue.NULL) emptyMap() else {
                val value = if (info.deleteMark == DeleteMarkValue.NOT_NULL)
                    DeleteMarkValue.getDefaultDeleteMarkValue(info)
                else info.deleteMarkValue
                mapOf("deleteMark" to value)
            }
            "$quotedCol = $valuePart" to params
        }
        val sql = "UPDATE $tableName SET $setClause$autoModify WHERE $condition"
        return NativeDeletePlan(sql, markParams, buildIdParamMap(idVal, idAttrs))
    }
}
