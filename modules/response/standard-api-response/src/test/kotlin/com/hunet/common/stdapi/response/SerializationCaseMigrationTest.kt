@file:Suppress("NonAsciiCharacters", "SpellCheckingInspection")
package com.hunet.common.stdapi.response

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.hunet.common.util.getAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

class SerializationCaseMigrationTest {

    @Test
    fun `getAnnotation을 사용한 JsonProperty annotation 검색이 정상 동작한다`() {
        data class TestPayload(
            @JsonProperty("custom_name")
            val userName: String,

            val normalField: String
        )

        val properties = TestPayload::class.memberProperties
        val userNameProp = properties.first { it.name == "userName" }
        val normalProp = properties.first { it.name == "normalField" }

        val jsonPropertyAnnotation = userNameProp.getAnnotation<JsonProperty>()
        val normalAnnotation = normalProp.getAnnotation<JsonProperty>()

        assertNotNull(jsonPropertyAnnotation)
        assertEquals("custom_name", jsonPropertyAnnotation?.value)

        assertNull(normalAnnotation)
    }

    @Test
    fun `getAnnotation을 사용한 JsonAlias annotation 검색이 정상 동작한다`() {
        data class TestPayload(
            @JsonAlias("old_name", "legacy_name")
            val newName: String,

            val simpleField: String
        )

        val properties = TestPayload::class.memberProperties
        val newNameProp = properties.first { it.name == "newName" }
        val simpleProp = properties.first { it.name == "simpleField" }

        val jsonAliasAnnotation = newNameProp.getAnnotation<JsonAlias>()
        val simpleAnnotation = simpleProp.getAnnotation<JsonAlias>()

        assertNotNull(jsonAliasAnnotation)
        assertArrayEquals(arrayOf("old_name", "legacy_name"), jsonAliasAnnotation?.value)

        assertNull(simpleAnnotation)
    }

    @Test
    fun `getAnnotation을 사용한 NoCaseTransform annotation 검색이 정상 동작한다`() {
        data class TestPayload(
            @NoCaseTransform
            val KeepCaseField: String,

            val normalField: String
        )

        val properties = TestPayload::class.memberProperties
        val keepCaseProp = properties.first { it.name == "KeepCaseField" }
        val normalProp = properties.first { it.name == "normalField" }

        val noCaseAnnotation = keepCaseProp.getAnnotation<NoCaseTransform>()
        val normalAnnotation = normalProp.getAnnotation<NoCaseTransform>()

        assertNotNull(noCaseAnnotation)
        assertNull(normalAnnotation)
    }

    @Test
    fun `빈 값 처리 로직이 getAnnotation으로 정상 동작한다`() {
        data class EmptyValuePayload(
            @JsonProperty("")
            val emptyPropertyName: String,

            @JsonProperty("valid_name")
            val validPropertyName: String,

            @JsonAlias("", "valid_alias")  // 빈 문자열이 포함된 aliases
            val mixedAliases: String
        )

        val properties = EmptyValuePayload::class.memberProperties
        val emptyProp = properties.first { it.name == "emptyPropertyName" }
        val validProp = properties.first { it.name == "validPropertyName" }
        val mixedProp = properties.first { it.name == "mixedAliases" }

        val emptyJsonProperty = emptyProp.getAnnotation<JsonProperty>()?.value?.takeUnless { it.isBlank() }
        val validJsonProperty = validProp.getAnnotation<JsonProperty>()?.value?.takeUnless { it.isBlank() }
        val mixedAliases = mixedProp.getAnnotation<JsonAlias>()?.value?.filterNot { it.isBlank() } ?: emptyList()

        assertNull(emptyJsonProperty, "빈 문자열은 null로 처리되어야 한다.")
        assertEquals("valid_name", validJsonProperty)
        assertEquals(listOf("valid_alias"), mixedAliases, "빈 문자열은 필터링되어야 한다.")
    }

    @Test
    fun `annotation이 없는 경우 null을 반환한다`() {
        data class PlainPayload(
            val name: String,
            val value: Int
        )

        val prop = PlainPayload::class.memberProperties.first { it.name == "name" }

        assertNull(prop.getAnnotation<JsonProperty>())
        assertNull(prop.getAnnotation<JsonAlias>())
        assertNull(prop.getAnnotation<NoCaseTransform>())
    }
}
