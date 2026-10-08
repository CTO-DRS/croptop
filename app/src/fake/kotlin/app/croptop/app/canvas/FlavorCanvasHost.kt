package app.croptop.app.canvas

import app.croptop.feature.editor.canvas.CanvasHost
import app.croptop.feature.editor.canvas.ComposeCanvasHost

/** `fake` flavor: pure-Compose canvas, no NDK. */
val FlavorCanvasHost: CanvasHost = ComposeCanvasHost
