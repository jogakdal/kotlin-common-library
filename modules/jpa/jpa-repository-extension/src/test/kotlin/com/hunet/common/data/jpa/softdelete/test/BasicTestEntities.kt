package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository
import com.hunet.common.data.jpa.softdelete.annotation.DeleteMark
import com.hunet.common.data.jpa.softdelete.internal.DeleteMarkValue
import jakarta.persistence.*
import java.time.LocalDateTime
import org.hibernate.annotations.Where

/* 기본 기능 검증용 통합 엔티티 모음 (AliveYesEntity, StrictTestEntity, NotNullAliveEntity, WhereDuplicatedEntity) */

@Entity
@Table(name = "alive_yes_entity")
class AliveYesEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // YES = alive, NO = deleted
    @DeleteMark(aliveMark = DeleteMarkValue.YES, deletedMark = DeleteMarkValue.NO)
    @Column(name = "status", nullable = false)
    var status: String? = null,

    var name: String? = null,
)

interface AliveYesEntityRepository : SoftDeleteJpaRepository<AliveYesEntity, Long>

@Entity
@Table(name = "strict_test_entity")
class StrictTestEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @Column(name = "val_str")
    var value: String? = null,
)

interface StrictTestEntityRepository : SoftDeleteJpaRepository<StrictTestEntity, Long>

/* NOT_NULL alive mark 엔티티 (alive: statusTs NOT NULL) */
@Entity
@Table(name = "notnull_mark_entity")
class NotNullAliveEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NOT_NULL, deletedMark = DeleteMarkValue.NULL)
    @Column(name = "status_ts")
    var statusTs: LocalDateTime? = LocalDateTime.now(),

    var info: String? = null,
)

interface NotNullAliveEntityRepository : SoftDeleteJpaRepository<NotNullAliveEntity, Long>

/* @Where 중복 alive 필터 경고 확인용 */
@Entity
@Table(name = "where_entity")
@Where(clause = "deleted_at IS NULL")
class WhereDuplicatedEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @DeleteMark(aliveMark = DeleteMarkValue.NULL, deletedMark = DeleteMarkValue.NOW)
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    var label: String? = null,
)

interface WhereDuplicatedEntityRepository : SoftDeleteJpaRepository<WhereDuplicatedEntity, Long>
