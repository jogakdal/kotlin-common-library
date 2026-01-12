plugins {
    id("com.hunet.commonlib.convention")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.kapt")
}

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(project(":common-core"))

    // Spring Boot BOM
    implementation(platform(commonLibs.springBootDependencies))
    kapt(platform(commonLibs.springBootDependencies))

    // Spring Boot Auto-configuration
    implementation(commonLibs.springBootAutoconfigure)
    kapt(commonLibs.springBootConfigProcessor)

    // JXLS (Apache POI 기반 템플릿 엔진)
    implementation("org.jxls:jxls:2.14.0")
    implementation("org.jxls:jxls-poi:2.14.0")

    // Kotlin Coroutines (비동기 지원)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")

    // Logback (JXLS 수식 에러 로그 캡처용)
    implementation("ch.qos.logback:logback-classic")

    testImplementation(platform(commonLibs.springBootDependencies))
    testImplementation(commonLibs.junitJupiterApi)
    testRuntimeOnly(commonLibs.junitJupiterEngine)
    testRuntimeOnly(commonLibs.junitPlatformLauncher)
    testImplementation(commonLibs.springBootStarterTest)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<Test> { useJUnitPlatform() }

// 샘플 실행 태스크 (Kotlin)
tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Excel Generator Kotlin 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.ExcelGeneratorSample")
}

// 샘플 실행 태스크 (Java)
tasks.register<JavaExec>("runJavaSample") {
    group = "application"
    description = "Excel Generator Java 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.ExcelGeneratorJavaSample")
}

// 템플릿 구조 확인 태스크
tasks.register<JavaExec>("inspectTemplate") {
    group = "application"
    description = "템플릿 파일 구조 확인"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.TemplateInspector")
}
