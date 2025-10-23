plugins {
    id("com.hunet.commonlib.convention")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.spring")
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
    implementation(commonLibs.kotlinReflect)
    implementation(commonLibs.slf4jApi)
    testImplementation(kotlin("test"))
    testImplementation("org.springframework:spring-test")
    testImplementation(commonLibs.springBootStarterTest)
    testImplementation("org.mockito:mockito-core:5.14.1")
    testRuntimeOnly("org.mockito:mockito-agent:5.14.1")
    testImplementation(project(":test-support"))
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }
