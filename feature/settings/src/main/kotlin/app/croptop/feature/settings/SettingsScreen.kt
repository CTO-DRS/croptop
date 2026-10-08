package app.croptop.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCurveEditor
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.model.CanvasBackground
import app.croptop.core.model.Curve
import app.croptop.core.model.RenderingIntent
import app.croptop.core.model.StylusBarrelAction
import app.croptop.core.model.ThemeMode
import app.croptop.core.model.UiDensityMode
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WheelBehavior
import app.croptop.core.model.WorkingSpace
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute : NavKey

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCatalog: (() -> Unit)?,
    onOpenShortcuts: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLicenses: () -> Unit = {},
    appVersion: String = "",
    onShowWelcome: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.ui.collectAsStateWithLifecycle()
    val agentVm = hiltViewModel<app.croptop.core.agent.AgentViewModel>()
    val agentSettings by agentVm.settings.collectAsStateWithLifecycle()
    val agentStatus by agentVm.status.collectAsStateWithLifecycle()
    val modelsVm = hiltViewModel<AiModelsViewModel>()
    val models by modelsVm.state.collectAsStateWithLifecycle()
    SettingsContent(
        prefs,
        viewModel::update,
        onBack,
        onOpenCatalog,
        modifier,
        onOpenShortcuts,
        onOpenLicenses,
        about = AboutInfo(appVersion, viewModel.capabilities),
        onShowWelcome = onShowWelcome,
        agent = AgentUi(agentSettings, agentStatus, agentVm.clientConfig(agentSettings), agentVm::setEnabled, agentVm::regenerateToken, agentVm::setConfirmExport),
        aiModels = AiModelsUi(models, modelsVm::install, modelsVm::cancel, modelsVm::delete),
    )
}

@Composable
fun SettingsContent(
    prefs: UiPreferences,
    onUpdate: ((UiPreferences) -> UiPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenCatalog: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenShortcuts: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    about: AboutInfo? = null,
    onShowWelcome: (() -> Unit)? = null,
    agent: AgentUi? = null,
    aiModels: AiModelsUi? = null,
) {
    val c = Ae.colors
    Column(modifier.fillMaxSize().background(c.app)) {
        AeAppHeader(startPadding = 8.dp) {
            AeIconButton(AeIcons.Close, "Close settings", onBack, shortcut = "Esc")
            AeText("Settings", style = Ae.type.title)
        }
        AeDivider()
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Section("Appearance") {
                    Setting("Theme") {
                        AeSegmented(ThemeMode.entries, prefs.theme, { t ->
                            onUpdate { it.copy(theme = t) }
                        }, { it.name.lowercase().replaceFirstChar(Char::uppercase) })
                    }
                    Setting("Interface density", "Auto uses compact rows when a mouse or trackpad is connected.") {
                        AeSegmented(UiDensityMode.entries, prefs.density, { d ->
                            onUpdate { it.copy(density = d) }
                        }, { it.name.lowercase().replaceFirstChar(Char::uppercase) })
                    }
                    Setting("Canvas background") {
                        AeDropdown(
                            options = CanvasBackground.entries,
                            selected = prefs.canvasBackground,
                            onSelect = { b -> onUpdate { it.copy(canvasBackground = b) } },
                            label = { it.label },
                        )
                    }
                }
                Section("Input") {
                    Setting("Mouse wheel") {
                        AeDropdown(WheelBehavior.entries, prefs.wheel, { w -> onUpdate { it.copy(wheel = w) } }, { it.label })
                    }
                    Setting("Stylus barrel button") {
                        AeDropdown(
                            StylusBarrelAction.entries,
                            prefs.stylusBarrelAction,
                            { action -> onUpdate { it.copy(stylusBarrelAction = action) } },
                            { it.label },
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val resetPressureFocus = remember { FocusRequester() }
                        Column {
                            AeText("Stylus pressure")
                            AeText(
                                "Adjust how pen pressure maps to brush dynamics.",
                                style = Ae.type.caption,
                                color = Ae.colors.textTertiary,
                                maxLines = 2,
                            )
                        }
                        AeCurveEditor(
                            prefs.stylusPressureCurve,
                            { curve -> onUpdate { it.copy(stylusPressureCurve = curve) } },
                            Modifier.widthIn(max = 400.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                            onTabExit = { resetPressureFocus.requestFocus() },
                        )
                        AeButton(
                            "Reset pressure curve",
                            onClick = { onUpdate { it.copy(stylusPressureCurve = Curve.Identity) } },
                            modifier = Modifier.focusRequester(resetPressureFocus),
                        )
                    }
                    Setting("Keyboard shortcuts", "Photoshop-compatible. Press Meta+/ anywhere to see them all.") {
                        AeButton("Customize…", onOpenShortcuts)
                    }
                }
                Section("Performance") {
                    Setting(
                        "Memory for images",
                        "How much of this device's memory CropTop may use before moving undo steps and tiles to disk.",
                    ) {
                        AeInlineSlider(
                            label = null,
                            value = prefs.memoryBudgetPercent.toFloat(),
                            onValueChange = { v -> onUpdate { it.copy(memoryBudgetPercent = v.toInt()) } },
                            range = UiPreferences.MemoryBudgetRange.first.toFloat()..UiPreferences.MemoryBudgetRange.last.toFloat(),
                            defaultValue = 40f,
                            unit = "%",
                            trackWidth = 160.dp,
                            contentDescription = "Memory for images",
                        )
                    }
                    about?.capabilities?.let { cap ->
                        Setting("Graphics", cap.gpuName ?: "GPU details appear once the canvas has started") {
                            val depths = cap.supportedDepthBits.sorted().joinToString("/")
                            AeText("${cap.abi} · $depths-bit", style = Ae.type.caption, color = Ae.colors.textSecondary)
                        }
                    }
                }
                Section("Color") {
                    Setting("New document color space", "Used for new canvases. Opened files keep their own profile.") {
                        AeDropdown(
                            WorkingSpace.entries,
                            prefs.defaultWorkingSpace,
                            { w -> onUpdate { it.copy(defaultWorkingSpace = w) } },
                            { it.label },
                        )
                    }
                    Setting("Rendering intent", "How colors are mapped when converting between profiles.") {
                        AeDropdown(
                            RenderingIntent.entries,
                            prefs.renderingIntent,
                            { r -> onUpdate { it.copy(renderingIntent = r) } },
                            { it.label },
                        )
                    }
                }
                if (agent != null) {
                    Section("AI agents (MCP)") { AgentSettingsBody(agent) }
                }
                if (aiModels != null && aiModels.state.rows.isNotEmpty()) {
                    Section("AI models") { AiModelsBody(aiModels) }
                }
                Section("About") {
                    Setting("CropTop", about?.let { "Version ${it.version} · engine ${it.capabilities.engineVersion}" }) {}
                    Setting("Open-source licenses") { AeButton("View", onOpenLicenses) }
                    if (onShowWelcome != null) Setting("Welcome tour", "See the short introduction again.") { AeButton("Show", onShowWelcome) }
                }
                if (onOpenCatalog != null) {
                    Section("Developer") {
                        Setting("Component catalog") { AeButton("Open", onOpenCatalog) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeText(title.uppercase(), style = Ae.type.panelHeader, color = Ae.colors.textSecondary)
        Column(
            Modifier.fillMaxWidth().background(Ae.colors.surface1, Ae.shapes.popover).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun Setting(label: String, help: String? = null, control: @Composable () -> Unit) {
    // Keeps the label column readable while controls stay right-aligned.
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AeText(label)
            if (help != null) AeText(help, style = Ae.type.caption, color = Ae.colors.textTertiary, maxLines = 2)
        }
        control()
    }
}

data class AboutInfo(val version: String, val capabilities: EngineCapabilities)
