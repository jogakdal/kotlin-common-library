package com.hunet.common.lib.repository

@Deprecated("Moved to com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository", ReplaceWith("com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository"))
interface SoftDeleteJpaRepository<E, ID: java.io.Serializable> : com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository<E, ID>

@Deprecated("Moved to com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl", ReplaceWith("com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl"))
open class SoftDeleteJpaRepositoryImpl<E: Any, ID: java.io.Serializable>(
    entityInformation: org.springframework.data.jpa.repository.support.JpaEntityInformation<E, *>,
    entityManager: jakarta.persistence.EntityManager
) : com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryImpl<E, ID>(entityInformation, entityManager)

@Deprecated("Use com.hunet.common.data.jpa.softdelete.UpsertKey", ReplaceWith("com.hunet.common.data.jpa.softdelete.UpsertKey"))
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class UpsertKey

// DeleteMark annotation forward
@Deprecated("Use com.hunet.common.data.jpa.softdelete.annotation.DeleteMark", ReplaceWith("com.hunet.common.data.jpa.softdelete.annotation.DeleteMark"))
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeleteMark(val aliveMark: com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue, val deletedMark: com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue)

// Sequence 관련 forward
@Deprecated("Use com.hunet.common.data.jpa.sequence.GenerateSequentialCode", ReplaceWith("com.hunet.common.data.jpa.sequence.GenerateSequentialCode"))
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GenerateSequentialCode(
    val prefixExpression: String = "",
    val prefixProvider: kotlin.reflect.KClass<out com.hunet.common.data.jpa.sequence.PrefixProvider> = com.hunet.common.data.jpa.sequence.DefaultPrefixProvider::class,
)

@Deprecated("Use com.hunet.common.data.jpa.sequence.SequenceGenerator", ReplaceWith("com.hunet.common.data.jpa.sequence.SequenceGenerator"))
interface SequenceGenerator : com.hunet.common.data.jpa.sequence.SequenceGenerator

@Deprecated("Use com.hunet.common.data.jpa.sequence.PrefixProvider", ReplaceWith("com.hunet.common.data.jpa.sequence.PrefixProvider"))
interface PrefixProvider : com.hunet.common.data.jpa.sequence.PrefixProvider

@Deprecated("Use com.hunet.common.data.jpa.sequence.DefaultPrefixProvider", ReplaceWith("com.hunet.common.data.jpa.sequence.DefaultPrefixProvider"))
open class DefaultPrefixProvider : com.hunet.common.data.jpa.sequence.DefaultPrefixProvider()

// Properties forward
@Deprecated("Use com.hunet.common.data.jpa.softdelete.SoftDeleteProperties", ReplaceWith("com.hunet.common.data.jpa.softdelete.SoftDeleteProperties"))
typealias SoftDeleteProperties = com.hunet.common.data.jpa.softdelete.SoftDeleteProperties

// Registry forward
@Deprecated("Use com.hunet.common.data.jpa.softdelete.SoftDeleteRepositoryRegistry", ReplaceWith("com.hunet.common.data.jpa.softdelete.SoftDeleteRepositoryRegistry"))
open class SoftDeleteRepositoryRegistry : com.hunet.common.data.jpa.softdelete.SoftDeleteRepositoryRegistry()

// AutoConfiguration forward
@Deprecated("Use com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration", ReplaceWith("com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration"))
@org.springframework.context.annotation.Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(SoftDeleteProperties::class)
open class SoftDeleteJpaRepositoryAutoConfiguration : com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepositoryAutoConfiguration()

// Typealias & constant forwarders for backward compatibility
@Deprecated("Use com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue", ReplaceWith("com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue"))
typealias DeleteMarkValue = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue

@Deprecated("Use com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo", ReplaceWith("com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo"))
typealias DeleteMarkInfo = com.hunet.common.data.jpa.softdelete.internal.DeleteMarkInfo

@Deprecated("Use com.hunet.common.data.jpa.softdelete.internal.MYSQL_DATETIME_MIN", ReplaceWith("com.hunet.common.data.jpa.softdelete.internal.MYSQL_DATETIME_MIN"))
val MYSQL_DATETIME_MIN: java.time.LocalDateTime = com.hunet.common.data.jpa.softdelete.internal.MYSQL_DATETIME_MIN
