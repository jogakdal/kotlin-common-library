# **Standard API Response 라이브러리 사용 가이드**

> 본 문서는 `:standard-api-response` 모듈을 적용(사용)하는 서비스/애플리케이션 **개발자** 관점에서의 활용 방법을 설명합니다.
>
> ### 관련 문서 (Cross References)
> | 문서                                                                               | 목적 / 차이점                                                           |
> |----------------------------------------------------------------------------------|--------------------------------------------------------------------|
> | [standard-api-specification.md](standard-api-specification.md)                   | 표준 API 규격: request 규칙, response의 필드 정의, 상태/에러 규칙, 리스트 처리 방식 정의     |
> | [standard-api-response-reference.md](standard-api-response-reference.md) | 레퍼런스 가이드. 모듈에 대한 상세 설명 제공 |
> | [standard-api-response-examples.md](standard-api-response-examples.md)           | 다양한 실전 예시 모음: 케이스 변환, 페이지/커서, Alias/Canonical, @NoCaseTransform 예. |
> | [README.md](../README.md)                                                        | 루트 개요 및 지원 CaseConvention 요약 표.                                    |
>
> 참고: 아래 내용은 실무 활용 가이드이며, 구현상의 세부 사항이나 필수 준수 규격은 standard-api-specification 문서를 우선하여 따릅니다.

---
## **목적 및 주요 특징 (Overview)**

- 모든 REST API 응답을 StandardResponse<T : BasePayload> **단일 형태**로 일관되게 표현
- 응답 공통 메타필드 제공: status, version, datetime, duration, payload
- **페이지네이션(Page)** / **커서 기반(Incremental)** 리스트 응답 구조 내장 지원
- 오류 응답 표준화 (ErrorPayload 활용)
- Jackson + kotlinx.serialization 혼용 사용 가능 (직렬화 라이브러리 선택에 유연성 제공)
- 처리 시간 자동 주입 옵션 지원 (@InjectDuration 어노테이션)
- Kotlin / Java 상호 운용 지원 (@JvmStatic 빌더 메서드 등 제공)
- **전역 CaseConvention 기반 응답 키(case) 변환 및 강력한 Alias/Canonical 역직렬화 지원** (다양한 입력 키 형태 매핑)

---
## **핵심 타입 및 개념**

| **타입 (Class/Type)** | **설명 (Description)**                                        |
|---|-------------------------------------------------------------|
| **StandardResponse\<T>** | 최상위 표준 응답 래퍼 (response wrapper). 제네릭 T는 BasePayload 구현체     |
| **StandardStatus** | 응답 상태 enum – `NONE`, `SUCCESS`, `FAILURE` (주로 `SUCCESS`/`FAILURE` 사용) |
| **BasePayload** / **BasePayloadImpl** | Payload 마커 인터페이스 / 기본 구현체 (빈 payload 용)                     |
| **ErrorPayload** / **ErrorDetail** | 오류 응답용 페이로드 / 상세 오류 정보 (`code`, `message` 등 포함)                 |
| **PageableList\<T>** / **PageListPayload\<T>** | 페이지(Page) 기반 리스트 응답 래퍼 (pageable 필드 구조)                     |
| **IncrementalList\<T, P>** / **IncrementalListPayload\<T, P>** | 커서 기반(Incremental, 더보기) 리스트 래퍼 (incremental 필드 구조)          |
| **Items\<T>** | 리스트 아이템 목록 및 메타정보 (`list` + `total` + `current` 개수 등)             |
| **PageInfo** / **OrderInfo** / **OrderBy** | 페이지 번호/크기, 정렬 정보 등의 메타데이터 객체                                |
| **CursorInfo\<P>** | 커서 범위 (`start`, `end`) 및 `expandable`(이후 데이터 존재 여부) 정보 객체         |
| **@InjectDuration** | 컨트롤러 응답 객체에 사용 시 `duration` 필드 자동 채움 어노테이션                    |
| **StandardCallbackResult** | 콜백 형태 빌드 결과를 담는 컨테이너 (`payload`, `status`, `version` 반환)          |

---
## **표준 응답 구조**

표준 API 응답은 모든 경우 아래와 같은 공통 JSON 구조를 가집니다:

```json5
{
  "status": "SUCCESS",                      // StandardStatus (응답 상태)
  "version": "1.0",                         // 버전 (서비스 혹은 스키마 버전)
  "datetime": "2025-09-16T12:34:56.789Z",   // 응답 발생 시각 (ISO-8601 UTC 시간)
  "duration": 12,                           // 처리 소요 시간(ms) (자동 주입 가능)
  "payload": { /* 실제 페이로드 데이터 */ }
}
```
- **status**: 요청 처리 결과 상태를 나타냅니다. 기본값은 `SUCCESS`이며, 실패나 예외 시 `FAILURE`를 사용하고 이 경우 payload는 주로 `ErrorPayload` 형식으로 오류 정보를 제공합니다. (`StandardStatus` 참고)
- **version**: 응답 버전 (예: API 버전 또는 데이터 스키마 버전). 기본 `"1.0"` 등의 문자열이며, 필요에 따라 서버에서 설정 가능합니다.
- **datetime**: 응답 생성 시간 (UTC 표준시). ISO-8601 포맷으로 일관되게 출력됩니다.
- **duration**: 요청 처리에 소요된 시간(ms). 응답에 자동 포함되며, 라이브러리의 기능을 통해 자동 측정/주입할 수 있습니다.
- **payload**: 실제 응답 데이터를 담는 컨테이너 객체입니다. 응답이 성공(`SUCCESS`)인 경우 비즈니스 데이터(payload DTO)가 들어가며, 실패(`FAILURE`)인 경우 `ErrorPayload` (오류 목록 등) 객체가 들어갑니다.

> Note: `status`가 `FAILURE`인 경우 가급적 `ErrorPayload`를 활용해 표준화된 오류 형식을 제공하는 것을 권장합니다. (자세한 내용은 아래 오류 처리 패턴 참고)

---
## **의존성 추가 (Dependency)**
라이브러리를 프로젝트에 추가하려면 Gradle 또는 Maven에 다음과 같이 의존성을 선언합니다:
- **Gradle (Kotlin DSL)**:
```groovy
dependencies {
    implementation("com.hunet.common_library:common-core:<version>") // 공통 코어 모듈
    implementation("com.hunet.common_library:std-api-annotations:<version>") // @InjectDuration 등 어노테이션 모듈
    implementation("com.hunet.common_library:standard-api-response:<version>") // 표준 응답 모듈
}
```
- **Maven**:
```xml
<dependencies>
    <!-- 공통 코어 모듈 -->
    <dependency>
        <groupId>com.hunet.common_library</groupId>
        <artifactId>common-core</artifactId>
        <version>버전</version>
    </dependency>

    <!-- @InjectDuration 등 어노테이션 모듈 -->
    <dependency>
        <groupId>com.hunet.common_library</groupId>
        <artifactId>std-api-annotations</artifactId> 
        <version>버전</version>
    </dependency>

    <!-- 표준 응답 모듈 -->
    <dependency>
        <groupId>com.hunet.common_library</groupId>
        <artifactId>standard-api-response</artifactId>
        <version>버전</version>
    </dependency>
</dependencies>
```
- 사내 Nexus 등 프라이빗 저장소를 사용할 경우, `settings.xml` 또는 Gradle `repositories` 블록에 해당 Maven 저장소 주소를 추가해야 합니다.
- 2025년 10월 1일 기준 최신 버전은 다음과 같습니다.
```plaintext
  common-core: 1.0.3-SNAPSHOT
  std-api-annotations: 1.0.1-SNAPSHOT
  standard-api-response: 1.1.0-SNAPSHOT
```

---
## **기본 사용법 – StandardResponse 빌드**
표준 응답을 생성할 때는 StandardResponse 클래스의 정적 빌더 메서드를 사용합니다. **Kotlin**과 **Java** 각각에 대해 기본 사용 예시는 다음과 같습니다:

### **Kotlin 예제**
```kotlin
val okResponse = StandardResponse.build(ErrorPayload("OK", "정상"))
val failResponse = StandardResponse.build(
    ErrorPayload("E400", "잘못된 요청"),
    status = StandardStatus.FAILURE,
    version = "1.1"
)
// callback 빌더 사용 – payload 계산 로직과 실행 시간을 함께 측정
val timedResponse = StandardResponse.build<ErrorPayload>(payload = null) {
    StandardCallbackResult(ErrorPayload("OK", "완료"), StandardStatus.SUCCESS, "2.0")
}
```

- 위 코드는 **성공 응답**과 **오류 응답**을 생성하는 기본 패턴을 보여줍니다. `StandardResponse.build(payload)`에 `BasePayload`를 구현한 객체를 넘기면 해당 payload를 포함한 표준 응답 객체가 만들어 집니다.
    - 첫 번째 줄에서는 `ErrorPayload("OK", "정상")`을 `payload`로 감싸 성공 응답을 생성했습니다. (status는 명시하지 않으면 기본 `SUCCESS`)
    - 두 번째 예에서는 `status = FAILURE`로 지정하여 실패 응답을 만들고, 버전도 `"1.1"`로 오버라이드했습니다. payload는 `ErrorPayload`에 오류 코드 `"E400"`과 메시지 `"잘못된 요청"`을 담아 사용했습니다.
- 세 번째 `timedResponse`예시는 **콜백 빌더(callback build)**를 사용한 패턴입니다. `StandardResponse.build<T>(payload = null) { ... }` 형태로 람다 블록을 제공하면, 라이브러리가 해당 블록 실행 **전후로 시간을 측정**하여 `duration` 필드를 자동 채워줍니다. 콜백 내부에서는 `StandardCallbackResult(payload, status, version)`을 반환해야 하며, 예시에서는 `"OK"` 코드의 `ErrorPayload`를 성공 상태로 반환했습니다. (이 방식은 수행 시간이 중요한 로직을 람다 내부에 작성하면 자동으로 걸린 시간이 계산된다는 점이 특징입니다.)

> Note: 위 예시에는 편의상 `ErrorPayload`를 payload로 사용하였지만, 실제 API에서는 도메인에 맞는 별도의 Payload DTO를 정의하여 `StandardResponse<YourPayload>` 형태로 사용하는 것이 일반적입니다. (`ErrorPayload`는 주로 오류 응답이나 단순 메시지 응답용으로 사용됩니다.)

### **Java 예제**
```java
ErrorPayload okPayload = new ErrorPayload("OK", "정상", null);
StandardResponse<ErrorPayload> okResponse = StandardResponse.build(okPayload);

StandardResponse<ErrorPayload> failResponse = StandardResponse.build(
    new ErrorPayload("E500", "서버 오류", null),
    StandardStatus.FAILURE,
    "1.2"
);

StandardResponse<ErrorPayload> timedResponse = StandardResponse.buildWithCallback(
    () -> new StandardCallbackResult(new ErrorPayload("OK", "완료", null), StandardStatus.SUCCESS, "2.0")
);
```
- Java 에서도 Kotlin과 유사하게 `StandardResponse.build(payload, status, version)` 메서드를 활용합니다. 기본 성공 응답(`okResponse`)과 실패 응답(`failResponse`) 생성 예시는 위와 같습니다. Java는 기본 인자를 지원하지 않으므로, `status`나 `version` 등을 지정하지 않을 경우에도 오버로드된 메서드 시그니처에 맞게 인자를 모두 명시해야 합니다.
- 세 번째 `timedResponse`는 **콜백 빌더**를 사용한 예시로, Java에서는 전용 메서드인 `StandardResponse.buildWithCallback(Supplier)`을 제공합니다.<br>
  람다에서 `StandardCallbackResult(payload, status, version)`을 반환하면 Kotlin과 동일하게 실행 시간을 측정하여 `duration`을 채워줍니다.

> 참고: Java에서 `StandardResponse.build...()` 메서드를 사용할 때 주의사항은 [Java 상호 운용성](#java-상호-운용성-주의사항) 섹션에 정리되어 있습니다. 또한, 페이지네이션이나 커서 기반 리스트를 위한 유틸리티는 별도 메서드(`fromPageJava`, `buildFromTotalJava` 등)를 제공하므로 아래 [리스트 응답 처리 섹션](#리스트-응답-처리-페이지네이션--커서)을 참고하세요.

---
## **자동 Duration 주입 (`@InjectDuration`)**
라이브러리는 응답 생성 시 소요 시간(duration)을 자동으로 계산하여 주입할 수 있는 기능을 제공합니다. 이 기능을 사용하려면 설정과 DTO 정의에 다음과 같이 적용합니다:
- **설정 활성화:** Spring Boot `application.yml`에 옵션을 켭니다.

```yaml
standard-api-response:
  auto-duration-calculation:
    active: true
```
- 위와 같이 설정하면 라이브러리가 자동으로 요청 처리 시간을 측정합니다.
- **DTO 필드에 어노테이션:** 응답 payload의 DTO 클래스에 `@InjectDuration` 어노테이션을 자동 측정할 필드에 추가합니다.

```kotlin
data class ApiResult(
    @InjectDuration val duration: Long? = null,
    val data: Any
): BasePayload
```
- 컨트롤러에서 `ApiResult`를 반환하면, 응답 직전에 라이브러리가 `duration` 값을 계산하여 해당 필드에 자동으로 채워줍니다. 개발자는 수동으로 시간을 계산하지 않아도 되므로 편리합니다.

> 주의: 빌더에서 명시적으로 duration 값을 지정한 경우(파라미터 전달) 해당 값이 유지되지만, 필터/Advice 흐름에서 @InjectDuration 이 붙은 mutable 필드가 있다면 실제 요청 경과시간으로 덮어써질 수 있습니다. 부분 구간 측정이 필요하면 별도 필드를 두고 직접 측정 값을 세팅하세요.

---
## **오류 응답 처리 패턴**
서비스에서 오류가 발생한 경우 **일관된 응답 포맷**을 유지하기 위해 다음과 같은 표준 패턴을 따르는 것을 권장합니다:
- **시스템/검증 오류**: 클라이언트의 잘못된 요청이나 서버 내부 에러 등으로 요청을 처리할 수 없을 때는 `status = FAILURE`로 설정하고, `ErrorPayload`를 payload로 사용해 code와 message 등의 오류 정보를 제공합니다 (예: `ErrorPayload("E400", "잘못된 요청")`).
- **복수 오류 누적**: 여러 개의 오류를 한꺼번에 전달해야 하는 경우, 하나의 `ErrorPayload` 객체에 `addError(code, message)` 메서드로 추가 오류를 누적합니다. (`ErrorPayload.errors` 리스트에 추가)
- **추가 메타데이터**: 오류에 대한 부가 정보(디버깅용 ID, 필드 정보 등)가 필요하면 `ErrorPayload.appendix` 맵을 활용해 key-value 형태로 자유롭게 담습니다.

이러한 패턴을 따르면 클라이언트는 status를 확인한 뒤 payload 내의 errors 배열과 appendix를 일관된 방식으로 처리할 수 있습니다.

---
## **리스트 응답 처리 (페이지네이션 & 커서)**
표준 응답 구조는 **페이징 목록**과 **커서 기반(연속 더보기) 목록**에 대한 공통 래퍼를 제공합니다.<br>
상황에 맞게 적절한 리스트 payload 클래스를 사용하면, 응답 payload에 페이지 정보나 커서 정보를 포함한 일관된 구조를 적용할 수 있습니다:

| **요구 사항 및 기능** | **사용 구조 (Payload/List 클래스)**                                                                          |
| --- |-------------------------------------------------------------------------------------------------------|
| 일반 페이지네이션 (페이지 번호 기반) | **PageListPayload\<T>** / **PageableList\<T>** (응답 JSON에서 `payload.pageable` 필드로 표현)                  |
| 연속 더보기 (스크롤 Pagination) | **IncrementalListPayload\<T, P>** / **IncrementalList\<T, P>** (응답 JSON에서 `payload.incremental` 필드로 표현) |
| 총 아이템 수 기반 커서 계산 | `IncrementalList.buildFromTotal(...)` 메서드 (자동으로 `cursor.expandable` 결정)                                   |
| 커스텀 커서 값 사용 | `IncrementalList.build(...)` 메서드로 start/end 직접 지정 (특수한 커서 계산 방식 필요한 경우)                                 |

일반적으로 **페이지네이션 API** 응답은 `payload.pageable` 형식으로, **더보기(커서) API** 응답은 `payload.incremental` 형식으로 내려주며, 라이브러리의 해당 클래스들을 사용하면 JSON 포맷을 손쉽게 맞출 수 있습니다.

### **페이지네이션 리스트 응답 (PageableList)**
서버에서 Spring Data의 `Page<T>` 객체 등을 사용한다면, 이를 `PageListPayload`/`PageableList` 형태로 변환해 주는 유틸리티를 활용할 수 있습니다.
#### **Kotlin:**
`PageListPayload.fromPage(page, mapper)` 함수를 사용하여 변환합니다. 예를 들어 엔티티 `MyEntity`의 `Page<MyEntity>`를 DTO 리스트로 변환하는 경우:
```kotlin
fun <E> toPagePayload(page: Page<E>, map: (E) -> MyItemPayload): PageListPayload<MyItemPayload> =
    PageListPayload.fromPage(page, map)
```
- 위 함수는 `Page` 객체와 `엔티티 -> DTO` 변환 람다를 받아서 `PageListPayload` 객체를 반환합니다.<br>`payload.pageable.page`에는 페이지 정보(size, 현재 페이지 번호, 전체 페이지 수)가, `payload.pageable.items`에는 변환된 DTO 리스트와 `이 DTO에 포함된 아이템 수` 등이 채워집니다.

#### **Java:**
`PageableList.fromPageJava(page, mapper)` 정적 메서드를 사용합니다:
```java
Page<MyEntity> page = ...;
PageableList<MyDtoPayload> listPayload = PageableList.fromPageJava(page, e -> new MyDtoPayload(/* mapping */));
```
- Java에서는 제네릭 reified 함수를 직접 호출할 수 없으므로, `fromPageJava()` 함수가 제공됩니다.<br>위 코드로 `Page<MyEntity>`를 `PageableList<MyDtoPayload>`로 변환하면 응답 JSON의 `payload.pageable` 구조를 자동으로 구성할 수 있습니다.

### **커서 기반 리스트 응답 (IncrementalList)**
커서 기반 (“더보기” 또는 무한 스크롤 방식) API의 경우, `IncrementalListPayload`/`IncrementalList` 클래스를 사용하여 응답에 **cursor 정보**와 **정렬(order) 정보**를 포함할 수 있습니다.

#### **Kotlin:**
만약 **전체 아이템 개수**를 알고 있다면 `IncrementalList.buildFromTotal(items, startIndex, howMany, totalItems, cursorField)` 메서드로 리스트 payload를 생성할 수 있습니다.
```kotlin
val incList = IncrementalList.buildFromTotal<String, Long>(
    items = listOf("A", "B"),
    startIndex = 0,
    howMany = 2,
    totalItems = 10,
    cursorField = "id"
)
```

- 위 코드는 문자열 리스트 `["A", "B"]`에 대해, 시작 커서 `0`, 가져온 개수 `2`, 전체 아이템 수 `10`, 그리고 커서 기준 필드명을 `"id"`로 하여 `IncrementalList`를 생성합니다.<br>결과 `incList`를 표준 응답에 담으면 JSON `payload.incremental` 아래에 `cursor (start=0, end=1 등)`, `items (total=10, current=2, list=[…])` 구조가 채워집니다.<br>`cursor.expandable` 필드는 (전체 개수와 요청한 개수를 비교하여) 이후 더 가져올 아이템이 있는지 여부를 나타냅니다.

#### **Java:**
`IncrementalList.buildFromTotalJava(...)` 메서드를 사용합니다:
```java
IncrementalList<String, Long> incList = IncrementalList.buildFromTotalJava(
    List.of("A", "B"),
    0L,    // start cursor
    2L,    // howMany (count)
    10L,   // totalItems
    "id",
    null,  // order (정렬 정보 없을 때 null)
    (field, item) -> item  // cursor value extractor (예: item -> id)
);
```
- Java에서의 사용은 위와 같으며, Generic 타입 추론을 위해 매개변수가 다소 많지만 `buildFromTotalJava()` 함수를 통해 IncrementalList 객체를 얻을 수 있습니다. 정렬 정보(OrderInfo)가 있다면 해당 객체를 인자로 넘길 수도 있습니다.

> 참고:  Incremental 리스트를 생성할 때 **totalItems를 모르는 경우** 또는 커서 정보를 직접 제어해야 하는 경우에는 `IncrementalList.build(items, startCursor, endCursor, expandable, orderInfo)` 등을 사용할 수도 있습니다. 대부분의 경우 `buildFromTotal`을 활용하면 편리하게 expandable 여부까지 처리됩니다.

---
## **Java 상호 운용성 주의사항**
이 라이브러리는 Kotlin으로 작성되었지만 Java에서 사용하기 위한 인터페이스를 제공합니다. Java 개발자가 사용할 때 알아두면 좋은 주의사항은 다음과 같습니다:

| **항목** | **설명 및 요구사항**                                                                                                                                                                                                                                                                                                                                                 |
| --- |---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **기본 파라미터 처리** | Kotlin 함수의 기본 파라미터는 Java에서 직접 활용할 수 없으므로, `StandardResponse.build(...)` 등의 메서드를 호출할 때는 오버로드된 정적 메서드를 사용하고 **모든 인자**를 명시적으로 전달해야 합니다. (예: `StandardResponse.build(payload)`를 Java에서 호출 시, 내부적으로 `@JvmStatic` 오버로드가 제공되어 있으나 인자를 생략할 수는 없습니다.)                                                                                                                    |
| **reified 함수 호출** | Kotlin의 reified 제네릭 함수를 Java에서는 직접 호출할 수 없습니다. 따라서 제네릭 타입 정보를 요하는 기능(예: `PageListPayload.fromPage`)은 Java 전용 유틸리티 메서드인 `fromPageJava(...)`, `buildFromTotalJava(...)` 등을 대신 사용해야 합니다.                                                                                                                                                                         |
| **제네릭 캐스팅** | `StandardResponse<T>.getPayload()`의 반환 타입은 BasePayload로 취급됩니다. **정상적으로 타입 매개변수가 적용된 경우** Java에서도 `resp.getPayload()`가 `T` 타입으로 캐스팅되지만, 컴파일러 경고 없이 안전하게 사용하려면 `StandardResponse<YourPayloadType>`으로 타입을 명확히 지정해야 합니다. (예: `StandardResponse<ErrorPayload> resp` 형태로 받아서 `resp.getPayload()` 사용) 만약 타입을 명시할 수 없는 상황에서는 적절한 캐스팅 또는 Jackson 노드를 이용한 재파싱이 필요할 수 있습니다. |

---
## **테스트 및 검증 예시**
라이브러리를 활용하면 **응답 구조에 대한 테스트**를 용이하게 작성할 수 있습니다. 예를 들어 Spring MVC 환경에서 컨트롤러 응답을 검증하는 통합 테스트를 Kotlin과 Java로 각각 작성하면 다음과 같습니다:

### **Kotlin - MockMvc 통합 테스트 예**
```kotlin
@SpringBootTest
@AutoConfigureMockMvc
class PingApiTests(@Autowired val mockMvc: MockMvc) {

    @Test
    fun ping_success() {
        val mvcResult = mockMvc.perform(get("/api/ping"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.payload.errors[0].code").value("OK"))
            .andReturn()

        val body = mvcResult.response.contentAsString
        val resp = StandardResponse.deserialize<ErrorPayload>(body)

        assertEquals(StandardStatus.SUCCESS, resp.status)
        val error = resp.payload.errors.first()
        assertEquals("OK", error.code)
        assertEquals("pong", error.message)
    }
}
```
위 테스트는 `/api/ping` 엔드포인트에 대한 응답이 표준 규격을 따르는지 확인합니다.<br>
먼저 MockMvc로 JSON 응답의 `status`와 `payload.errors[0].code` 값을 검증하고, 응답 본문을 `StandardResponse.deserialize<ErrorPayload>`로 파싱하여 **객체로 역직렬화**한 뒤 `resp.status`와 `resp.payload.errors` 내용을 다시 한 번 확인합니다. (이 예에서는 ping API가 `"OK"`, `"pong"` 메시지의 `ErrorPayload`를 반환하는 시나리오를 가정했습니다.)

### **Java - MockMvc 통합 테스트 예**
```java
@SpringBootTest
@AutoConfigureMockMvc
class PingApiTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void ping_success() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.payload.errors[0].code").value("OK"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        StandardResponse<ErrorPayload> resp = StandardResponse.deserialize(body, ErrorPayload.class);

        Assertions.assertEquals(StandardStatus.SUCCESS, resp.getStatus());
        Assertions.assertFalse(resp.getPayload().getErrors().isEmpty());
        Assertions.assertEquals("OK", resp.getPayload().getErrors().get(0).getCode());
        Assertions.assertEquals("pong", resp.getPayload().getErrors().get(0).getMessage());
    }
}
```
Java 테스트도 개념은 동일합니다. `StandardResponse.deserialize(String, Class<T>)` 메서드를 사용하여 JSON 문자열을 `StandardResponse<ErrorPayload>` 객체로 변환한 후 필드 값을 검증합니다.

### **응답 역직렬화 및 리스트 처리 예시**
표준 응답으로 **페이지네이션 또는 커서 리스트**를 반환하는 경우, 이를 다시 객체로 파싱하는 것도 지원됩니다. 예를 들어, `/api/items?page=1` 같은 페이지 API 응답을 역직렬화하는 코드를 Kotlin으로 작성하면 다음과 같습니다:
```kotlin
val mvcResult = mockMvc.perform(get("/api/items?page=1"))
    .andExpect(status().isOk)
    .andReturn()

val pageResp = StandardResponse.deserialize<PageListPayload<MyItemPayload>>(
    mvcResult.response.contentAsString
)
assertTrue(pageResp.payload.pageable.items.list.isNotEmpty())
```
위에서는 응답 JSON을 `StandardResponse<PageListPayload<MyItemPayload>>` 형태로 파싱하여, `payload.pageable.items.list`에 DTO들이 담겨 있음을 확인할 수 있습니다.

**Incremental (커서 기반) 응답**도 마찬가지로 역직렬화가 가능하며, 특히 Kotlin에서는 제네릭 타입까지 포함하여 파싱할 수 있습니다:
```kotlin
@Test
fun incremental_deserialize() {
    val json = """
    {
      "status": "SUCCESS",
      "version": "1.0",
      "datetime": "2025-09-16T12:45:00Z",
      "duration": 5,
      "payload": {
        "cursor": { "field": "idx", "start": 0, "end": 1, "expandable": true },
        "order": null,
        "items": {
          "total": 50, "current": 2,
          "list": [ { "idx": 0, "text": "log-0" }, { "idx": 1, "text": "log-1" } ]
        }
      }
    }
    """.trimIndent()

    val resp = StandardResponse.deserialize<IncrementalList<LogEntryPayload, Long>>(json)
    assertEquals(StandardStatus.SUCCESS, resp.status)
    assertEquals(2, resp.payload.items.list.size)
    assertTrue(resp.payload.cursor?.expandable == true)
}
```
위 Kotlin 테스트에서는 `IncrementalList<LogEntryPayload, Long>` 타입으로 JSON을 파싱하여, 커서 정보와 리스트 아이템(`LogEntryPayload`)들을 제대로 매핑하는지 검증합니다. (expandable == true인 것도 확인)

Java에서는 제네릭 타입 인자를 runtime에 직접 전달하기 어렵기 때문에, `StandardResponse.deserialize(json, IncrementalList.class)`처럼 raw 타입으로 파싱해야 합니다.<br>
이 경우 payload의 내부 리스트 타입 정보가 소실되므로, 아이템 접근 시 캐스팅이나 별도 처리 과정이 필요합니다 (예를 들어 Jackson의 JsonNode로 한번 더 파싱하거나, `StandardResponse<YourPayload>` 형태로 받도록 설계를 달리해야 합니다):
```java
@Test
void incremental_deserialize() {
    String json = """
    {
      "status": "SUCCESS",
      "version": "1.0",
      "datetime": "2025-09-16T12:45:00Z",
      "duration": 5,
      "payload": {
        "cursor": {"field": "idx", "start": 0, "end": 1, "expandable": true},
        "order": null,
        "items": {
          "total": 50, "current": 2,
          "list": [ {"idx": 0, "text": "log-0"}, {"idx": 1, "text": "log-1"} ]
        }
      }
    }
    """;

    // 제네릭 정보 손실을 피하려면 TypeReference 등을 활용한 커스텀 파싱 필요
    StandardResponse<IncrementalList> resp = StandardResponse.deserialize(json, IncrementalList.class);
    Assertions.assertEquals(StandardStatus.SUCCESS, resp.getStatus());
    // resp.getPayload()가 IncrementalList 타입이지만 내부 리스트의 제네릭 타입 정보 없음
    // (items 접근 시 안전한 캐스팅 또는 별도 처리 필요)
}
```
위 Java 예시는 구조 검증용으로 단순화한 코드이며, 실제로는 Jackson의 TypeReference나 Gson 등의 방법을 사용해 내부 제네릭까지 파싱하는 전략을 취할 수 있습니다.

---
## **케이스 컨벤션을 통한 응답 키 변환**
Standard API Response 라이브러리는 **케이스 컨벤션(case convention)** 변환 기능을 내장하여, 응답 JSON의 키(key) 이름을 일관된 형태로 변환해 줄 수 있습니다. 예를 들어 전체 응답의 키를 `snake_case`나 `kebab-case`로 일괄 변환하는 식입니다.

### **케이스 변환 기능 개요**
응답 객체를 JSON으로 직렬화할 때, 키 변환은 다음 **3단계 순서**로 이루어 집니다:
1. **Jackson 기본 직렬화** – 객체의 프로퍼티 이름(propertyName) 또는 해당 필드의 `@JsonProperty`로 지정된 alias를 사용하여 1차적으로 JSON 마샬링을 수행합니다.
2. **Alias 치환 (선택)** – 만약 DTO 클래스에 `@JsonProperty`로 별도 키가 명시되어 있다면, 원본 프로퍼티 이름을 정의된 alias 값으로 치환합니다. (예: `userId` 필드에 `@JsonProperty("user_id")`가 있으면 일단 `user_id`로 키 설정)
3. **전역 CaseConvention 적용** – 최종으로, 설정된 케이스 컨벤션(e.g. `SNAKE_CASE`, `KEBAB_CASE` 등)을 전역적으로 적용하여 키 문자열을 변환합니다. (예: `camelCase` -> `snake_case`로 변환 등)

이 과정을 통해, DTO에 정의된 alias 및 전역 컨벤션이 모두 반영된 최종 JSON 키 출력이 만들어집니다.

### **사용 방법 및 우선순위**
케이스 변환 기능은 **전역 설정**과 **개별 DTO 어노테이션** 두 가지 방식으로 사용할 수 있습니다:
```kotlin
@ResponseCase(CaseConvention.SNAKE_CASE)  // DTO 단위로 케이스 지정
data class UserPayload(
    val userId: Long,
    val firstName: String,
    val emailAddress: String
): BasePayload

// 위 DTO를 사용하여 응답 생성:
val json1 = StandardResponse.build(UserPayload(1, "용호", "jogakdal@gmail.com")).toJson()
// 결과 JSON 키: "user_id", "first_name", "email_address"  (스네이크케이스 적용)

// 메서드 호출 시 케이스 컨벤션 파라미터 지정:
val json2 = StandardResponse.build(UserPayload(1, "용호", "jogakdal@gmail.com"))
              .toJson(case = CaseConvention.KEBAB_CASE)
// 결과 JSON 키: "user-id", "first-name", "email-address"  (케밥케이스 적용)
```

위 예시에서 보듯이, 특정 DTO 클래스에 `@ResponseCase(CaseConvention.SNAKE_CASE)` 어노테이션을 붙이면 해당 DTO를 payload로 직렬화할 때 **기본 snake_case**가 적용됩니다. 또한 `toJson(case = ...)`처럼 **메서드 호출 시점에 케이스 컨벤션을 파라미터로 직접 지정**할 수도 있습니다.
- 케이스 변환 **우선순위**: `toJson(case=...)` **메서드 인자 지정** > DTO 클래스의 `@ResponseCase` 어노테이션 > 글로벌 기본값 (IDENTITY 기본 설정).<br>(즉, 코드에서 명시적으로 case를 지정하면 그 값이 최우선으로 적용되고, 없으면 DTO에 개별 설정이 있나 확인, 그래도 없으면 전역 설정값을 따릅니다.)
- 케이스 컨벤션으로 설정 가능한 값(`enum CaseConvention`):

| **값** | **설명** | **변환 예시** (userId필드)     |
| --- | --- |--------------------------|
| IDENTITY | 변경 없이 그대로 | userId (원본 유지)           |
| SNAKE_CASE | 소문자 스네이크 | user_id                  |
| SCREAMING_SNAKE_CASE | 대문자 스네이크 | USER_ID                  |
| KEBAB_CASE | 케밥(case) | user-id                  |
| CAMEL_CASE | lowerCamel (기본) | userId (이미 camel이면 변화 없음) |
| PASCAL_CASE | UpperCamel | UserId                   |
- 이외에 약어(Acronym) 및 숫자 처리 규칙으로, 연속된 대문자는 하나의 토큰으로 인식하거나 대소문자 경계를 구분합니다. 예를 들어 `APIURLVersion2`라는 필드는 snake로 변환 시 `api_url_version2` 처럼 `API`를 한 단어로 보고 구분하며, 숫자 `2`는 토큰으로 유지합니다.
- **특정 필드 변환 제외**: 만약 일부 필드는 케이스 변환의 영향을 받지 않도록 하고 싶다면, 그 필드에 `@NoCaseTransform` 어노테이션을 붙이면 됩니다. 이 어노테이션이 있는 필드는 alias 치환 및 underscore↔dash 교차 변환에서 **예외(skip)** 처리됩니다.

```kotlin
data class Sample(
    @JsonProperty("api_key") @NoCaseTransform val apiKey: String,
    val normalField: String
): BasePayload

val json = StandardResponse.build(Sample("K1", "V"))
             .toJson(case = CaseConvention.KEBAB_CASE)
// 결과: apiKey 필드는 @JsonProperty에 지정된 "api_key" 그대로 유지되고,
// normalField는 케밥케이스로 "normal-field" 변환됨
```
- 위 예시에서 `apiKey` 필드는 `@NoCaseTransform` 어노테이션으로 인해 케밥 케이스 변환에서 제외되어, 미리 지정된 alias `"api_key"` 형태가 최종 출력에 사용됩니다.

### **전역 설정 (`application.yml`)으로 케이스 변환 제어**
`application.yml` 설정을 통해 응답 직렬화 시의 기본 케이스 변환 동작을 전역적으로 제어할 수 있습니다:
```yaml
standard-api-response:
  case:
    enabled: true                   # 케이스 변환 활성화 여부 (기본값: true)
    default: IDENTITY               # 기본 케이스 변환 방식 (예: IDENTITY, SNAKE_CASE, CAMEL_CASE ...)
    query-override: true            # 쿼리 파라미터로 케이스 방식 지정 허용 여부
    header-override: true           # 헤더로 케이스 방식 지정 허용 여부
    query-param: case               # 케이스 방식을 지정하는 쿼리 파라미터 이름
    header-name: X-Response-Case    # 케이스 방식을 지정하는 헤더 이름
```
- `enabled`: 전역 케이스 변환 기능의 활성화 여부입니다. (`false`로 두면 모든 응답이 원본 키 그대로 나가며, 아래 설정들은 무시됨)
- `default`: 별도 지시가 없을 때 사용할 기본 케이스 컨벤션을 지정합니다. (`IDENTITY`가 기본값이며, 예를 들어 `"SNAKE_CASE"`로 설정하면 모든 응답 키가 기본적으로 `snake_case`로 출력됨)
- `query-override`, `header-override`: 요청을 처리할 때 클라이언트가 특정 **쿼리스트링 파라미터**나 **HTTP 헤더**를 통해 원하는 케이스 컨벤션을 지정하도록 허용할지 여부입니다. (`true`이면 허용)
- `query-param`, `header-name`: 각각 쿼리 파라미터 이름과 헤더 이름을 설정합니다. (위 예시는 `?case=snake_case` 또는 `X-Response-Case: snake_case` 헤더로 클라이언트가 요청 시 `snake_case` 응답을 받을 수 있음을 의미)

**전역 설정값 적용 우선순위:**
1. **쿼리 파라미터** – `query-override`: `true`인 경우, 들어온 HTTP 요청 URL의 쿼리스트링에서 `case` 파라미터 값을 읽어 해당 케이스를 적용합니다. (예: `GET /api/example?case=CAMEL_CASE`)
2. **헤더 값** – `header-override`: `true`인 경우, 요청 헤더 `X-Response-Case` 값을 확인해 케이스 방식을 결정합니다.
3. **DTO의 `@ResponseCase` 어노테이션** – 위 두 가지가 지정되지 않은 경우, 개별 DTO 클래스에 지정된 `@ResponseCase` 설정이 있으면 그 값을 사용합니다.
4. **기본 설정값** – 아무 것도 지정되지 않은 경우 default에 명시된 전역 기본 케이스를 적용합니다.

예를 들어, 전역 설정을 다음과 같이 할 수 있습니다:
```yaml
standard-api-response:
  case:
    enabled: false       # 케이스 변환 비활성화
    default: SNAKE_CASE  # (참고: 비활성화 시 default 설정은 실제 변환에 영향을 미치지 않음)
```
위처럼 `enabled: false`로 설정하면 **케이스 변환 기능을 전역으로 끄는** 것이고, 비록 기본 케이스를 `SNAKE_CASE`로 적어 두었더라도 응답 키는 변환되지 않습니다. (활성화 여부가 최우선 조건)

> 주의: `application.yml`의 케이스 설정은 **직렬화된 응답(JSON 출력)의 키 변환** 에만 영향을 주며, JSON **역직렬화(입력 처리) 시에는 영향을 미치지 않습니다.** (역직렬화 alias 처리에 대해서는 아래를 참고하십시오.)

---
## **Alias 및 Canonical 역직렬화 (다양한 입력 키 매핑)**
표준 API Response 라이브러리는 **다양한 형태의 입력 JSON 키를 단일 객체 프로퍼티에 매핑**할 수 있는 강력한 역직렬화 기능을 제공합니다. 즉, 클라이언트가 `snake_case`, `camelCase`, `kebab-case` 등 어떤 형태로 키를 보내와도, 미리 정의된 하나의 프로퍼티로 안정적으로 매핑될 수 있습니다. 또한 여러 별칭(alias)을 지정해도 대소문자나 구분자 차이를 무시하고 매핑합니다.

### **Alias 선언 및 매핑 규칙**
우선 DTO 클래스에 **Jackson의 `@JsonProperty`와 `@JsonAlias`** 를 활용하여 키의 여러 형태를 선언해둘 수 있습니다:
```kotlin
data class AliasSample(
    @JsonProperty("user_id") val userId: Long,
    @JsonProperty("given_name") @JsonAlias("givenName") val firstName: String,
    @JsonProperty("surname") @JsonAlias("familyName", "lastName") val lastName: String,
    val emailAddress: String
): BasePayload

// 사용 예시
val json = """{
  "user_id": 10,
  "given_name": "용호",
  "surname": "황",
  "emailAddress": "jogakdal@gmail.com"
}"""

val resp = StandardResponse.deserialize<AliasSample>(json)
println(resp.payload.userId)       // 10
println(resp.payload.firstName)    // "용호"
println(resp.payload.lastName)     // "황"
```

위 `AliasSample` DTO를 보면:
- **직렬화(서버 -> JSON)** 시 `@JsonProperty` 설정에 따라 각 프로퍼티가 우선 해당 이름으로 출력됩니다. 예를 들어 `userId` 필드는 `@JsonProperty("user_id")`이 붙었으므로 JSON 출력 키가 `"user_id"`가 됩니다. `firstName`은 `"given_name"` (alias 지정), `lastName`은 `"surname"`으로 출력됩니다. 나머지 `emailAddress`는 어노테이션이 없으므로 기본 `camelCase` 그대로 (`"emailAddress"`) 출력됩니다.
- **역직렬화(JSON -> 객체)** 시에는 다음과 같은 다양한 형태의 키들을 모두 인식하여 매핑합니다:
    - `"userId"`, `"USER-ID"`, `"user-id"`, `"user_id"`, `"UserId"`, `"userid"`, `"USERID"` 등 **대소문자나 구분자 형태가 달라도** 모두 `userId` 프로퍼티로 매핑됩니다. (`user_id`의 Canonical 형태를 계산해 동일하게 간주)
    - `"given_name"`뿐 아니라 `"givenName"` (`@JsonAlias`로 지정), `"firstName"` (필드명 자체) 등의 변형된 입력도 모두 `firstName` 프로퍼티로 매핑됩니다.
    - `"surname"`, `"familyName"`, `"lastName"` 등 `lastName` 필드에 대해 정의한 별칭이나 케이스 변형들도 모두 `lastName` 프로퍼티로 처리됩니다.

이처럼 **언더스코어 ↔ 하이픈(`_` ↔ `-`) 교차 변환은 자동**으로 처리되고, **대소문자는 무시**되며, `snake_case`, `kebab-case`, `camelCase` 등이 혼합되어 들어와도 내부적으로 동일한 canonical 키로 인식되어 매핑됩니다.

### **Canonical 키 계산 방식**

내부적으로 입력 JSON의 키를 처리할 때는, **영문자와 숫자만 추출해 소문자화한 문자열**을 *canonical form*으로 삼습니다.

예를 들어:
- `"First_Name"` -> 추출 => `"firstname"`
- `"FIRST-NAME"` -> 추출 => `"firstname"`

둘 다 canonical 키가 `“firstname”`으로 동일하기 때문에 동일한 프로퍼티로 인지하게 됩니다. (`-`, `_` 등의 구분자나 대소문자 차이는 제거)

### **중첩 구조 및 컬렉션/맵 처리**

Alias/Canonical 매핑은 응답 payload 내부의 **재귀적인 구조에도 적용**됩니다. 아래의 경우들을 모두 커버합니다:

- Payload 클래스 내에 또 다른 `BasePayload` 타입의 서브 객체가 있는 경우 (중첩 DTO)
- 컬렉션(Collection\<T>, 리스트 등)의 요소 타입이 `BasePayload`인 경우
- 맵(Map\<K, V>)의 값 타입 V가 `BasePayload`인 경우
- 복합 구조: 예를 들어 List\<Map\<String, ChildPayload>> 같이 컬렉션 안에 맵, 그 맵의 값이 `Payload`인 경우 등도 전부 순회하며 키 매핑 처리

즉, **payload 전체 트리에 걸쳐** alias 및 canonical 매핑 규칙이 적용되어 어떤 깊이의 JSON이라도 키 이름 변형을 허용합니다.

### **맵 키 매핑 예제**

다음은 Map 필드를 포함하는 예시 DTO와, 해당 JSON을 역직렬화하는 코드입니다:

```kotlin
data class Child(
    @JsonProperty("child_name") @JsonAlias("childName", "child-name")
    val childName: String
): BasePayload

data class Wrapper(
    @JsonProperty("items_map")
    val items: Map<String, Child>
): BasePayload

val json = """
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-09-16T00:00:00Z",
  "duration": 10,
  "payload": {
    "items-map": {
      "a": { "child-name": "황용호" }
    }
  }
}
"""

val resp = StandardResponse.deserialize<Wrapper>(json)
println(resp.payload.items["a"]?.childName)  // 출력: "황용호"
```

위 구조에서 Wrapper의 `items` 필드는 `Map<String, Child>` 타입이고, JSON에서는 `items-map` 키 아래에 `"a": { "child-name": "황용호" }` 형태로 들어왔습니다.

라이브러리의 canonical 매핑은 `"items-map"` -> `items` 프로퍼티로, `"child-name"` -> `Child.childName` 프로퍼티로 정확히 매핑해 주며, 역직렬화 결과 `resp.payload.items["a"].childName`에 원하는 값이 들어가게 됩니다.

### **성능 및 캐싱 처리**

Alias 및 Canonical 매핑을 위해 라이브러리가 리플렉션과 문자열 처리를 수행하지만, **동일한 클래스에 대해서는 한 번만** 분석을 수행합니다.<br>
클래스(KClass) 단위로 **전역 캐싱**되므로, 애플리케이션 동작 중 반복되는 직렬화/역직렬화에 성능 영향을 최소화합니다. (대량의 DTO를 사용하는 경우 초기에 한 번 Reflection 비용이 발생)<br>
필요하다면 `clearAliasCaches()` 메서드 호출을 통해 캐시를 비울 수 있습니다 (예: 동적으로 클래스를 로드하는 환경 등 특수한 경우).

### **`@NoCaseTransform` 어노테이션과 Alias의 관계**

앞서 설명한 `@NoCaseTransform`은 **출력 시** 해당 필드의 케이스 변환을 건너뛰게 하지만, **입력(JSON 역직렬화) 시에는 Alias/Canonical 매핑에는 영향이 없습니다.** <br>
즉, `@NoCaseTransform`이 붙어 있어도 다양한 입력 케이스를 동일 property로 받아들이는 기능은 정상 동작합니다. 단지 출력할 때만 원래 alias 형태를 유지하는 역할입니다.

### **디버깅을 위한 체크리스트**
Alias/Canonical 매핑 기능을 사용할 때 발생할 수 있는 흔한 혼동 사례와 점검 방법은 다음과 같습니다:

| **증상 (문제 상황)** | **원인 가능성** | **점검 사항 및 해결책** |
| --- | --- |---|
| 역직렬화된 값이 null로 나온다 | JSON 키가 지원하는 변형 규칙 밖에 있음(매핑되지 않음) | DTO에 해당 필드에 `@JsonProperty`/`@JsonAlias` 선언이 제대로 되어 있는지, 철자나 단어 구분이 일치하는지 확인합니다. (예: 하이픈이 누락되었거나 alias를 지정 안 한 경우 등)     |
| JSON 키가 엉뚱한 필드로 매핑되었다 | 서로 다른 필드의 alias 간 **충돌** 발생 | 애플리케이션 구동 시 WARN 로그를 확인합니다. 충돌이 있다면 DTO의 `@JsonAlias` 값을 수정하여 유일하게 만드세요. (서로 다른 필드가 동일 canonical 키를 갖게 될 때 충돌 발생)        |
| 특정 필드를 케이스 변환에서 제외했는데 출력에 계속 변환된다 | 해당 필드에 `@NoCaseTransform` 누락 | DTO 클래스 정의에서 빠뜨린 것은 아닌지 확인하고 `@NoCaseTransform` 어노테이션을 추가합니다. (`@JsonProperty`만 있고 `@NoCaseTransform`이 없으면 전역 변환에 영향 받음) |

### **종합 예시: Case 변환 + Alias 적용**
마지막으로, **케이스 컨벤션 변환과 Alias 역직렬화가 함께 동작하는 예시**를 보여드립니다.<br>
앞서 정의한 `AliasSample` DTO(각 필드에 `@JsonProperty`/`@JsonAlias`를 지정, `@ResponseCase` 미지정 시 기본값 Identity 적용)를 사용한다고 가정합니다.

클라이언트로부터 다음과 같은 JSON 입력을 받았을 때:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-09-16T00:00:00Z",
  "duration": 15,
  "payload": {
    "USER-ID": 10,
    "FIRST-NAME": "용호",
    "email-address": "jogakdal@gmail.com"
  }
}
```

서버에서는 이를 역직렬화하여 `StandardResponse<AliasSample>` 객체로 변환할 수 있습니다:
```kotlin
val parsed = StandardResponse.deserialize<AliasSample>(jsonString)
println(parsed.payload.userId)       // 10
println(parsed.payload.firstName)    // "용호"
println(parsed.payload.emailAddress) // "jogakdal@gmail.com"
```

대문자 `USER-ID`, `FIRST-NAME`과 소문자 케밥 `email-address` 등의 입력 키는 모두 올바르게 `userId`, `firstName`, `emailAddress` 프로퍼티로 매핑됩니다.

이 파싱된 객체를 다시 JSON으로 직렬화할 때 특정 케이스 컨벤션을 적용할 수도 있습니다. 예를 들어 **kebab-case**로 출력하려면:
```kotlin
val outputJson = parsed.toJson(case = CaseConvention.KEBAB_CASE)
println(outputJson)
```
출력 JSON의 payload 부분 키들은 `"user-id"`, `"first-name"`, `"email-address"`처럼 케밥 케이스로 변환되어 나가게 됩니다. (케이스 변환은 출력에만 영향을 주며, 역직렬화 시에는 다양한 형태를 자동 수용함을 기억하세요)

---
## **고급 사용 패턴 – Callback 빌더 활용**
`StandardResponse.build()` 함수에는 **payload 콜백(lambda)** 을 넘겨 실행 시간 측정과 함께 응답을 생성하는 기능이 있습니다. 이를 활용하면 응답 생성을 더 유연하게 관리할 수 있는데, 직접 결과를 빌드하는 것과 콜백을 사용하는 방식의 차이는 다음과 같습니다:

| **방식** | **사용 시점** | **특징 및 장단점** |
| --- | --- | --- |
| **직접 build** | 이미 payload를 계산 완료한 경우 | 단순하고 추가 오버헤드 없음. 곧바로 주어진 payload로 응답 생성 |
| **콜백 build** | payload 계산 과정 자체를 응답 빌드에 포함하려는 경우 | 람다 블록 실행 전후로 시간 측정하여 `duration` 산출. 블록 내부에서 `status`/`version` 결정 가능. |

아래는 **직접 빌드**와 **콜백 빌드**의 간단한 코드 비교입니다:
- **Kotlin:**
```kotlin
val direct = StandardResponse.build(ErrorPayload("OK", "완료"))
val viaCallback = StandardResponse.build<ErrorPayload>(payload = null) {
    StandardCallbackResult(ErrorPayload("OK", "완료"), StandardStatus.SUCCESS, "2.2")
}
```
- **Java:**
```java
StandardResponse<ErrorPayload> via = StandardResponse.buildWithCallback(
    () -> new StandardCallbackResult(new ErrorPayload("OK", "완료", null),
                                     StandardStatus.SUCCESS, "2.2")
);
```
위 `viaCallback`/`via` 변수들은 **동일한 payload를 반환하지만**, 콜백을 사용함으로써 `duration` 필드에 build 호출부터 결과 생성까지 걸린 시간이 자동 측정되어 포함됩니다.
<br><br>
### **Kotlin 고급 패턴 예시 (Callback 빌더 응용)**
콜백 빌더를 응용하면 복잡한 비즈니스 로직을 간결하게 처리하면서, 공통적으로 소요 시간 측정이나 예외 처리 래핑 등을 표준화할 수 있습니다. 아래 몇 가지 활용 패턴들을 소개합니다:
#### (1) 조건부 status / version 동적 결정
```kotlin
data class HealthPayload(val service: String, val ok: Boolean): BasePayload

fun health(): StandardResponse<HealthPayload> =
  StandardResponse.build<HealthPayload>(payload = null) {
    val startChecks = listOf("db", "cache")
    val failures = mutableListOf<String>()
    startChecks.forEach { name ->
      val passed = runCatching { checkDependency(name) }.getOrElse { false }
      if (!passed) failures += name
    }
    if (failures.isEmpty()) {
      StandardCallbackResult(
        HealthPayload("core", true),
        status = StandardStatus.SUCCESS,
        version = "2.1"
      )
    } else {
      StandardCallbackResult(
        ErrorPayload("HEALTH_FAIL", "${failures.joinToString()} 장애"),
        status = StandardStatus.FAILURE,
        version = "2.1"
      )
    }
  }
```
위 코드에서는 몇 가지 종속 기능의 health 상태를 점검한 뒤, 모두 통과하면 `HealthPayload`를 `SUCCESS`로 반환하고, 하나라도 실패하면 `ErrorPayload`로 `FAILURE` 응답을 생성합니다.<br>
이때 **콜백 내부에서 반환한 `status`와 `version` 값이 최종 응답에 적용**되며 (초기에 build 호출 시 전달한 값보다 우선), 또한 로직 실행 시간은 자동으로 측정되어 `duration`에 포함됩니다.
<br><br>

#### **(2) 예외 발생 시 자동 Failure 래핑**
```kotlin
data class ReportPayload(val generatedAt: Instant, val size: Int): BasePayload

fun generateReport(): StandardResponse<BasePayload> =
    StandardResponse.build(payload = null) {
        try {
            val data = heavyQuery() // (시간이 오래 걸리는 작업)
            val report = buildReport(data)
            StandardCallbackResult(ReportPayload(Instant.now(), report.size), StandardStatus.SUCCESS, "1.0")
        } catch (e: ValidationException) {
            StandardCallbackResult(
                ErrorPayload("VALIDATION", e.message ?: "validation error"), 
                StandardStatus.FAILURE, 
                "1.0"
            )
        } catch (e: Exception) {
            StandardCallbackResult(ErrorPayload("UNEXPECTED", e.message ?: "err"), StandardStatus.FAILURE, "1.0")
        }
    }
```

위처럼 콜백 내부에서 `try`-`catch`를 사용하면, 발생하는 예외를 잡아 `ErrorPayload`로 변환하고 `FAILURE` 상태로 감싸 줄 수 있습니다.<br>
이 패턴을 통해 서비스 로직의 예외를 모두 표준 응답 형태로 **한 곳에서 래핑**할 수 있고, 호출 측에서는 항상 `StandardResponse` 형태만 처리하면 되므로 일관성이 높아집니다.
<br><br>

#### **(3) 사전 연산 범위 조정 (측정 포함/제외 여부)**
콜백 빌더를 사용할 때 **어떤 연산을 시간 측정에 포함할지**를 결정할 수 있습니다.<br>
예를 들어, 응답 생성 전에 미리 데이터를 가져오는 작업이 있다면 그것을 콜백 밖에서 할지, 안에서 할지에 따라 `duration`에 포함 여부가 달라집니다:
```kotlin
// (비추천) 콜백 호출 **이전에** 미리 연산 수행 -> duration 계산에 포함되지 않음
val preData = preloadLargeCache()  // 미리 대용량 캐시 로드
val resp1 = StandardResponse.build(payload = null) {
    StandardCallbackResult(preData, StandardStatus.SUCCESS, "1.0")
}

// (권장) 포함하고 싶은 연산은 콜백 **내부**로 이동 -> duration에 포함됨
val resp2 = StandardResponse.build(payload = null) {
    val data = preloadLargeCache()  // 이 연산 시간도 측정에 포함
    StandardCallbackResult(data, StandardStatus.SUCCESS, "1.0")
}
```

즉, `StandardResponse.build()` 호출 **이후에 실행되는 코드**만이 `duration` 측정에 포함됩니다.<br>
가급적 응답과 직접 관련된 처리는 모두 콜백 안으로 넣고, 정말 응답 시간에 상관없는 사전 준비 작업만 콜백 밖에서 수행하는 것이 좋습니다.
<br><br>

#### **(4) 반복 사용 가능한 헬퍼 함수로 추출**
여러 API에서 공통으로 활용할 패턴이라면 **제네릭 헬퍼 함수**로 추출해 두면 편리합니다:

```kotlin
inline fun <reified P: BasePayload> timedResponse(version: String = "1.0", block: () -> P): StandardResponse<P> =
    StandardResponse.build<P>(payload = null) {
        val payload = block()
        StandardCallbackResult(payload, StandardStatus.SUCCESS, version)
    }

// 사용 예:
data class UserPayload(val id: Long, val name: String): BasePayload

val resp = timedResponse("2.0") {
    // 비즈니스 로직 실행
    UserPayload(1, "용호")
}
```

위 `timedResponse` 유틸리티 함수는 람다에서 `BasePayload`를 생성하도록 한 뒤 자동으로 `SUCCESS` 응답으로 감싸주며, 걸린 시간을 측정해 줍니다.<br>
각 컨트롤러에서 일일이 `try`-`catch`나 시간 측정을 넣지 않고도 간결하게 응답 생성을 표준화할 수 있습니다.
<br><br>

#### (5) 실패 누적 수집 + ErrorPayload 확장
다수의 입력을 처리하는 배치 API에서 부분 실패가 발생해도 처리를 계속하고 싶다면, `ErrorPayload`를 확장하여 오류를 누적하는 방식이 유용합니다:
```kotlin
fun batchInsert(records: List<String>): StandardResponse<ErrorPayload> =
    StandardResponse.build(payload = null) {
        val resultError = ErrorPayload("OK", "모두 성공")
        records.forEach { r ->
            runCatching { insertOne(r) }.onFailure { ex ->
                if (resultError.errors.isEmpty()) {
                    // 첫 실패 발생 시 코드/메시지 변경 (부분 실패 표시)
                    resultError.code = "PART_FAIL"
                    resultError.message = "일부 실패"
                }
                resultError.addError("ROW_FAIL", ex.message ?: r)
            }
        }
        val finalStatus = if (resultError.errors.size > 1) StandardStatus.FAILURE
                          else StandardStatus.SUCCESS
        StandardCallbackResult(resultError, finalStatus, "1.0")
    }
```
이 코드에서는 `records` 리스트의 각 항목을 처리하면서 실패가 발생하면 `ErrorPayloa`d의 에러 리스트에 추가하고, 첫 실패 시에는 전체 코드/메시지를 `"PART_FAIL"`, `"일부 실패"`로 변경했습니다.<br>
처리 완료 후 errors 리스트에 한 개 이상의 항목이 있으면 `FAILURE` 상태로 응답을 반환합니다.<br>
이렇게 하면 한 번의 API 응답으로 어떤 레코드들이 실패했는지 모두 전달할 수 있습니다.
<br><br>

#### (6) duration 직접 주입(수동 측정)
대부분의 경우 콜백 빌더의 자동 측정을 활용하면 되지만, 필요에 따라 개발자가 직접 측정한 시간을 사용해 `duration`에 넣을 수도 있습니다.

`StandardResponse.build()` 호출 시 `duration` 파라미터에 직접 값을 넘기면, 내부 자동 측정값 대신 그 값이 사용됩니다:
```kotlin
fun manualTimedResponse(): StandardResponse<ErrorPayload> {
    val t0 = System.nanoTime()
    // (여기에 사전 작업 또는 별도 로직 수행 가능)
    val elapsed = (System.nanoTime() - t0) / 1_000_000  // ms 단위 변환
    return StandardResponse.build(
        payload = null,
        duration = elapsed
    ) {
        StandardCallbackResult(ErrorPayload("OK", "수동"), StandardStatus.SUCCESS, "1.0")
    }
}
```
이렇게 하면 `manualTimedResponse()` 결과의 `duration` 필드는 `elapsed` 변수로 계산한 값으로 채워지고, 라이브러리의 자동 측정치는 무시됩니다. (특정 구간만 측정하거나 커스텀 타이밍이 필요한 경우에 활용)

### **Java 고급 패턴 예시**
Java 환경에서도 Kotlin과 유사한 패턴을 적용할 수 있습니다. Java는 람다 문법 제약 등으로 표현이 다소 장황할 수 있으나, 몇 가지 예시를 보면:

#### **(1) 초기 status 값 vs 콜백 반환값 override**
```java
StandardResponse<ErrorPayload> overridden = StandardResponse.buildWithCallback(
    () -> new StandardCallbackResult(new ErrorPayload("OK", "완료", null),
                                     StandardStatus.SUCCESS, "2.2"),
    StandardStatus.FAILURE,  // 초기 status 값 (실제 결과에는 무시됨)
    "0.0",                  // 초기 version (무시됨)
    null                    // duration=null -> 자동 측정 사용
);
```
위 예에서 `buildWithCallback()` 함수의 두 번째 인자로 `FAILURE`를 주었지만, 콜백 내부에서 `SUCCESS`를 반환했으므로 최종 응답의 `status`는 `SUCCESS`가 됩니다. (초기값은 무시됨)<br>
이처럼 Java에서도 콜백 내 반환이 우선시됩니다.
<br><br>

#### **(2) 예외 처리 래핑 패턴**
```java
public StandardResponse<ErrorPayload> runTask() {
    return StandardResponse.buildWithCallback(() -> {
        try {
            TaskResult result = taskService.execute();
            return new StandardCallbackResult(result.toPayload(), StandardStatus.SUCCESS, "1.0");
        } catch (KnownException e) {
            return new StandardCallbackResult(
                new ErrorPayload("KNOWN", e.getMessage(), null),
                StandardStatus.FAILURE, "1.0"
            );
        } catch (Exception e) {
            return new StandardCallbackResult(
                new ErrorPayload("UNEXPECTED", e.getMessage(), null),
                StandardStatus.FAILURE, "1.0"
            );
        }
    });
}
```
[Kotlin 예제 (2)](#2-예외-발생-시-자동-failure-래핑)와 동일한 로직을 Java로 구현한 것으로, `KnownException`과 일반 `Exception`을 구분해 각각 다른 코드로 `ErrorPayload`를 반환하고 있습니다.<br>
이처럼 여러 `catch` 블록에서 각각 `StandardCallbackResult`를 반환하면 적절한 `FAILURE` 응답으로 포장됩니다.
<br><br>

#### **(3) 수동 duration 값 제공**
```java
public StandardResponse<ErrorPayload> manualTimed() {
    long start = System.currentTimeMillis();
    // 필요하면 여기서 사전 작업 수행
    long manualDuration = System.currentTimeMillis() - start;
    return StandardResponse.buildWithCallback(
        () -> new StandardCallbackResult(new ErrorPayload("OK", "수동", null),
                                         StandardStatus.SUCCESS, "1.0"),
        StandardStatus.SUCCESS,
        "1.0",
        manualDuration  // 자동 측정 대신 이 값을 사용
    );
}
```
앞서 [Kotlin (6)](#6-duration-직접-주입수동-측정)과 동일한 개념으로, 계산한 `manualDuration` 값을 네번째 인자로 전달하여 `duration`을 직접 지정했습니다.
<br><br>

#### **(4) 재사용 유틸리티 메서드**
```java
public static <P extends BasePayload> StandardResponse<P> time(String version, Supplier<P> supplier) {
    return StandardResponse.buildWithCallback(
        () -> new StandardCallbackResult(supplier.get(), StandardStatus.SUCCESS, version)
    );
}
```
Kotlin의 `timedResponse`와 유사하게 Java에서도 제네릭 메서드로 감싼 예입니다.<br>
사용 시 `time("2.0", () -> new UserPayload( ... ))` 형태로 호출하면, Payload 생성과 시간 측정이 함께 이루어진 표준 응답이 반환됩니다.
<br><br>

#### **(5) 배치 처리 + 오류 누적**
```java
public StandardResponse<ErrorPayload> batchProcess(List<String> inputs) {
    return StandardResponse.buildWithCallback(() -> {
        ErrorPayload ep = new ErrorPayload("OK", "모두 성공", null);
        for (String in : inputs) {
            try {
                processOne(in);
            } catch (Exception ex) {
                if (ep.getErrors().isEmpty()) {
                    ep.setCode("PART_FAIL");
                    ep.setMessage("일부 실패");
                }
                ep.addError("ROW_FAIL", ex.getMessage());
            }
        }
        StandardStatus status = ep.getErrors().size() > 1 ? StandardStatus.FAILURE
                                                          : StandardStatus.SUCCESS;
        return new StandardCallbackResult(ep, status, "1.0");
    });
}
```
[Kotlin (5) 예제](#5-실패-누적-수집--errorpayload-확장)와 대응되는 Java 구현입니다. 실패 발생 시 `ErrorPayload`의 코드/메시지를 변경하고, 오류를 `addError()` 함수로 누적하는 로직이 동일하게 적용됩니다.

Tip:
> **DB 조회나 외부 API 호출, 데이터 가공 등 실행 시간이 무시할 수 없는 모든 작업을 반드시 콜백 람다 내부에 넣는 것** 이 중요합니다. 그래야 `duration`에 정확한 응답 생성 시간이 반영됩니다.

---
## **OpenAPI (Swagger) 연동 예**
StandardResponse를 사용하면서 Swagger/OpenAPI 문서에 Example을 포함시키고 싶은 경우, `@ApiResponse`의 example 값에 **표준 응답 JSON을 직접 작성**하면 됩니다. 간단한 예를 들면:
```kotlin
@Operation(
  summary = "Ping",
  description = "헬스 체크",
  responses = [
    ApiResponse(
      responseCode = "200",
      description = "성공",
      content = [Content(
        mediaType = "application/json",
        examples = [
          ExampleObject(
            name = "pingSuccess",
            value = """{
              "status": "SUCCESS",
              "version": "1.0",
              "datetime": "2025-09-16T12:34:56Z",
              "duration": 10,
              "payload": {
                "errors": [ { "code": "OK", "message": "pong" } ],
                "appendix": {}
              }
            }"""
          )
        ]
      )]
    )
  ]
)
@GetMapping("/api/ping")
fun ping(): StandardResponse<ErrorPayload> =
    StandardResponse.build(ErrorPayload("OK", "pong"))
```
위와 같이 `@Operation` 내 `responses`에 `ExampleObject`를 넣어 표준 응답의 JSON 예시를 작성해두면, Swagger UI 상에서 해당 응답 예시를 확인할 수 있습니다.<br>
다른 API의 응답도 동일한 패턴으로 Example을 작성하면 됩니다. (각 API 마다 payload 내용만 다르게 채워주면 되므로 재활용이 가능합니다)

---
## **마이그레이션 가이드 (Legacy -> StandardResponse 전환)**
기존 레거시 응답 포맷을 **표준 응답 구조**로 전환하려는 경우, 1:1로 치환할 수 있는 예시 가이드를 제공합니다. 아래 “Before → After” 형태의 예시들을 참고하여 응답 구조를 교체하면 됩니다.

### **1) 단일 데이터 응답의 마이그레이션**
**Before (예시)** – 개별 객체를 감싸서 반환하고 상태 코드를 별도로 두는 경우:
```json
{ "data": { "id": 10, "name": "황용호" }, "code": "OK" }
```

**After (표준)** – 표준 응답으로 변환:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-09-16T12:34:56Z",
  "duration": 10,
  "payload": { "id": 10, "name": "황용호" }
}
```
- **변경 핵심:** 기존 data 필드의 내용을 표준 응답의 `payload`로 옮기고, 최상위에 `status` 필드를 추가하여 성공 시 `"SUCCESS"`를 넣습니다.
- 기존 code: `"OK"` 등 보조 상태 표시는 일반적으로 불필요해지므로 제거하거나, 꼭 필요하다면 payload 내부에 별도 필드로 포함시키는 것을 고려합니다.

### **2) 실패/오류 응답의 마이그레이션**
**Before** – 다양한 형태로 쓰이던 실패 응답 예:
```json
{ "error": "E_AUTH" }
```
```json
{ "success": false, "message": "권한 없음" }
```
```json
{ "code": "E_VALID", "message": "이메일 형식 오류" }
```
**After (표준)** – 모두 일관된 구조의 실패 응답으로:
```json
{
  "status": "FAILURE",
  "version": "1.0",
  "datetime": "2025-09-16T12:34:56Z",
  "duration": 5,
  "payload": {
    "errors": [ { "code": "E_AUTH", "message": "권한 없음" } ],
    "appendix": {}
  }
}
```
- **변경 핵심:** `status`를 `FAILURE`로 두고, 오류 정보는 `payload.errors` 배열의 첫 번째 요소로 넣습니다 (`code`와 `message` 모두 포함한 객체).
- 여러 개의 오류를 전해야 하는 경우 `errors` 배열에 추가하면 됩니다. 예를 들어 검증 오류가 다수라면 각 오류 항목을 하나의 `{code, message}` 객체로 만들어 배열에 넣음으로써 전달 가능합니다.
- 추가적인 디버그 정보나 메타데이터는 `appendix` 맵에 자유롭게 담아 함께 보내면 됩니다 (없으면 빈 {} 객체로 표시하거나 해당 필드를 생략 가능).

### **3) 리스트 응답의 마이그레이션**
#### (A) 페이지네이션(`pageable`)로 전환
**Before** – 페이지 응답 예시:
```json
{ "list": [{"id": 1}, {"id": 2}], "total": 100, "page": 1, "size": 20 }
```
**After** – PageListPayload(PageableList) 구조 적용:
```json
{
  "status": "SUCCESS",
  "version": "1.0",
  "datetime": "2025-09-16T12:34:56Z",
  "duration": 50,
  "payload": {
    "pageable": {
      "page": { "size": 20, "total": 5, "current": 1 },
      "order": { "sorted": false, "by": [] },
      "items": { "total": 100, "current": 2, "list": [{"id": 1}, {"id": 2}] }
    }
  }
}
```
- `payload.pageable.page`: `size`는 요청된 페이지 크기, `current`는 현재 페이지 번호, `total`은 **총 페이지 수**를 의미합니다. 기존 응답에서 전체 아이템 수와 페이지 크기를 알고 있다면, `총 페이지 수 = ceil(totalItems / size)`로 계산해 넣습니다 (위 예에서는 100개, 20씩 -> 총 5페이지).
- `payload.pageable.items`: 기존 list와 `total` 값을 옮긴 구조입니다. `total`은 전체 아이템 수 (100), `current`는 현재 페이지에 포함된 아이템 개수 (예: 2개), `list`는 아이템 배열 그대로.
- `payload.pageable.order`: 정렬 정보가 있다면 채워주고, 없으면 위 예시처럼 기본값을 표현합니다. (`"sorted": false`는 정렬 없음 의미)
  <br><br>

#### (B) 기존 더보기 방식을 표준 `incremental` 구조로 전환
**Before** – 커서 기반 더보기 응답 예시:
```json
{ "items": [ {"id": 1}, {"id": 2} ], "next": "id: 2" }
```

**After (표준)** – `IncrementalListPayload`(`IncrementalList`) 구조 적용:
```json
{
  "status": "SUCCESS",
  "payload": {
    "incremental": {
      "cursor": { "field": "id", "start": 1, "end": 2, "expandable": true },
      "order": {
        "sorted": false,
        "by": [ { "field": "id", "direction": "asc" } ]
      },
      "items": { "total": 100, "current": 2, "list": [ {"id": 1}, {"id": 2} ] }
    }
  }
}
```
- `payload.incremental.cursor`: 기존 `"next": "id: 2"` 등의 커서 표현을 구조화했습니다. `"field": "id"`는 커서 기준 필드명, `"start": 1`은 현재 응답에서의 시작 커서 (첫 아이템 id), `"end": 2`는 마지막 커서 값 (마지막 아이템 id)입니다. `"expandable": true`는 뒤에 더 가져올 데이터가 **있음**을 의미합니다 (다음 호출 가능). 만약 더 가져올 것이 없으면 `false`로 지정합니다.
- `payload.incremental.items`: 페이지네이션과 유사하게 현재 리스트의 아이템 개수 및 전체 개수를 표시합니다. 만약 전체 개수를 알 수 없다면 `total`을 생략하거나 `null`로 둘 수도 있습니다.
- `payload.incremental.order`: 정렬 정보를 포함합니다. 커서 기반 API에서는 **정렬 순서**가 데이터 일관성에 중요하므로, 가능하면 어떤 필드 기준으로 정렬되고 있는지 넣어주는 것이 좋습니다. (위 예에서는 `id` 오름차순으로 정렬되었다고 명시)
  <br><br>

### **4) 공통 변환 체크리스트**
기존 응답을 마이그레이션할 때 함께 고려하면 좋은 **데이터 형식 통일 가이드**입니다:
- **날짜/시간 포맷**: 가능한 모든 일시(DateTime) 표현을 ISO-8601 표준인 `"yyyy-MM-dd'T'HH:mm:ss[.SSS]X"` 형식으로 통일합니다. 예시: `"2025-09-16T12:34:56Z"` (밀리초 .SSS는 필요 시 추가).
- **Boolean 값**: 문자열 `"Y"`, `"N"` 또는 숫자 `0`, `1` 등으로 주고받던 불리언 값을 **JSON boolean** 타입인 `true`/`false`로 변경합니다.
- **숫자 값**: 숫자를 따옴표로 감싼 `"100"`과 같은 형태로 주던 것을 **숫자 타입** `100`으로 바꿉니다. (JSON에서는 숫자 타입으로 넣으면 됩니다)
- **빈 컬렉션**: 빈 리스트나 맵 등을 나타낼 때 `null` 대신 `[]` 혹은 `{}` 같은 빈 컬렉션 표기로 통일합니다. (`null` 남발을 줄이고 클라이언트 파싱 편의 향상)
- **키 이름 케이스**: 서비스 정책에 따라 `snake_case`, `camelCase` 등을 결정하여, 표준 응답 생성 시 해당 컨벤션에 맞춰 일괄 변환합니다. (예: `@ResponseCase` 어노테이션 사용 또는 `toJson(case=...)` 파라미터 활용)
  <br><br>

### **5) HTTP 상태 코드 매핑 권장**
표준 응답의 status 값과 실제 HTTP 응답 코드를 분리하여 생각할 수 있습니다. 일반적인 매핑 권장 예는 다음과 같습니다:
- **정상 처리:** HTTP 200 (OK) / 201 (Created) / 204 (No Content) 등 -> status: **SUCCESS** + 적절한 payload (데이터 또는 확인 메시지 등).
- **클라이언트 오류:** HTTP 400 (Bad Request) / 401 (Unauthorized) / 403 (Forbidden) / 404 (Not Found) / 409 (Conflict) / 422 (Unprocessable Entity) / 429 (Too Many Requests) 등 -> `status`: **FAILURE** + `ErrorPayload(code, message)`에 구체적인 오류 코드와 설명을 담아서 보냅니다.
- **서버 오류:** HTTP 500 ~ 599 -> `status`: **FAILURE** + `ErrorPayload("E_SERVER", "서버 오류")`. (원인별로 `E_DB`, `E_TIMEOUT` 등 상세 코드를 정의해 주면 좋습니다만, 외부에 노출해선 안 될 정보는 주의해서 다룹니다.)

HTTP 상태 코드는 그대로 사용하되, 응답 바디의 형식을 표준화함으로써 클라이언트는 `SUCCESS`/`FAILURE`와 오류 세부 코드를 항상 동일한 구조로 얻게 됩니다.

### **6) 단계적 적용 팁**
레거시 코드에서 표준 응답으로 전환할 때 한번에 모든 엔드포인트를 변경하기 어려울 수 있습니다. 아래와 같은 전략을 활용해 점진적으로 마이그레이션을 진행할 수 있습니다:

- **엔드포인트별 단계적 전환:** 중요도가 낮은 API부터 하나씩 응답 모델 DTO를 표준으로 변경하고, 컨트롤러의 반환 타입을 `StandardResponse`로 바꿔 나갑니다. (작게 쪼개서 순차 배포)
- **임시 호환 계층 사용:** 만약 기존 클라이언트가 새 구조를 처리하지 못하는 경우, 일정 기간 **어댑터**를 둘 수 있습니다. 예를 들어 `LegacyResponse`를 `StandardResponse`로 감싸거나 변환해 주는 래퍼를 두고, 구버전 클라이언트와 신버전 클라이언트를 모두 지원하도록 합니다.
- **글로벌 예외 처리 적용:** Spring의 `@ControllerAdvice`나 필터 등을 활용하여, 컨트롤러에서 던져지는 예외를 **한 곳에서 `StandardResponse(FAILURE)` 형태로 래핑**해주면, 개별 컨트롤러 코드를 일일이 수정하지 않고도 표준 오류 응답으로 전환할 수 있습니다. (점진적 리팩토링 비용 감소)
---

## **FAQ (자주 묻는 질문)**

| **질문 (Q)** | **답변 (A)** |
|---|---|
| **`duration` 필드가 항상 0으로 나온다** | 자동 주입 기능이 꺼져있거나, DTO에 `@InjectDuration` 어노테이션이 누락된 경우입니다. 설정(`auto-duration-calculation`)과 DTO 정의를 확인하세요. |
| **payload 역직렬화에 실패한다 (예: `null`로 나온다)** | 응답 JSON 구조나 타입 정보를 DTO와 맞게 정의했는지 확인해야 합니다. 제네릭 타입일 경우 `StandardResponse.deserialize(json, Class<T>)` 형태로 타입을 명시하면 해결됩니다. |
| **Java에서 reified 함수 (ex. `fromPage()`) 호출 불가** | Kotlin의 reified 함수는 Java에서 직접 호출할 수 없으므로, `fromPageJava()`, `buildFromTotalJava()` 등의 Java 전용 메서드를 사용해야 합니다. |
| **추가적인 커스텀 상태 값을 사용하고 싶다** | `StandardStatus`는 `SUCCESS`/`FAILURE`로 고정되어 있으므로 새로운 상태 값을 정의하지 않습니다. 대신 필요한 경우 `ErrorPayload`의 `code`나 `message` 또는 `appendix`에 세부 코드를 넣어 구분하도록 합니다. |
| **클라이언트 요청의 필드명이 다양한 케이스 컨벤션(case convention)으로 들어오는데 매핑 가능할까?** | 가능합니다. DTO에 `@JsonProperty` 및 `@JsonAlias`를 선언하고 본 라이브러리의 Canonical 매핑 기능을 활용하면, 대소문자/케이스(언더스코어, 대쉬 등) 변형을 모두 수용하여 동일한 프로퍼티로 처리합니다. |
| **특정 필드만 응답 케이스 변환의 대상에서 제외하고 싶다** | DTO 정의에서 해당 필드에 `@NoCaseTransform`을 추가하면 전역 케이스 변환 시 무시됩니다. (alias 그대로 유지) |
