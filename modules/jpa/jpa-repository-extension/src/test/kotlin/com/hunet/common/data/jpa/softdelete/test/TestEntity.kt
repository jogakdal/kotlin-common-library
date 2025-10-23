package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "test_entity")
class TestEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,
    var name: String? = null
)
