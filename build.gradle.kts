import java.net.URI

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:3.4.4")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.10")
        classpath("org.jetbrains.kotlin:kotlin-reflect:1.9.24")
        classpath("org.jetbrains.kotlin:kotlin-allopen:2.1.10")
        classpath("org.jetbrains.kotlin:kotlin-noarg:2.1.10")
        classpath("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.10")
        classpath("com.epages:restdocs-api-spec-gradle-plugin:0.19.4")
        classpath("com.epages:restdocs-api-spec-gradle-plugin:0.19.4")
    }
}

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("kapt") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.spring") version "2.1.20"
    id("idea")
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("maven-publish")
}

group = "com.hunet.common_library"
version = "0.0.4-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    implementation(kotlin("stdlib"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("org.springdoc:springdoc-openapi-ui:1.8.0")
    implementation("org.springframework.restdocs:spring-restdocs-mockmvc:3.0.3")
    implementation("com.epages:restdocs-api-spec-mockmvc:0.19.4")
    implementation("org.springframework.boot:spring-boot-starter-test")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.hunet.common_library"
            artifactId = "kotlin-common-lib"
            version = "0.0.4-SNAPSHOT"
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
            val nexusId: String? = project.findProperty("nexus.id") as String?
                ?: System.getenv("NEXUS_ID")
            val nexusPassword: String? = project.findProperty("nexus.password") as String?
                ?: System.getenv("NEXUS_PASSWORD")
            credentials {
                username = nexusId
                password = nexusPassword
            }
        }
    }
}