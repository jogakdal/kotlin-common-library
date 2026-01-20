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

    // JXLS 3.x (Apache POI 기반 템플릿 엔진, 스트리밍 지원)
    implementation("org.jxls:jxls-poi:3.0.0")

    // Apache POI full schemas (피벗 테이블 XML 조작용)
    // JXLS 3.0 권장: POI 5.2.2 이하
    implementation("org.apache.poi:poi-ooxml-full:5.2.2")

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

// Spring Boot 샘플 실행 태스크 (Kotlin)
tasks.register<JavaExec>("runSpringBootSample") {
    group = "application"
    description = "Excel Generator Spring Boot Kotlin 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.spring.ExcelGeneratorSpringBootSample")
}

// Spring Boot 샘플 실행 태스크 (Java)
tasks.register<JavaExec>("runSpringBootJavaSample") {
    group = "application"
    description = "Excel Generator Spring Boot Java 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.spring.ExcelGeneratorSpringBootJavaSample")
}

// SimpleTemplateEngine 샘플 실행 태스크
tasks.register<JavaExec>("runSimpleEngineSample") {
    group = "application"
    description = "SimpleTemplateEngine 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.excel.SimpleTemplateEngineSample")
}

// 테스트 클래스패스 출력 (java 명령 실행용)
tasks.register("printTestClasspath") {
    doLast {
        println(sourceSets["test"].runtimeClasspath.asPath)
    }
}
