import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    kotlin("jvm")
    id("maven-publish")
}

val rootGroup = rootProject.findProperty("group") as String?
if (rootGroup != null) group = rootGroup

fun resolveModuleVersion(): String {
    val rootVersion = rootProject.version.toString()
    val keyByPath = "moduleVersion." + path.removePrefix(":").replace(':', '.')
    val keyByName = "moduleVersion." + name
    val v1 = findProperty(keyByPath) as String?
    val v2 = findProperty(keyByName) as String?
    return (v1 ?: v2 ?: rootVersion).trim()
}

version = resolveModuleVersion()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll("-Xjsr305=strict")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val skipPublish: Boolean = when (val raw = findProperty("publish.skip")) {
    is Boolean -> raw
    is String -> raw.toBooleanStrictOrNull() == true
    else -> false
}

afterEvaluate {
    if (skipPublish) return@afterEvaluate
    extensions.configure(PublishingExtension::class.java) {
        publications {
            if (findByName("maven") == null) {
                create("maven", MavenPublication::class.java) {
                    components.findByName("java")?.let { from(it) }
                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()
                }
            } else withType(MavenPublication::class.java).configureEach {
                groupId = project.group.toString()
                version = project.version.toString()
            }
        }
        if (repositories.none { it.name == "nexus" }) {
            val releaseUrl = findProperty("repository.release.url") as String?
            val snapshotUrl = findProperty("repository.snapshot.url") as String?
            if (!releaseUrl.isNullOrBlank() && !snapshotUrl.isNullOrBlank()) {
                repositories {
                    maven {
                        name = "nexus"
                        url = uri(
                            if (version.toString().endsWith("-SNAPSHOT")) snapshotUrl else releaseUrl
                        )
                        credentials {
                            username = (findProperty("nexus.id") as String?) ?: System.getenv("NEXUS_ID")
                            password = (findProperty("nexus.password") as String?) ?: System.getenv("NEXUS_PASSWORD")
                        }
                    }
                }
            }
        }
    }
}
