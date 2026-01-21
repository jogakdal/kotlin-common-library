package com.hunet.common.excel

import java.util.function.Supplier

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
    private val images: Map<String, ByteArray>,
    private val metadata: DocumentMetadata?
) : ExcelDataProvider {

    override fun getValue(name: String): Any? = values[name]

    override fun getItems(name: String): Iterator<Any>? = collections[name]?.invoke()

    override fun getImage(name: String): ByteArray? = images[name]

    override fun getAvailableNames(): Set<String> =
        values.keys + collections.keys + images.keys

    override fun getMetadata(): DocumentMetadata? = metadata

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
            val (iterables, others) = data.entries.partition { (_, v) ->
                v is Iterable<*> || v is Iterator<*> || v is Sequence<*>
            }

            val values = others.associate { it.key to it.value }
            val collections = iterables.associate { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                key to when (value) {
                    is Iterable<*> -> { -> (value as Iterable<Any>).iterator() }
                    is Iterator<*> -> { -> value as Iterator<Any> }
                    is Sequence<*> -> { -> (value as Sequence<Any>).iterator() }
                    else -> throw IllegalStateException("Unexpected type: ${value::class}")
                }
            }

            return SimpleDataProvider(values, collections, emptyMap(), null)
        }

        /**
         * 빈 SimpleDataProvider를 반환합니다.
         */
        @JvmStatic
        fun empty(): SimpleDataProvider = SimpleDataProvider(emptyMap(), emptyMap(), emptyMap(), null)

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
        private var metadata: DocumentMetadata? = null

        /** 단일 값을 추가합니다. */
        fun value(name: String, value: Any) = apply { values[name] = value }

        /** 컬렉션을 추가합니다. (즉시 로딩) */
        fun items(name: String, items: Iterable<Any>) = apply {
            collections[name] = { items.iterator() }
        }

        /** 컬렉션을 추가합니다. (지연 로딩 - Kotlin) */
        fun items(name: String, itemsSupplier: () -> Iterator<Any>) = apply {
            collections[name] = itemsSupplier
        }

        /** 컬렉션을 추가합니다. (지연 로딩 - Java Supplier) */
        fun itemsFromSupplier(name: String, itemsSupplier: Supplier<Iterator<Any>>) = apply {
            collections[name] = { itemsSupplier.get() }
        }

        /** 이미지를 추가합니다. */
        fun image(name: String, imageData: ByteArray) = apply { images[name] = imageData }

        /** 문서 메타데이터를 설정합니다. (Kotlin DSL) */
        fun metadata(block: DocumentMetadataBuilder.() -> Unit) = apply {
            this.metadata = DocumentMetadataBuilder().apply(block).build()
        }

        /** 문서 메타데이터를 설정합니다. (Java Consumer) */
        fun metadata(configurer: java.util.function.Consumer<DocumentMetadata.Builder>) = apply {
            this.metadata = DocumentMetadata.Builder().also { configurer.accept(it) }.build()
        }

        /** 문서 메타데이터를 설정합니다. (직접 설정) */
        fun metadata(metadata: DocumentMetadata) = apply { this.metadata = metadata }

        /** SimpleDataProvider를 빌드합니다. */
        fun build() = SimpleDataProvider(values, collections, images, metadata)
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
