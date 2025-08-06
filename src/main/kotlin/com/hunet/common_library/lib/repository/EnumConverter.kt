package com.hunet.common_library.lib.repository

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import kotlin.reflect.full.companionObjectInstance

/**
 * GenericEnumConverter<E, V>
 *
 * 이 추상 JPA AttributeConverter는 데이터베이스의 컬럼 타입 V와 enum 클래스 E 간의 매핑을
 * 리플렉션을 통해 처리하는 범용 컨버터입니다.
 *
 * E에 대한 요구사항:
 * - enum 클래스여야 합니다.
 * - `value: V` 프로퍼티 또는 `getValue(): V` 메서드를 선언해야 합니다.
 * - companion object에 `fromValue(value: V): E` 메서드를 정의해야 합니다.
 *
 * 사용 예:
 * ```
 * @Converter(autoApply = true)
 * class MyEnumConverter : GenericEnumConverter<MyEnum, String>(MyEnum::class.java, String::class.java)
 * ```
 * 작성 후 JPA는 `MyEnum.value`를 DB에 저장하고, 조회 시 `MyEnum.fromValue(...)`으로 enum을 복원합니다.
 *
 * 동작:
 * - 요구된 `value` 또는 `fromValue` 멤버가 없으면 런타임에 IllegalStateException을 발생시킵니다.
 * - 이 추상 클래스는 `@Converter(autoApply = false)`로 설정되어 있으며,
 *   자동 적용을 위해서는 서브클래스에서 `@Converter(autoApply = true)`를 지정해야 합니다.
 */
@Converter(autoApply = false)
@Suppress("UNCHECKED_CAST")
abstract class GenericEnumConverter<E : Enum<E>, V : Any>(
    private val enumType: Class<E>,
    private val valueType: Class<V> = String::class.java as Class<V>
) : AttributeConverter<E, V> {
    override fun convertToDatabaseColumn(attribute: E?): V? =
        attribute?.let {
            try {
                val method = enumType.getMethod("getValue")
                @Suppress("UNCHECKED_CAST")
                method.invoke(it) as V
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException("Enum ${enumType.simpleName} must have value property or getValue()", e)
            }
        }

    override fun convertToEntityAttribute(dbData: V?): E? =
        dbData?.let { value ->
            try {
                val companion = enumType.kotlin.companionObjectInstance
                    ?: throw IllegalStateException("Enum ${enumType.simpleName} has no companion object")
                val method = companion::class.java.getMethod("fromValue", valueType)
                @Suppress("UNCHECKED_CAST")
                method.invoke(companion, value) as E
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException("Companion of ${enumType.simpleName} must have fromValue(${valueType.simpleName})", e)
            }
        }
}
