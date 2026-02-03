# VariableProcessor 활용 예제

## Resolver 기반 치환

<!-- snippet:vp-kotlin-quickstart:start -->
```kotlin
/** VariableProcessor Quick Start 예제 */
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
        "upper" to "Hwang Yongho",
        "sum" to listOf(10, 20, 30)
    )
    println(r1) // Service=MyService, USER=HWANG YONGHO, SUM=60

    val r2 = processor.process(
        "오늘=%{formatDate}%",
        "formatDate" to listOf(System.currentTimeMillis(), "yyyy-MM-dd HH:mm")
    )
    println(r2)

    val custom = VariableProcessor.Delimiters("\${", "}")
    val r3 = processor.process(
        "Hi=\${greet}",
        custom,
        "greet" to "Hwang Yongho"
    )
    println(r3) // Hi=Hello, Hwang Yongho
}
```
<!-- snippet:vp-kotlin-quickstart:end -->


### Java Quick Start
<!-- snippet:vp-java-quickstart:start -->
```java
/** Java Quick Start 예제 */
public class VariableProcessorJavaExample {

    public static void main(String[] args) {
        VariableResolverRegistry registry = VariableProcessorJava.registry(
                Map.of(
                        "appName",a -> "MyService",
                        "upper", a -> {
                            Object v = a.isEmpty() ? null : a.get(0);
                            return v == null ? "" : v.toString().toUpperCase();
                        },
                        "sum", a -> a.stream()
                                .filter(Number.class::isInstance)
                                .mapToLong(o -> ((Number) o).longValue()).sum(),
                        "greet", a -> "Hello, " + (a.isEmpty() ? "Anonymous" : a.getFirst())
                )
        );
        VariableProcessor vp = new VariableProcessor(List.of(registry));

        String out = VariableProcessorJava.process(
                vp,
                "Service=%{appName}%, USER=%{upper}%",
                Map.of("upper", "Hwang Yongho")
        );
        System.out.println(out); // Service=MyService, USER=HWANG YONGHO

        var opts = new VariableProcessorJava.OptionsBuilder()
                .ignoreCase(true)
                .enableDefaultValue(true)
                .ignoreMissing(true)
                .build();

        String out2 = VariableProcessorJava.process(
                vp,
                "User=%{name|guest}% / Sum=%{sum|0}% / Raw=%{unknown|N/A}%",
                Map.of("sum", List.of(10,20,30), "NAME", "Hwang Yongho"),
                opts
        );
        System.out.println(out2); // User=Hwang Yongho / Sum=60 / Raw=N/A

        String out3 = vp.process(
                "Hi=<<greet>>",
                new VariableProcessor.Delimiters("<<", ">>"),
                Map.of("greet", "Hwang Yongho")
        );
        System.out.println(out3); // Hi=Hello, Hwang Yongho
    }
}
```
<!-- snippet:vp-java-quickstart:end -->

---

## 데이터 맵 기반 동적 치환 (processWithData)

레지스트리 없이 데이터 맵의 값을 직접 참조하여 치환합니다.

### Kotlin 예제
```kotlin
data class User(val name: String, val age: Int)
data class Address(val city: String, val zipCode: String)

fun main() {
    val vp = VariableProcessor(emptyList())  // 레지스트리 불필요

    // 단순 변수 치환
    val data1 = mapOf("title" to "보고서", "date" to "2026-02-03")
    val r1 = vp.processWithData("제목: \${title}, 날짜: \${date}", data1)
    println(r1)  // 제목: 보고서, 날짜: 2026-02-03

    // 객체 프로퍼티 접근 (dot notation)
    val data2 = mapOf(
        "user" to User("황용호", 30),
        "address" to Address("서울", "12345")
    )
    val r2 = vp.processWithData(
        "이름: \${user.name}, 나이: \${user.age}, 도시: \${address.city}",
        data2
    )
    println(r2)  // 이름: 황용호, 나이: 30, 도시: 서울

    // 중첩 Map 접근
    val data3 = mapOf(
        "config" to mapOf(
            "database" to mapOf("host" to "localhost", "port" to 5432)
        )
    )
    val r3 = vp.processWithData(
        "DB: \${config.database.host}:\${config.database.port}",
        data3
    )
    println(r3)  // DB: localhost:5432

    // 커스텀 구분자 사용
    val r4 = vp.processWithData(
        "Hello %{user.name}%!",
        data2,
        VariableProcessor.Options(
            delimiters = VariableProcessor.Delimiters("%{", "}%")
        )
    )
    println(r4)  // Hello 황용호!
}
```

### Java 예제
```java
public class ProcessWithDataExample {
    public record User(String name, int age) {}

    public static void main(String[] args) {
        VariableProcessor vp = new VariableProcessor(List.of());

        // 단순 변수 치환
        Map<String, Object> data1 = Map.of("title", "Report", "count", 100);
        String r1 = vp.processWithData("Title: ${title}, Count: ${count}", data1);
        System.out.println(r1);  // Title: Report, Count: 100

        // 객체 프로퍼티 접근
        Map<String, Object> data2 = Map.of("user", new User("황용호", 25));
        String r2 = vp.processWithData("Name: ${user.name}, Age: ${user.age}", data2);
        System.out.println(r2);  // Name: 황용호, Age: 25
    }
}
```
