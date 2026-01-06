# SoftDeleteJpaRepository 예제/레시피

## 문서 간 교차 링크
- [사용자 가이드](./soft-delete-user-guide.md)
- [레퍼런스](./soft-delete-reference.md)
- [마이그레이션](./soft-delete-migration.md)

## 목차(TOC)
1. 단일 엔티티 소프트 삭제/복구
2. 업서트/병합 정책(IGNORE vs OVERWRITE)
3. 관계 3단계 삭제(RECURSIVE vs BULK)
4. 조건 기반 삭제/조회/락
5. 성능 튜닝(배치/인덱스)
6. 예제 코드 모음
7. Appendix

---

## 1. 단일 엔티티 소프트 삭제/복구
권장 DeleteMark 패턴(DATETIME, NOT NULL, 최소값 DEFAULT) 기준 예시입니다.
- 삭제(Soft Delete):
```kotlin
userRepository.softDeleteById(userId)
```
- 복구(Undelete):
```kotlin
userRepository.updateById(userId, mapOf("deleted_at" to LocalDateTime.parse("1000-01-01T00:00:00")))
```
- 조회(Alive만):
```kotlin
val page = userRepository.findAllByCondition(/* 조건 */)
```
주의: `@Query` 혼용 시 Alive 조건 누락에 유의하세요.

## 2. 업서트/병합 정책(IGNORE vs OVERWRITE)
- IGNORE(기본): 입력 값이 null이면 기존 값을 덮어쓰지 않습니다.
```kotlin
userRepository.upsert(user.copy(nickname = null)) // 기존 nickname 유지
```
- OVERWRITE: 입력 값이 null이어도 기존 값을 덮어씁니다.
```kotlin
// 설정: softdelete.upsert.null-merge=OVERWRITE
userRepository.upsert(user.copy(nickname = null)) // nickname 제거(초기화)
```
선택 기준: 데이터 안전(IGNORE) vs 의도적 초기화(OVERWRITE).

## 3. 관계 3단계 삭제(RECURSIVE vs BULK)
- RECURSIVE: 부모 → 자식(→ 손자)까지 삭제 마크를 일관되게 적용.
```kotlin
orderRepository.softDeleteById(orderId) // 자식/손자도 함께 마킹(권장: 관계에 DeleteMark 부여)
```
- BULK: 대상만 일괄 처리(자식은 별도 처리 필요).
```kotlin
orderRepository.softDeleteById(orderId) // 후속 배치로 자식 처리
```
선택 팁: 무결성 우선(RECURSIVE) vs 처리량/속도 우선(BULK).

## 4. 조건 기반 삭제/조회/락
- 필드 기반 삭제:
```kotlin
userRepository.softDeleteByField("email", email)
```
- 다중 필드 조건 삭제:
```kotlin
userRepository.softDeleteByFields(mapOf("companyId" to companyId, "status" to "INACTIVE"))
```
- 조건 객체 기반:
```kotlin
userRepository.softDeleteByCondition(condition)
```
- 락(비관적, PESSIMISTIC_WRITE):
```kotlin
val locked = userRepository.rowLockById(userId)
```
주의: 락 타임아웃/데드락 시 재시도 정책 권장.

## 5. 성능 튜닝(배치/인덱스)
- 업서트 배치 플러시 간격:
```yaml
softdelete:
  upsert-all:
    flush-interval: 50
```
- Alive 인덱스: `CREATE INDEX idx_users_deleted_at ON users (deleted_at)`
- BULK 이후 동기화: `flush/clear` 수행 후 재조회.

## 6. 예제 코드 모음
- 엔티티, 리포지토리, 서비스 사용 예를 한 곳에 모아 재현 가능한 최소 예제로 제공합니다.

```kotlin
// Entity
@Entity
@Table(name = "users")
data class User(
  @Id val id: Long,
  val email: String,
  @Column(name = "deleted_at", nullable = false)
  @DeleteMark(aliveMark = DeleteMarkValue.DATE_TIME_MIN, deletedMark = DeleteMarkValue.NOW)
  val deletedAt: LocalDateTime = LocalDateTime.parse("1000-01-01T00:00:00")
)

// Repository
interface UserRepository : SoftDeleteJpaRepository<User, Long>

// Service usages (예시)
@Service
class UserService(private val userRepository: UserRepository) {
  @Transactional
  fun softDeleteUser(id: Long) {
    userRepository.softDeleteById(id)
  }

  @Transactional
  fun restoreUser(id: Long) {
    userRepository.updateById(id, mapOf("deleted_at" to LocalDateTime.parse("1000-01-01T00:00:00")))
  }

  @Transactional
  fun upsertUsers(users: List<User>) {
    userRepository.upsertAll(users)
  }

  @Transactional(readOnly = true)
  fun findAlivePage(pageable: Pageable) = userRepository.findAllByCondition("", pageable)

  @Transactional
  fun lockForUpdate(id: Long): User = userRepository.rowLockById(id)
}
```

구성 예시(application.yml):
```yaml
softdelete:
  delete:
    strategy: RECURSIVE
  upsert-all:
    flush-interval: 50
  alive-predicate:
    enabled: true
  query:
    strict: true
```

### 6.1 교차 사용 대체 API 미니 스니펫
- findById / existsById 대체
```kotlin
// 기존 (권장하지 않음)
val e1 = repo.findById(id) // 삭제된 행 포함 가능
val has1 = repo.existsById(id)

// 권장(Alive 보장)
val e2 = repo.findOneById(id)
val has2 = repo.existsAliveById(id)
```

- deleteById 대체
```kotlin
// 기존 (실삭제)
// repo.deleteById(id)

// 권장 (소프트 삭제)
repo.softDeleteById(id)
```

- save 대체(upsert 명확화)
```kotlin
// 기존 (save는 null 병합 정책이 불명확)
// repo.save(entity)

// 권장 (업서트 + 명확한 병합 정책)
repo.upsert(entity)
```

- 존재 확인(Alive만)
```kotlin
val alive = repo.existsAliveById(id)
```

## 7. Appendix
- 운영 팁: 삭제 시각 기반 리포트, 주기적 복구 검증 배치, Alive 조건 린터/테스트 룰 도입
- 디버깅 팁: @SQLRestriction 중복 여부 로그 점검, 락/타임아웃 메트릭 수집
