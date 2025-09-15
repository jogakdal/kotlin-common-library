plugins {
    id("com.hunet.commonlib.convention")
    id("org.jetbrains.kotlin.plugin.spring")
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
