package app.croptop.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.croptop.core.model.CanvasBackground
import app.croptop.core.model.Curve
import app.croptop.core.model.RenderingIntent
import app.croptop.core.model.StylusBarrelAction
import app.croptop.core.model.Swatch
import app.croptop.core.model.ThemeMode
import app.croptop.core.model.UiDensityMode
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WheelBehavior
import app.croptop.core.model.WorkingSpace
import app.croptop.core.model.WorkspaceLayout
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Singleton
class DataStorePreferencesRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    /** Device-only secrets (the agent pairing token): a separate file that backups and device transfers leave out. */
    @param:javax.inject.Named(DEVICE_STORE) private val device: DataStore<Preferences> = store,
) : PreferencesRepository {

    override val ui: Flow<UiPreferences> = store.data.map(::read)

    private fun read(p: Preferences) = UiPreferences(
        theme = p[Keys.theme].toEnum(ThemeMode.DARK),
        density = p[Keys.density].toEnum(UiDensityMode.AUTO),
        canvasBackground = p[Keys.canvas].toEnum(CanvasBackground.THEME),
        wheel = p[Keys.wheel].toEnum(WheelBehavior.SCROLL),
        memoryBudgetPercent = (p[Keys.memoryBudget] ?: 40).coerceIn(UiPreferences.MemoryBudgetRange),
        defaultWorkingSpace = p[Keys.workingSpace].toEnum(WorkingSpace.DISPLAY_P3),
        renderingIntent = p[Keys.intent].toEnum(RenderingIntent.RELATIVE_COLORIMETRIC),
        stylusPressureCurve = p[Keys.stylusPressureCurve]?.let { raw ->
            runCatching { json.decodeFromString(Curve.serializer(), raw) }.getOrNull()
        } ?: Curve.Identity,
        stylusBarrelAction = p[Keys.stylusBarrelAction].toEnum(StylusBarrelAction.EYEDROPPER),
    )

    override suspend fun updateUi(transform: (UiPreferences) -> UiPreferences) {
        store.edit { p ->
            val next = transform(read(p))
            p[Keys.theme] = next.theme.name
            p[Keys.density] = next.density.name
            p[Keys.canvas] = next.canvasBackground.name
            p[Keys.wheel] = next.wheel.name
            p[Keys.memoryBudget] = next.memoryBudgetPercent.coerceIn(UiPreferences.MemoryBudgetRange)
            p[Keys.workingSpace] = next.defaultWorkingSpace.name
            p[Keys.intent] = next.renderingIntent.name
            p[Keys.stylusPressureCurve] = json.encodeToString(Curve.serializer(), next.stylusPressureCurve)
            p[Keys.stylusBarrelAction] = next.stylusBarrelAction.name
        }
    }

    override val workspace: Flow<WorkspaceLayout> = store.data.map { p ->
        p[Keys.workspace]?.let { runCatching { json.decodeFromString(WorkspaceLayout.serializer(), it) }.getOrNull() }
            ?: WorkspaceLayout.Essentials
    }

    override suspend fun setWorkspace(layout: WorkspaceLayout) {
        store.edit { it[Keys.workspace] = json.encodeToString(WorkspaceLayout.serializer(), layout) }
    }

    override val customWorkspaces: Flow<List<WorkspaceLayout>> = store.data.map { p -> decodeList(p[Keys.customWorkspaces]) }

    override suspend fun saveCustomWorkspace(layout: WorkspaceLayout) {
        store.edit { p ->
            val list = decodeList(p[Keys.customWorkspaces]).filterNot { it.name == layout.name } + layout
            p[Keys.customWorkspaces] = json.encodeToString(ListSerializer(WorkspaceLayout.serializer()), list)
        }
    }

    override suspend fun deleteCustomWorkspace(name: String) {
        store.edit { p ->
            val list = decodeList(p[Keys.customWorkspaces]).filterNot { it.name == name }
            p[Keys.customWorkspaces] = json.encodeToString(ListSerializer(WorkspaceLayout.serializer()), list)
        }
    }

    override val keymapJson: Flow<String?> = store.data.map { it[Keys.keymap] }

    override suspend fun setKeymapJson(json: String?) {
        store.edit { p -> if (json == null) p.remove(Keys.keymap) else p[Keys.keymap] = json }
    }

    override val onboardingSeenVersion: Flow<Int> = store.data.map { it[Keys.onboarding] ?: 0 }

    override suspend fun setOnboardingSeen(versionCode: Int) {
        store.edit { it[Keys.onboarding] = versionCode }
    }

    override val agentSettings: Flow<AgentSettings> = kotlinx.coroutines.flow.combine(store.data, device.data) { p, d ->
        AgentSettings(p[Keys.agentEnabled] ?: false, p[Keys.agentPort] ?: 39510, d[Keys.agentToken] ?: "", p[Keys.agentConfirmExport] ?: true)
    }

    override suspend fun setAgentSettings(settings: AgentSettings) {
        store.edit {
            it[Keys.agentEnabled] = settings.enabled
            it[Keys.agentPort] = settings.port
            it[Keys.agentConfirmExport] = settings.confirmExport
            // Older versions kept the token here, where backups could carry it to another device.
            it.remove(Keys.agentToken)
        }
        device.edit { it[Keys.agentToken] = settings.token }
    }

    override val swatches: Flow<List<Swatch>?> = store.data.map { p ->
        p[Keys.swatches]?.let { runCatching { json.decodeFromString(ListSerializer(Swatch.serializer()), it) }.getOrNull() }
    }

    override suspend fun setSwatches(swatches: List<Swatch>?) {
        store.edit { p ->
            if (swatches == null) p.remove(Keys.swatches) else p[Keys.swatches] = json.encodeToString(ListSerializer(Swatch.serializer()), swatches)
        }
    }

    private fun decodeList(raw: String?): List<WorkspaceLayout> =
        raw?.let { runCatching { json.decodeFromString(ListSerializer(WorkspaceLayout.serializer()), it) }.getOrNull() }.orEmpty()

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val theme = stringPreferencesKey("ui.theme")
        val density = stringPreferencesKey("ui.density")
        val canvas = stringPreferencesKey("ui.canvas_background")
        val wheel = stringPreferencesKey("input.wheel")
        val workspace = stringPreferencesKey("workspace.current")
        val customWorkspaces = stringPreferencesKey("workspace.custom")
        val keymap = stringPreferencesKey("input.keymap")
        val memoryBudget = intPreferencesKey("perf.memory_budget_percent")
        val onboarding = intPreferencesKey("app.onboarding_seen_version")
        val agentEnabled = androidx.datastore.preferences.core.booleanPreferencesKey("agent.enabled")
        val agentPort = intPreferencesKey("agent.port")
        val agentToken = stringPreferencesKey("agent.token")
        val agentConfirmExport = androidx.datastore.preferences.core.booleanPreferencesKey("agent.confirmExport")
        val swatches = stringPreferencesKey("color.swatches")
        val workingSpace = stringPreferencesKey("color.default_space")
        val intent = stringPreferencesKey("color.rendering_intent")
        val stylusPressureCurve = stringPreferencesKey("input.stylus_pressure_curve")
        val stylusBarrelAction = stringPreferencesKey("input.stylus_barrel_action")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

/** Name of the DataStore for device-only secrets; see `backup_rules.xml` and `data_extraction_rules.xml`. */
const val DEVICE_STORE = "croptop_device"
