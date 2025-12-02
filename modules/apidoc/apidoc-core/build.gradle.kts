plugins {
    id("com.hunet.commonlib.convention")
}

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.kotlinReflect)
    implementation(platform(commonLibs.springBootDependencies))
    implementation(project(":apidoc-annotations"))
    implementation(project(":standard-api-response"))
    implementation(project(":common-core"))
    implementation(commonLibs.springRestdocsMockmvc)
    implementation(commonLibs.epagesRestdocsApiSpecMockmvc)
    implementation(commonLibs.swaggerAnnotations)
    implementation(commonLibs.jakartaAnnotationApi)
    implementation(commonLibs.springContext)
    implementation(commonLibs.springdocStarterWebmvcUi)

    testImplementation(kotlin("test"))
    testImplementation(commonLibs.springTest)
    testImplementation(commonLibs.springBootStarterTest)
    testImplementation(commonLibs.mockitoCore)
    testImplementation(commonLibs.mockitoJunitJupiter)
    testImplementation(project(":test-support"))
}
