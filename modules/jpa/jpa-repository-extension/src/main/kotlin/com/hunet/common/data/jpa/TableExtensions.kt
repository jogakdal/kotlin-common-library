package com.hunet.common.data.jpa

import jakarta.persistence.Table
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import com.hunet.common.util.getAnnotation
import com.hunet.common.util.getDirectAnnotation
import kotlin.reflect.KClass

/**
 * 엔티티 클래스의 @Table(name=…) 값을 반환합니다.
 * - 프록시(CGLIB) 가능성 고려: 현재 클래스에 없으면 즉시 슈퍼 클래스(Kotlin KClass 변환)에서 한 번 더 조회.
 * - getAnnotation (통합 탐색) 대신 KClass 직접 선언 우선 + 슈퍼 클래스 fallback.
 *   필요 시 더 깊은 상속 체인 탐색은 의도적으로 제외(과도한 비용/예상치 못한 상속 혼동 방지).
 * - 직접 선언 여부만 확인해도 되는 단순 경로이므로 getDirectAnnotation 사용 후 null일 때 통합 탐색(getAnnotation) 시도.
 */
inline val Any.nativeTableName: String
    get() {
        val kClass = this::class
        // @field / @property 상관없이 KClass에 올라온 어노테이션 중 직접 선언
        kClass.getDirectAnnotation<Table>()?.name?.let { return it }
        // 통합 탐색(프록시 등으로 누락된 경우) - 현재 클래스
        kClass.getAnnotation<Table>()?.name?.let { return it }
        // 슈퍼 클래스 fallback
        val superK = kClass.java.superclass?.kotlin
        superK?.getDirectAnnotation<Table>()?.name?.let { return it }
        superK?.getAnnotation<Table>()?.name?.let { return it }
        throw IllegalStateException("엔티티 ${kClass.simpleName}에 @Table(name=…)이 없습니다")
    }

/**
 * Class 레벨에서 테이블명 결정.
 * - 직접 @Table 있으면 사용.
 * - 없고 @Inheritance(SINGLE_TABLE) 전략이면 상위 클래스 @Table fallback.
 * - 그 외에는 명시적 @Table 강제.
 */
val Class<*>.resolvedTableName: String
    get() {
        // 현재 클래스 직접 선언 @Table
        this.getAnnotation<Table>()?.name?.let { return it }
        // SINGLE_TABLE 전략 루트(@Inheritance SINGLE_TABLE + @Table) 슈퍼클래스 탐색
        val singleTableRoot = generateSequence(this.superclass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .firstOrNull { sc ->
                sc.getAnnotation<Inheritance>()?.strategy == InheritanceType.SINGLE_TABLE &&
                        sc.getAnnotation<Table>() != null
            }
        singleTableRoot?.getAnnotation<Table>()?.name?.let { return it }
        throw IllegalStateException(
            "Entity ${this.simpleName} must declare @Table(name=...) (or inherit SINGLE_TABLE root)"
        )
    }
val KClass<*>.resolvedTableName: String get() = this.java.resolvedTableName
