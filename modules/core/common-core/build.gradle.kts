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

    compileOnly(commonLibs.springBootAutoconfigure)
    annotationProcessor(commonLibs.springBootConfigProcessor)

    testImplementation(commonLibs.junitJupiterApi)
    testRuntimeOnly(commonLibs.junitJupiterEngine)
    // JPA 구현(Hibernate) & 임베디드 DB(H2) for testing
    testImplementation(commonLibs.springBootStarterDataJpa)
    testImplementation(commonLibs.h2)
    // 외부 사용 통합 테스트에서 ApplicationContextRunner 사용
    testImplementation(commonLibs.springBootStarterTest)
}
