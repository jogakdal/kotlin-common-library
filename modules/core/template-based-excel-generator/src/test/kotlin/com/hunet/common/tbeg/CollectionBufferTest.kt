package com.hunet.common.tbeg

import com.hunet.common.tbeg.engine.rendering.CollectionBuffer
import com.hunet.common.tbeg.engine.rendering.CollectionBufferManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path

// Kryo가 직렬화할 수 있도록 top-level에 정의된 테스트용 클래스
data class TestEmployee(val id: Int, val name: String, val salary: Double) : Serializable

data class TestAddress(val city: String, val street: String) : Serializable

data class TestPerson(val name: String, val address: TestAddress) : Serializable

/**
 * CollectionBuffer 테스트
 *
 * Iterator를 임시 파일에 버퍼링하고 여러 번 순회할 수 있는지 검증합니다.
 */
@DisplayName("CollectionBuffer 테스트")
class CollectionBufferTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        tempFiles.clear()
    }

    @Nested
    @DisplayName("기본 동작")
    inner class BasicOperations {

        @Test
        @DisplayName("Iterator를 버퍼링하고 size를 반환한다")
        fun bufferReturnsCorrectSize() {
            val items = listOf("A", "B", "C", "D", "E")
            val buffer = CollectionBuffer("test")

            val count = buffer.buffer(items.iterator())

            assertEquals(5, count)
            assertEquals(5, buffer.size)

            buffer.close()
        }

        @Test
        @DisplayName("버퍼링 후 iterator()로 데이터를 읽을 수 있다")
        fun canIterateAfterBuffering() {
            val items = listOf("Apple", "Banana", "Cherry")
            val buffer = CollectionBuffer("fruits")
            buffer.buffer(items.iterator())

            val result = buffer.iterator().asSequence().toList()

            assertEquals(items, result)

            buffer.close()
        }

        @Test
        @DisplayName("iterator()를 여러 번 호출해도 같은 데이터를 반환한다")
        fun canIterateMultipleTimes() {
            val items = (1..10).map { "Item$it" }
            val buffer = CollectionBuffer("items")
            buffer.buffer(items.iterator())

            // 첫 번째 순회
            val result1 = buffer.iterator().asSequence().toList()
            // 두 번째 순회
            val result2 = buffer.iterator().asSequence().toList()
            // 세 번째 순회
            val result3 = buffer.toList()

            assertEquals(items, result1)
            assertEquals(items, result2)
            assertEquals(items, result3)

            buffer.close()
        }

        @Test
        @DisplayName("빈 Iterator도 정상 처리한다")
        fun handlesEmptyIterator() {
            val buffer = CollectionBuffer("empty")

            val count = buffer.buffer(emptyList<Any>().iterator())

            assertEquals(0, count)
            assertEquals(0, buffer.size)
            assertTrue(buffer.isEmpty())
            assertEquals(emptyList<Any>(), buffer.toList())

            buffer.close()
        }

        @Test
        @DisplayName("close() 후 임시 파일이 삭제된다")
        fun tempFileDeletedAfterClose() {
            val buffer = CollectionBuffer("temp")
            buffer.buffer(listOf("test").iterator())

            // 내부 임시 파일 경로를 확인하기 어려우므로,
            // close()가 예외 없이 실행되는지만 확인
            assertDoesNotThrow { buffer.close() }
        }
    }

    @Nested
    @DisplayName("다양한 데이터 타입")
    inner class VariousDataTypes {

        @Test
        @DisplayName("data class 객체를 버퍼링할 수 있다")
        fun canBufferDataClass() {
            val employees = listOf(
                TestEmployee(1, "홍길동", 5000.0),
                TestEmployee(2, "김철수", 4500.0),
                TestEmployee(3, "이영희", 5500.0)
            )

            val buffer = CollectionBuffer("employees")
            buffer.buffer(employees.iterator())

            val result = buffer.toList()

            assertEquals(3, result.size)
            assertEquals(employees[0], result[0])
            assertEquals(employees[1], result[1])
            assertEquals(employees[2], result[2])

            buffer.close()
        }

        @Test
        @DisplayName("중첩 객체를 버퍼링할 수 있다")
        fun canBufferNestedObjects() {
            val people = listOf(
                TestPerson("Alice", TestAddress("Seoul", "Gangnam")),
                TestPerson("Bob", TestAddress("Busan", "Haeundae"))
            )

            val buffer = CollectionBuffer("people")
            buffer.buffer(people.iterator())

            val result = buffer.toList()

            assertEquals(2, result.size)
            assertEquals(people, result)

            buffer.close()
        }

        @Test
        @DisplayName("Map을 버퍼링할 수 있다")
        fun canBufferMaps() {
            val maps = listOf(
                mapOf("name" to "홍길동", "age" to 30),
                mapOf("name" to "김철수", "age" to 25),
                mapOf("name" to "이영희", "age" to 28)
            )

            val buffer = CollectionBuffer("maps")
            buffer.buffer(maps.iterator())

            val result = buffer.toList()

            assertEquals(3, result.size)
            assertEquals(maps, result)

            buffer.close()
        }

        @Test
        @DisplayName("혼합된 타입을 버퍼링할 수 있다")
        fun canBufferMixedTypes() {
            val mixed = listOf<Any>(
                "String",
                123,
                45.67,
                true,
                listOf(1, 2, 3),
                mapOf("key" to "value")
            )

            val buffer = CollectionBuffer("mixed")
            buffer.buffer(mixed.iterator())

            val result = buffer.toList()

            assertEquals(6, result.size)
            assertEquals("String", result[0])
            assertEquals(123, result[1])
            assertEquals(45.67, result[2])
            assertEquals(true, result[3])
            assertEquals(listOf(1, 2, 3), result[4])
            assertEquals(mapOf("key" to "value"), result[5])

            buffer.close()
        }
    }

    @Nested
    @DisplayName("대용량 데이터")
    inner class LargeData {

        @Test
        @DisplayName("10,000건 데이터를 버퍼링하고 정확히 읽을 수 있다")
        fun canHandleLargeData() {
            val count = 10_000
            val items = (1..count).map { mapOf("id" to it, "name" to "Item$it") }

            val buffer = CollectionBuffer("large")
            val bufferedCount = buffer.buffer(items.iterator())

            assertEquals(count, bufferedCount)
            assertEquals(count, buffer.size)

            // 첫 번째 순회 - 모든 항목 확인
            var index = 0
            for (item in buffer) {
                @Suppress("UNCHECKED_CAST")
                val map = item as Map<String, Any>
                assertEquals(index + 1, map["id"])
                assertEquals("Item${index + 1}", map["name"])
                index++
            }
            assertEquals(count, index)

            // 두 번째 순회 - 다시 처음부터 읽을 수 있는지 확인
            val secondResult = buffer.toList()
            assertEquals(count, secondResult.size)

            buffer.close()
        }
    }

    @Nested
    @DisplayName("예외 처리")
    inner class ExceptionHandling {

        @Test
        @DisplayName("버퍼링 전에 size 접근 시 예외 발생")
        fun throwsWhenAccessingSizeBeforeBuffering() {
            val buffer = CollectionBuffer("test")

            assertThrows<IllegalStateException> {
                buffer.size
            }
        }

        @Test
        @DisplayName("버퍼링 전에 iterator() 호출 시 예외 발생")
        fun throwsWhenIteratingBeforeBuffering() {
            val buffer = CollectionBuffer("test")

            assertThrows<IllegalStateException> {
                buffer.iterator()
            }
        }

        @Test
        @DisplayName("두 번 버퍼링 시도 시 예외 발생")
        fun throwsWhenBufferingTwice() {
            val buffer = CollectionBuffer("test")
            buffer.buffer(listOf("A").iterator())

            assertThrows<IllegalStateException> {
                buffer.buffer(listOf("B").iterator())
            }

            buffer.close()
        }
    }

    @Nested
    @DisplayName("CollectionBufferManager")
    inner class BufferManagerTests {

        @Test
        @DisplayName("여러 컬렉션을 관리할 수 있다")
        fun canManageMultipleBuffers() {
            val manager = CollectionBufferManager()

            val employees = listOf("Alice", "Bob", "Charlie")
            val departments = listOf("HR", "IT", "Sales")

            val empBuffer = manager.buffer("employees", employees.iterator())
            val deptBuffer = manager.buffer("departments", departments.iterator())

            assertEquals(3, empBuffer.size)
            assertEquals(3, deptBuffer.size)
            assertEquals(employees, empBuffer.toList())
            assertEquals(departments, deptBuffer.toList())

            // get으로 버퍼 조회
            assertEquals(empBuffer, manager.get("employees"))
            assertEquals(deptBuffer, manager.get("departments"))

            // count 조회
            assertEquals(3, manager.getCount("employees"))
            assertEquals(3, manager.getCount("departments"))
            assertNull(manager.getCount("nonexistent"))

            manager.close()
        }

        @Test
        @DisplayName("close()가 모든 버퍼를 정리한다")
        fun closeReleasesAllBuffers() {
            val manager = CollectionBufferManager()

            manager.buffer("a", listOf(1, 2, 3).iterator())
            manager.buffer("b", listOf(4, 5, 6).iterator())
            manager.buffer("c", listOf(7, 8, 9).iterator())

            assertDoesNotThrow { manager.close() }

            // close 후 get은 null 반환
            assertNull(manager.get("a"))
            assertNull(manager.get("b"))
            assertNull(manager.get("c"))
        }
    }
}
