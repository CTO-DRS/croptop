package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterLayerChoiceTest {
    private val displace = checkNotNull(FilterCatalog.get(FilterCatalog.PREFIX + "displace"))

    @Test
    fun `displace takes its map from a layer of the document`() {
        val map = displace.params.filterIsInstance<ChoiceParam>().first { it.key == "map" }
        assertEquals(ChoiceParam.Source.PIXEL_LAYERS, map.source)
        assertEquals(ParamValue.Choice("none"), displace.defaults()["map"])
        // A layer reference survives normalising (the options list only names "none"), anything else falls back.
        val withLayer = displace.normalize(displace.defaults() + ("map" to ParamValue.Choice("layer:42")))
        assertEquals(ParamValue.Choice("layer:42"), withLayer["map"])
        assertEquals(ParamValue.Choice("none"), displace.normalize(displace.defaults() + ("map" to ParamValue.Choice("file:x")))["map"])
    }

    @Test
    fun `other choices do not accept layer references`() {
        val fit = displace.params.filterIsInstance<ChoiceParam>().first { it.key == "fit" }
        assertEquals(ChoiceParam.Source.STATIC, fit.source)
        assertEquals(ParamValue.Choice("stretch"), displace.normalize(displace.defaults() + ("fit" to ParamValue.Choice("layer:42")))["fit"])
    }

    @Test
    fun `scales are clamped to the filter's range`() {
        assertEquals(ParamValue.Num(999f), displace.normalize(displace.defaults() + ("h" to ParamValue.Num(5000f)))["h"])
    }
}
