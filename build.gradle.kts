plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("kapt") version "2.1.20" apply false
    kotlin("plugin.jpa") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.spring") version "2.1.20" apply false
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("maven-publish")
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

// --- Snippet Sync (내부 common-core 예제 기반) ---
val snippetSource = file("modules/core/common-core/src/test/kotlin/com/hunet/common_library/lib/examples/VariableProcessorQuickStartExample.kt")
val rootReadme = file("README.md")
val coreReadme = file("modules/core/common-core/README.md")

val syncSnippets by tasks.registering {
    group = "documentation"
    description = "root & common-core README 스니펫 동기화"
    inputs.file(snippetSource)
    outputs.files(rootReadme, coreReadme)
    doLast {
        val text = snippetSource.readText()
        val snippetRegex = Regex("// snippet:vp-quickstart:start(.*?)// snippet:vp-quickstart:end", RegexOption.DOT_MATCHES_ALL)
        val match = snippetRegex.find(text) ?: error("스니펫 범위를 찾을 수 없습니다 (vp-quickstart)")
        val raw = match.groupValues[1].trimStart('\n','\r')
        val codeBlock = raw.trim().lines().joinToString("\n")
        val replacement = buildString {
            append("<!-- snippet:vp-quickstart:start -->\n")
            append("```kotlin\n")
            append(codeBlock)
            append("\n```\n")
            append("<!-- snippet:vp-quickstart:end -->")
        }
        val pattern = Regex("<!-- snippet:vp-quickstart:start -->(.*?)<!-- snippet:vp-quickstart:end -->", RegexOption.DOT_MATCHES_ALL)
        listOf(rootReadme, coreReadme).forEach { f ->
            val original = f.readText()
            val updated = pattern.replace(original) { replacement }
            if (original != updated) {
                f.writeText(updated)
                println("[syncSnippets] updated ${'$'}{f.path}")
            } else println("[syncSnippets] no changes for ${'$'}{f.path}")
        }
    }
}

// docs 태스크가 아직 없다면 등록
if (tasks.findByName("docs") == null) {
    tasks.register("docs") { dependsOn(syncSnippets) }
} else {
    tasks.named("docs") { dependsOn(syncSnippets) }
}
