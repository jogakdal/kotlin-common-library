package com.hunet.common_library.lib.repository

import com.hunet.common_library.lib.SpringContextHolder
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.Serializable

@Component
class SoftDeleteRepositoryRegistry {
    private val repoMap = mutableMapOf<Class<*>, SoftDeleteJpaRepository<Any, Serializable>>()

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        SpringContextHolder.context.getBeansOfType(SoftDeleteJpaRepository::class.java).values.forEach { repo ->
            @Suppress("UNCHECKED_CAST")
            val sdRepo = repo as SoftDeleteJpaRepository<Any, Serializable>
            repoMap[sdRepo.getEntityClass()] = sdRepo
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : Any> getRepositoryFor(entity: E): SoftDeleteJpaRepository<E, Serializable>? =
        repoMap[entity.javaClass] as? SoftDeleteJpaRepository<E, Serializable>
}

