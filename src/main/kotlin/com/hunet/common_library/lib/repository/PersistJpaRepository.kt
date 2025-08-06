package com.hunet.common_library.lib.repository

import com.hunet.common_library.lib.logger.commonLogger
import jakarta.persistence.EntityManager
import jakarta.persistence.Table
import jakarta.persistence.TemporalType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.util.*

@NoRepositoryBean
interface PersistJpaRepository<T, ID: Serializable> : JpaRepository<T, ID> {
    /**
     * 기존 데이터 존재 여부를 체크하지 않고 바로 insert 수행, 속도가 빠르다.
     * 중복 데이터가 존재하지 않는다는 확신이 있는 경우에만 사용해야 한다.
     * 존재 여부 체크를 수행하기 위해서는 기존의 save(), saveAll()을 사용한다.
     */
    fun persist(entity: T)
    fun persistAll(entities: Iterable<T>)

    /**
     * DELETE 문을 직접 사용하여 주어진 조건의 데이터를 DELETE한다.
     * @param key: WHERE절의 조건이 되는 키 값. Query 객체의 setParameter()의 key로 지정된다. entity의 필드명이 아닌 DB 필드명을 주어야 한다.
     * @param value: WHERE절의 조건이 되는 비교 값. Query 객체의 setParameter()의 value로 지정된다.
     * @param pageSize: 한 번에 지울 row 수 default: 10000
     * @param condition: DELETE 문의 조건. default: "$key = :$key"
     * @param temporalType: value 파라미터의 type이 DATE 형일 때 지정될 Temporal Type. default: TemporalType.TIMESTAMP
     */
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
        val LOG by commonLogger()
    }

    protected val entityType by lazy {
        entityInformation.javaType
    }

    protected val entityName by lazy {
        entityManager.metamodel.entity(entityType).name
    }

    protected val tableName by lazy {
        entityInformation.javaType.getAnnotation(Table::class.java).name
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
    override fun bulkDelete(
        condition: String,
        pageSize: Int,
        temporalType: TemporalType
    ) = bulkDelete(entityManager, "", 0, false, pageSize, { condition }, temporalType)

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
                if (value is Date) setParameter(key, value, temporalType)
                else setParameter(key, value)
            }
            singleResult as Long
        }

        LOG.info("Delete previous data from $tableName " +
                "(count query = [$countQuery], delete query = [$deleteQuery], " +
                "value = $value, previous row count = $totalCount)")

        em.createNativeQuery(deleteQuery).apply {
            maxResults = pageSize
            if (key != "") {
                if (value is Date) setParameter(key, value, temporalType)
                else setParameter(key, value)
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
