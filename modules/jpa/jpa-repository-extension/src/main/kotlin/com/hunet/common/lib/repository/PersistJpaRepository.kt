package com.hunet.common.lib.repository

import com.hunet.common.data.jpa.extension.getAnnotation
import com.hunet.common.logging.commonLogger
import jakarta.persistence.EntityManager
import jakarta.persistence.Table
import jakarta.persistence.TemporalType
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.util.*
import kotlin.getValue

@NoRepositoryBean
interface PersistJpaRepository<T, ID: Serializable> : JpaRepository<T, ID> {
    fun persist(entity: T)
    fun persistAll(entities: Iterable<T>)
    fun bulkDelete(
        key: String,
        value: Any,
        pageSize: Int = 10000,
        condition: (String) -> String = { "$it = :$it" },
        temporalType: TemporalType = TemporalType.TIMESTAMP
    )
    fun bulkDelete(
        condition: String,
        pageSize: Int = 10000,
        temporalType: TemporalType = TemporalType.TIMESTAMP
    )
    fun bulkDelete(
        em: EntityManager,
        key: String,
        value: Any,
        useIndependentCommit: Boolean = false,
        pageSize: Int = 10000,
        condition: (String) -> String = { "$it = :$it" },
        temporalType: TemporalType = TemporalType.TIMESTAMP
    )
}

@NoRepositoryBean
class PersistJpaRepositoryImpl<T : Any, ID: Serializable>(
    entityInformation: JpaEntityInformation<T, *>,
    val entityManager: EntityManager,
) : SimpleJpaRepository<T, ID>(entityInformation, entityManager), PersistJpaRepository<T, ID> {
    companion object {
//        private val LOG = LoggerFactory.getLogger(PersistJpaRepositoryImpl::class.java)
        private val LOG by commonLogger()
    }

    protected val entityType by lazy { entityInformation.javaType }
    protected val entityName by lazy { entityManager.metamodel.entity(entityType).name }
    protected val tableName by lazy {
        entityInformation.getAnnotation<Table>()?.name ?: throw IllegalStateException(
            "Entity ${entityInformation.javaType.simpleName} must declare @Table(name=...)"
        )
    }

    @Transactional
    override fun persist(entity: T) = entityManager.persist(entity)

    @Transactional
    override fun persistAll(entities: Iterable<T>) = entities.forEach { persist(it) }

    @Transactional
    @Modifying
    override fun bulkDelete(
        key: String,
        value: Any,
        pageSize: Int,
        condition: (String) -> String,
        temporalType: TemporalType
    ) = bulkDelete(entityManager, key, value, false, pageSize, condition, temporalType)

    @Transactional
    @Modifying
    override fun bulkDelete(condition: String, pageSize: Int, temporalType: TemporalType) =
        bulkDelete(entityManager, "", 0, false, pageSize, { condition }, temporalType)

    @Transactional
    @Modifying
    override fun bulkDelete(
        em: EntityManager,
        key: String,
        value: Any,
        useIndependentCommit: Boolean,
        pageSize: Int,
        condition: (String) -> String,
        temporalType: TemporalType
    ) {
        val where = condition(key)
        val countQuery = "SELECT COUNT(t) FROM $entityName t WHERE $where"
        val deleteQuery = "DELETE FROM $tableName WHERE $where LIMIT $pageSize"
        val totalCount = em.createQuery(countQuery).run {
            if (key != "") {
                if (value is Date) setParameter(key, value, temporalType) else setParameter(key, value)
            }
            singleResult as Long
        }
        LOG.info(
            "Delete previous data from $tableName (count query = [$countQuery], delete query = [$deleteQuery], " +
                    "value = $value, previous row count = $totalCount)"
        )
        em.createNativeQuery(deleteQuery).apply {
            maxResults = pageSize
            if (key != "") {
                if (value is Date) setParameter(key, value, temporalType) else setParameter(key, value)
            }
            var count = totalCount
            var accumulation = 0L
            while (count > 0) {
                if (useIndependentCommit) {
                    try {
                        em.transaction.begin()
                        accumulation += executeUpdate()
                        em.transaction.commit()
                    } catch (e: Exception) {
                        em.transaction.rollback()
                    }
                } else accumulation += executeUpdate()
                count -= pageSize
                LOG.info("$accumulation/$totalCount rows deleted FROM [$tableName] WHERE $where")
            }
        }
    }
}
