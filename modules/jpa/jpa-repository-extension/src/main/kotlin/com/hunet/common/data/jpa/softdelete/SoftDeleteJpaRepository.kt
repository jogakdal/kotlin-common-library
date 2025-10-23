package com.hunet.common.data.jpa.softdelete

import com.hunet.common.data.jpa.sequence.GenerateSequentialCode
import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.softdelete.annotation.deleteMarkInfo
import com.hunet.common.data.jpa.softdelete.annotation.getAnnotation
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import com.hunet.common.lib.SpringContextHolder
import com.hunet.common.util.isNotEmpty
import jakarta.persistence.*
import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
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
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
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
class SoftDeleteJpaRepositoryImpl<E : Any, ID: Serializable>(
    val entityInformation: JpaEntityInformation<E, *>,
    val entityManager: EntityManager
) : SimpleJpaRepository<E, ID>(entityInformation, entityManager), SoftDeleteJpaRepository<E, ID> {
    private val log = LoggerFactory.getLogger(javaClass)
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

    companion object {
        inline fun <reified A : Annotation> KClass<*>.annotatedFields(): List<KMutableProperty1<*, *>> =
            generateSequence(this) { it.java.superclass?.kotlin }
                .filter { klass -> klass.findAnnotation<MappedSuperclass>() != null }
                .flatMap { klass -> klass.declaredMemberProperties.asSequence() }
                .filter { it.findAnnotation<A>() != null }
                .mapNotNull { prop -> (prop as? KMutableProperty1<*, *>)?.apply { isAccessible = true } }
                .toList()
    }

    override fun getEntityClass(): Class<E> = entityType

    private fun findByUpsertKey(entity: E): E? {
        // 1) Kotlin 프로퍼티(@PROPERTY)에 붙은 @UpsertKey 우선 탐색
        val upsertKeyProp = entityType.kotlin.memberProperties.firstOrNull {
            it.findAnnotation<UpsertKey>() != null
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
        // 2) Kotlin 프로퍼티가 없으면 Java 필드(@FIELD)에 붙은 @UpsertKey 탐색
        val upsertKeyField = entityType.declaredFields.firstOrNull { it.isAnnotationPresent(UpsertKey::class.java) }
            ?: return null
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
                    prop.findAnnotation<CreatedDate>() != null -> {}
                    prop.findAnnotation<LastModifiedDate>() != null -> prop.setter.call(existing, LocalDateTime.now())
                    else -> prop.getter.call(entity)?.let { newValue -> prop.setter.call(existing, newValue) }
                }
            }
        }
        return entityManager.merge(existing)
    }

    private fun applyUpdateEntity(entity: E, copyFunc: (E) -> Unit): E {
        val createdProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.findAnnotation<CreatedDate>() != null
        }
        val lastModifiedProps = entity::class.memberProperties.filterIsInstance<KMutableProperty1<E, Any?>>().filter {
            it.findAnnotation<LastModifiedDate>() != null
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
        entity::class.memberProperties
            .filterIsInstance<KMutableProperty1<Any, Any?>>()
            .filter { it.findAnnotation<GenerateSequentialCode>() != null }
            .forEach { prop ->
                prop.isAccessible = true
                val current = (prop.get(entity) as? String).orEmpty()
                if (current.isBlank()) {
                    val ann = prop.findAnnotation<GenerateSequentialCode>()!!
                    val prefix = if (ann.prefixExpression.isNotBlank()) {
                        com.hunet.common.data.jpa.sequence.spelExpressionParser
                            .parseExpression(ann.prefixExpression)
                            .getValue(StandardEvaluationContext(entity)) as String
                    } else ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                    val genCandidate = sequenceGenerator.generateKey(prefix, entity) as? String
                    val finalCode = genCandidate?.takeIf { it.isNotBlank() }
                        ?: (prefix + seqCounters.computeIfAbsent(prefix) { AtomicLong(0) }.incrementAndGet())
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
                        com.hunet.common.data.jpa.sequence.spelExpressionParser
                            .parseExpression(ann.prefixExpression)
                            .getValue(StandardEvaluationContext(entity)) as String
                    } else ann.prefixProvider.java.getDeclaredConstructor().newInstance().determinePrefix(entity)
                    val genCandidate = sequenceGenerator.generateKey(prefix, entity) as? String
                    val finalCode = genCandidate?.takeIf { it.isNotBlank() }
                        ?: (prefix + seqCounters.computeIfAbsent(prefix) { AtomicLong(0) }.incrementAndGet())
                    field.set(entity, finalCode)
                }
            }
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
            setParams();
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

    @Transactional
    @Modifying
    override fun softDelete(entity: E): Int {
        entity::class.memberProperties
            .filter { prop -> prop.findAnnotation<OneToMany>() != null || prop.getAnnotation<OneToMany>() != null }
            .forEach { prop ->
                prop.isAccessible = true
                val children = prop.getter.call(entity) as? Collection<*> ?: return@forEach
                children.filterNotNull().forEach { child ->
                    val childRepo = registry.getRepositoryFor(child) ?: throw IllegalStateException(
                        "SoftDeleteJpaRepository not found for ${child.javaClass.simpleName}"
                    )
                    childRepo.softDelete(child)
                }
            }
        @Suppress("UNCHECKED_CAST") val idVal = entityInformation.getId(entity) as ID
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
                    if (info.deleteMark == DeleteMarkValue.NOT_NULL) DeleteMarkValue.getDefaultDeleteMarkValue(info)
                    else info.deleteMarkValue
                )
            }
            if (idAttrs.size == 1) {
                setParameter(idAttrs.first(), idVal)
            } else {
                val paramMap: Map<String, Any?> = when (idVal) {
                    is Map<*, *> -> {
                        idVal.entries.filter { (k, _) -> k in idAttrs }.associate { (k, v) ->
                            require(k is String) { "Compound ID map key must be String but was ${k?.javaClass?.name}" }
                            k to v
                        }
                    }
                    else -> {
                        idAttrs.associateWith { attr ->
                            val prop = idVal::class.memberProperties.firstOrNull {
                                it.name == attr
                            } ?: throw IllegalArgumentException("${idVal::class.simpleName} class에 '$attr' 프로퍼티 없음")
                            prop.isAccessible = true
                            prop.getter.call(idVal)
                        }
                    }
                }
                paramMap.forEach { (k, v) -> setParameter(k, v ?: throw IllegalArgumentException("'$k' 값이 없습니다")) }
            }
        }
        val affected = query.executeUpdate()
        entityManager.flush()
        entityManager.clear()
        return affected
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
