plugins {
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = rootProject.property("group") as String
    repositories { mavenCentral() }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.findByName("java")?.let {
            (it as org.gradle.api.plugins.JavaPluginExtension).toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll("-Xjsr305=strict")
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

// --- Snippet Sync (VariableProcessor 예제 기반) ---
val snippetSource = file("modules/core/common-core/src/test/kotlin/com/hunet/common_library/lib/examples/VariableProcessorQuickStartExample.kt")
val javaSnippetSource = file("modules/core/common-core/src/test/java/com/hunet/common_library/lib/examples/VariableProcessorJavaExample.java")
val rootReadme = file("README.md")
val coreReadme = file("modules/core/common-core/README.md")
val variableProcessExampleFile = file("docs/variable-processor-examples.md")

val syncSnippets by tasks.registering {
    group = "documentation"
    description = "root & common-core README 스니펫 동기화"
    inputs.files(snippetSource, javaSnippetSource)
    outputs.files(variableProcessExampleFile)
    doLast {
        // --- Kotlin snippet (vp-kotlin-quickstart) ---
        val kText = snippetSource.readText()
        val kRegex = Regex("// snippet:vp-kotlin-quickstart:start(.*?)// snippet:vp-kotlin-quickstart:end", RegexOption.DOT_MATCHES_ALL)
        val kMatch = kRegex.find(kText) ?: error("스니펫 범위를 찾을 수 없습니다 (vp-kotlin-quickstart)")
        val kRaw = kMatch.groupValues[1].trimStart('\n','\r')
        val kCodeBlock = kRaw.trim().lines().joinToString("\n")
        val kReplacement = buildString {
            append("<!-- snippet:vp-kotlin-quickstart:start -->\n")
            append("```kotlin\n")
            append(kCodeBlock)
            append("\n```\n")
            append("<!-- snippet:vp-kotlin-quickstart:end -->")
        }
        val kPattern = Regex("<!-- snippet:vp-kotlin-quickstart:start -->(.*?)<!-- snippet:vp-kotlin-quickstart:end -->", RegexOption.DOT_MATCHES_ALL)

        // --- Java snippet (vp-java-quickstart) ---
        val jText = javaSnippetSource.readText()
        val jRegex = Regex("// snippet:vp-java-quickstart:start(.*?)// snippet:vp-java-quickstart:end", RegexOption.DOT_MATCHES_ALL)
        val jMatch = jRegex.find(jText) ?: error("스니펫 범위를 찾을 수 없습니다 (vp-java-quickstart)")
        val jRaw = jMatch.groupValues[1].trimStart('\n','\r')
        val jCodeBlock = jRaw.trim().lines().joinToString("\n")
        val jReplacement = buildString {
            append("<!-- snippet:vp-java-quickstart:start -->\n")
            append("```java\n")
            append(jCodeBlock)
            append("\n```\n")
            append("<!-- snippet:vp-java-quickstart:end -->")
        }
        val jPattern = Regex("<!-- snippet:vp-java-quickstart:start -->(.*?)<!-- snippet:vp-java-quickstart:end -->", RegexOption.DOT_MATCHES_ALL)

        // --- Upsert into docs file ---
        var doc = variableProcessExampleFile.readText()

        // Kotlin block: replace if exists, otherwise append with a heading
        val docAfterK = kPattern.replace(doc) { kReplacement }
        doc = if (docAfterK == doc) {
            doc + "\n\n### Kotlin Quick Start\n" + kReplacement + "\n"
        } else docAfterK

        // Java block: replace if exists, otherwise append with a heading
        val docAfterJ = jPattern.replace(doc) { jReplacement }
        val finalDoc = if (docAfterJ == doc) {
            doc + "\n\n### Java Quick Start\n" + jReplacement + "\n"
        } else docAfterJ

        if (variableProcessExampleFile.readText() != finalDoc) {
            variableProcessExampleFile.writeText(finalDoc)
            println("[syncSnippets] updated ${variableProcessExampleFile.path}")
        } else println("[syncSnippets] no changes for ${variableProcessExampleFile.path}")
    }
}

if (tasks.findByName("docs") == null) {
    tasks.register("docs") { dependsOn(syncSnippets) }
} else {
    tasks.named("docs") { dependsOn(syncSnippets) }
}
