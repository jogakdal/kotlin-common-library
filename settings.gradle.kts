pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.kotlin.kapt") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.jpa") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.spring") version "2.1.20"
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        register("commonLibs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "hunet-common-libs"
include(
    ":common-core",
    ":standard-api-response",
    ":std-api-documentation",
    ":std-api-annotations",
    ":test-support",
    ":jpa-repository-extension"
)

project(":common-core").projectDir = file("modules/core/common-core")
project(":standard-api-response").projectDir = file("modules/response/standard-api-response")
project(":std-api-documentation").projectDir = file("modules/docs/std-api-documentation")
project(":std-api-annotations").projectDir = file("modules/annotations/std-api-annotations")
project(":test-support").projectDir = file("modules/test/test-support")
project(":jpa-repository-extension").projectDir = file("modules/jpa/jpa-repository-extension")
