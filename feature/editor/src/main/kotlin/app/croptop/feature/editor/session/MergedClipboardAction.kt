package app.croptop.feature.editor.session

/**
 * Prepares the merged image before changing either clipboard. A rejected or failed preparation
 * must leave both the app clipboard and the platform clipboard untouched.
 */
internal suspend fun copyMergedImageToClipboard(
    prepareImage: suspend () -> String?,
    copyMerged: () -> Boolean,
    publishToPlatformClipboard: (String) -> Unit,
): Boolean {
    val uri = prepareImage() ?: return false
    if (!copyMerged()) return false
    publishToPlatformClipboard(uri)
    return true
}
