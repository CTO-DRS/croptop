import app.croptop.buildlogic.croptopAbis
import app.croptop.buildlogic.libs
import app.croptop.buildlogic.version
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Native build for modules with C++ code. Expects src/main/cpp/CMakeLists.txt. */
class AndroidNdkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        extensions.configure<LibraryExtension> {
            ndkVersion = libs.version("ndk")
            defaultConfig {
                ndk { abiFilters += croptopAbis() }
                externalNativeBuild {
                    cmake {
                        cppFlags += "-std=c++20"
                        arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                    }
                }
            }
            externalNativeBuild {
                cmake {
                    path = file("src/main/cpp/CMakeLists.txt")
                    version = libs.version("cmake")
                }
            }
        }
    }
}
