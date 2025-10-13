plugins {
    id("com.hunet.commonlib.convention")
}

// 퍼블리시 스킵 (컨벤션 플러그인 afterEvaluate 시점에서 읽음)
extra["publish.skip"] = true

repositories { mavenCentral() }

dependencies {
    implementation(platform(commonLibs.springBootDependencies))
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.kotlinReflect)
    implementation(commonLibs.springBootStarterWeb)
    implementation(commonLibs.springBootStarterTest)
    implementation(commonLibs.springBootStarterDataJpa)
    implementation(commonLibs.springRestdocsMockmvc)
    implementation(commonLibs.epagesRestdocsApiSpecMockmvc)
    implementation(commonLibs.jacksonModuleKotlin)
    implementation(project(":common-core"))
    // 테스트 전용 임베디드 DB
    testImplementation("com.h2database:h2:2.2.224")
}

// 기존 group/version/java/publishing 제거 (Convention 처리)
