package com.hunet.common.tbeg.engine.rendering

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * 자동 캐싱 컬렉션.
 *
 * count를 미리 알고 있고, 첫 번째 순회 시 데이터를 캐싱합니다.
 * 이후 순회는 캐시에서 읽으므로 여러 번 순회해도 안전합니다.
 *
 * **동작 방식:**
 * 1. 첫 번째 iterator() 호출: 원본 Iterator에서 읽으면서 내부 캐시에 저장
 * 2. 이후 iterator() 호출: 캐시에서 읽음
 *
 * **메모리 특성:**
 * - 순회 전: 메모리 사용 없음 (Iterator만 보유)
 * - 첫 번째 순회 완료 후: 전체 데이터가 메모리에 캐싱됨
 * - 순회 중 중단 시: 그때까지 읽은 데이터만 캐싱됨
 *
 * **사용 조건:**
 * - DataProvider.getItemCount()가 구현되어 있어야 함
 * - SXSSF 모드에서 사용 (XSSF는 List 직접 사용)
 */
internal class AutoCachingCollection(
    private val name: String,
    override val size: Int,
    private val source: Iterator<Any>
) : Collection<Any> {

    private var cache: MutableList<Any>? = null
    private var fullyConsumed = false
    private var currentlyIterating = false

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<Any> {
        // 이미 완전히 순회되었으면 캐시에서 반환
        if (fullyConsumed) {
            return cache!!.iterator()
        }

        // 동시 순회 방지
        if (currentlyIterating) {
            throw IllegalStateException(
                "컬렉션 '$name'이 이미 순회 중입니다. " +
                    "동시 순회는 지원하지 않습니다."
            )
        }

        currentlyIterating = true
        cache = mutableListOf()

        return CachingIterator(source, cache!!) {
            fullyConsumed = true
            currentlyIterating = false
        }
    }

    override fun contains(element: Any): Boolean {
        // 캐시가 있으면 캐시에서 검색
        if (fullyConsumed) {
            return cache!!.contains(element)
        }
        // 없으면 전체 순회하여 검색
        return iterator().asSequence().any { it == element }
    }

    override fun containsAll(elements: Collection<Any>): Boolean {
        if (fullyConsumed) {
            return cache!!.containsAll(elements)
        }
        val set = iterator().asSequence().toSet()
        return set.containsAll(elements)
    }

    /**
     * 원본 Iterator를 순회하면서 캐시에 저장하는 Iterator
     */
    private class CachingIterator(
        private val source: Iterator<Any>,
        private val cache: MutableList<Any>,
        private val onComplete: () -> Unit
    ) : Iterator<Any> {

        override fun hasNext(): Boolean {
            val hasMore = source.hasNext()
            if (!hasMore) {
                onComplete()
            }
            return hasMore
        }

        override fun next(): Any {
            val item = source.next()
            cache.add(item)
            return item
        }
    }
}

/**
 * Iterator를 임시 파일에 버퍼링하여 메모리 효율적으로 처리합니다.
 *
 * DataProvider에서 getItemCount()가 제공되지 않을 때 사용됩니다.
 * Iterator를 한 번 순회하면서 임시 파일에 저장하고, count를 파악합니다.
 * 이후 Collection처럼 사용할 수 있으며, iterator()를 여러 번 호출해도 안전합니다.
 *
 * **메모리 효율성:**
 * - 전체 데이터를 메모리에 유지하지 않음
 * - iterator() 호출 시 임시 파일에서 스트리밍 방식으로 읽음
 * - 대용량 데이터 처리에 적합
 *
 * **직렬화:**
 * - Kryo 라이브러리 사용 (Serializable 구현 불필요)
 * - 대부분의 Java/Kotlin 객체를 직렬화할 수 있음
 *
 * 사용 후 반드시 close()를 호출하여 임시 파일을 삭제해야 합니다.
 */
internal class CollectionBuffer(
    private val name: String
) : Collection<Any>, Closeable {

    private var tempFile: Path? = null
    private var itemCount: Int = 0
    private var buffered: Boolean = false

    companion object {
        private val logger = LoggerFactory.getLogger(CollectionBuffer::class.java)

        // ThreadLocal로 Kryo 인스턴스 관리 (thread-safe)
        private val kryoThreadLocal = ThreadLocal.withInitial {
            Kryo().apply {
                isRegistrationRequired = false
                // 알려지지 않은 클래스도 직렬화 가능
                setReferences(true)
                // 기본 생성자가 없는 클래스도 직렬화 가능 (local class, data class 등)
                instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())
            }
        }

        /**
         * 여러 컬렉션을 버퍼링합니다.
         */
        fun bufferAll(collections: Map<String, Iterator<Any>>): Map<String, CollectionBuffer> {
            return collections.mapValues { (name, iterator) ->
                CollectionBuffer(name).also { it.buffer(iterator) }
            }
        }

        /**
         * 모든 버퍼를 닫습니다.
         */
        fun closeAll(buffers: Collection<CollectionBuffer>) {
            buffers.forEach { buffer ->
                runCatching { buffer.close() }
            }
        }
    }

    /**
     * Iterator를 버퍼링하고 count를 반환합니다.
     *
     * Iterator를 한 번만 순회하면서 임시 파일에 저장합니다.
     * 메모리에 전체 데이터를 올리지 않으며, 순회 완료 후 count를 알 수 있습니다.
     *
     * @param iterator 버퍼링할 Iterator
     * @return 아이템 수
     */
    fun buffer(iterator: Iterator<Any>): Int {
        if (buffered) {
            throw IllegalStateException("이미 버퍼링되었습니다: $name")
        }

        tempFile = Files.createTempFile("tbeg_collection_${name}_", ".tmp")
        var count = 0
        val kryo = kryoThreadLocal.get()

        try {
            Output(Files.newOutputStream(tempFile!!).buffered()).use { output ->
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    kryo.writeClassAndObject(output, item)
                    count++
                }
            }
        } catch (e: Exception) {
            // 버퍼링 실패 시 임시 파일 삭제
            runCatching { Files.deleteIfExists(tempFile!!) }
            tempFile = null
            throw e
        }

        itemCount = count
        buffered = true
        logger.debug("컬렉션 '{}' 버퍼링 완료: {}건 -> {}", name, count, tempFile)
        return count
    }

    // ========== Collection 인터페이스 구현 ==========

    override val size: Int
        get() {
            if (!buffered) {
                throw IllegalStateException("아직 버퍼링되지 않았습니다: $name")
            }
            return itemCount
        }

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<Any> {
        if (!buffered) {
            throw IllegalStateException("아직 버퍼링되지 않았습니다: $name")
        }
        if (itemCount == 0) {
            return emptyList<Any>().iterator()
        }
        return KryoBufferedIterator(tempFile!!, itemCount)
    }

    override fun contains(element: Any): Boolean {
        return iterator().asSequence().any { it == element }
    }

    override fun containsAll(elements: Collection<Any>): Boolean {
        val set = iterator().asSequence().toSet()
        return set.containsAll(elements)
    }

    // ========== Closeable 구현 ==========

    override fun close() {
        tempFile?.let { file ->
            runCatching {
                Files.deleteIfExists(file)
                logger.debug("컬렉션 '{}' 임시 파일 삭제: {}", name, file)
            }
        }
        tempFile = null
    }

    /**
     * 임시 파일에서 Kryo로 데이터를 읽는 Iterator
     */
    private class KryoBufferedIterator(
        private val tempFile: Path,
        private val totalCount: Int
    ) : Iterator<Any> {
        private var input: Input? = null
        private var kryo: Kryo? = null
        private var readCount = 0
        private var closed = false

        override fun hasNext(): Boolean {
            if (closed) return false
            val hasMore = readCount < totalCount
            if (!hasMore) {
                closeStream()
            }
            return hasMore
        }

        override fun next(): Any {
            if (closed || readCount >= totalCount) {
                throw NoSuchElementException()
            }

            if (input == null) {
                kryo = kryoThreadLocal.get()
                input = Input(Files.newInputStream(tempFile).buffered())
            }

            val item = kryo!!.readClassAndObject(input!!)
            readCount++

            if (readCount >= totalCount) {
                closeStream()
            }

            return item
        }

        private fun closeStream() {
            if (!closed) {
                input?.close()
                input = null
                kryo = null
                closed = true
            }
        }
    }
}

/**
 * CollectionBuffer 관리자
 *
 * 여러 컬렉션의 버퍼를 관리하고, 모든 버퍼를 한 번에 닫을 수 있습니다.
 */
internal class CollectionBufferManager : Closeable {
    private val buffers = mutableMapOf<String, CollectionBuffer>()

    /**
     * 컬렉션을 버퍼링합니다.
     *
     * @param name 컬렉션 이름
     * @param iterator 버퍼링할 Iterator
     * @return 버퍼 (Collection으로 사용 가능)
     */
    fun buffer(name: String, iterator: Iterator<Any>): CollectionBuffer {
        val buffer = CollectionBuffer(name)
        buffer.buffer(iterator)
        buffers[name] = buffer
        return buffer
    }

    /**
     * 버퍼를 가져옵니다.
     */
    fun get(name: String): CollectionBuffer? = buffers[name]

    /**
     * 버퍼링된 컬렉션의 count를 반환합니다.
     */
    fun getCount(name: String): Int? = buffers[name]?.size

    /**
     * 모든 버퍼를 닫고 임시 파일을 삭제합니다.
     */
    override fun close() {
        CollectionBuffer.closeAll(buffers.values)
        buffers.clear()
    }
}
