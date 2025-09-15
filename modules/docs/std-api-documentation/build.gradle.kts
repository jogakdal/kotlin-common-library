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
}
