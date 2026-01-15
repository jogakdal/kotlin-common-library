package com.hunet.common.data.jpa

import jakarta.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TableExtensions에 대한 단위 테스트:
 * 1) SINGLE_TABLE 상속 전략에서 자식 엔티티가 @Table 미지정 시 resolvedTableName fallback.
 * 2) nativeTableName은 직접/슈퍼 한 단계(@Table)만 허용하므로 자식 인스턴스에서 예외 발생.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@Table(name = "single_table_root")
open class SingleTableRoot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var common: String? = null,
)

@Entity
class SingleTableChild(
    common: String? = null
): SingleTableRoot(common = common)

class TableExtensionsTest {
    @Test
    fun resolvedTableName_fallbackToRootForSingleTableChild() {
        assertEquals("single_table_root", SingleTableChild::class.java.resolvedTableName)
    }

    @Test
    fun nativeTableName_fallbackToRootForSingleTableChild() {
        val child = SingleTableChild()
        assertEquals("single_table_root", child.nativeTableName)
    }
}
