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
val variableProcessExampleFile = file("apidoc/variable-processor-examples.md")

val syncSnippets by tasks.registering {
    group = "documentation"
    description = "root & common-core README 스니펫 동기화"
    inputs.files(snippetSource, javaSnippetSource)
    outputs.files(variableProcessExampleFile)
    doLast {
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

        var doc = variableProcessExampleFile.readText()

        val docAfterK = kPattern.replace(doc) { kReplacement }
        doc = if (docAfterK == doc) {
            doc + "\n\n### Kotlin Quick Start\n" + kReplacement + "\n"
        } else docAfterK

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

// --- Docs versions auto update (unified) ---
val updateDocsVersions by tasks.registering {
    group = "documentation"
    description = "문서의 최신 버전 정보 블록을 gradle.properties 값으로 동기화합니다."
    // Files
    val stdGuide = file("apidoc/standard-api-response-library-guide.md")
    val softDeleteGuide = file("apidoc/soft-delete-user-guide.md")
    val softDeleteRef = file("apidoc/soft-delete-reference.md")
    inputs.files(stdGuide, softDeleteGuide, softDeleteRef)
    outputs.files(stdGuide, softDeleteGuide, softDeleteRef)
    doLast {
        fun prop(name: String) = findProperty(name)?.toString()
        val rootVersion = project.version.toString()
        val nowSeoul = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val ts = nowSeoul.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))

        // 1) Update standard-api-response-library-guide.md (existing behavior)
        if (stdGuide.exists()) {
            val commonCore = prop("moduleVersion.common-core") ?: rootVersion
            val apidocCore = prop("moduleVersion.apidoc-core") ?: prop("moduleVersion.std-api-documentation") ?: rootVersion
            val apidocAnnotations = prop("moduleVersion.apidoc-annotations") ?: prop("moduleVersion.std-api-annotations") ?: rootVersion
            val stdApiResp = prop("moduleVersion.standard-api-response") ?: rootVersion
            val newBlock = """
```
Last updated: $ts
common-core: $commonCore
apidoc-core: $apidocCore
apidoc-annotations: $apidocAnnotations
standard-api-response: $stdApiResp
```
""".trim()
            val pattern = Regex("<!-- version-info:start -->[\\s\\S]*?<!-- version-info:end -->")
            val text = stdGuide.readText()
            if (pattern.containsMatchIn(text)) {
                val replacement = "<!-- version-info:start -->\n$newBlock\n<!-- version-info:end -->"
                val updated = pattern.replace(text) { replacement }
                if (updated != text) {
                    stdGuide.writeText(updated)
                    println("[updateDocsVersions] updated std guide versions")
                }
            } else {
                logger.warn("[updateDocsVersions] version marker block not found in ${stdGuide.path}")
            }
        } else logger.warn("[updateDocsVersions] std guide not found: ${stdGuide.path}")

        // 2) Update soft-delete-user-guide.md
        if (softDeleteGuide.exists()) {
            val jpaRepoExtVersion = prop("moduleVersion.jpa-repository-extension") ?: rootVersion
            val newBlock = """
```
Last updated: $ts
jpa-repository-extension: $jpaRepoExtVersion
```
""".trim()
            val pattern = Regex("<!-- version-info:start -->[\\s\\S]*?<!-- version-info:end -->")
            val text = softDeleteGuide.readText()
            if (pattern.containsMatchIn(text)) {
                val replacement = "<!-- version-info:start -->\n$newBlock\n<!-- version-info:end -->"
                val updated = pattern.replace(text) { replacement }
                if (updated != text) {
                    softDeleteGuide.writeText(updated)
                    println("[updateDocsVersions] updated soft-delete guide version -> $jpaRepoExtVersion")
                }
            } else {
                logger.warn("[updateDocsVersions] version marker block not found in ${softDeleteGuide.path}")
            }
        } else logger.warn("[updateDocsVersions] soft-delete guide not found: ${softDeleteGuide.path}")

        // 3) Update soft-delete-reference.md
        if (softDeleteRef.exists()) {
            val jpaRepoExtVersion = prop("moduleVersion.jpa-repository-extension") ?: rootVersion
            val newBlock = """
```
Last updated: $ts
jpa-repository-extension: $jpaRepoExtVersion
```
""".trim()
            val pattern = Regex("<!-- version-info:start -->[\\s\\S]*?<!-- version-info:end -->")
            val text = softDeleteRef.readText()
            if (pattern.containsMatchIn(text)) {
                val replacement = "<!-- version-info:start -->\n$newBlock\n<!-- version-info:end -->"
                val updated = pattern.replace(text) { replacement }
                if (updated != text) {
                    softDeleteRef.writeText(updated)
                    println("[updateDocsVersions] updated soft-delete reference version -> $jpaRepoExtVersion")
                }
            } else {
                logger.warn("[updateDocsVersions] version marker block not found in ${softDeleteRef.path}")
            }
        } else logger.warn("[updateDocsVersions] soft-delete reference not found: ${softDeleteRef.path}")
    }
}

if (tasks.findByName("docs") == null) {
    tasks.register("docs") { dependsOn(updateDocsVersions) }
} else {
    tasks.named("docs") { dependsOn(updateDocsVersions) }
}

val existingBuild = tasks.findByName("build")
val rootBuild: TaskProvider<Task> = if (existingBuild != null) tasks.named("build") else tasks.register("build") {
    group = "build"
    description = "Aggregate build for all subprojects"
}
rootBuild.configure { dependsOn(updateDocsVersions) }

gradle.projectsEvaluated {
    rootBuild.configure {
        gradle.rootProject.subprojects.forEach { sub ->
            if (sub != rootProject && sub.tasks.findByName("build") != null) {
                dependsOn(sub.path + ":build")
            }
        }
    }
}
