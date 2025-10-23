package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.sequence.SequenceGenerator
import com.hunet.common.data.jpa.sequence.applySequentialCode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SequenceGeneratorDirectTest {
    @Test
    fun generateSequentialCodeForSeqEntity() {
        // given
        val seqGen = object : SequenceGenerator {
            private var counter = 0L
            override fun generateKey(prefix: String, entity: Any?): Any? = prefix + (++counter)
        }
        val entity = SeqEntity() // code 초기값 null
        // when
        applySequentialCode(entity, seqGen)
        // then
        assertNotNull(entity.code)
        assertTrue(entity.code!!.startsWith("PX-"), "Generated code should start with PX-")
    }
}

