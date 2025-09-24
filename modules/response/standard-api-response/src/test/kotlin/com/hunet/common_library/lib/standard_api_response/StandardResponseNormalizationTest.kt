package com.hunet.common_library.lib.standard_api_response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

@Serializable
data class UserPayload(
    val userId: Long = 0,
    val userName: String = "",
    val emailAddress: String = ""
): BasePayload

class StandardResponseNormalizationTest {

    @Test
    fun `혼합 대소문자 상위 키 역직렬화`() {
        val json = """
            {
              "STATUS": "success",
              "version": "1.2",
              "date_time": "2025-09-16T10:00:00Z",
              "DuRaTiOn": 55,
              "PAY_load": {
                "user_id": 10,
                "user_name": "황용호",
                "email-address": "jogakdal@gmail.com"
              }
            }
        """.trimIndent()

        val resp = StandardResponse.deserialize<UserPayload>(json)
        assertEquals(StandardStatus.SUCCESS, resp.status)
        assertEquals("1.2", resp.version)
        assertEquals(55, resp.duration)
        val p = resp.payload
        assertEquals(10, p.userId)
        assertEquals("황용호", p.userName)
        assertEquals("jogakdal@gmail.com", p.emailAddress)
    }

    @Test
    fun `kebab과 snake 혼합 타입 지정 역직렬화`() {
        val json = """
            {
              "failure": "should_be_ignored",
              "Status": "SUCCESS",
              "DATE_TIME": "2025-09-16T11:11:11Z",
              "DURATION": 123,
              "pay-load": {
                "USER-ID": 99,
                "USER_NAME": "황용호",
                "E-MAIL_ADDRESS": "jogakdal@gmail.com"
              }
            }
        """.trimIndent()

        val resp = StandardResponse.deserialize(json, UserPayload::class.java)
        assertEquals(StandardStatus.SUCCESS, resp.status)
        assertEquals("1.0", resp.version) // version absent => default
        assertEquals(123, resp.duration)
        val p = resp.payload
        assertEquals(99, p.userId)
        assertEquals("황용호", p.userName)
        assertEquals("jogakdal@gmail.com", p.emailAddress)
    }

    @Test
    fun `payload 키 변형 pay_load 처리`() {
        val json = """
            {
                "status":"SUCCESS",
                "version":"2.0",
                "datetime":"2025-09-16T00:00:00Z",
                "duration":1,
                "pay_load":{
                    "user-id":1,
                    "user-name":"황용호",
                    "email_address":"jogakdal@gmail.com"
                }
            }
        """.trimIndent()
        val resp = StandardResponse.deserialize<UserPayload>(json)
        with(resp.payload) {
            assertEquals(1, userId)
            assertEquals("황용호", userName)
            assertEquals("jogakdal@gmail.com", emailAddress)
        }
    }
}
