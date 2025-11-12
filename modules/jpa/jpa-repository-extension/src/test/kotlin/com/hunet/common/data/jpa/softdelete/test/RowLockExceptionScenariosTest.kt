@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.data.jpa.softdelete.test

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RowLockExceptionScenariosTest {
    @Autowired
    lateinit var testEntityRepository: TestEntityRepository

    @Test
    fun `존재하지 않는 아이디로 rowLockById 호출 시 NoSuchElementException 발생`() {
        assertThrows(NoSuchElementException::class.java) {
            testEntityRepository.rowLockById(-9999L) { _: TestEntity -> /* no-op */ Unit }
        }
    }

    @Test
    fun `존재하지 않는 값으로 rowLockByField 호출 시 NoSuchElementException 발생`() {
        assertThrows(NoSuchElementException::class.java) {
            testEntityRepository.rowLockByField("name", "__never__") { e: TestEntity -> e }
        }
    }

    @Test
    fun `존재하지 않는 맵으로 rowLockByFields 호출 시 NoSuchElementException 발생`() {
        assertThrows(NoSuchElementException::class.java) {
            testEntityRepository.rowLockByFields(mapOf("name" to "__never__")) { e: TestEntity -> e }
        }
    }

    @Test
    fun `존재하지 않는 조건으로 rowLockByCondition 호출 시 NoSuchElementException 발생`() {
        assertThrows(NoSuchElementException::class.java) {
            testEntityRepository.rowLockByCondition("e.name = '__never__'") { e: TestEntity -> e }
        }
    }
}
