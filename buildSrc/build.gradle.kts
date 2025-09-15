plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

group = "com.hunet.common_library.buildlogic"
version = "0.1.1" // bump to invalidate cached old descriptor

gradlePlugin {
    plugins {
        create("hunetLibConvention") {
            id = "com.hunet.common-library.convention"
            implementationClass = "com.hunet.commonlibrary.build.ConventionPublishingPlugin" // updated package
            displayName = "Hunet Common Library Convention Plugin"
            description = "Applies shared Kotlin/JVM, publication, toolchain, and repository conventions with per-module version overrides"
        }
    }
}

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("gradle-plugin", version = "2.1.20"))
}
