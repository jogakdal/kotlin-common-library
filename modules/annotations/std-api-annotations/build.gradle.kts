plugins {
    kotlin("jvm")
    id("maven-publish")
}

group = "com.hunet.common_library"
version = "1.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar(); withJavadocJar()
}

repositories { mavenCentral() }

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.jacksonModuleKotlin)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "std-api-annotations"
            version = project.version.toString()
        }
    }
}
