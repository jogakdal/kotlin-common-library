plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
}
