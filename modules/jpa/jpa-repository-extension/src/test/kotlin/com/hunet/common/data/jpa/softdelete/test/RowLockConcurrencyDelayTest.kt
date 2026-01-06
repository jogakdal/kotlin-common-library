@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
class RowLockConcurrencyDelayTest {
    @Autowired
    lateinit var repo: TestEntityRepository

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    /**
     * 트랜잭션 A가 특정 행에 대해 PESSIMISTIC WRITE 락을 잡고 있는 동안
     * 트랜잭션 B가 동일 행에 대한 쓰기(업데이트)를 시도하면 완료 시간이 지연되는지 측정한다.
     */
    @Test
    fun `동시 락 경합 시 지연이 발생한다`() {
        val target = repo.upsert(TestEntity(name = "lock-delay-target"))
        val startGate = CountDownLatch(1)
        val lockAcquiredLatch = CountDownLatch(1)
        val doneGate = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        var bElapsedMs = -1L
        val tx = TransactionTemplate(txManager)

        // 트랜잭션 A: 락을 잡고 일정 시간 유지
        pool.submit {
            startGate.await(2, TimeUnit.SECONDS)
            tx.execute {
                repo.rowLockById(target.id!!) { _: TestEntity ->
                    // 락 획득 신호
                    lockAcquiredLatch.countDown()
                    Thread.sleep(1500)
                    true
                }
            }
            doneGate.countDown()
        }
        // 트랜잭션 B: 동일 행에 대한 실제 업데이트 시도, 완료 시간 측정
        pool.submit {
            startGate.await(2, TimeUnit.SECONDS)
            // A가 락을 획득한 후 시작
            lockAcquiredLatch.await(2, TimeUnit.SECONDS)
            val t0 = System.nanoTime()
            tx.execute {
                repo.updateById(target.id!!) { it.name = "updated-by-B" }
            }
            val t1 = System.nanoTime()
            bElapsedMs = TimeUnit.NANOSECONDS.toMillis(t1 - t0)
            doneGate.countDown()
        }

        // 동시에 시작
        startGate.countDown()
        // 두 작업 완료 기다림
        doneGate.await(6, TimeUnit.SECONDS)
        pool.shutdownNow()

        // then: B가 최소 1000ms 이상 지연되었음을 기대
        assertTrue(bElapsedMs >= 1000, "동시 락 경합 시 지연이 발생해야 합니다. 측정 지연=$bElapsedMs ms")
    }
}
