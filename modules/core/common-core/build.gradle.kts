plugins {
    kotlin("jvm")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.spring")
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
    implementation(commonLibs.kotlinReflect)
    implementation(platform(commonLibs.springBootDependencies))
    implementation(commonLibs.springContext)
    implementation(commonLibs.springTx)
    implementation(commonLibs.jakartaPersistenceApi)
    implementation(commonLibs.jakartaAnnotationApi)
    implementation(commonLibs.slf4jApi)

    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "common-core"
            version = project.version.toString()
        }
    }
}
