package app.croptop.core.engine

/** Crash-recovery journals (`jni_recovery.cpp`). Every handle is owned by the caller. */
internal object NativeRecoveryBridge {
    external fun nativeAutosaveCreate(dir: String): Long
    external fun nativeAutosaveSubmit(
        handle: Long,
        metadata: ByteArray,
        manifest: ByteArray,
        stores: LongArray,
        uuids: Array<String>,
        workingIcc: ByteArray?,
    ): Boolean
    external fun nativeAutosaveFlush(handle: Long, timeoutMs: Long): Boolean
    external fun nativeAutosaveDiscard(handle: Long): String?
    external fun nativeAutosaveLastError(handle: Long): String?
    external fun nativeAutosaveDestroy(handle: Long)

    external fun nativeList(root: String): Array<String>?
    external fun nativeOpen(dir: String): Long
    external fun nativeClose(handle: Long)
    external fun nativeMetadata(handle: Long): ByteArray?
    external fun nativeManifest(handle: Long): ByteArray?
    external fun nativeWorkingIcc(handle: Long): ByteArray?
    external fun nativeLayerInfo(handle: Long, uuid: String): IntArray?
    external fun nativeLoadLayer(handle: Long, uuid: String, store: Long): String?
    external fun nativeDiscardDir(dir: String): String?
}
