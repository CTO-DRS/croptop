// C++20 engine, Vulkan renderer, JNI bindings.
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.croptop.android.library)
    alias(libs.plugins.croptop.android.ndk)
}

// Android ASan builds require the NDK runtime and wrap.sh to launch. Keep the
// normal app/debug APK loadable; the host harness has an explicit sanitizer
// configuration and Android sanitizer packaging can be added as a dedicated variant.
extensions.configure<LibraryExtension> {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DCROPTOP_ENABLE_SANITIZERS=OFF"
            }
        }
    }
}

dependencies {
    api(projects.core.engineApi)
    implementation(projects.core.io)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(testFixtures(projects.core.engineApi))
}
