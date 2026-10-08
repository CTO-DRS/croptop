package app.croptop.feature.editor

import app.croptop.feature.editor.session.ActionOutcome

internal enum class RepositoryImportKind { BRUSHES, SWATCHES }

/** Import results are success only for known success messages; parser details remain failures. */
internal fun repositoryImportResult(kind: RepositoryImportKind, result: String): ActionOutcome {
    val succeeded = when (kind) {
        RepositoryImportKind.BRUSHES -> result.startsWith("Imported ")
        RepositoryImportKind.SWATCHES -> result.startsWith("Added ") || result == "No new colors in that file"
    }
    return if (succeeded) ActionOutcome.Completed(result) else ActionOutcome.Failed(result)
}
