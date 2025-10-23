package com.hunet.common.support

import com.hunet.common.logging.commonLogger
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.core.io.ClassPathResource
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

open class DataFeed {
    /**
     * 구문 실행 시마다 새 EntityManager를 생성하기 위해 팩토리 형태로 제공
     * (테스트 시딩 용도: 1차 캐시/동시성/자원 누수 최소화)
     */
    lateinit var entityManagerFactory: EntityManagerFactory

    companion object { val LOG by commonLogger() }

    /** 스크립트 파일 실행 (다중 구문 허용, 실패 구문은 로깅 후 계속 수행) */
    open fun executeScriptFromFile(scriptFilePath: String) {
        val resource = ClassPathResource(scriptFilePath)
        if (!resource.exists()) {
            LOG.warn("[DataFeed] script file not found: {}", scriptFilePath)
            return
        }
        val rawLines = try {
            BufferedReader(InputStreamReader(Objects.requireNonNull(resource.inputStream))).use { it.readLines() }
        } catch (e: Exception) {
            LOG.error("[DataFeed] failed to read script: {} - {}", scriptFilePath, e.message, e)
            return
        }
        if (rawLines.isEmpty()) {
            LOG.debug("[DataFeed] empty script: {}", scriptFilePath)
            return
        }
        val parsed = parseStatements(rawLines)
        if (parsed.isEmpty()) {
            LOG.debug("[DataFeed] no executable statements: {}", scriptFilePath)
            return
        }
        for ((sql, line) in parsed) {
            try {
                executeStatement(sql)
            } catch (ex: Exception) {
                LOG.error(
                    "[DataFeed] failed statement at line {}: {}\n----- SQL START -----\n{}\n----- SQL END -----",
                    line, ex.message, sql, ex
                )
            }
        }
    }

    /** 단일 SQL (INSERT/UPDATE/DELETE 등) */
    open fun executeUpsertSql(query: String) {
        try {
            executeStatement(query)
        } catch (e: IOException) {
            LOG.error("[DataFeed] executeUpsertSql error: {}", e.message, e)
        }
    }

    // FSM 기반 파서
    private data class Parsed(val sql: String, val line: Int)

    private fun parseStatements(lines: List<String>): List<Parsed> {
        val result = mutableListOf<Parsed>()
        val current = StringBuilder()
        var currentStartLine = 0
        var inSingle = false
        var inDouble = false
        var inBlockComment = false
        var semicolonCount = 0

        lines.forEachIndexed { idx, rawLine ->
            val lineNum = idx + 1
            var i = 0
            while (i < rawLine.length) {
                if (inBlockComment) {
                    val endIdx = rawLine.indexOf("*/", i)
                    if (endIdx == -1) {
                        i = rawLine.length
                        continue
                    } else {
                        inBlockComment = false
                        i = endIdx + 2
                        continue
                    }
                }
                val ch = rawLine[i]
                val next = if (i + 1 < rawLine.length) rawLine[i + 1] else '\u0000'
                if (!inSingle && !inDouble) {
                    if (ch == '-' && next == '-') break
                    if (ch == '/' && next == '/') break
                    if (ch == '#') break
                    if (ch == '/' && next == '*') {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                }
                if (!inDouble && ch == '\'') {
                    if (inSingle && next == '\'') {
                        if (currentStartLine == 0 && !ch.isWhitespace()) currentStartLine = lineNum
                        current.append(ch)
                        current.append(next)
                        i += 2
                        continue
                    }
                    inSingle = !inSingle
                    if (currentStartLine == 0) currentStartLine = lineNum
                    current.append(ch)
                    i++
                    continue
                } else if (!inSingle && ch == '"') {
                    inDouble = !inDouble
                    if (currentStartLine == 0) currentStartLine = lineNum
                    current.append(ch)
                    i++
                    continue
                }
                if (currentStartLine == 0 && !ch.isWhitespace()) currentStartLine = lineNum
                if (ch == ';' && !inSingle && !inDouble) {
                    val stmt = current.toString().trim()
                    if (stmt.isNotBlank()) {
                        result.add(Parsed(stmt, currentStartLine))
                        semicolonCount++
                    }
                    current.setLength(0)
                    currentStartLine = 0
                    i++
                    continue
                }
                current.append(ch)
                i++
            }
            if (current.isNotEmpty()) current.append('\n')
        }
        val tail = current.toString().trim()
        if (tail.isNotBlank()) result.add(Parsed(tail, if (currentStartLine != 0) currentStartLine else lines.size))
        if (semicolonCount == 0) return legacyLineMode(lines)
        return result
    }

    private fun legacyLineMode(lines: List<String>): List<Parsed> {
        val list = mutableListOf<Parsed>()
        var inBlock = false
        lines.forEachIndexed { idx, raw ->
            val lineNum = idx + 1
            var i = 0
            val sb = StringBuilder()
            while (i < raw.length) {
                val ch = raw[i]
                val next = if (i + 1 < raw.length) raw[i + 1] else '\u0000'
                if (inBlock) {
                    val end = raw.indexOf("*/", i)
                    if (end == -1) {
                        i = raw.length
                        continue
                    } else {
                        inBlock = false
                        i = end + 2
                        continue
                    }
                }
                if (ch == '/' && next == '*') {
                    inBlock = true
                    i += 2
                    continue
                }
                if (ch == '-' && next == '-') break
                if (ch == '/' && next == '/') break
                if (ch == '#') break
                sb.append(ch)
                i++
            }
            val trimmed = sb.toString().trim()
            if (trimmed.isNotBlank()) list.add(Parsed(trimmed, lineNum))
        }
        return list
    }

    protected open fun executeStatement(sql: String) {
        if (sql.isBlank()) return
        if (!::entityManagerFactory.isInitialized) {
            LOG.debug("[DataFeed] entityManagerFactory not initialized (no-op mode?); skip: {}", abbreviate(sql))
            return
        }
        var em: EntityManager? = null
        val start = System.currentTimeMillis()
        try {
            em = entityManagerFactory.createEntityManager()
            val tx = em.transaction
            tx.begin()
            em.createNativeQuery(sql).executeUpdate()
            tx.commit()
            LOG.debug("[DataFeed] executed ({} ms): {}", System.currentTimeMillis() - start, abbreviate(sql))
        } catch (e: Exception) {
            try {
                em?.transaction?.let { if (it.isActive) it.rollback() }
            } catch (_: Exception) {}
            throw e
        } finally {
            try {
                em?.close()
            } catch (_: Exception) {}
        }
    }

    private fun abbreviate(sql: String, max: Int = 120) =
        if (sql.length <= max) sql.replace('\n', ' ') else sql.substring(0, max).replace('\n', ' ') + "..."
}
