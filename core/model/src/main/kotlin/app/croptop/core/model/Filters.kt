package app.croptop.core.model

/**
 * The Filter menu in [AdjustmentSpec] form, so the destructive-adjustment dialog and preview session
 * drive filters too. Ids start with [PREFIX]; units follow Photoshop's dialogs (percent where Photoshop shows %).
 * [groups] orders them into Photoshop's submenus.
 */
object FilterCatalog {
    const val PREFIX = "filter."

    fun isFilter(specId: String): Boolean = specId.startsWith(PREFIX)

    private fun n(key: String, label: String, min: Float, max: Float, default: Float, unit: String? = null, decimals: Int = 0, step: Float = 1f) =
        NumberParam(key, label, min, max, default, step, decimals, unit)

    private fun ch(key: String, label: String, vararg options: Pair<String, String>) =
        ChoiceParam(key, label, options.map { ChoiceParam.Option(it.first, it.second) }, options.first().first)

    private fun spec(id: String, name: String, vararg params: ParamSpec) =
        AdjustmentSpec(PREFIX + id, name, params.toList())

    val All: List<AdjustmentSpec> = listOf(
        // Blur
        spec("gaussianBlur", "Gaussian Blur", n("radius", "Radius", 0.1f, 1000f, 2f, "px", 1, 0.1f)),
        spec("boxBlur", "Box Blur", n("radius", "Radius", 1f, 999f, 5f, "px")),
        spec("motionBlur", "Motion Blur", n("angle", "Angle", -360f, 360f, 0f, "°"), n("distance", "Distance", 1f, 2000f, 10f, "px")),
        spec("radialBlur", "Radial Blur", ch("method", "Blur Method", "spin" to "Spin", "zoom" to "Zoom"), n("amount", "Amount", 1f, 100f, 10f)),
        spec("surfaceBlur", "Surface Blur", n("radius", "Radius", 1f, 100f, 5f, "px"), n("threshold", "Threshold", 2f, 255f, 15f, "levels")),
        spec(
            "tiltShift", "Tilt-Shift",
            n("blur", "Blur", 0f, 100f, 15f, "px"), n("focus", "Focus Band", 0f, 5000f, 100f, "px"),
            n("transition", "Transition", 0f, 5000f, 200f, "px"), n("angle", "Angle", -180f, 180f, 0f, "°"),
        ),
        // Sharpen
        spec(
            "unsharpMask", "Unsharp Mask",
            n("amount", "Amount", 1f, 500f, 50f, "%"), n("radius", "Radius", 0.1f, 1000f, 1f, "px", 1, 0.1f), n("threshold", "Threshold", 0f, 255f, 0f, "levels"),
        ),
        spec(
            "smartSharpen", "Smart Sharpen",
            n("amount", "Amount", 1f, 500f, 100f, "%"), n("radius", "Radius", 0.1f, 64f, 1f, "px", 1, 0.1f),
            n("reduceNoise", "Reduce Noise", 0f, 100f, 10f, "%"), ch("remove", "Remove", "gaussian" to "Gaussian Blur", "lens" to "Lens Blur"),
        ),
        spec("highPass", "High Pass", n("radius", "Radius", 0.1f, 1000f, 10f, "px", 1, 0.1f)),
        // Distort
        spec("twirl", "Twirl", n("angle", "Angle", -999f, 999f, 50f, "°")),
        spec("pinch", "Pinch", n("amount", "Amount", -100f, 100f, 50f, "%")),
        spec("spherize", "Spherize", n("amount", "Amount", -100f, 100f, 100f, "%")),
        spec(
            "wave", "Wave",
            n("wavelength", "Wavelength", 2f, 999f, 120f, "px"), n("amplitude", "Amplitude", 0f, 999f, 35f, "px"),
            ToggleParam("horizontal", "Horizontal", true), ToggleParam("vertical", "Vertical", true),
        ),
        spec("ripple", "Ripple", n("amount", "Amount", -999f, 999f, 100f, "%"), ch("size", "Size", "medium" to "Medium", "small" to "Small", "large" to "Large")),
        spec(
            "displace", "Displace",
            n("h", "Horizontal Scale", -999f, 999f, 10f, "%"), n("v", "Vertical Scale", -999f, 999f, 10f, "%"),
            ChoiceParam(
                "map", "Displacement Map", listOf(ChoiceParam.Option("none", "Choose a layer…")), "none",
                source = ChoiceParam.Source.PIXEL_LAYERS,
            ),
            ch("fit", "Map Fit", "stretch" to "Stretch to Fit", "tile" to "Tile"),
        ),
        spec("polarCoordinates", "Polar Coordinates", ch("mode", "Mode", "toPolar" to "Rectangular to Polar", "toRect" to "Polar to Rectangular")),
        // Noise
        spec(
            "addNoise", "Add Noise",
            n("amount", "Amount", 0f, 400f, 12.5f, "%", 1, 0.1f), ch("distribution", "Distribution", "uniform" to "Uniform", "gaussian" to "Gaussian"),
            ToggleParam("monochromatic", "Monochromatic"),
        ),
        spec("filmGrain", "Film Grain", n("intensity", "Intensity", 0f, 100f, 30f, "%"), n("size", "Grain Size", 1f, 64f, 2f, "px")),
        spec("median", "Median", n("radius", "Radius", 1f, 100f, 3f, "px")),
        spec("dustAndScratches", "Dust & Scratches", n("radius", "Radius", 1f, 100f, 2f, "px"), n("threshold", "Threshold", 0f, 255f, 0f, "levels")),
        spec("reduceNoise", "Reduce Noise", n("strength", "Strength", 0f, 10f, 6f), n("preserve", "Preserve Details", 0f, 100f, 60f, "%")),
        // Pixelate
        spec("mosaic", "Mosaic", n("cell", "Cell Size", 2f, 200f, 10f, "px")),
        spec("crystallize", "Crystallize", n("cell", "Cell Size", 3f, 300f, 10f, "px")),
        spec(
            "colorHalftone", "Color Halftone",
            n("radius", "Max. Radius", 4f, 127f, 8f, "px"), n("angleR", "Channel 1", -360f, 360f, 108f, "°"),
            n("angleG", "Channel 2", -360f, 360f, 162f, "°"), n("angleB", "Channel 3", -360f, 360f, 90f, "°"),
        ),
        // Stylize
        spec("emboss", "Emboss", n("angle", "Angle", -360f, 360f, 135f, "°"), n("height", "Height", 1f, 10f, 3f, "px"), n("amount", "Amount", 1f, 500f, 100f, "%")),
        spec("findEdges", "Find Edges"),
        spec(
            "glowingEdges", "Glowing Edges",
            n("width", "Edge Width", 1f, 14f, 2f), n("brightness", "Edge Brightness", 0f, 20f, 6f), n("smoothness", "Smoothness", 1f, 15f, 5f),
        ),
        spec("oilPaint", "Oil Paint", n("stylization", "Stylization", 0.1f, 10f, 4f, decimals = 1, step = 0.1f), n("cleanliness", "Cleanliness", 0f, 10f, 5f, decimals = 1, step = 0.1f)),
        spec("diffuseGlow", "Diffuse Glow", n("graininess", "Graininess", 0f, 10f, 6f), n("glow", "Glow Amount", 0f, 20f, 10f), n("clear", "Clear Amount", 0f, 20f, 15f)),
        // Render
        spec("clouds", "Clouds", ColorParam("foreground", "Foreground", RgbaColor.Black), ColorParam("background", "Background", RgbaColor.White)),
        spec(
            "lensFlare", "Lens Flare",
            n("x", "Light X", 0f, 100f, 25f, "%"), n("y", "Light Y", 0f, 100f, 30f, "%"), n("brightness", "Brightness", 10f, 300f, 100f, "%"),
            ch("lens", "Lens Type", "zoom" to "50–300mm Zoom", "prime35" to "35mm Prime", "prime105" to "105mm Prime", "movie" to "Movie Prime"),
        ),
        spec("vignette", "Vignette", n("amount", "Amount", -100f, 100f, -50f, "%"), n("midpoint", "Midpoint", 0f, 100f, 50f, "%"), n("feather", "Feather", 1f, 100f, 50f, "%")),
        spec("chromaticAberration", "Chromatic Aberration", n("red", "Fix Red/Cyan Fringe", -100f, 100f, 0f, "px"), n("blue", "Fix Blue/Yellow Fringe", -100f, 100f, 0f, "px")),
        spec(
            "lensCorrection", "Lens Correction",
            n("distortion", "Distortion", -100f, 100f, 0f, "%"),
            n("redCyan", "Red/Cyan Fringe", -100f, 100f, 0f, "%"),
            n("blueYellow", "Blue/Yellow Fringe", -100f, 100f, 0f, "%"),
            n("vignette", "Vignette", -100f, 100f, 0f, "%"),
            n("vignetteMidpoint", "Vignette Midpoint", 0f, 100f, 50f, "%"),
            n("verticalPerspective", "Vertical Perspective", -100f, 100f, 0f, "%"),
            n("horizontalPerspective", "Horizontal Perspective", -100f, 100f, 0f, "%"),
            n("angle", "Angle", -180f, 180f, 0f, "°", 1, 0.1f),
            n("scale", "Scale", 0.5f, 1.5f, 1f, "×", 2, 0.01f),
            ToggleParam("edgeExtend", "Edge Extend"),
        ),
        spec(
            "defringe", "Defringe",
            n("purpleAmount", "Purple Amount", 0f, 20f, 0f, step = 0.5f),
            n("greenAmount", "Green Amount", 0f, 20f, 0f, step = 0.5f),
            n("edgeThreshold", "Edge Threshold", 0.005f, 1f, 0.08f, decimals = 3, step = 0.005f),
        ),
    )

    val groups: List<Pair<String, List<String>>> = listOf(
        "Blur" to listOf("gaussianBlur", "boxBlur", "motionBlur", "radialBlur", "surfaceBlur", "tiltShift"),
        "Sharpen" to listOf("unsharpMask", "smartSharpen", "highPass"),
        "Distort" to listOf("twirl", "pinch", "spherize", "wave", "ripple", "polarCoordinates", "displace"),
        "Noise" to listOf("addNoise", "filmGrain", "median", "dustAndScratches", "reduceNoise"),
        "Pixelate" to listOf("mosaic", "crystallize", "colorHalftone"),
        "Stylize" to listOf("emboss", "findEdges", "glowingEdges", "oilPaint", "diffuseGlow"),
        "Render" to listOf("clouds", "lensFlare", "vignette", "chromaticAberration"),
        "Lens Correction" to listOf("lensCorrection", "defringe"),
    ).map { (group, ids) -> group to ids.map { PREFIX + it } }

    operator fun get(id: String): AdjustmentSpec? = All.firstOrNull { it.id == id }
}
