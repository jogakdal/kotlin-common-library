plugins {
    kotlin("jvm")
    kotlin("plugin.jpa")
    id("org.jetbrains.kotlin.plugin.spring")
    id("maven-publish")
}

group = "com.hunet.common_library"
version = "1.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(platform(commonLibs.springBootDependencies))
    implementation(commonLibs.springBootStarterDataJpa)
    implementation(commonLibs.kotlinReflect)
    implementation(project(":common-core"))

    testImplementation(commonLibs.springBootStarterTest)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "jpa-repository-extension"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "nexus"
            url = uri(
                if (version.toString().endsWith("-SNAPSHOT")) {
                    project.findProperty("repository.snapshot.url") as String
                } else {
                    project.findProperty("repository.release.url") as String
                }
            )
            val nexusId: String? = project.findProperty("nexus.id") as String? ?: System.getenv("NEXUS_ID")
            val nexusPassword: String? = project.findProperty("nexus.password") as String? ?: System.getenv("NEXUS_PASSWORD")
            credentials {
                username = nexusId
                password = nexusPassword
            }
        }
    }
}
