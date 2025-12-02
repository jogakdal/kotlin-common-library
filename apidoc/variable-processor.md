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

| 필드 | 기본             | 설명                                      |
|------|----------------|-----------------------------------------|
| delimiters | `("%{", "}%")` | 토큰 구분자 (open / close)                   |
| ignoreCase | `true`         | 토큰 이름 대소문자 무시 (충돌 시 예외)                 |
| ignoreMissing | `false`        | 미등록 토큰/Resolver 없을 때 원문 유지 (기본값도 없으면 원문) |
| enableDefaultValue | `false`        | `%{token\|fallback}%` 기본값 문법 활성화        |
| defaultDelimiter | `\|`           | 기본값 구분자 문자 |
| escapeChar | `\\`           | 기본값 파싱 이스케이프 문자                         |

### 기본값/미등록 처리 정책
| 상황 | ignoreMissing=false | ignoreMissing=true |
|------|--------------------|--------------------|
| Resolver 없음, 기본값 없음 | 예외 | 원문 유지 |
| Resolver 없음, 기본값 존재 | 예외 | 기본값 사용 |
| Resolver 존재, 파라미터 없음, 기본값 있음 | 기본값 반환 | 기본값 반환 |

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
