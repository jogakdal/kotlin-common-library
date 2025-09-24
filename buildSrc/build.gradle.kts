plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
}

// buildSrc 내 교착 유발 가능 태스크 비활성화
listOf("validatePlugins","pluginUnderTestMetadata","test","check").forEach { tName ->
    tasks.matching { it.name == tName }.configureEach { enabled = false }
}
