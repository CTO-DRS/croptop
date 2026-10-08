plugins {
    `kotlin-dsl`
}

group = "app.croptop.buildlogic"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
    compileOnly(libs.roborazzi.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "croptop.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "croptop.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "croptop.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "croptop.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "croptop.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidNdk") {
            id = "croptop.android.ndk"
            implementationClass = "AndroidNdkConventionPlugin"
        }
        register("androidRoborazzi") {
            id = "croptop.android.roborazzi"
            implementationClass = "AndroidRoborazziConventionPlugin"
        }
        register("kotlinJvm") {
            id = "croptop.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
    }
}
