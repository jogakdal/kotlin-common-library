# VariableProcessor 고급 사용 가이드

## 개요
토큰 기반 문자열 치환을 단순/확장 모두 지원하는 경량 구성 요소입니다. Spring 빈 자동 주입 또는 수동 생성 방식 지원.

## 설계 요약
- Resolver 집합(VariableResolverRegistry)들을 수집하여 내부 map 구성
- process* 계열 메서드가 Regex 기반 탐색 후 Resolver 실행
- 파라미터 전달: vararg Pair<String, Any?> 또는 Map<String, Any?> (Map 오버로드) 지원
- 캐싱: Delimiters 별 Regex 캐시 (ConcurrentHashMap)

## Options
`VariableProcessor.Options` 로 모든 동작 제어:

| 필드                 | 기본             | 설명                                       |
|--------------------|----------------|------------------------------------------|
| delimiters         | `("%{", "}%")` | 토큰 구분자 (open / close)                    |
| ignoreCase         | `true`         | 토큰 이름 대소문자 무시 (충돌 시 예외)                  |
| ignoreMissing      | `false`        | 미등록 토큰/Resolver 없을 때 원문 유지 (기본값도 없으면 원문) |
| enableDefaultValue | `false`        | `%{token\|fallback}%` 기본값 문법 활성화         |
| defaultDelimiter   | `\|`           | 기본값 구분자 문자                               |
| escapeChar         | `\\`           | 기본값 파싱 이스케이프 문자                          |

### 기본값/미등록 처리 정책
| 상황                           | ignoreMissing=false | ignoreMissing=true |
|------------------------------|---------------------|--------------------|
| Resolver 없음, 기본값 없음          | 예외                  | 원문 유지              |
| Resolver 없음, 기본값 존재          | 예외                  | 기본값 사용             |
| Resolver 존재, 파라미터 없음, 기본값 있음 | 기본값 반환              | 기본값 반환             |

### Kotlin 사용 예
```kotlin
val out = vp.process(
    "User=%{name|guest}% / Sum=%{sum|0}% / Raw=%{UNKNOWN|N/A}%",
    options = VariableProcessor.Options(
        delimiters = VariableProcessor.Delimiters("%{", "}%"),
        ignoreCase = true,
        enableDefaultValue = true,
        ignoreMissing = true
    ),
    "NAME" to "Hwang Yongho",
    "sum" to listOf(1,2,3)
)
// User=Hwang Yongho / Sum=6 / Raw=N/A
```

### Java 사용 예 (Map 오버로드 + Builder)
```java
VariableProcessor vp = new VariableProcessor(List.of(new MyRegistry()));
VariableProcessor.Options opts = new VariableProcessorJava.OptionsBuilder()
    .ignoreCase(true)
    .enableDefaultValue(true)
    .ignoreMissing(true)
    .build();
String out = vp.process(
    "User=%{name|guest}% / Sum=%{sum|0}% / Raw=%{unknown|N/A}%",
    opts,
    Map.of("sum", List.of(10,20,30), "NAME", "Hwang Yongho")
);
```

커스텀 구분자 예 (Kotlin):
```kotlin
val r = vp.process(
    "Hello <<user|guest>> / Total=<<sum>>",
    options = VariableProcessor.Options(
        delimiters = VariableProcessor.Delimiters("<<", ">>"),
        enableDefaultValue = true,
        ignoreMissing = true
    ),
    "sum" to listOf(5,5)
)
// Hello guest / Total=10
```

## 데이터 맵 기반 동적 치환 (processWithData)

레지스트리 등록 없이 데이터 맵에서 직접 값을 참조하여 치환합니다.

### 지원 표현식
| 표현식                  | 설명      | 예시                                   |
|----------------------|---------|--------------------------------------|
| `${variable}`        | 단순 변수   | `${name}` → `data["name"]`           |
| `${object.property}` | 프로퍼티 접근 | `${user.name}` → `data["user"].name` |

### 프로퍼티 접근 순서
1. Map인 경우: 키로 직접 접근
2. 일반 객체: 필드 → getter(`getXxx`) → is getter(`isXxx`) → Kotlin 프로퍼티

### Kotlin 사용 예
```kotlin
data class User(val name: String, val age: Int)

val data = mapOf(
    "title" to "사용자 정보",
    "user" to User("황용호", 30),
    "items" to mapOf("count" to 5)
)

val result = vp.processWithData(
    "제목: \${title}, 이름: \${user.name}, 나이: \${user.age}, 개수: \${items.count}",
    data
)
// 제목: 사용자 정보, 이름: 황용호, 나이: 30, 개수: 5
```

### 커스텀 구분자 사용
```kotlin
val result = vp.processWithData(
    "Hello %{user.name}%!",
    data,
    VariableProcessor.Options(
        delimiters = VariableProcessor.Delimiters("%{", "}%"),
        ignoreMissing = true
    )
)
```

### 기본 옵션
- 구분자: `${`, `}` (JSTL/EL 스타일)
- ignoreMissing: `true` (미등록 변수는 원문 유지)

> **참고**: `processWithData`는 레지스트리 기반 Resolver를 사용하지 않으므로, 단순 값 치환에 적합합니다.
> 합계 계산 등 로직이 필요한 경우 기존 `process()` + Resolver 조합을 사용하세요.

## Spring 통합
### 1) 표준 DI (Kotlin)
```kotlin
@Component
class GreetingService(private val variableProcessor: VariableProcessor) {
    fun greet(user: String?) = variableProcessor.process(
        "Hello %{name|guest}%",
        VariableProcessor.Options(enableDefaultValue = true, ignoreMissing = true),
        mapOf("NAME" to user)
    )
}
```

### 2) 표준 DI (Java)
```java
@Service
public class GreetingService {
  private final VariableProcessor vp;
  public GreetingService(VariableProcessor vp) { this.vp = vp; }
  public String greet(String user) {
    return vp.process(
      "Hello %{name|guest}%",
      new VariableProcessorJava.OptionsBuilder()
        .enableDefaultValue(true)
        .ignoreMissing(true)
        .build(),
      Map.of("NAME", user)
    );
  }
}
```

### 3) SpringContextHolder Fallback (DI 불가 상황)
```kotlin
fun legacyCall(raw: String?): String {
    val vp = SpringContextHolder.getBean<VariableProcessor>()
    return vp.process(
        "[LEGACY] %{name|unknown}% :: %{raw|empty}%",
        VariableProcessor.Options(enableDefaultValue = true, ignoreMissing = true),
        mapOf("name" to raw, "raw" to raw)
    )
}
```
> 주의: SpringContextHolder는 테스트 용이성과 의존성 명시성을 떨어뜨리므로 마지막 수단으로만 사용.

## 스니펫 (Quick Start)
메인 README와 동기화되는 예제: README.md 참조.

## FAQ
Q. Resolver 간 이름 충돌 시?
A. 마지막 등록 wins. (사전 검증 권장)

Q. 기본값 문법은 중첩 가능?
A. 현재 단순 파싱 (중첩 미지원).

Q. 성능 최적화 포인트?
A. Options 재사용, Resolver 순수 함수, 긴 텍스트는 한 번 처리.

Q. 다중 스레드 안전성?
A. 불변 map + Regex 캐시. Resolver 가 side-effect 없으면 안전.
