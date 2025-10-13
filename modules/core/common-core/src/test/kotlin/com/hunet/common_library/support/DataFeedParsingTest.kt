package com.hunet.common_library.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class DataFeedParsingTest {

    private class CapturingDataFeed : DataFeed() {
        val executed = mutableListOf<String>()
        override fun executeStatement(sql: String) { executed += sql.trim() }
    }

    @Test
    fun `세미콜론으로 구분된 멀티라인 스크립트가 올바르게 파싱된다`() {
        val feed = CapturingDataFeed()
        feed.executeScriptFromFile("db/multiline.sql")
        // 기대 구문 4개
        assertEquals(4, feed.executed.size, "총 4개의 SQL 구문이 파싱되어야 합니다")
        assertIterableEquals(
            listOf(
                "INSERT INTO users(id, name)\nVALUES (1, 'Alice')",
                "INSERT INTO roles(id, name) VALUES (1, 'ADMIN')",
                "INSERT INTO features(flag, enabled) VALUES ('exp-x', true)",
                "INSERT INTO trailing_semicolon(id) VALUES (99)"
            ),
            feed.executed,
            "파싱된 구문 시퀀스가 예상과 다릅니다"
        )
    }

    @Test
    fun `레거시 세미콜론 없음 모드에서 비어있지 않은 각 라인이 실행된다`() {
        val feed = CapturingDataFeed()
        feed.executeScriptFromFile("db/legacy_no_semicolon.sql")
        assertEquals(2, feed.executed.size, "세미콜론 없는 모드에서 2개의 실행 라인이 필요")
        assertIterableEquals(
            listOf(
                "INSERT INTO table_a(id, name) VALUES (1, 'A')",
                "INSERT INTO table_b(id, name) VALUES (2, 'B')"
            ),
            feed.executed
        )
    }

    @Test
    fun `문자열 리터럴 내부의 세미콜론은 분리되지 않는다`() {
        val feed = CapturingDataFeed()
        feed.executeScriptFromFile("db/string_semicolon.sql")
        assertEquals(2, feed.executed.size, "문자열 내부 세미콜론은 구문 분리 기준이 아니어야 함")
        assertIterableEquals(
            listOf(
                "INSERT INTO notes(id, body) VALUES (1, 'hello;world;still one')",
                "INSERT INTO notes(id, body) VALUES (2, 'second')"
            ),
            feed.executed
        )
    }

    @Test
    fun `멀티라인 블록 주석은 완전히 건너뛴다`() {
        val feed = CapturingDataFeed()
        feed.executeScriptFromFile("db/block_comments.sql")
        assertEquals(3, feed.executed.size, "블록 주석이 모두 스킵되고 3개 구문만 실행되어야 함")
        assertIterableEquals(
            listOf(
                "INSERT INTO a(id, val) VALUES (1, 'A')",
                "INSERT INTO b(id, txt) VALUES (2, \"B;B\")",
                "INSERT INTO c(id, msg) VALUES (3, 'C;C;C')"
            ),
            feed.executed
        )
    }
}
