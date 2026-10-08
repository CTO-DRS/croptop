// Crash recovery over RecoveryJournal/RecoveryAutosave.
// Writers: nativeAutosaveCreate(dir) → one RecoveryAutosave per document on a shared Background pool;
// nativeAutosaveSubmit hands it a COW snapshot (metadata JSON, manifest JSON, layer stores by uuid).
// Readers: nativeList(root) lists valid journals; nativeOpen(dir) reads one with the same
// manifest/layerInfo/loadLayer shape as a .croptop archive.
#include <jni.h>

#include <chrono>
#include <climits>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "job_system.h"
#include "native_store_handle.h"
#include "recovery_autosave.h"
#include "recovery_journal.h"

namespace {

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string bytesFromJava(JNIEnv* env, jbyteArray value) {
    if (value == nullptr) return {};
    const auto length = env->GetArrayLength(value);
    std::string result(static_cast<std::size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

jbyteArray bytesToJava(JNIEnv* env, const std::string& value) {
    if (value.size() > static_cast<std::size_t>(INT32_MAX)) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    }
    return result;
}

/** One background worker for every document's journal: autosave must never compete with editing. */
croptop::JobPool& recoveryPool() {
    static croptop::JobPool pool(1);
    return pool;
}

croptop::RecoveryAutosave* autosave(jlong value) { return reinterpret_cast<croptop::RecoveryAutosave*>(value); }
croptop::RecoveryReader* reader(jlong value) { return reinterpret_cast<croptop::RecoveryReader*>(value); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveCreate(JNIEnv* env, jobject, jstring dir) {
    const auto path = fromJava(env, dir);
    if (path.empty()) return 0;
    try {
        return reinterpret_cast<jlong>(new croptop::RecoveryAutosave(recoveryPool(), std::filesystem::path(path)));
    } catch (const std::exception&) {
        return 0;
    }
}

/** Queues a snapshot; replaces one that has not started. Returns false on invalid arguments. */
extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveSubmit(
    JNIEnv* env, jobject, jlong handle, jbyteArray metadata, jbyteArray manifest, jlongArray stores, jobjectArray uuids,
    jbyteArray working_icc) {
    auto* target = autosave(handle);
    if (target == nullptr || metadata == nullptr || manifest == nullptr || stores == nullptr || uuids == nullptr) return JNI_FALSE;
    const auto count = env->GetArrayLength(stores);
    // Autosave snapshots include the normal layer stores and their optional A16 mask stores.
    if (count < 0 || count > 1000 || env->GetArrayLength(uuids) != count) return JNI_FALSE;
    std::vector<jlong> handles(static_cast<std::size_t>(count));
    if (count > 0) env->GetLongArrayRegion(stores, 0, count, handles.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    try {
        croptop::RecoverySnapshot snapshot;
        snapshot.metadata_json = bytesFromJava(env, metadata);
        snapshot.manifest_json = bytesFromJava(env, manifest);
        if (env->ExceptionCheck()) return JNI_FALSE;
        if (working_icc != nullptr) {
            const auto icc_length = env->GetArrayLength(working_icc);
            if (icc_length < 0 || static_cast<std::uint64_t>(icc_length) > croptop::kRecoveryMaxWorkingIccBytes) return JNI_FALSE;
            snapshot.working_icc.resize(static_cast<std::size_t>(icc_length));
            if (icc_length > 0) {
                env->GetByteArrayRegion(working_icc, 0, icc_length, reinterpret_cast<jbyte*>(snapshot.working_icc.data()));
                if (env->ExceptionCheck()) return JNI_FALSE;
            }
        }
        snapshot.layers.reserve(static_cast<std::size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(handles[static_cast<std::size_t>(index)]);
            auto uuid = static_cast<jstring>(env->GetObjectArrayElement(uuids, index));
            if (store == nullptr || uuid == nullptr) {
                if (uuid != nullptr) env->DeleteLocalRef(uuid);
                return JNI_FALSE;
            }
            // TileStore copies share tile buffers (COW): this is cheap and isolates the journal from later edits.
            snapshot.layers.push_back({fromJava(env, uuid), store->pixels});
            env->DeleteLocalRef(uuid);
        }
        target->submit(std::move(snapshot));
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveFlush(JNIEnv*, jobject, jlong handle,
                                                                                                          jlong timeout_ms) {
    auto* target = autosave(handle);
    return target != nullptr && target->flush(std::chrono::milliseconds(timeout_ms)) ? JNI_TRUE : JNI_FALSE;
}

/** Drops pending work and deletes the journal (clean save or intentional close); null on success. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveDiscard(JNIEnv* env, jobject,
                                                                                                           jlong handle) {
    auto* target = autosave(handle);
    if (target == nullptr) return env->NewStringUTF("Invalid autosave handle");
    std::string error;
    return target->discard(error) ? nullptr : env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveLastError(JNIEnv* env, jobject,
                                                                                                             jlong handle) {
    auto* target = autosave(handle);
    if (target == nullptr) return nullptr;
    const auto status = target->status();
    return status.last_error.empty() ? nullptr : env->NewStringUTF(status.last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeAutosaveDestroy(JNIEnv*, jobject, jlong handle) {
    delete autosave(handle);
}

/** Valid, fully verified journal directories under [root], sorted by name. */
extern "C" JNIEXPORT jobjectArray JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeList(JNIEnv* env, jobject, jstring root) {
    std::vector<std::filesystem::path> dirs;
    try {
        const auto path = fromJava(env, root);
        if (!path.empty()) dirs = croptop::RecoveryReader::list(std::filesystem::path(path));
    } catch (const std::exception&) {
        dirs.clear();
    }
    auto string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    auto result = env->NewObjectArray(static_cast<jsize>(dirs.size()), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;
    for (std::size_t index = 0; index < dirs.size(); ++index) {
        auto value = env->NewStringUTF(dirs[index].string().c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeOpen(JNIEnv* env, jobject, jstring dir) {
    try {
        std::string error;
        auto opened = croptop::RecoveryReader::open(std::filesystem::path(fromJava(env, dir)), error);
        return reinterpret_cast<jlong>(opened.release());
    } catch (const std::exception&) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reader(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeMetadata(JNIEnv* env, jobject, jlong handle) {
    const auto* source = reader(handle);
    return source == nullptr ? nullptr : bytesToJava(env, source->metadataJson());
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeManifest(JNIEnv* env, jobject, jlong handle) {
    const auto* source = reader(handle);
    return source == nullptr ? nullptr : bytesToJava(env, source->manifestJson());
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeWorkingIcc(JNIEnv* env, jobject,
                                                                                                          jlong handle) {
    const auto* source = reader(handle);
    if (source == nullptr || source->workingIcc().empty()) return nullptr;
    const auto& profile = source->workingIcc();
    if (profile.size() > croptop::kRecoveryMaxWorkingIccBytes || profile.size() > static_cast<std::size_t>(INT32_MAX)) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(profile.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(profile.size()), reinterpret_cast<const jbyte*>(profile.data()));
    }
    return result;
}

/** [format, width, height] like the archive's layer info, or null. */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeLayerInfo(JNIEnv* env, jobject, jlong handle,
                                                                                                       jstring uuid) {
    const auto* source = reader(handle);
    if (source == nullptr) return nullptr;
    croptop::CroptopLayerInfo info;
    std::string error;
    const auto id = fromJava(env, uuid);
    if (id.empty() || !source->layerInfo(id, info, error)) return nullptr;
    const jint values[] = {static_cast<jint>(info.format), static_cast<jint>(info.width), static_cast<jint>(info.height)};
    auto result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeLoadLayer(JNIEnv* env, jobject, jlong handle,
                                                                                                     jstring uuid, jlong store_handle) {
    const auto* source = reader(handle);
    auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
    if (source == nullptr || store == nullptr) return env->NewStringUTF("Invalid recovery journal or tile store");
    std::string error;
    try {
        if (!source->loadLayer(fromJava(env, uuid), store->pixels, error)) return env->NewStringUTF(error.c_str());
    } catch (const std::exception& e) {
        return env->NewStringUTF(e.what());
    }
    return nullptr;
}

/** Deletes a journal directory (user discarded it, or it was recovered and saved); null on success. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRecoveryBridge_nativeDiscardDir(JNIEnv* env, jobject, jstring dir) {
    std::string error;
    try {
        if (croptop::RecoveryJournal::discard(std::filesystem::path(fromJava(env, dir)), error)) return nullptr;
    } catch (const std::exception& e) {
        error = e.what();
    }
    return env->NewStringUTF(error.empty() ? "Could not delete recovery data" : error.c_str());
}
