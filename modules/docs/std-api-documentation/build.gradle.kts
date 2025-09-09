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
    implementation(commonLibs.kotlinReflect)
    implementation(platform(commonLibs.springBootDependencies))
    implementation(project(":std-api-annotations"))
    implementation(project(":standard-api-response"))
    implementation(commonLibs.springRestdocsMockmvc)
    implementation(commonLibs.epagesRestdocsApiSpecMockmvc)
    implementation(commonLibs.swaggerAnnotations)
    implementation(commonLibs.jakartaAnnotationApi)
    implementation(commonLibs.springContext)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "std-api-documentation"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "nexus"
            val releaseUrl = project.findProperty("repository.release.url") as String?
            val snapshotUrl = project.findProperty("repository.snapshot.url") as String?
            if (releaseUrl != null && snapshotUrl != null) {
                url = uri(if (version.toString().endsWith("-SNAPSHOT")) snapshotUrl else releaseUrl)
                credentials {
                    username = (project.findProperty("nexus.id") as String?) ?: System.getenv("NEXUS_ID")
                    password = (project.findProperty("nexus.password") as String?) ?: System.getenv("NEXUS_PASSWORD")
                }
            }
        }
    }
}
