package app.croptop.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterCatalogTest {
    @Test
    fun catalogListsEveryFilterOnceInItsGroup() {
        val all = setOf(
            "gaussianBlur", "boxBlur", "motionBlur", "unsharpMask", "highPass",
            "addNoise", "filmGrain", "median", "dustAndScratches", "reduceNoise",
            "mosaic", "crystallize", "colorHalftone",
            "radialBlur", "surfaceBlur", "tiltShift", "smartSharpen", "twirl", "pinch", "spherize", "wave",
            "ripple", "polarCoordinates", "emboss", "findEdges", "glowingEdges", "oilPaint", "diffuseGlow",
            "clouds", "lensFlare", "displace", "vignette", "chromaticAberration", "lensCorrection", "defringe",
        ).map { FilterCatalog.PREFIX + it }.toSet()

        assertEquals(all, FilterCatalog.All.map { it.id }.toSet())
        assertEquals(all.size, FilterCatalog.All.size)
        val grouped = FilterCatalog.groups.flatMap { it.second }
        assertEquals(FilterCatalog.All.map { it.id }.toSet(), grouped.toSet())
        assertEquals(grouped.size, grouped.toSet().size)
        assertEquals(true, "filter.highPass" in FilterCatalog.groups.single { it.first == "Sharpen" }.second)
    }

    @Test
    fun defringeSchemaMatchesNativeEngineUnitsAndDefaults() {
        val spec = requireNotNull(FilterCatalog["filter.defringe"])
        val normalized = spec.normalize(emptyMap())

        assertEquals(0f, (normalized.getValue("purpleAmount") as ParamValue.Num).v)
        assertEquals(0f, (normalized.getValue("greenAmount") as ParamValue.Num).v)
        assertEquals(0.08f, (normalized.getValue("edgeThreshold") as ParamValue.Num).v)
        assertEquals(setOf("purpleAmount", "greenAmount", "edgeThreshold"), spec.params.map { it.key }.toSet())
    }
}
