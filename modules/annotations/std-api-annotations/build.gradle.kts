plugins {
    id("com.hunet.common-library.convention")
}

repositories { mavenCentral() }

dependencies {
    implementation(commonLibs.kotlinStdlib)
    implementation(commonLibs.jacksonModuleKotlin)
}
