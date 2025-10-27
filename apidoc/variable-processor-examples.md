# Standard API Response 활용 예제

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
