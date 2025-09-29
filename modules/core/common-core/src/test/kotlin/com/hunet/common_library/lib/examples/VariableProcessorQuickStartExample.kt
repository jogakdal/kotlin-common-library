package com.hunet.common_library.lib.examples

import com.hunet.common_library.lib.VariableProcessor
import com.hunet.common_library.lib.VariableResolverRegistry

// snippet:vp-kotlin-quickstart:start
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
// snippet:vp-kotlin-quickstart:end
