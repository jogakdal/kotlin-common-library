plugins {
    `java-platform`
    `maven-publish`
}

group = property("group") as String
version = property("moduleVersion.common-bom") as String

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":common-core"))
        api(project(":tbeg"))
        api(project(":standard-api-response"))
        api(project(":jpa-repository-extension"))
        api(project(":apidoc-core"))
        api(project(":apidoc-annotations"))
        api(project(":test-support"))
    }
}

publishing {
    publications {
        create<MavenPublication>("bom") {
            from(components["javaPlatform"])
            artifactId = "common-bom"

            pom {
                name.set("Hunet Common Libraries BOM")
                description.set("Bill of Materials for Hunet Common Libraries - 호환되는 라이브러리 버전 조합을 제공합니다.")
            }
        }
    }

    repositories {
        maven {
            name = "nexus"
            val releasesRepoUrl = uri(property("repository.release.url") as String)
            val snapshotsRepoUrl = uri(property("repository.snapshot.url") as String)
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials {
                username = findProperty("nexus.id") as String? ?: ""
                password = findProperty("nexus.password") as String? ?: ""
            }
        }
    }
}
