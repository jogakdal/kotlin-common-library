package com.hunet.common_library.support

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

@Component
open class DataFeed {
    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Transactional
    open fun executeScriptFromFile(scriptFilePath: String) {
        try {
            val resource = ClassPathResource(scriptFilePath)
            BufferedReader(InputStreamReader(Objects.requireNonNull(resource.getInputStream()))).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    if ("" != line) {
                        entityManager.createNativeQuery(line).executeUpdate()
                    }
                }
                // FIXME 한줄씩 처리가 아닌 여러줄 처리 가능하도록 수정
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Transactional
    open fun executeUpsertSql(query: String) {
        try {
            entityManager.createNativeQuery(query).executeUpdate()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
