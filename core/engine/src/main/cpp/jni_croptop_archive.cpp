#include <jni.h>

#include <cerrno>
#include <climits>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

#include "croptop_format.h"
#include "native_store_handle.h"

namespace {
struct ArchiveHandle final {
    std::shared_ptr<int> fd;
    std::unique_ptr<croptop::CroptopArchive> archive;
};

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJava(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

ArchiveHandle* handle(jlong value) { return reinterpret_cast<ArchiveHandle*>(value); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeOpenCroptopArchiveNative(JNIEnv* env, jobject, jstring path) {
    const auto native_path = fromJava(env, path);
    if (native_path.empty()) return 0;
    const int raw_fd = open(native_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (raw_fd < 0) return 0;
    struct stat status {};
    if (fstat(raw_fd, &status) != 0 || status.st_size < 0) {
        close(raw_fd);
        return 0;
    }
    try {
        auto fd = std::make_shared<int>(raw_fd);
        croptop::CroptopReadSource source;
        source.size = static_cast<std::uint64_t>(status.st_size);
        source.read_at = [fd](std::uint64_t offset, std::span<std::uint8_t> output) {
            std::size_t done = 0;
            while (done < output.size()) {
                const auto count = pread(*fd, output.data() + done, output.size() - done,
                                         static_cast<off_t>(offset + done));
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) return false;
                done += static_cast<std::size_t>(count);
            }
            return true;
        };
        std::string error;
        auto archive = croptop::CroptopArchive::open(std::move(source), error);
        if (!archive) {
            close(raw_fd);
            return 0;
        }
        return reinterpret_cast<jlong>(new ArchiveHandle{std::move(fd), std::move(archive)});
    } catch (...) {
        close(raw_fd);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCloseCroptopArchiveNative(JNIEnv*, jobject, jlong value) {
    auto* archive = handle(value);
    if (archive == nullptr) return;
    if (archive->fd && *archive->fd >= 0) close(*archive->fd);
    delete archive;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCroptopManifestNative(JNIEnv* env, jobject, jlong value) {
    auto* state = handle(value);
    if (state == nullptr || !state->archive) return nullptr;
    std::vector<std::uint8_t> manifest;
    std::string error;
    if (!state->archive->readManifest(manifest, error) || manifest.size() > static_cast<std::size_t>(INT32_MAX)) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(manifest.size()));
    if (result != nullptr && !manifest.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(manifest.size()),
                                                                          reinterpret_cast<const jbyte*>(manifest.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCroptopWorkingIccNative(JNIEnv* env, jobject, jlong value) {
    auto* state = handle(value);
    if (state == nullptr || !state->archive) return nullptr;
    std::vector<std::uint8_t> profile;
    std::string error;
    if (!state->archive->readWorkingIcc(profile, error) || profile.size() > static_cast<std::size_t>(INT32_MAX)) return nullptr;
    if (profile.empty()) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(profile.size()));
    if (result != nullptr && !profile.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(profile.size()), reinterpret_cast<const jbyte*>(profile.data()));
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCroptopLayerInfoNative(JNIEnv* env, jobject, jlong value, jstring uuid) {
    auto* state = handle(value);
    if (state == nullptr || !state->archive) return nullptr;
    croptop::CroptopLayerInfo info;
    std::string error;
    const auto id = fromJava(env, uuid);
    if (id.empty() || !state->archive->layerInfo(id, info, error)) return nullptr;
    const jint values[] = {static_cast<jint>(info.format), static_cast<jint>(info.width), static_cast<jint>(info.height)};
    auto result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeLoadCroptopLayerNative(JNIEnv* env, jobject, jlong value, jstring uuid,
                                                                  jlong store_handle) {
    auto* state = handle(value);
    auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
    if (state == nullptr || !state->archive || store == nullptr) return toJava(env, "Invalid archive or tile store");
    std::string error;
    const auto id = fromJava(env, uuid);
    if (!state->archive->loadLayer(id, store->pixels, error)) return toJava(env, error);
    return nullptr;
}

/** `embedded/<id>/<name>` of an opened archive (a RAW smart object's original file), or null when absent. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCroptopEmbeddedNative(JNIEnv* env, jobject, jlong value, jstring id, jstring name) {
    auto* state = handle(value);
    if (state == nullptr || !state->archive) return nullptr;
    std::vector<std::uint8_t> bytes;
    std::string error;
    const auto file_id = fromJava(env, id);
    const auto file_name = fromJava(env, name);
    if (file_id.empty() || file_name.empty() || !state->archive->readEmbedded(file_id, file_name, bytes, error) ||
        bytes.size() > static_cast<std::size_t>(INT32_MAX)) {
        return nullptr;
    }
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}
