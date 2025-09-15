plugins {
    id("com.hunet.commonlib.convention")
}

repositories { mavenCentral() }

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.jacksonModuleKotlin)
}
