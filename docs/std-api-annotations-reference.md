# Std API Annotations Mini Reference

> NOTE: `@RequestDescription` / `@SwaggerDescribable` 는 모두 Deprecated 상태입니다. 향후 버전에서 제거 예정이며 **클래스/필드 문서화에는 표준 `@Schema` (필요 시 필드에는 `@Schema` + 간단한 설명만) 사용을 권장합니다.**

`std-api-annotations` 모듈은 표준 응답/문서화 체계에서 재사용되는 **어노테이션 & Enum 직렬화 규약**을 제공합니다. 이 문서는 간단 요약 / 시그니처 / 사용 패턴 / 주의 사항에 집중한 미니 레퍼런스입니다.

관련 문서:
- [standard-api-response-reference.md](./standard-api-response-reference.md)
- [standard-api-response-library-guide.md](./standard-api-response-library-guide.md)

---
## 1. 제공 어노테이션 & 인터페이스 개요
| 이름 | 타입 | 대상 | 용도 요약 |
|------|------|------|----------|
| `@EnumConstant` | Annotation | enum class | 표준 문서/스키마에서 열거형을 ‘표준 enum’으로 식별 (정적 수집/문서화) |
| `DescriptiveEnum` | Interface | enum 구현 | value / description / describable 제공 + 커스텀 (역)직렬화 지원 |
| `ExceptionCode` | Interface | enum 구현 | `DescriptiveEnum` 확장 + `code`/`message` 필드(예외/오류 코드 표현) |
| `@SwaggerDescribable` | Annotation (Deprecated) | class | (Deprecated) → 클래스 문서화 시 @Schema 사용 권장 |
| `@SwaggerDescription` | Annotation | property | 필드별 설명/optional 표시 (응답/요청 공용; @Schema 병행 가능) |
| ~~`@RequestDescription`~~ | Annotation (Deprecated) | property | (Deprecated) → `@SwaggerDescription` 또는 `@Schema` 로 대체 |
| `@Sequence` | Annotation | property | 문서/출력 정렬 순서를 위한 우선순위 정수 값 지정 |

---
## 2. 상세 시그니처 (요약)
```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EnumConstant

interface DescriptiveEnum {
    val value: String
    val description: String
    val describable: Boolean
    fun toText(): String               // = description
    fun toDescription(): String        // "'value': description"
    companion object {
        const val DESCRIPTION_MARKER = "{$}DESCRIPTION{$}"
        fun toStringList(list: List<DescriptiveEnum>): List<String>
        fun toDescription(list: Array<out DescriptiveEnum>): String
        fun replaceDescription(description: String, type: KClass<out DescriptiveEnum>): String
    }
}

interface ExceptionCode : DescriptiveEnum {
    val code: String
    val message: String
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Deprecated("Use standard OpenAPI @Schema instead; will be removed in a future release.")
annotation class SwaggerDescribable

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Deprecated("Use @SwaggerDescription or @Schema instead; to be removed")
annotation class RequestDescription(
  val name: String = "",
  val description: String = "",
  val optional: Boolean = false
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SwaggerDescription(
  val description: String = "",
  val optional: Boolean = false
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sequence(val value: Int)
```

---
## 3. Enum 직렬화/역직렬화 동작 (DescriptiveEnum)
| 항목 | 설명 |
|------|------|
| 직렬화 | `DescriptiveEnumSerializer` → JSON 문자열로 `value` 만 출력 |
| 역직렬화 순서 | `fromValue` → value(대소문자 무시) → enum name(대소문자 무시) |
| 기본값 처리 | 빈/ null → `fromValue("")` 시도 후 value=="" 항목 fallback |
| 컬렉션 | ContextualDeserializer 로 List/Set/Map value 동일 처리 |

### 기본 예시
```kotlin
@EnumConstant
enum class StandardStatus(
  override val value: String,
  override val description: String,
  override val describable: Boolean = true
) : DescriptiveEnum {
  NONE("", "없음"),
  SUCCESS("SUCCESS","성공"),
  FAILURE("FAILURE","실패");
  companion object {
    fun fromValue(v: String): StandardStatus = entries.firstOrNull { it.value.equals(v, true) } ?: SUCCESS
  }
}
```
직렬화: `SUCCESS`.
역직렬화: 입력이 `"success"`, `"SUCCESS"`, 이름 `"SUCCESS"` 모두 SUCCESS 매핑.

### DESCRIPTION_MARKER
문자열 설명 중 `{$}DESCRIPTION{$}` 토큰을 `replaceDescription()`로 후처리하여 enum value/description 목록을 삽입 가능.

---
## 4. (Deprecated) RequestDescription
→ `@SwaggerDescription` 또는 `@Schema` 사용.

---
## 5. @SwaggerDescription 과 @Schema 공존 규칙
우선순위 (buildDescriptors): **@Schema > @SwaggerDescription > (무시) Deprecated(@RequestDescription, @SwaggerDescribable 클래스 마커는 단순 탐색용)**

| 상황 | 결과 |
|------|------|
| @Schema + @SwaggerDescription | @Schema 기준(설명/required) 사용 |
| @SwaggerDescription 만 | description/optional 반영 |
| @Schema 만 | schema description / required 반영 |
| @RequestDescription | 무시 (Deprecation) |

권장: 한 필드 하나만. 표준 호환 강조 → @Schema, 간단 메타만 필요 → @SwaggerDescription.

---
## 6. @Sequence 활용
| 항목 | 설명 |
|------|------|
| 용도 | 문서/스니펫/직렬화 출력 정렬(문서 모듈에서 반영) |
| 값 범위 | Int (충돌 시 정렬 구현체가 secondary 정렬: 이름 순/선언 순) |
| 권장 | 루트 응답/핵심 메타 필드에 낮은 숫자 부여 (1,2,3...) |

예) `StandardResponse` 에서 status(1) → version(2) → datetime(3) → duration(4) → payload(5)

---
## 7. ExceptionCode 인터페이스
에러 코드 세트를 enum 으로 정의할 때:
```kotlin
@EnumConstant
enum class UserError(
  override val value: String,
  override val description: String,
  override val code: String,
  override val message: String,
  override val describable: Boolean = true
) : ExceptionCode {
  DUP_EMAIL("DUP_EMAIL", "중복 이메일", "E_USER_DUP", "이미 사용 중인 이메일입니다."),
  WEAK_PASSWORD("WEAK_PASSWORD", "약한 패스워드", "E_USER_WEAK", "보안 수준 미달");
}
```
직렬화 값: `DUP_EMAIL`, `WEAK_PASSWORD` 등 value.
문서화에서 description 과 별개로 code/message 를 ErrorPayload 로 매핑 가능.

---
## 8. 패턴 & 베스트 프랙티스
| 상황 | 권장 패턴 |
|------|-----------|
| 요청/응답 필드 설명 | `@Schema` 또는 `@SwaggerDescription` 단일 사용 |
| enum default | value="" NONE + fromValue fallback |
| 다국어 | description 에 메시지 키 후 외부 i18n 변환 |
| name alias 필요 | DTO 어노 대신 ParameterDescriptor / OpenAPI schema 수정 |
| Deprecated 정리 | @RequestDescription 단계적 제거 후 탐색 로그(optional) |

---
## 9. 오류/경계 케이스
| 케이스 | 동작 |
|--------|------|
| 역직렬화 값 null/빈 문자열 | defaultEnum(빈 value 항목 or fromValue("") 결과) 반환 |
| `fromValue` 정의 없음 | value/enum name fallback 로 탐색 |
| 매칭 실패 | InvalidFormatException throw (Jackson 처리) |
| EnumSet / List / Map value | ContextualDeserializer 로 동일 규칙 반복 적용 |

---
## 10. 표준 응답 모듈과의 관계
| 요소 | 상호 작용 |
|------|-----------|
| @EnumConstant | enum 수집 트리거 |
| DescriptiveEnum.DESCRIPTION_MARKER | description 템플릿 확장 |
| @Sequence | 필드 순서 지정 |
| @Schema vs @SwaggerDescription | @Schema 우선 적용 |

---
## 11. 마이그레이션 가이드 (enum)
| 단계 | 작업 |
|------|------|
| 1 | enum 각 상수에 비즈니스 식별자(value) & 사람 설명(description) 결정 |
| 2 | enum 선언에 인터페이스 구현: `: DescriptiveEnum` |
| 3 | `NONE` 또는 기본 항목(value="") 필요 시 추가 |
| 4 | 선택: companion 에 `fromValue` 팩토리 추가 (성능/명확성) |
| 5 | 문서 문자열에 목록 삽입 필요 시 DESCRIPTION_MARKER 사용 후 replace 로 후처리 |
| 6 | 기존 Jackson 커스텀 serializer 제거 (본 모듈 serializer 사용) |

---
## 12. 사용 예
```kotlin
@EnumConstant
enum class Role(
  override val value: String,
  override val description: String,
  override val describable: Boolean = true
) : DescriptiveEnum {
  ADMIN("ADMIN", "관리자"),
  USER("USER", "일반 사용자"),
  GUEST("GUEST", "게스트", describable = false);
  companion object { 
      fun fromValue(v: String) = entries.firstOrNull { it.value.equals(v,true) } ?: USER 
  }
}

// 권장: 클래스/필드 모두 @Schema 기반 표준 어노테이션 사용
data class CreateUserRequest(
  @Schema(description = "로그인 이메일", required = true) 
  val email: String,
  
  @Schema(description = "역할 코드", required = true) 
  val role: Role
)

@Schema(description = "사용자 응답 뷰")
data class UserView(
  @Schema(description = "식별자") val id: Long,
  @Schema(description = "가입 이메일") val email: String,
  @Schema(description = "역할") val role: Role,
  @Sequence(100) @Schema(description = "최근 로그인 Epoch ms") val lastLoginAt: Long? = null
)
```

---
## 13. 빠른 체크리스트
- [ ] enum: value/description/describable 정의 & (optional) fromValue
- [ ] 기본값 필요 시 value="" 항목 1개(NONE 등)
- [ ] 문서화 필드: 하나의 어노테이션만 (@Schema 또는 @SwaggerDescription)
- [ ] 정렬 필요 필드에 @Sequence
- [ ] Deprecated @RequestDescription 제거
- [ ] DESCRIPTION_MARKER 사용 시 replace 처리 적용

---
## 14. 한계 & 주의
| 항목 | 설명 |
|------|------|
| 동적 enum | 런타임 추가 불가 |
| 중복 value | 첫 매칭, 필요 시 fromValue 명시 |
| describable=false | 목록 설명 제외(역직렬화 영향 없음) |
| 혼합 사용 | @Schema + @SwaggerDescription 혼합 시 @Schema 우선 |

---
## 15. Cross References
| 문서 | 설명 |
|------|------|
| [standard-api-response-reference.md](./standard-api-response-reference.md) | 응답 구조 / Case / Alias / Duration |
| (향후) 문서화 모듈 Reference | Swagger/OpenAPI 커스텀 확장 세부 설명 |
