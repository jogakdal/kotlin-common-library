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
    implementation(platform(commonLibs.springBootDependencies))
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.kotlinReflect)
    implementation(commonLibs.springBootStarterWeb)
    implementation(commonLibs.springBootStarterTest)
    implementation(commonLibs.springBootStarterDataJpa)
    implementation(commonLibs.springRestdocsMockmvc)
    implementation(commonLibs.epagesRestdocsApiSpecMockmvc)
    implementation(commonLibs.jacksonModuleKotlin)
    implementation(project(":common-core"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "test-support"
            version = project.version.toString()
        }
    }
}
