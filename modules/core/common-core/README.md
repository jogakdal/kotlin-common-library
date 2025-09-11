# common-core

VariableProcessor 포함 공통 코어 모듈.

## Quick Start (자동 스니펫 삽입: Kotlin)

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

## Java Quick Start (수동 예제)
```java
VariableResolverRegistry registry = VariableProcessorJava.registry(Map.of(
  "appName", (Function<List<?>, Object>) a -> "MyService",
  "upper", (Function<List<?>, Object>) a -> a.isEmpty()?"":a.getFirst().toString().toUpperCase(),
  "sum", (Function<List<?>, Object>) a -> a.stream().filter(Number.class::isInstance).mapToLong(o->((Number)o).longValue()).sum()
));
VariableProcessor vp = new VariableProcessor(List.of(registry));
String out = VariableProcessorJava.process(vp,
  "Service=%{appName}%, USER=%{upper}%",
  Map.of("upper", "Hwang")
);
VariableProcessor.Options opts = new VariableProcessorJava.OptionsBuilder()
  .ignoreCase(true).enableDefaultValue(true).ignoreMissing(true).build();
String out2 = VariableProcessorJava.process(vp,
  "User=%{name|guest}% / Sum=%{sum|0}% / Raw=%{unknown|N/A}%",
  Map.of("sum", List.of(10,20,30), "NAME", "Yongho"),
  opts
);
```

### 옵션 요약
- delimiters: 기본 "%{", "}%"
- ignoreCase: 기본 true
- ignoreMissing: 기본 false
- enableDefaultValue: 기본값 문법 활성화 여부

자세한 내용: docs/variable-processor.md 참고.
