plugins {
    id("com.hunet.commonlib.convention")
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
    testRuntimeOnly(commonLibs.junitPlatformLauncher)
    testImplementation(commonLibs.h2)
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
