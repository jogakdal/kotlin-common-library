package com.hunet.common.tbeg.benchmark

import org.jxls.builder.JxlsStreaming
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder
import org.openjdk.jmh.annotations.*
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * 벤치마크 4: JXLS 비교 (TBEG과 동일한 POI 5.5.1 스택·동일 워크로드)
 *
 * 공정 비교를 위해 TBEG의 DataModeBenchmark와 동일한 조건에서 JXLS를 측정한다.
 * - 워크로드: 3컬럼 DOWN 반복 + SUM 수식 (BenchmarkSupport.createJxlsTemplate)
 * - 데이터: TBEG과 동일한 BenchmarkSupport.createMapData (Map)
 * - jxlsMemory: STREAMING_OFF (XSSF) — TBEG map()과 대응
 * - jxlsStreaming: STREAMING_ON (SXSSF) — TBEG dataProvider()와 대응
 * - 출력: buildAndFill(Map)이 반환하는 ByteArray (TBEG generate()와 대응)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Suppress("unused")
open class JxlsComparisonBenchmark {

    @Param("1000", "10000", "30000", "50000", "100000")
    open var rowCount: Int = 0

    private lateinit var templateBytes: ByteArray

    @Setup(Level.Trial)
    fun setup() {
        templateBytes = BenchmarkSupport.createJxlsTemplate()
    }

    @Benchmark
    fun jxlsMemory(): ByteArray = fill(JxlsStreaming.STREAMING_OFF)

    @Benchmark
    fun jxlsStreaming(): ByteArray = fill(JxlsStreaming.STREAMING_ON)

    private fun fill(streaming: JxlsStreaming): ByteArray =
        JxlsPoiTemplateFillerBuilder.newInstance()
            .withTemplate(ByteArrayInputStream(templateBytes))
            .withStreaming(streaming)
            .buildAndFill(BenchmarkSupport.createMapData(rowCount))
}
