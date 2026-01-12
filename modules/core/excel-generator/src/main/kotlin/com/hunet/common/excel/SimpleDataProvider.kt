package com.hunet.common.excel

/**
 * [ExcelDataProvider]의 기본 구현체.
 *
 * Map 기반으로 데이터를 제공하며, 컬렉션 타입(List, Set 등)은 자동으로
 * [getItems]에서 Iterator로 반환됩니다.
 *
 * @param values 단일 값 맵 (Iterable이 아닌 값들)
 * @param collections 컬렉션 제공 함수 맵 (지연 로딩)
 * @param images 이미지 데이터 맵
 */
class SimpleDataProvider private constructor(
    private val values: Map<String, Any>,
    private val collections: Map<String, () -> Iterator<Any>>,
    private val images: Map<String, ByteArray>
) : ExcelDataProvider {

    override fun getValue(name: String): Any? = values[name]

    override fun getItems(name: String): Iterator<Any>? = collections[name]?.invoke()

    override fun getImage(name: String): ByteArray? = images[name]

    override fun getAvailableNames(): Set<String> =
        values.keys + collections.keys + images.keys

    companion object {
        /**
         * Map으로부터 SimpleDataProvider를 생성합니다.
         *
         * Map의 값이 Iterable인 경우 자동으로 컬렉션으로 분류됩니다.
         *
         * ```kotlin
         * val provider = SimpleDataProvider.of(mapOf(
         *     "title" to "월별 보고서",
         *     "employees" to listOf(emp1, emp2, emp3)
         * ))
         * ```
         *
         * @param data 데이터 맵
         * @return SimpleDataProvider 인스턴스
         */
        @JvmStatic
        fun of(data: Map<String, Any>): SimpleDataProvider {
            val values = mutableMapOf<String, Any>()
            val collections = mutableMapOf<String, () -> Iterator<Any>>()

            data.forEach { (key, value) ->
                when (value) {
                    is Iterable<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        collections[key] = { (value as Iterable<Any>).iterator() }
                    }
                    is Iterator<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        collections[key] = { value as Iterator<Any> }
                    }
                    is Sequence<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        collections[key] = { (value as Sequence<Any>).iterator() }
                    }
                    else -> values[key] = value
                }
            }

            return SimpleDataProvider(values, collections, emptyMap())
        }

        /**
         * 빈 SimpleDataProvider를 반환합니다.
         */
        @JvmStatic
        fun empty(): SimpleDataProvider = SimpleDataProvider(emptyMap(), emptyMap(), emptyMap())

        /**
         * Builder를 반환합니다. (Java에서 사용하기 편리)
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * SimpleDataProvider 빌더.
     */
    class Builder {
        private val values = mutableMapOf<String, Any>()
        private val collections = mutableMapOf<String, () -> Iterator<Any>>()
        private val images = mutableMapOf<String, ByteArray>()

        /**
         * 단일 값을 추가합니다.
         */
        fun value(name: String, value: Any): Builder {
            values[name] = value
            return this
        }

        /**
         * 컬렉션을 추가합니다. (즉시 로딩)
         */
        fun items(name: String, items: Iterable<Any>): Builder {
            collections[name] = { items.iterator() }
            return this
        }

        /**
         * 컬렉션을 추가합니다. (지연 로딩 - Kotlin)
         */
        fun items(name: String, itemsSupplier: () -> Iterator<Any>): Builder {
            collections[name] = itemsSupplier
            return this
        }

        /**
         * 컬렉션을 추가합니다. (지연 로딩 - Java Supplier)
         */
        fun itemsFromSupplier(name: String, itemsSupplier: java.util.function.Supplier<Iterator<Any>>): Builder {
            collections[name] = { itemsSupplier.get() }
            return this
        }

        /**
         * 이미지를 추가합니다.
         */
        fun image(name: String, imageData: ByteArray): Builder {
            images[name] = imageData
            return this
        }

        /**
         * SimpleDataProvider를 빌드합니다.
         */
        fun build(): SimpleDataProvider = SimpleDataProvider(values, collections, images)
    }
}

/**
 * SimpleDataProvider를 DSL 방식으로 생성합니다.
 *
 * ```kotlin
 * val provider = simpleDataProvider {
 *     value("title", "월별 보고서")
 *     items("employees") { employeeRepository.streamAll().iterator() }
 *     image("logo", logoBytes)
 * }
 * ```
 */
fun simpleDataProvider(block: SimpleDataProvider.Builder.() -> Unit): SimpleDataProvider =
    SimpleDataProvider.Builder().apply(block).build()
