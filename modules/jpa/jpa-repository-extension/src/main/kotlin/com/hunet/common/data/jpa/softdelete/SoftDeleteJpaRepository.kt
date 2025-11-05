package com.hunet.common.data.jpa.softdelete

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.sequence.spelExpressionParser
import com.hunet.common.data.jpa.softdelete.annotation.deleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import com.hunet.common.logging.commonLogger
import com.hunet.common.util.annotatedFields
import com.hunet.common.util.getAnnotation
import com.hunet.common.util.isNotEmpty
import jakarta.persistence.*
import jakarta.persistence.criteria.Predicate
import org.hibernate.annotations.SQLRestriction
import org.hibernate.annotations.Where
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) // FIELD 추가하여 Java 필드 사용 가능
@Retention(AnnotationRetention.RUNTIME)
annotation class UpsertKey

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
    fun <R> rowLockById(id: ID, block: () -> R): R
    fun <R> rowLockByField(fieldName: String, fieldValue: Any, block: () -> R): R
    fun <R> rowLockByFields(fields: Map<String, Any>, block: () -> R): R
    fun <R> rowLockByCondition(condition: String, block: () -> R): R
    fun getEntityClass(): Class<E>
}

@NoRepositoryBean
class SoftDeleteJpaRepositoryImpl<E: Any, ID: Serializable>(
    val entityInformation: JpaEntityInformation<E, *>,
    val entityManager: EntityManager
) : SimpleJpaRepository<E, ID>(entityInformation, entityManager), SoftDeleteJpaRepository<E, ID> {
    private val registry: SoftDeleteRepositoryRegistry by lazy { SpringContextHolder.getBean() }
    private val sequenceGenerator: SequenceGenerator by lazy { SpringContextHolder.getBean() }
    val entityType by lazy { entityInformation.javaType }
    val entityName by lazy { entityManager.metamodel.entity(entityType).name }
    val tableName by lazy {
        entityInformation.javaType.getAnnotation(Table::class.java)?.name ?: throw IllegalStateException(
            "Entity ${entityInformation.javaType.simpleName} must have a @Table annotation with a name"
        )
    }
    private val autoModify: String by lazy {
        entityType.kotlin.annotatedFields<LastModifiedDate>().mapNotNull { it.getAnnotation<Column>()?.name }
            .filter { it.isNotEmpty() }.joinToString(separator = "") { name -> ", `$name` = NOW()" }
    }
    private val deleteMarkInfo: DeleteMarkInfo? = entityType.kotlin.deleteMarkInfo
    private val flushInterval: Int by lazy {
        SpringContextHolder.getProperty("softdelete.upsert-all.flush-interval", 50)
    }
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private val deleteDepthTL = ThreadLocal.withInitial { 0 }
    private val duplicateAliveFilterWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        val LOG by commonLogger()
    }

    override fun getEntityClass(): Class<E> = entityType

    private fun findByUpsertKey(entity: E): E? {
        val upsertKeyProp = entityType.kotlin.memberProperties.firstOrNull {
            it.getAnnotation<UpsertKey>() != null
        }
        if (upsertKeyProp != null) {
            upsertKeyProp.isAccessible = true
            val cb = entityManager.criteriaBuilder
            val cq = cb.createQuery(entityType)
            val root = cq.from(entityType)
            val predicates = mutableListOf<Predicate>(
                cb.equal(root.get<Any>(upsertKeyProp.name), upsertKeyProp.getter.call(entity))
            )
            deleteMarkInfo?.let {
                val deletePath = root.get<Any>(it.fieldName)
                val alivePredicate = when (it.aliveMark) {
                    DeleteMarkValue.NULL -> cb.isNull(deletePath)
                    DeleteMarkValue.NOT_NULL -> cb.isNotNull(deletePath)
                    else -> cb.equal(deletePath, it.aliveMarkValue)
                }
                predicates += alivePredicate
            }
            cq.where(*predicates.toTypedArray())
            return entityManager.createQuery(cq).resultList.firstOrNull()
        }

        val upsertKeyField = entityType.declaredFields.firstOrNull {
            it.isAnnotationPresent(UpsertKey::class.java)
        } ?: return null
        upsertKeyField.isAccessible = true
        val fieldValue = upsertKeyField.get(entity)
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(entityType)
        val root = cq.from(entityType)
        val predicates = mutableListOf<Predicate>(
            cb.equal(root.get<Any>(upsertKeyField.name), fieldValue)
        )
        deleteMarkInfo?.let {
            val deletePath = root.get<Any>(it.fieldName)
            val alivePredicate = when (it.aliveMark) {
                DeleteMarkValue.NULL -> cb.isNull(deletePath)
                DeleteMarkValue.NOT_NULL -> cb.isNotNull(deletePath)
                else -> cb.equal(deletePath, it.aliveMarkValue)
            }
            predicates += alivePredicate
        }
        cq.where(*predicates.toTypedArray())
        return entityManager.createQuery(cq).resultList.firstOrNull()
    }

    private fun copyAndMerge(entity: E, existing: E): E {
        entity::class.memberProperties.forEach { prop ->
            if (prop is KMutableProperty1<*, *>) {
                prop.isAccessible = true
                when {
                    prop.getAnnotation<CreatedDate>() != null -> {}
                    prop.getAnnotation<LastModifiedDate>() != null -> prop.setter.call(existing, LocalDateTime.now())
                    else -> {
                        val newValue = prop.getter.call(entity)
                        // GenerateSequentialCode 필드가 null로 설정된 경우도 복사하여 재생성 트리거
                        if (prop.getAnnotation<GenerateSequentialCode>() != null || newValue != null) {
                            prop.setter.call(existing, newValue)
                        }
                    }
                }
            }
        }

        generateSequentialCodesRecursively(existing)

        val merged = entityManager.merge(existing)
        return merged
    }

    private fun generateSequentialCodesRecursively(entity: Any) {
        generateSequentialCodesForEntity(entity)

        entity::class.memberProperties.forEach { prop ->
            if (prop is KMutableProperty1<*, *>) {
                prop.isAccessible = true
                try {
                    @Suppress("UNCHECKED_CAST")
                    val value = (prop as KMutableProperty1<Any, Any?>).get(entity)
                    when (value) {
                        is Collection<*> -> {
                            value.filterNotNull().forEach { child ->
                                if (hasJpaRelationAnnotation(prop)) {
                                    generateSequentialCodesRecursively(child)
                                }
                            }
                        }
                        else -> {
                            if (value != null && hasJpaRelationAnnotation(prop)) {
                                generateSequentialCodesRecursively(value)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 접근 불가능한 프로퍼티는 무시
                }
            }
        }
    }

    private fun hasJpaRelationAnnotation(prop: KMutableProperty1<*, *>): Boolean {
        return prop.annotations.any {
            it is OneToMany || it is OneToOne || it is ManyToOne || it is ManyToMany
        }
    }

    private fun generateSequentialCodesForEntity(entity: Any) {
        entity::class.memberProperties
            .filterIsInstance<KMutableProperty1<Any, Any?>>()
            .filter { it.getAnnotation<GenerateSequentialCode>() != null }
            .forEach { prop ->
                prop.isAccessible = true
                val current = (prop.get(entity) as? String).orEmpty()
                if (current.isBlank()) {
                    val ann = prop.getAnnotation<GenerateSequentialCode>()!!
                    val prefix = if (ann.prefixExpression.isNotBlank()) {
                        spelExpressionParser.parseExpression(ann.prefixExpression)
                            .getValue(StandardEvaluationContext(entity)) as String
                    } else ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                    val genCandidate = sequenceGenerator.generateKey(prefix, entity) as? String
                    val finalCode = genCandidate?.takeIf {
                        it.isNotBlank()
                    } ?: (prefix + seqCounters.computeIfAbsent(prefix) { AtomicLong(0) }.incrementAndGet())
                    try { prop.setter.call(entity, finalCode) } catch (_: Exception) {}
                }
            }

        entity.javaClass.declaredFields
            .filter { it.getAnnotation(GenerateSequentialCode::class.java) != null }
            .forEach { field ->
                field.isAccessible = true
                val current = (field.get(entity) as? String).orEmpty()
                if (current.isBlank()) {
                    val ann = field.getAnnotation(GenerateSequentialCode::class.java)
                    val prefix = if (ann.prefixExpression.isNotBlank()) {
                        spelExpressionParser.parseExpression(ann.prefixExpression)
                            .getValue(StandardEvaluationContext(entity)) as String
                    } else ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                    val genCandidate = sequenceGenerator.generateKey(prefix, entity) as? String
                    val finalCode = genCandidate?.takeIf {
                        it.isNotBlank()
                    } ?: (prefix + seqCounters.computeIfAbsent(prefix) { AtomicLong(0) }.incrementAndGet())
                    field.set(entity, finalCode)
                }
            }
    }

    private fun applyUpdateEntity(entity: E, copyFunc: (E) -> Unit): E {
        val createdProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.getAnnotation<CreatedDate>() != null
        }
        val lastModifiedProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.getAnnotation<LastModifiedDate>() != null
        }
        @Suppress("UNCHECKED_CAST")
        val deleteProp = deleteMarkInfo?.field as? KMutableProperty1<E, Any?>
        val originalCreated = createdProps.associateWith { it.get(entity) }
        val originalDelete = deleteProp?.get(entity)
        copyFunc(entity)
        originalCreated.forEach { (prop, value) -> prop.set(entity, value) }
        deleteProp?.set(entity, originalDelete)
        lastModifiedProps.forEach { prop -> prop.set(entity, LocalDateTime.now()) }
        return entity
    }

    private fun generateSequentialCodes(entity: E) {
        generateSequentialCodesForEntity(entity)
    }

    @Transactional
    override fun upsertAll(entities: List<E>): List<E> {
        val result = mutableListOf<E>()
        entities.forEachIndexed { idx, entity ->
            result += upsert(entity)
            if (idx > 0 && idx % flushInterval == 0) { entityManager.flush(); entityManager.clear() }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    override fun upsert(entity: E): E {
        generateSequentialCodes(entity)
        findByUpsertKey(entity)?.let { found ->
            val inputId = entityInformation.getId(entity) as ID?
            val foundId = entityInformation.getId(found) as ID?
            if (inputId != null && foundId != null && inputId != foundId) {
                throw IllegalStateException("업데이트 실패: 입력 엔티티 ID($inputId)와 DB 엔티티 ID($foundId)가 다릅니다.")
            }
            val merged = copyAndMerge(entity, found)
            generateSequentialCodes(merged)
            return merged
        }
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
                    entityManager.persist(entity)
                    generateSequentialCodes(entity)
                    return entity
                }
            }
            existing?.let {
                val merged = copyAndMerge(entity, it)
                generateSequentialCodes(merged)
                return merged
            }
        }
        generateSequentialCodes(entity)
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
    override fun updateByField(fieldName: String, fieldValue: Any, copyFunc: (E) -> Unit): List<E> =
        findAllByField(fieldName, fieldValue).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }

    @Transactional
    override fun updateByFields(fields: Map<String, Any>, copyFunc: (E) -> Unit): List<E> =
        findAllByFields(fields).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }

    @Transactional
    override fun updateByCondition(condition: String, copyFunc: (E) -> Unit): List<E> {
        val cond = if (condition.isNotEmpty()) condition else ""
        return findAllByCondition(cond).content.map { entity ->
            applyUpdateEntity(entity, copyFunc)
            entityManager.merge(entity)
        }
    }

    private inline fun <reified R> prepareQuery(
        selectClause: String, whereClause: String, noinline setParams: Query.() -> Unit
    ): TypedQuery<R> = prepareQuery(selectClause, whereClause, setParams, R::class.java)

    private fun <R> prepareQuery(
        selectClause: String, whereClause: String, setParams: Query.() -> Unit, resultClass: Class<R>
    ): TypedQuery<R> {
        val condition = if (whereClause.isNotEmpty()) "WHERE $whereClause" else ""
        val deleteClause = deleteMarkInfo?.let {
            when (it.aliveMark) {
                DeleteMarkValue.NULL -> " AND e.${it.fieldName} IS NULL"
                DeleteMarkValue.NOT_NULL -> " AND e.${it.fieldName} IS NOT NULL"
                else -> " AND e.${it.fieldName} = :aliveMarkValue"
            }
        } ?: ""

        return entityManager.createQuery(
            "$selectClause FROM $entityName e $condition$deleteClause", resultClass
        ).apply {
            setParams()
            deleteMarkInfo?.takeIf {
                it.aliveMark != DeleteMarkValue.NULL && it.aliveMark != DeleteMarkValue.NOT_NULL
            }?.let {
                setParameter("aliveMarkValue", it.aliveMarkValue)
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
            setLockMode(LockModeType.PESSIMISTIC_WRITE)
            resultList
        }

    @Transactional(readOnly = true)
    override fun findAllByField(fieldName: String, fieldValue: Any, pageable: Pageable?): Page<E> =
        executeFind("e.$fieldName = :fieldValue", { setParameter("fieldValue", fieldValue) }, pageable)

    @Transactional(readOnly = true)
    override fun findAllByFields(fields: Map<String, Any>, pageable: Pageable?): Page<E> =
        executeFind(
            fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" },
            { fields.forEach { (k, v) -> setParameter(k, v) } },
            pageable
        )

    @Transactional(readOnly = true)
    override fun findAllByCondition(condition: String, pageable: Pageable?): Page<E> =
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
        val fullCondition = (if (whereClause.isNotEmpty()) whereClause else "TRUE") + (deleteMarkInfo?.let {
            when (it.aliveMark) {
                DeleteMarkValue.NULL -> " AND (e.${it.fieldName} IS NULL)"
                DeleteMarkValue.NOT_NULL -> " AND (e.${it.fieldName} IS NOT NULL)"
                else -> " AND (e.${it.fieldName} = :aliveMarkValue)"
            }
        } ?: "")
        val query = entityManager.createQuery("SELECT e FROM $entityName e WHERE $fullCondition", entityType).apply {
            setParams()
            deleteMarkInfo?.takeIf {
                it.aliveMark != DeleteMarkValue.NULL && it.aliveMark != DeleteMarkValue.NOT_NULL
            }?.let {
                setParameter("aliveMarkValue", it.aliveMarkValue)
            }
            maxResults = 1
        }
        return Optional.ofNullable(query.resultList.firstOrNull())
    }

    @Transactional(readOnly = true)
    override fun findFirstByField(fieldName: String, fieldValue: Any): Optional<E> =
        executeFindFirst("e.$fieldName = :fieldValue") { setParameter("fieldValue", fieldValue) }

    @Transactional(readOnly = true)
    override fun findFirstByFields(fields: Map<String, Any>): Optional<E> {
        val whereClause = fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" }
        return executeFindFirst(whereClause) { fields.forEach { (k, v) -> setParameter(k, v) } }
    }

    @Transactional(readOnly = true)
    override fun findFirstByCondition(condition: String): Optional<E> =
        executeFindFirst(condition.ifEmpty { "TRUE" }) { }

    @Transactional(readOnly = true)
    override fun countByCondition(condition: String): Long =
        executeCount(if (condition.isNotEmpty()) condition else "TRUE", { })

    @Transactional(readOnly = true)
    override fun countByField(fieldName: String, fieldValue: Any): Long =
        executeCount("e.$fieldName = :fieldValue", { setParameter("fieldValue", fieldValue) })

    @Transactional(readOnly = true)
    override fun countByFields(fields: Map<String, Any>): Long = executeCount(
        fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" },
        { fields.forEach { (k, v) -> setParameter(k, v) } }
    )

    @Transactional(readOnly = true)
    override fun count(): Long = executeCount("TRUE") { }

    @Transactional
    @Modifying
    override fun softDelete(entity: E): Int {
        deleteDepthTL.set(deleteDepthTL.get() + 1)
        try {
            // 중복 alive 필터(@Where/@SQLRestriction + deleteMarkInfo) 경고
            if (deleteMarkInfo != null && !duplicateAliveFilterWarned.get()) {
                val hasWhere = entityType.getAnnotation(Where::class.java) != null
                // SQLRestriction은 클래스 또는 필드 레벨 가능. 여기서는 클래스 애노테이션만 간감지
                val hasSqlRestriction = entityType.getAnnotation(SQLRestriction::class.java) != null
                if ((hasWhere || hasSqlRestriction) && duplicateAliveFilterWarned.compareAndSet(false, true)) {
                    LOG.warn(
                        "[SoftDeleteJpaRepository] 엔티티 '${entityType.simpleName}'에 @Where/@SQLRestriction과 " +
                                "deleteMarkInfo가 함께 적용되어 있어, 소프트 삭제 이후 조회 시 중복 필터링으로 문제가 발생할 수 있습니다. " +
                                "한쪽을 제거하거나 향후 벌크 전략 사용을 고려하세요.")
                }
            }

            entity::class.memberProperties
                .filter { p -> p.getAnnotation<OneToMany>() != null }
                .forEach { p ->
                    p.isAccessible = true
                    val ann = p.getAnnotation<OneToMany>() ?: return@forEach
                    val mappedBy = ann.mappedBy
                    if (mappedBy.isBlank()) return@forEach

                    val childClass = p.returnType.arguments.firstOrNull()?.type?.classifier as? kotlin.reflect.KClass<*>
                    val childJavaClass = childClass?.java ?: return@forEach
                    val childEntityName = try {
                        entityManager.metamodel.entity(childJavaClass).name
                    } catch (_: Exception) { childJavaClass.simpleName }

                    val deleteInfoForChild = childClass.deleteMarkInfo
                    val aliveCheck = deleteInfoForChild?.let { info ->
                        when (info.aliveMark) {
                            DeleteMarkValue.NULL -> " AND c.${info.fieldName} IS NULL"
                            DeleteMarkValue.NOT_NULL -> " AND c.${info.fieldName} IS NOT NULL"
                            else -> " AND c.${info.fieldName} = :_aliveChildMark"
                        }
                    } ?: ""

                    // parent ID 기반 조회 (단일 PK인 경우). 복합 PK 또는 식별자 추출 실패 시 기존 entity 바인딩 fallback.
                    @Suppress("UNCHECKED_CAST")
                    val parentIdAny = try { entityInformation.getId(entity) as Any } catch (_: Exception) { null }
                    val idAttrs = entityInformation.idAttributeNames
                    val parentIdAttr = idAttrs.firstOrNull() // 단일 PK 대비 필요
                    val canUseIdPathSingle = parentIdAny != null && idAttrs.size == 1
                    val canUseComposite = parentIdAny != null && idAttrs.size > 1
                    val jpql = when {
                        canUseIdPathSingle && parentIdAttr != null -> {
                            "SELECT c FROM $childEntityName c WHERE c.$mappedBy.$parentIdAttr = :_parentId$aliveCheck"
                        }
                        canUseComposite -> {
                            val compositeWhere = idAttrs.joinToString(" AND ") { pk ->
                                "c.$mappedBy.$pk = :_parent_${pk}"
                            }
                            "SELECT c FROM $childEntityName c WHERE $compositeWhere$aliveCheck"
                        }
                        else -> "SELECT c FROM $childEntityName c WHERE c.$mappedBy = :_parent$aliveCheck"
                    }

                    val q = entityManager.createQuery(jpql, childJavaClass).apply {
                        when {
                            canUseIdPathSingle && parentIdAttr != null -> setParameter("_parentId", parentIdAny)
                            canUseComposite -> {
                                // parentIdAny가 Map 또는 IdClass/EmbeddedId라고 가정하고 필드 추출
                                val paramMap: Map<String, Any?> = when (parentIdAny) {
                                    is Map<*, *> -> {
                                        parentIdAny.entries.filter { (k, _) -> k in idAttrs }.associate { (k, v) ->
                                            require(k is String) {
                                                "Compound ID key는 String 타입이어야 합니다.(현재 타입: ${k?.javaClass?.name})"
                                            }
                                            k to v
                                        }
                                    }
                                    else -> idAttrs.associateWith { attr ->
                                        val prop = parentIdAny::class.memberProperties.firstOrNull { it.name == attr }
                                            ?: throw IllegalArgumentException(
                                                "Parent composite id 객체에 '$attr' 프로퍼티가 없습니다."
                                            )
                                        prop.isAccessible = true
                                        prop.getter.call(parentIdAny)
                                    }
                                }
                                paramMap.forEach { (k, v) ->
                                    setParameter(
                                        "_parent_${k}",
                                        v ?: throw IllegalArgumentException("'_parent_${k}' 값이 없습니다.")
                                    )
                                }
                            }
                            else -> setParameter("_parent", entity)
                        }
                        if (
                            deleteInfoForChild != null &&
                            deleteInfoForChild.aliveMark !in listOf(DeleteMarkValue.NULL, DeleteMarkValue.NOT_NULL)
                        ) {
                            setParameter("_aliveChildMark", deleteInfoForChild.aliveMarkValue)
                        }
                    }
                    val childList = q.resultList
                    childList.forEach { child ->
                        val childRepo = registry.getRepositoryFor(child) ?: throw IllegalStateException(
                            "SoftDeleteJpaRepository not found for ${child.javaClass.simpleName}"
                        )
                        childRepo.softDelete(child)
                    }
                }

            // 부모 deleteMark 메모리 갱신 (자식 처리 후)
            deleteMarkInfo?.let { info ->
                val deleteMarkField = info.field
                if (deleteMarkField is KMutableProperty1<*, *>) {
                    deleteMarkField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val prop = deleteMarkField as KMutableProperty1<E, Any?>
                    val newDeleteMarkValue = when (info.deleteMark) {
                        DeleteMarkValue.NULL -> null
                        DeleteMarkValue.NOW -> LocalDateTime.now()
                        DeleteMarkValue.NOT_NULL -> DeleteMarkValue.getDefaultDeleteMarkValue(info)
                        else -> info.deleteMarkValue
                    }
                    prop.set(entity, newDeleteMarkValue)
                }
            }

            @Suppress("UNCHECKED_CAST")
            val idVal = entityInformation.getId(entity) as ID
            val idAttrs = entityInformation.idAttributeNames.also {
                if (it.isEmpty()) throw IllegalStateException("ID 필드가 없습니다.")
            }
            val condition = idAttrs.joinToString(" AND ") { "`${it}` = :$it" }
            val sql = if (deleteMarkInfo != null) {
                val setClause = "`${deleteMarkInfo.dbColumnName}` = " +
                        if (deleteMarkInfo.deleteMark == DeleteMarkValue.NULL) "NULL" else ":deleteMark"

                "UPDATE $tableName SET $setClause$autoModify WHERE $condition"
            } else "DELETE FROM $tableName WHERE $condition"
            val query = entityManager.createNativeQuery(sql).apply {
                deleteMarkInfo?.takeIf { it.deleteMark != DeleteMarkValue.NULL }?.let { info ->
                    setParameter(
                        "deleteMark",
                        if (info.deleteMark == DeleteMarkValue.NOT_NULL)
                            DeleteMarkValue.getDefaultDeleteMarkValue(info)
                        else info.deleteMarkValue
                    )
                }
                if (idAttrs.size == 1) {
                    setParameter(idAttrs.first(), idVal)
                } else {
                    val paramMap: Map<String, Any?> = when (idVal) {
                        is Map<*, *> -> idVal.entries.filter { (k, _) -> k in idAttrs }.associate { (k, v) ->
                            require(k is String) {
                                "Compound ID map key는 반드시 String 타입이어야 합니다. (현재 타입: ${k?.javaClass?.name})"
                            }
                            k to v
                        }
                        else -> idAttrs.associateWith { attr ->
                            val prop = idVal::class.memberProperties.firstOrNull {
                                it.name == attr
                            } ?: throw IllegalArgumentException(
                                "${idVal::class.simpleName} class에 '$attr' 프로퍼티가 없습니다."
                            )
                            prop.isAccessible = true
                            prop.getter.call(idVal)
                        }
                    }
                    paramMap.forEach { (k, v) ->
                        setParameter(k, v ?: throw IllegalArgumentException("'$k' 값이 없습니다."))
                    }
                }
            }
            val affected = query.executeUpdate()
            entityManager.flush()

            if (deleteDepthTL.get() == 1) entityManager.clear()
            return affected
        } finally {
            deleteDepthTL.set(deleteDepthTL.get() - 1)
            if (deleteDepthTL.get() <= 0) deleteDepthTL.remove()
        }
    }

    @Transactional @Modifying
    override fun softDeleteByField(fieldName: String, fieldValue: Any) =
        findAllByField(fieldName, fieldValue).content.sumOf { softDelete(it) }

    @Transactional @Modifying
    override fun softDeleteByCondition(condition: String) =
        findAllByCondition(if (condition.isNotEmpty()) condition else "").content.sumOf { softDelete(it) }

    @Transactional @Modifying
    override fun softDeleteByFields(fields: Map<String, Any>) =
        findAllByFields(fields).content.sumOf { softDelete(it) }

    @Transactional @Modifying
    override fun softDeleteById(id: ID) = findOneById(id).orElse(null)?.let(this::softDelete) ?: 0

    @Transactional
    override fun <R> rowLockById(id: ID, block: () -> R): R {
        val idAttrs = entityInformation.idAttributeNames.toList()
        val whereClause =
            if (idAttrs.size == 1) "e.${idAttrs[0]} = :${idAttrs[0]}"
            else idAttrs.joinToString(" AND ") { "e.$it = :$it" }
        applyPessimisticLock(whereClause) {
            if (idAttrs.size == 1) setParameter(idAttrs[0], id) else {
                val paramMap = extractIdParamMap(id as Any, idAttrs)
                paramMap.forEach { (name, value) ->
                    setParameter(name, value ?: throw IllegalArgumentException("'$name' 값이 없습니다"))
                }
            }
        }
        return block()
    }

    @Transactional
    override fun <R> rowLockByField(fieldName: String, fieldValue: Any, block: () -> R): R {
        applyPessimisticLock("e.$fieldName = :fieldValue") { setParameter("fieldValue", fieldValue) }
        return block()
    }

    @Transactional
    override fun <R> rowLockByFields(fields: Map<String, Any>, block: () -> R): R {
        val where = fields.entries.joinToString(" AND ") { "e.${it.key} = :${it.key}" }
        applyPessimisticLock(where) { fields.forEach { (k, v) -> setParameter(k, v) } }
        return block()
    }

    @Transactional
    override fun <R> rowLockByCondition(condition: String, block: () -> R): R {
        val base = if (condition.isNotEmpty()) condition else "TRUE"
        applyPessimisticLock(base) { }
        return block()
    }

    override fun refresh(entity: E): E { entityManager.refresh(entity); return entity }

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
}

@Configuration
@EnableConfigurationProperties(SoftDeleteProperties::class)
@EnableJpaRepositories(repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)
class SoftDeleteJpaRepositoryAutoConfiguration
