# hunet-common-libs

멀티 모듈 공통 라이브러리. 각 모듈별 상세 문서는 해당 모듈 디렉터리의 README를 참고.

주요 모듈
- common-core: VariableProcessor 등 핵심 유틸 (modules/core/common-core/README.md 참고)
- 기타: response, documentation, annotations, jpa, test-support

## 의존성 (예: Gradle Kotlin DSL)
```kotlin
dependencies {
    implementation("com.hunet.common_library:common-core:1.0.0-SNAPSHOT")
}
```

## VariableProcessor Quick Start (공통 안내)
이 스니펫은 common-core 모듈 내부 예제 코드와 동기화됩니다.

<!-- snippet:vp-quickstart:start -->
```kotlin
/** VariableProcessor Quick Start 예제 (모듈 내부) */
class BasicVariableRegistry : VariableResolverRegistry {
    override val resolvers = mapOf(
        "appName" to { _: List<Any?> -> "MyService" },
        "upper" to { args: List<Any?> -> args.firstOrNull()?.toString()?.uppercase().orEmpty() },
        "sum" to { args: List<Any?> -> args.filterIsInstance<Number>().sumOf { it.toLong() } },
        "greet" to { args: List<Any?> ->
            val name = args.getOrNull(0)?.toString() ?: "Anonymous"
            "Hello, $name"
        }
    )
}

class DateVariableRegistry : VariableResolverRegistry {
    override val resolvers = mapOf(
        "formatDate" to { args: List<Any?> ->
            val epochMillis = args.getOrNull(0) as? Long ?: error("epochMillis(Long) 필요")
            val pattern = args.getOrNull(1) as? String ?: "yyyy-MM-dd HH:mm"
            val dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            java.time.format.DateTimeFormatter.ofPattern(pattern).format(dt)
        }
    )
}

fun main() {
    val processor = VariableProcessor(listOf(BasicVariableRegistry(), DateVariableRegistry()))

    val r1 = processor.process(
        "Service=%{appName}%, USER=%{upper}%, SUM=%{sum}%",
        "upper" to "Hwang",
        "sum" to listOf(10, 20, 30)
    )
    println(r1) // Service=MyService, USER=HWANG, SUM=60

    val r2 = processor.process(
        "오늘=%{formatDate}%",
        "formatDate" to listOf(System.currentTimeMillis(), "yyyy-MM-dd HH:mm")
    )
    println(r2)

    val custom = VariableProcessor.Delimiters("\${", "}")
    val r3 = processor.process(
        "Hi=\${greet}",
        custom,
        "greet" to "World"
    )
    println(r3) // Hi=Hello, World
}
```
<!-- snippet:vp-quickstart:end -->

자세한 사용 / 옵션 표: modules/core/common-core/docs/variable-processor.md

### 개념 요약
- Resolver: 토큰명 -> (List<Any?>) -> Any 함수 매핑
- process(template, vararg): 기본 구분자 + ignoreCase=true
- process(template, ignoreCase, ...): 대소문자 제어
- process(template, delimiters, ...): 커스텀 구분자 지정
- process(template, options, ...): 모든 설정(구분자/ignoreMissing/기본값/대소문자) 지정
- 기본값 문법: %{token`|`fallback}% (enableDefaultValue=true)
- 미등록 토큰 무시: ignoreMissing=true

### Options 예시
```kotlin
val out = processor.process(
  "User=%{name|guest}% / Sum=%{sum|0}%",
  options = VariableProcessor.Options(
    ignoreCase = true,
    ignoreMissing = true,
    enableDefaultValue = true
  ),
  "NAME" to "Hwang Yongho",
  "sum" to listOf(1,2,3)
)
```

문서 동기화: `./gradlew syncSnippets`

## 문서 & 예제 링크
- [상세 가이드(루트)](docs/variable-processor.md)
- [모듈 사본](modules/core/common-core/docs/variable-processor.md)
- [실행형 예제](examples/VariableProcessorExample.kt)
