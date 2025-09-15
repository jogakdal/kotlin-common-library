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
    testImplementation(kotlin("test"))
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }
