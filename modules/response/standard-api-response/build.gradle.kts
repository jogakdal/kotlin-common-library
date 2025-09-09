plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.spring")
    id("maven-publish")
}

group = "com.hunet.common_library"
version = "1.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
    withJavadocJar()
}

repositories { mavenCentral() }

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(platform(commonLibs.springBootDependencies))
    implementation(commonLibs.kotlinxSerializationJson)
    implementation(commonLibs.kotlinxDatetime)
    implementation(commonLibs.jacksonModuleKotlin)
    implementation(commonLibs.jacksonDatatypeJsr310)
    implementation(commonLibs.springContext)
    implementation(commonLibs.springDataCommons)
    implementation(commonLibs.swaggerAnnotations)
    implementation(commonLibs.springRestdocsMockmvc)
    implementation(commonLibs.epagesRestdocsApiSpecMockmvc)
    implementation(commonLibs.jakartaAnnotationApi)
    implementation(project(":common-core"))
    implementation(project(":std-api-annotations"))

    implementation(commonLibs.springBootStarterWeb)
    testImplementation(kotlin("test"))
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "standard-api-response"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "nexus"
            val releaseUrl = project.findProperty("repository.release.url") as String
            val snapshotUrl = project.findProperty("repository.snapshot.url") as String
            url = uri(if (version.toString().endsWith("-SNAPSHOT")) snapshotUrl else releaseUrl)
            credentials {
                username = (project.findProperty("nexus.id") as String?) ?: System.getenv("NEXUS_ID")
                password = (project.findProperty("nexus.password") as String?) ?: System.getenv("NEXUS_PASSWORD")
            }
        }
    }
}
