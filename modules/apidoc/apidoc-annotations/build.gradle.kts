plugins {
    id("com.hunet.commonlib.convention")
}

repositories { mavenCentral() }

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.jacksonModuleKotlin)
    // DescriptiveEnum 치환 로직에 VariableProcessor 활용을 위해 common-core 의존 추가
    implementation(project(":common-core"))

    testImplementation(commonLibs.junitJupiterApi)
    testRuntimeOnly(commonLibs.junitJupiterEngine)
}
