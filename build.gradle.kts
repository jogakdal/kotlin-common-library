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
            (it as JavaPluginExtension).toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll("-Xjsr305=strict")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        if (!project.hasProperty("disableMockitoAgent")) {
            doFirst {
                val cpFiles = classpath.files
                val agentJar = cpFiles.firstOrNull { it.name.startsWith("mockito-agent") }
                    ?: cpFiles.firstOrNull { it.name.startsWith("byte-buddy-agent") }
                    ?: cpFiles.firstOrNull { it.name.startsWith("mockito-core") }
                if (agentJar != null) {
                    val existing = (this as org.gradle.process.JavaForkOptions).jvmArgs ?: emptyList()
                    if (existing.none { it.contains(agentJar.name) }) {
                        jvmArgs("-javaagent:${agentJar.absolutePath}")
                    }
                    if (existing.none { it == "-XX:+EnableDynamicAgentLoading" }) {
                        jvmArgs("-XX:+EnableDynamicAgentLoading")
                    }
                    println("[test-config] Ensured javaagent (${agentJar.name}) for ${project.path}")
                }
            }
        }
    }
}

// --- Snippet Sync (VariableProcessor 예제 기반) ---
val snippetSource = file("modules/core/common-core/src/test/kotlin/com/hunet/common/lib/examples/VariableProcessorQuickStartExample.kt")
val javaSnippetSource = file("modules/core/common-core/src/test/java/com/hunet/common/lib/examples/VariableProcessorJavaExample.java")
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

// standard-api-response-library-guide.md 파일 내 version 블록 자동 업데이트
val updateLibraryGuideVersions by tasks.registering {
    group = "documentation"
    description = "`standard-api-response-library-guide.md` 파일 내 version info 블록을 업데이트합니다."
    val guideFile = file("docs/standard-api-response-library-guide.md")
    inputs.file(guideFile)
    outputs.file(guideFile)
    doLast {
        if (!guideFile.exists()) {
            logger.warn("[updateLibraryGuideVersions] guide file not found: ${guideFile.path}")
            return@doLast
        }
        val rootVersion = project.version.toString()
        fun prop(name: String) = findProperty(name)?.toString()
        val commonCore = prop("moduleVersion.common-core") ?: rootVersion
        val annotations = prop("moduleVersion.std-api-annotations") ?: rootVersion
        val stdApi = prop("moduleVersion.standard-api-response") ?: rootVersion
        val nowSeoul = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val ts = nowSeoul.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val newBlock = """
```
Last updated: $ts
common-core: $commonCore
std-api-annotations: $annotations
standard-api-response: $stdApi
```
""".trim()
        val pattern = Regex("<!-- version-info:start -->[\\s\\S]*?<!-- version-info:end -->")
        val text = guideFile.readText()
        if (!pattern.containsMatchIn(text)) {
            logger.warn("[updateLibraryGuideVersions] version marker block이 없습니다; 스킵합니다.")
            return@doLast
        }
        val replacement = "<!-- version-info:start -->\n$newBlock\n<!-- version-info:end -->"
        val updated = pattern.replace(text) { replacement }
        if (updated != text) {
            guideFile.writeText(updated)
            println("[updateLibraryGuideVersions] updated versions -> common-core=$commonCore, std-api-annotations=$annotations, standard-api-response=$stdApi")
        } else {
            println("[updateLibraryGuideVersions] no changes (already up to date)")
        }
    }
}

if (tasks.findByName("docs") == null) {
    tasks.register("docs") { dependsOn(updateLibraryGuideVersions) }
} else {
    tasks.named("docs") { dependsOn(updateLibraryGuideVersions) }
}

val existingBuild = tasks.findByName("build")
val rootBuild: TaskProvider<Task> = if (existingBuild != null) {
    tasks.named("build")
} else {
    tasks.register("build") {
        group = "build"
        description = "Aggregate build for all subprojects"
    }
}

rootBuild.configure {
    dependsOn(updateLibraryGuideVersions)
}

gradle.projectsEvaluated {
    rootBuild.configure {
        gradle.rootProject.subprojects.forEach { sub ->
            if (sub != rootProject && sub.tasks.findByName("build") != null) {
                dependsOn(sub.path + ":build")
            }
        }
    }
    rootProject.subprojects.find { it.path == ":standard-api-response" }?.let { respProj ->
        respProj.tasks.matching { it.name == "build" }.configureEach {
            dependsOn(rootProject.tasks.named("updateLibraryGuideVersions"))
        }
    }
}
