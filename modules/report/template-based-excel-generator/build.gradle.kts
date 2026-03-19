plugins {
    id("com.hunet.commonlib.convention")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.kapt")
    id("me.champeau.jmh") version "0.7.3"
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

    // Apache POI (Excel 처리)
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Apache POI full schemas (피벗 테이블 XML 조작용)
    implementation("org.apache.poi:poi-ooxml-full:5.2.5")

    // Kotlin Coroutines (비동기 지원)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")

    // Kryo (객체 직렬화 - Serializable 없이도 동작)
    implementation("com.esotericsoftware:kryo:5.6.0")

    // Logback
    implementation("ch.qos.logback:logback-classic")

    testImplementation(platform(commonLibs.springBootDependencies))
    testImplementation(commonLibs.junitJupiterApi)
    testRuntimeOnly(commonLibs.junitJupiterEngine)
    testRuntimeOnly(commonLibs.junitPlatformLauncher)
    testImplementation(commonLibs.springBootStarterTest)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<Test> { useJUnitPlatform() }

jmh {
    fork = 1
    warmupIterations = 1
    iterations = 3
    jvmArgs = listOf("-Xms512m", "-Xmx4g")
    profilers = listOf("gc")
}

// 샘플 실행 태스크 (Kotlin)
tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "TBEG (Template Based Excel Generator) Kotlin 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.TbegSample")
}

// 샘플 실행 태스크 (Java)
tasks.register<JavaExec>("runJavaSample") {
    group = "application"
    description = "TBEG (Template Based Excel Generator) Java 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.TbegJavaSample")
}

// Spring Boot 샘플 실행 태스크 (Kotlin)
tasks.register<JavaExec>("runSpringBootSample") {
    group = "application"
    description = "TBEG Spring Boot Kotlin 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.TbegSpringBootSample")
}

// Spring Boot 샘플 실행 태스크 (Java)
tasks.register<JavaExec>("runSpringBootJavaSample") {
    group = "application"
    description = "TBEG Spring Boot Java 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.TbegSpringBootJavaSample")
}

// TemplateRenderingEngine 샘플 실행 태스크
tasks.register<JavaExec>("runRenderingEngineSample") {
    group = "application"
    description = "TemplateRenderingEngine 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.TemplateRenderingEngineSample")
}

// 다중 Repeat 영역 샘플 실행 태스크
tasks.register<JavaExec>("runMultiRepeatSample") {
    group = "application"
    description = "다중 Repeat 영역 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.MultiRepeatSample")
}

// 셀 병합 샘플 실행 태스크
tasks.register<JavaExec>("runCellMergeSample") {
    group = "application"
    description = "셀 병합 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.CellMergeSampleRunner")
}

// JMH 벤치마크 커스텀 러너 (정리된 테이블 출력)
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "TBEG JMH 벤치마크 실행 (정리된 테이블 출력)"
    dependsOn("jmhCompileGeneratedClasses")
    classpath = sourceSets["jmh"].runtimeClasspath +
        files(layout.buildDirectory.dir("jmh-generated-resources")) +
        files(layout.buildDirectory.dir("jmh-generated-classes"))
    mainClass.set("com.hunet.common.tbeg.benchmark.TbegBenchmarkRunner")
    maxHeapSize = "8g"
    jvmArgs = listOf("-Xms512m")
}

// Rich Sample 실행 태스크 (시각적 데모용)
tasks.register<JavaExec>("runRichSample") {
    group = "application"
    description = "Rich Sample 실행 (분기 매출 실적 보고서)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.RichSample")
}

// 빈 컬렉션 + emptyRange 샘플 실행 태스크
tasks.register<JavaExec>("runEmptyCollectionSample") {
    group = "application"
    description = "빈 컬렉션 + emptyRange 기능 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.EmptyCollectionSample")
}

tasks.register<JavaExec>("runFormulaSubstitutionSample") {
    group = "application"
    description = "변수형 마커 수식 치환 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.FormulaSubstitutionSample")
}

tasks.register<JavaExec>("runHideableSample") {
    group = "application"
    description = "hideable 기능 샘플 실행"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hunet.common.tbeg.samples.HideableSample")
}

// 테스트 클래스패스 출력 (java 명령 실행용)
tasks.register("printTestClasspath") {
    doLast {
        println(sourceSets["test"].runtimeClasspath.asPath)
    }
}
