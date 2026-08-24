package com.hunet.common.tbeg.benchmark

import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.results.AggregationPolicy
import org.openjdk.jmh.results.IterationResult
import org.openjdk.jmh.results.Result
import org.openjdk.jmh.results.ScalarResult
import java.lang.management.ManagementFactory

/**
 * JMH 커스텀 프로파일러: 힙 사용 피크치 측정.
 *
 * 백그라운드 샘플링 스레드로 iteration 동안 힙 사용량(`getHeapMemoryUsage().used`)을
 * 짧은 간격(0.5ms)으로 관측하여 최대값(피크)을 보고한다.
 * `gc.alloc.rate.norm`(처리 중 누적 할당량)과 달리, **어느 한 시점의 최대 힙 사용량(피크 힙)**을 나타낸다.
 * 스트리밍(SXSSF)의 피크 힙 억제 효과를 이 지표로 확인할 수 있다.
 */
class PeakMemoryProfiler : InternalProfiler {

    private val heapBean = ManagementFactory.getMemoryMXBean()

    @Volatile private var peakBytes = 0L
    @Volatile private var running = false
    private var sampler: Thread? = null

    override fun getDescription() = "Peak heap usage profiler"

    override fun beforeIteration(benchmarkParams: BenchmarkParams, iterationParams: IterationParams) {
        peakBytes = 0L
        running = true
        sampler = Thread {
            while (running) {
                val used = heapBean.heapMemoryUsage.used
                if (used > peakBytes) peakBytes = used
                try {
                    Thread.sleep(0, 500_000) // 0.5ms
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "peak-mem-sampler"
            start()
        }
    }

    override fun afterIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
        result: IterationResult
    ): Collection<Result<*>> {
        running = false
        sampler?.join(200)
        return listOf(
            ScalarResult("mem.peak.heap", peakBytes.toDouble(), "bytes", AggregationPolicy.MAX)
        )
    }
}
