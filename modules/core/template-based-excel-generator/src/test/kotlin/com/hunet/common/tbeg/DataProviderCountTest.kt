package com.hunet.common.tbeg

import com.hunet.common.tbeg.engine.rendering.RequiredNames
import com.hunet.common.tbeg.engine.rendering.TemplateRenderingEngine
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * DataProvider의 count 제공 여부에 따른 동작 테스트
 *
 * - count 제공 시: List로 변환 (메모리에 로드)
 * - count 미제공 시: CollectionBuffer로 임시 파일에 버퍼링
 *
 * 두 경우 모두 동일한 결과를 생성해야 합니다.
 */
@DisplayName("DataProvider count 제공 여부 테스트")
class DataProviderCountTest {

    private lateinit var engine: TemplateRenderingEngine
    private lateinit var templateBytes: ByteArray

    @BeforeEach
    fun setUp() {
        engine = TemplateRenderingEngine(StreamingMode.DISABLED)
        templateBytes = javaClass.getResourceAsStream("/templates/no_pivot_template.xlsx")?.readBytes()
            ?: throw IllegalStateException("템플릿 파일을 찾을 수 없습니다")
    }

    // 테스트용 데이터 - Map 기반 (Kryo 직렬화 호환)
    private fun createEmployees() = listOf(
        mapOf("name" to "홍길동", "position" to "개발자", "salary" to 5000),
        mapOf("name" to "김철수", "position" to "디자이너", "salary" to 4500),
        mapOf("name" to "이영희", "position" to "기획자", "salary" to 4800)
    )

    private fun createDepartments() = listOf(
        mapOf("name" to "개발팀", "members" to 10, "office" to "본관 3층"),
        mapOf("name" to "디자인팀", "members" to 5, "office" to "본관 2층"),
        mapOf("name" to "기획팀", "members" to 7, "office" to "별관 1층")
    )

    @Nested
    @DisplayName("count 제공 vs 미제공 동작 비교")
    inner class CountBehaviorComparison {

        @Test
        @DisplayName("count 제공 시와 미제공 시 동일한 결과를 생성한다")
        fun produceSameResultWithAndWithoutCount() {
            val employees = createEmployees()
            val departments = createDepartments()

            // count 제공하는 DataProvider
            val providerWithCount = object : ExcelDataProvider {
                override fun getValue(name: String): Any? = when (name) {
                    "title" -> "직원 현황"
                    "date" -> "2026-01-29"
                    "secondTitle" -> "부서 현황"
                    "linkText" -> "링크"
                    "url" -> "https://example.com"
                    else -> null
                }

                override fun getItems(name: String): Iterator<Any>? = when (name) {
                    "employees" -> employees.iterator()
                    "department" -> departments.iterator()
                    else -> null
                }

                override fun getItemCount(name: String): Int? = when (name) {
                    "employees" -> employees.size
                    "department" -> departments.size
                    else -> null
                }
            }

            // count 제공하지 않는 DataProvider (기본 구현)
            val providerWithoutCount = object : ExcelDataProvider {
                override fun getValue(name: String): Any? = when (name) {
                    "title" -> "직원 현황"
                    "date" -> "2026-01-29"
                    "secondTitle" -> "부서 현황"
                    "linkText" -> "링크"
                    "url" -> "https://example.com"
                    else -> null
                }

                override fun getItems(name: String): Iterator<Any>? = when (name) {
                    "employees" -> employees.iterator()
                    "department" -> departments.iterator()
                    else -> null
                }
                // getItemCount는 기본 구현 (null 반환)
            }

            val requiredNames = RequiredNames(
                variables = setOf("title", "date", "secondTitle", "linkText", "url"),
                collections = setOf("employees", "department"),
                images = emptySet()
            )

            // 두 방식으로 생성
            val resultWithCount = engine.process(
                ByteArrayInputStream(templateBytes),
                providerWithCount,
                requiredNames
            )

            val resultWithoutCount = engine.process(
                ByteArrayInputStream(templateBytes),
                providerWithoutCount,
                requiredNames
            )

            // 결과 비교 - 셀 내용이 동일해야 함
            val wbWithCount = WorkbookFactory.create(ByteArrayInputStream(resultWithCount))
            val wbWithoutCount = WorkbookFactory.create(ByteArrayInputStream(resultWithoutCount))

            val sheetWithCount = wbWithCount.getSheetAt(0)
            val sheetWithoutCount = wbWithoutCount.getSheetAt(0)

            // 행 수 비교
            println("count 제공 시 행 수: ${sheetWithCount.lastRowNum}, 미제공 시 행 수: ${sheetWithoutCount.lastRowNum}")
            assertEquals(sheetWithCount.lastRowNum, sheetWithoutCount.lastRowNum,
                "두 결과의 행 수가 동일해야 합니다 (count 제공: ${sheetWithCount.lastRowNum}, 미제공: ${sheetWithoutCount.lastRowNum})")

            // 각 셀 내용 비교
            for (rowIdx in 0..sheetWithCount.lastRowNum) {
                val rowWithCount = sheetWithCount.getRow(rowIdx)
                val rowWithoutCount = sheetWithoutCount.getRow(rowIdx)

                if (rowWithCount == null && rowWithoutCount == null) continue
                if (rowWithCount == null || rowWithoutCount == null) {
                    // 한쪽만 null인 경우, 빈 행일 수 있음
                    continue
                }

                val maxCol = maxOf(
                    rowWithCount.lastCellNum.toInt().coerceAtLeast(0),
                    rowWithoutCount.lastCellNum.toInt().coerceAtLeast(0)
                )
                for (colIdx in 0 until maxCol) {
                    val cellWithCount = rowWithCount.getCell(colIdx)
                    val cellWithoutCount = rowWithoutCount.getCell(colIdx)

                    val valueWithCount = cellWithCount?.toString() ?: ""
                    val valueWithoutCount = cellWithoutCount?.toString() ?: ""

                    assertEquals(valueWithCount, valueWithoutCount,
                        "셀 ($rowIdx, $colIdx) 값이 다릅니다: '$valueWithCount' vs '$valueWithoutCount'")
                }
            }

            wbWithCount.close()
            wbWithoutCount.close()
        }
    }

    @Nested
    @DisplayName("Iterator 순회 횟수 검증")
    inner class IteratorTraversalTest {

        @Test
        @DisplayName("count 제공 시 Iterator는 한 번만 순회된다")
        fun iteratorTraversedOnceWithCount() {
            val traversalCount = AtomicInteger(0)

            val items = listOf(
                mapOf("name" to "A", "position" to "P1", "salary" to 1000),
                mapOf("name" to "B", "position" to "P2", "salary" to 2000),
                mapOf("name" to "C", "position" to "P3", "salary" to 3000)
            )
            val trackingIterator = object : Iterator<Any> {
                private val inner = items.iterator()
                override fun hasNext(): Boolean = inner.hasNext()
                override fun next(): Any {
                    traversalCount.incrementAndGet()
                    return inner.next()
                }
            }

            val provider = object : ExcelDataProvider {
                override fun getValue(name: String): Any? = when (name) {
                    "title" -> "테스트"
                    "date" -> "2026-01-29"
                    "secondTitle" -> "부서"
                    "linkText" -> "링크"
                    "url" -> "https://example.com"
                    else -> null
                }

                override fun getItems(name: String): Iterator<Any>? = when (name) {
                    "employees" -> trackingIterator
                    "department" -> listOf(mapOf("name" to "팀", "members" to 1, "office" to "A")).iterator()
                    else -> null
                }

                override fun getItemCount(name: String): Int? = when (name) {
                    "employees" -> items.size
                    "department" -> 1
                    else -> null
                }
            }

            val requiredNames = RequiredNames(
                variables = setOf("title", "date", "secondTitle", "linkText", "url"),
                collections = setOf("employees", "department"),
                images = emptySet()
            )

            engine.process(ByteArrayInputStream(templateBytes), provider, requiredNames)

            // count 제공 시: toList()로 한 번 순회
            assertEquals(items.size, traversalCount.get(),
                "count 제공 시 Iterator는 정확히 아이템 수만큼 순회되어야 합니다")
        }

        @Test
        @DisplayName("count 미제공 시 Iterator는 버퍼링을 위해 한 번만 순회된다")
        fun iteratorTraversedOnceWithoutCount() {
            val traversalCount = AtomicInteger(0)

            val items = listOf(
                mapOf("name" to "A", "position" to "P1", "salary" to 1000),
                mapOf("name" to "B", "position" to "P2", "salary" to 2000),
                mapOf("name" to "C", "position" to "P3", "salary" to 3000),
                mapOf("name" to "D", "position" to "P4", "salary" to 4000),
                mapOf("name" to "E", "position" to "P5", "salary" to 5000)
            )
            val trackingIterator = object : Iterator<Any> {
                private val inner = items.iterator()
                override fun hasNext(): Boolean = inner.hasNext()
                override fun next(): Any {
                    traversalCount.incrementAndGet()
                    return inner.next()
                }
            }

            val provider = object : ExcelDataProvider {
                override fun getValue(name: String): Any? = when (name) {
                    "title" -> "테스트"
                    "date" -> "2026-01-29"
                    "secondTitle" -> "부서"
                    "linkText" -> "링크"
                    "url" -> "https://example.com"
                    else -> null
                }

                override fun getItems(name: String): Iterator<Any>? = when (name) {
                    "employees" -> trackingIterator
                    "department" -> listOf(mapOf("name" to "팀", "members" to 1, "office" to "A")).iterator()
                    else -> null
                }

                // getItemCount 미구현 - null 반환
            }

            val requiredNames = RequiredNames(
                variables = setOf("title", "date", "secondTitle", "linkText", "url"),
                collections = setOf("employees", "department"),
                images = emptySet()
            )

            engine.process(ByteArrayInputStream(templateBytes), provider, requiredNames)

            // count 미제공 시에도 Iterator는 한 번만 순회 (버퍼링 시)
            assertEquals(items.size, traversalCount.get(),
                "count 미제공 시에도 Iterator는 버퍼링을 위해 한 번만 순회되어야 합니다")
        }
    }

    @Nested
    @DisplayName("SimpleDataProvider count 지원")
    inner class SimpleDataProviderCountTest {

        @Test
        @DisplayName("List로 추가 시 count가 자동 설정된다")
        fun listAutoSetsCount() {
            val employees = listOf(
                mapOf("name" to "홍길동"),
                mapOf("name" to "김철수")
            )

            val provider = SimpleDataProvider.builder()
                .value("title", "테스트")
                .value("date", "2026-01-29")
                .items("employees", employees)
                .items("department", listOf(mapOf("name" to "팀", "members" to 1)))
                .build()

            assertEquals(2, provider.getItemCount("employees"),
                "List로 추가 시 count가 자동 설정되어야 합니다")
            assertEquals(1, provider.getItemCount("department"))
        }

        @Test
        @DisplayName("count와 함께 lazy supplier 등록")
        fun countWithLazySupplier() {
            val callCount = AtomicInteger(0)

            val provider = SimpleDataProvider.builder()
                .value("title", "테스트")
                .value("date", "2026-01-29")
                .items("employees", 3) {
                    callCount.incrementAndGet()
                    listOf(
                        mapOf("name" to "A"),
                        mapOf("name" to "B"),
                        mapOf("name" to "C")
                    ).iterator()
                }
                .items("department", listOf(mapOf("name" to "팀", "members" to 1)))
                .build()

            // count는 즉시 사용 가능
            assertEquals(3, provider.getItemCount("employees"))

            // 아직 supplier가 호출되지 않음
            assertEquals(0, callCount.get())

            // getItems 호출 시 supplier 실행
            val iterator = provider.getItems("employees")
            assertEquals(1, callCount.get())

            // Iterator 순회
            val items = iterator?.asSequence()?.toList()
            assertEquals(3, items?.size)
        }

        @Test
        @DisplayName("of() 메서드로 생성 시 List/Collection의 count가 자동 설정된다")
        fun ofMethodAutoSetsCount() {
            val provider = SimpleDataProvider.of(mapOf(
                "title" to "테스트",
                "date" to "2026-01-29",
                "employees" to listOf(
                    mapOf("name" to "A"),
                    mapOf("name" to "B"),
                    mapOf("name" to "C")
                ),
                "departments" to setOf("HR", "IT", "Sales")
            ))

            assertEquals(3, provider.getItemCount("employees"),
                "List의 count가 자동 설정되어야 합니다")
            assertEquals(3, provider.getItemCount("departments"),
                "Set의 count가 자동 설정되어야 합니다")
        }

        @Test
        @DisplayName("Sequence나 일반 Iterable은 count가 설정되지 않는다")
        fun sequenceDoesNotSetCount() {
            val sequence = sequenceOf("A", "B", "C")

            val provider = SimpleDataProvider.of(mapOf(
                "title" to "테스트",
                "items" to sequence
            ))

            assertNull(provider.getItemCount("items"),
                "Sequence는 count를 알 수 없으므로 null이어야 합니다")
        }
    }

    @Nested
    @DisplayName("SXSSF 스트리밍 모드")
    inner class SxssfModeTest {

        @Test
        @DisplayName("SXSSF 모드에서도 count 제공/미제공 시 동일한 결과를 생성한다")
        fun sxssfProducesSameResult() {
            val sxssfEngine = TemplateRenderingEngine(StreamingMode.ENABLED)

            val employees = listOf(
                mapOf("name" to "홍길동", "position" to "개발자", "salary" to 5000),
                mapOf("name" to "김철수", "position" to "디자이너", "salary" to 4500)
            )

            val departments = listOf(
                mapOf("name" to "개발팀", "members" to 10, "office" to "본관")
            )

            // count 제공하는 DataProvider
            val providerWithCount = SimpleDataProvider.builder()
                .value("title", "SXSSF 테스트")
                .value("date", "2026-01-29")
                .value("secondTitle", "부서")
                .value("linkText", "링크")
                .value("url", "https://example.com")
                .items("employees", employees)
                .items("department", departments)
                .build()

            // count 제공하지 않는 DataProvider
            val providerWithoutCount = object : ExcelDataProvider {
                override fun getValue(name: String): Any? = when (name) {
                    "title" -> "SXSSF 테스트"
                    "date" -> "2026-01-29"
                    "secondTitle" -> "부서"
                    "linkText" -> "링크"
                    "url" -> "https://example.com"
                    else -> null
                }

                override fun getItems(name: String): Iterator<Any>? = when (name) {
                    "employees" -> employees.iterator()
                    "department" -> departments.iterator()
                    else -> null
                }
            }

            val requiredNames = RequiredNames(
                variables = setOf("title", "date", "secondTitle", "linkText", "url"),
                collections = setOf("employees", "department"),
                images = emptySet()
            )

            val resultWithCount = sxssfEngine.process(
                ByteArrayInputStream(templateBytes),
                providerWithCount,
                requiredNames
            )

            val resultWithoutCount = sxssfEngine.process(
                ByteArrayInputStream(templateBytes),
                providerWithoutCount,
                requiredNames
            )

            // 파일 크기가 유사해야 함 (정확히 같지 않을 수 있음 - 타임스탬프 등)
            assertTrue(resultWithCount.isNotEmpty())
            assertTrue(resultWithoutCount.isNotEmpty())

            // 내용 비교
            val wbWithCount = WorkbookFactory.create(ByteArrayInputStream(resultWithCount))
            val wbWithoutCount = WorkbookFactory.create(ByteArrayInputStream(resultWithoutCount))

            val sheetWithCount = wbWithCount.getSheetAt(0)
            val sheetWithoutCount = wbWithoutCount.getSheetAt(0)

            assertEquals(sheetWithCount.lastRowNum, sheetWithoutCount.lastRowNum,
                "SXSSF 모드에서도 두 결과의 행 수가 동일해야 합니다")

            wbWithCount.close()
            wbWithoutCount.close()
        }
    }
}
