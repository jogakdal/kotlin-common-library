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
    implementation(project(":apidoc-annotations"))

    implementation(commonLibs.springBootStarterWeb)
    implementation(commonLibs.kotlinReflect)
    implementation(commonLibs.slf4jApi)
    testImplementation(kotlin("test"))
    testImplementation(commonLibs.springTest)
    testImplementation(commonLibs.mockitoCore)
    testImplementation(commonLibs.mockitoJunitJupiter)
    testImplementation(project(":test-support"))
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }
