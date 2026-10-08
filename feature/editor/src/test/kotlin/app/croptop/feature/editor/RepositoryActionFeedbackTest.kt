package app.croptop.feature.editor

import app.croptop.feature.editor.session.ActionOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryActionFeedbackTest {
    @Test
    fun malformedBrushAndSwatchMessagesRemainFailures() {
        val brushFailure = "This isn't an CropTop brushes file."
        val swatchFailure = "The swatch file is truncated."

        assertEquals(
            ActionOutcome.Failed(brushFailure),
            repositoryImportResult(RepositoryImportKind.BRUSHES, brushFailure),
        )
        assertEquals(
            ActionOutcome.Failed(swatchFailure),
            repositoryImportResult(RepositoryImportKind.SWATCHES, swatchFailure),
        )
    }

    @Test
    fun successfulAndEmptySwatchImportsRemainCompleted() {
        assertEquals(
            ActionOutcome.Completed("Imported 2 brushes"),
            repositoryImportResult(RepositoryImportKind.BRUSHES, "Imported 2 brushes"),
        )
        assertEquals(
            ActionOutcome.Completed("Added 3 swatches"),
            repositoryImportResult(RepositoryImportKind.SWATCHES, "Added 3 swatches"),
        )
        assertEquals(
            ActionOutcome.Completed("No new colors in that file"),
            repositoryImportResult(RepositoryImportKind.SWATCHES, "No new colors in that file"),
        )
    }
}
