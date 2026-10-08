package app.croptop.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import kotlinx.serialization.Serializable

@Serializable
data object LicensesRoute : NavKey

private const val DATASTORE_PROTOBUF_LICENSE_ASSET =
    "datastore-preferences-external-protobuf-LICENSE.txt"

data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val notes: String = "",
    val textAsset: String? = null,
)

/** Libraries and assets shipped in the app layer (Kotlin/Compose side). Native ones come from the inventory. */
val AppLicenses = listOf(
    LicenseEntry("AndroidX / Jetpack Compose", "BOM 2026.09.00", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry(
        "JVM dependency notice inventory",
        "",
        "Component and upstream notice summary",
        textAsset = "JVM-THIRD_PARTY.md",
    ),
    LicenseEntry("Kotlin", "2.4.20", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("kotlinx.coroutines", "1.11.0", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("kotlinx.serialization", "1.11.0", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("Dagger Hilt", "2.60.1", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("Room", "2.8.5", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("DataStore", "1.2.1", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry(
        "DataStore External Protobuf",
        "1.2.1",
        "BSD-3-Clause",
        textAsset = DATASTORE_PROTOBUF_LICENSE_ASSET,
    ),
    LicenseEntry("Navigation 3", "1.2.0", "Apache-2.0", textAsset = "apache-2.0.txt"),
    LicenseEntry("Inter (font)", "4.x", "SIL Open Font License 1.1", textAsset = "inter-OFL-1.1.txt"),
    LicenseEntry("JetBrains Mono (font)", "2.x", "SIL Open Font License 1.1", textAsset = "jetbrains-mono-OFL-1.1.txt"),
)

/** CropTop itself: free software under the GNU GPL, version 3 or later. */
val AppSelfLicense = LicenseEntry(
    "CropTop",
    "",
    "GNU General Public License v3.0 or later",
    "Free software: you may use, study, share and change it. Versions you distribute must stay under the same license. " +
        "The CropTop name and logo are trademarks; forks must use their own name.",
    textAsset = "gpl-3.0.txt",
)

/**
 * The downloadable AI models, from the shipped manifest (assets/ai/models.json): name, license and the attribution each
 * license asks for. Apache-2.0 models share the Apache text; MIT and BSD models carry their own copyright notice.
 */
fun parseModelNotices(manifestJson: String): List<LicenseEntry> = runCatching {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(manifestJson) as kotlinx.serialization.json.JsonObject
    val models = root["models"] as kotlinx.serialization.json.JsonArray
    models.map { element ->
        val model = element as kotlinx.serialization.json.JsonObject
        fun str(o: kotlinx.serialization.json.JsonObject?, key: String) =
            (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val license = model["license"] as? kotlinx.serialization.json.JsonObject
        val id = str(model, "id")
        val spdx = str(license, "spdx")
        LicenseEntry(
            name = str(model, "displayName").ifBlank { id },
            version = "",
            license = spdx,
            notes = listOf(str(license, "attribution"), str(license, "sourceUrl")).filter(String::isNotBlank).joinToString(" · "),
            textAsset = when {
                spdx == "Apache-2.0" -> "apache-2.0.txt"
                id.startsWith("midas") -> "model-midas-MIT.txt"
                id.startsWith("nafnet") -> "model-nafnet-MIT.txt"
                id.startsWith("realesr") -> "model-realesrgan-BSD-3-Clause.txt"
                else -> null
            },
        )
    }
}.getOrDefault(emptyList())

/** Parses the Markdown table rows of THIRD_PARTY.md (Component | Version | License | Link mode | Notices). */
fun parseNoticeTable(markdown: String): List<LicenseEntry> = markdown.lines()
    .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Component") }
    .mapNotNull { line ->
        val cells = line.trim('|').split("|").map { it.trim().replace("`", "") }
        if (cells.size < 3) null else LicenseEntry(cells[0], cells[1], cells[2], cells.getOrElse(4) { "" })
    }

internal fun licenseAssetsFor(entry: LicenseEntry): List<String> {
    entry.textAsset?.let { return listOf(it) }
    return when {
        entry.name.startsWith("LibRaw") -> listOf("libraw-CDDL-1.0.txt", "libraw-COPYRIGHT.txt")
        entry.name.startsWith("Clipper2") -> listOf("clipper2-BSL-1.0.txt")
        entry.name.startsWith("Zstandard") -> listOf("zstd-BSD-3-Clause.txt")
        entry.name.startsWith("Little CMS") -> listOf("lcms2-MIT.txt")
        entry.name.startsWith("libjpeg-turbo") -> listOf("libjpeg-turbo-LICENSE.md", "libjpeg-turbo-README.ijg.txt")
        entry.name.startsWith("libpng") -> listOf("libpng-LICENSE.txt")
        entry.name.startsWith("libwebp") -> listOf("libwebp-COPYING.txt", "libwebp-PATENTS.txt")
        entry.name.startsWith("libtiff") -> listOf("libtiff-LICENSE.md")
        entry.name.startsWith("LLVM libc++") -> listOf("llvm-libcxx-Apache-2.0-with-LLVM-exception.txt")
        entry.name.startsWith("lensfun") -> listOf("lensfun-database-CC-BY-SA-3.0.txt")
        else -> emptyList()
    }
}

/** Open-source licenses. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedLicense by remember { mutableStateOf<LicenseEntry?>(null) }
    val native = remember {
        runCatching { context.assets.open("licenses/THIRD_PARTY.md").bufferedReader().use { it.readText() } }
            .map(::parseNoticeTable).getOrDefault(emptyList())
    }
    val models = remember {
        runCatching { context.assets.open("ai/models.json").bufferedReader().use { it.readText() } }.map(::parseModelNotices).getOrDefault(emptyList())
    }
    val c = Ae.colors
    Column(Modifier.fillMaxSize().background(c.app)) {
        AeAppHeader(startPadding = 8.dp) {
            AeIconButton(AeIcons.Close, "Back to settings", onBack)
            AeText("Open-source licenses", style = Ae.type.title)
        }
        AeDivider()
        LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Header("CropTop") }
            item { Entry(AppSelfLicense) { selectedLicense = AppSelfLicense } }
            item { Header("App") }
            items(AppLicenses) { Entry(it) { selectedLicense = it } }
            if (models.isNotEmpty()) {
                item { Header("On-device AI models") }
                items(models) { Entry(it) { selectedLicense = it } }
            }
            item { Header("Image engine") }
            items(native) { Entry(it) { selectedLicense = it } }
        }
    }
    selectedLicense?.let { entry ->
        val licenseText = remember(entry) {
            licenseAssetsFor(entry).joinToString("\n\n---\n\n") { asset ->
                val content = runCatching {
                    context.assets.open("licenses/texts/$asset").bufferedReader().use { it.readText() }
                }.getOrElse { "License text could not be loaded." }
                "[$asset]\n\n$content"
            }.ifBlank { entry.notes.ifBlank { "No bundled license text for this entry." } }
        }
        AeDialog(
            title = "${entry.name} license",
            onDismiss = { selectedLicense = null },
            actions = { AeButton("Close", onClick = { selectedLicense = null }, style = AeButtonStyle.Primary) },
            width = 760.dp,
        ) {
            AeText(
                listOf(entry.license, entry.version).filter { it.isNotBlank() }.joinToString(" · "),
                style = Ae.type.caption,
                color = Ae.colors.textSecondary,
            )
            AeText(
                licenseText,
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                maxLines = 10_000,
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    AeText(
        text.uppercase(),
        style = Ae.type.panelHeader,
        color = Ae.colors.textSecondary,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Entry(e: LicenseEntry, onViewLicense: () -> Unit = {}) {
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AeText(listOf(e.name, e.version).filter { it.isNotBlank() }.joinToString(" · "))
        AeText(e.license, style = Ae.type.caption, color = Ae.colors.textSecondary, maxLines = 3)
        if (e.notes.isNotBlank()) AeText(e.notes, style = Ae.type.caption, color = Ae.colors.textTertiary, maxLines = 4)
        if (licenseAssetsFor(e).isNotEmpty()) {
            AeButton("View license text", onClick = onViewLicense, style = AeButtonStyle.Ghost)
        }
    }
}
