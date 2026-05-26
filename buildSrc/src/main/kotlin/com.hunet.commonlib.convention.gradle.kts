import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

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

// release 버전 아티팩트가 Nexus release 저장소에 이미 배포돼 있는지 확인한다.
// release 저장소는 동일 좌표(GAV) 덮어쓰기를 막으므로, 이미 있으면 publish를 건너뛰어야 한다.
// SNAPSHOT은 덮어쓰기가 허용되므로 이 검사 대상이 아니다(호출 측에서 제외한다).
fun isReleaseAlreadyPublished(): Boolean {
    val moduleVersion = version.toString()
    val releaseUrl = (findProperty("repository.release.url") as String?)?.trim()?.trimEnd('/') ?: return false
    val groupPath = group.toString().replace('.', '/')
    val jarUrl = "$releaseUrl/$groupPath/$name/$moduleVersion/$name-$moduleVersion.jar"
    val user = (findProperty("nexus.id") as String?) ?: System.getenv("NEXUS_ID")
    val password = (findProperty("nexus.password") as String?) ?: System.getenv("NEXUS_PASSWORD")
    return runCatching {
        val connection = (URI(jarUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        connection.responseCode.also { connection.disconnect() } in 200..299
    }.getOrElse {
        // 확인 실패(네트워크 오류 등) 시에는 배포 누락을 막기 위해 publish를 진행한다.
        logger.warn("[publish-guard] $name:$moduleVersion 존재 여부 확인에 실패했습니다(${it.message}). publish를 진행합니다.")
        false
    }
}

// 배포 계획 진단용 태스크. 각 모듈이 publish 시 배포될지 건너뛸지 미리 점검한다.
// Jenkins 빌드 전 `./gradlew reportPublishPlan` 으로 무엇이 올라갈지 확인할 수 있다.
tasks.register("reportPublishPlan") {
    group = "publishing"
    description = "publish 시 이 모듈이 배포될지 건너뛸지 판정 결과를 출력합니다."
    doLast {
        val moduleVersion = project.version.toString()
        val verdict = when {
            skipPublish -> "SKIP (publish.skip=true)"
            moduleVersion.endsWith("-SNAPSHOT") -> "PUBLISH (snapshot)"
            isReleaseAlreadyPublished() -> "SKIP (이미 Nexus release에 존재)"
            else -> "PUBLISH (신규 release)"
        }
        logger.lifecycle("[publish-plan] ${project.group}:${project.name}:$moduleVersion -> $verdict")
    }
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

    // 이미 배포된 release 버전을 다시 publish하면 Nexus release 저장소가 400으로 거부한다.
    // 동일 좌표가 이미 존재하면 해당 모듈의 nexus publish 태스크를 건너뛴다.
    tasks.withType(PublishToMavenRepository::class.java).configureEach {
        val publishTask = this
        onlyIf("이미 배포된 release 버전이면 publish를 건너뛴다") {
            val moduleVersion = project.version.toString()
            when {
                publishTask.repository?.name != "nexus" -> true
                moduleVersion.endsWith("-SNAPSHOT") -> true
                isReleaseAlreadyPublished() -> {
                    logger.lifecycle("[publish-guard] ${project.group}:${project.name}:$moduleVersion 이미 Nexus release에 존재하여 publish를 건너뜁니다.")
                    false
                }
                else -> true
            }
        }
    }
}
