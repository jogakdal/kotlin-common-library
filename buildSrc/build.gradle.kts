plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

group = "com.hunet.common_library.buildlogic"
version = "0.1.0"

gradlePlugin {
    plugins {
        create("hunetLibConvention") {
            id = "com.hunet.common-library.convention"
            implementationClass = "com.hunet.common_library.build.ConventionPublishingPlugin"
            displayName = "Hunet Common Library Convention Plugin"
            description = "Applies shared Kotlin/JVM, publication, toolchain, and repository conventions with per-module version overrides"
        }
    }
}

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("gradle-plugin", version = "2.1.20"))
}
