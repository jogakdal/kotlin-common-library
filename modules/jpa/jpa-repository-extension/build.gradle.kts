plugins {
    id("com.hunet.common-library.convention")
    kotlin("plugin.jpa")
    id("org.jetbrains.kotlin.plugin.spring")
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
