package app.croptop.feature.editor

import app.croptop.core.engine.api.OverlayState
import app.croptop.core.engine.api.Viewport
import app.croptop.core.model.DocRect
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.canvas.ViewportBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportBridgeTest {
    private val log = mutableListOf<String>()

    private fun bridge() = ViewportBridge<String, String> { doc, surface, v ->
        log += "attach $doc@$surface ${v.viewWidth}"
        object : Viewport {
            override fun update(state: ViewportState) {
                log += "update $doc ${state.zoom}"
            }

            override fun setOverlays(overlays: OverlayState) {
                val guides = overlays.guides.joinToString { "${it.axis}:${it.position}" }
                log += "overlays=${overlays.selectionBounds.size};selection=${overlays.showSelection};guides=${overlays.showGuides};$guides"
            }

            override fun detach() {
                log += "detach $doc@$surface"
            }
        }
    }

    private val sized = ViewportState(viewWidth = 800, viewHeight = 600)

    @Test
    fun attachesOnlyWithDocumentSurfaceAndSize() {
        val b = bridge()
        b.setTarget("A")
        b.setSurface("s1")
        assertFalse(b.isAttached) // no size yet
        b.setViewport(sized)
        assertTrue(b.isAttached)
        b.setViewport(sized.copy(zoom = 2f))
        assertEquals(listOf("attach A@s1 800", "overlays=0;selection=true;guides=true;", "update A 2.0"), log)
    }

    @Test
    fun surfaceRecreationAndDocumentSwitchDetachFirst() {
        val b = bridge()
        b.setViewport(sized)
        b.setSurface("s1")
        b.setTarget("A")
        b.setSurface(null) // surfaceDestroyed
        b.setSurface("s2") // surfaceCreated
        b.setTarget("B") // document switch
        b.release()
        assertEquals(
            listOf(
                "attach A@s1 800", "overlays=0;selection=true;guides=true;", "detach A@s1",
                "attach A@s2 800", "overlays=0;selection=true;guides=true;", "detach A@s2",
                "attach B@s2 800", "overlays=0;selection=true;guides=true;", "detach B@s2",
            ),
            log,
        )
        assertFalse(b.isAttached)
    }

    @Test
    fun latestOverlaysReachAttachedViewportAndAreRestoredAfterReattach() {
        val b = bridge()
        val overlays = OverlayState(
            selectionBounds = listOf(DocRect(1, 2, 3, 4)),
            guides = listOf(Guide(GuideAxis.HORIZONTAL, 12.5f), Guide(GuideAxis.VERTICAL, 30f)),
            showSelection = false,
            showGuides = false,
        )
        b.setOverlays(overlays)
        b.setViewport(sized)
        b.setTarget("A")
        b.setSurface("s1")
        b.setOverlays(overlays.copy(showGuides = true))
        b.setSurface(null)
        b.setSurface("s2")

        assertEquals(
            listOf(
                "attach A@s1 800",
                "overlays=1;selection=false;guides=false;HORIZONTAL:12.5, VERTICAL:30.0",
                "overlays=1;selection=false;guides=true;HORIZONTAL:12.5, VERTICAL:30.0",
                "detach A@s1",
                "attach A@s2 800",
                "overlays=1;selection=false;guides=true;HORIZONTAL:12.5, VERTICAL:30.0",
            ),
            log,
        )
    }
}
