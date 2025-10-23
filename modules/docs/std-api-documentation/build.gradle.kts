plugins {
    id("com.hunet.commonlib.convention")
}

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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework:spring-test")
    testImplementation(commonLibs.springBootStarterTest)
    testImplementation("org.mockito:mockito-core:5.14.1")
    testRuntimeOnly("org.mockito:mockito-agent:5.14.1")
    testImplementation(project(":test-support"))
}
