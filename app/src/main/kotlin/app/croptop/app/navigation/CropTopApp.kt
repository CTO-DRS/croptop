package app.croptop.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.croptop.app.AppViewModel
import app.croptop.app.BuildConfig
import app.croptop.app.canvas.FlavorCanvasHost
import app.croptop.app.files.clipboardImageUri
import app.croptop.app.files.imageDropTarget
import app.croptop.app.files.rememberOpenImageLauncher
import app.croptop.app.onboarding.OnboardingViewModel
import app.croptop.app.onboarding.WelcomeDialog
import app.croptop.app.palette.CommandPalette
import app.croptop.app.palette.PaletteItem
import app.croptop.core.designsystem.catalog.AeCatalog
import app.croptop.core.designsystem.component.LocalOpenCommandPalette
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.DensityPreference
import app.croptop.core.designsystem.theme.LocalCaptionBar
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.designsystem.theme.rememberAeDensity
import app.croptop.core.designsystem.theme.rememberCaptionBar
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.ActionId
import app.croptop.core.input.ActionScope
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutScope
import app.croptop.core.model.ThemeMode
import app.croptop.core.model.UiDensityMode
import app.croptop.feature.develop.DevelopRoute
import app.croptop.feature.develop.DevelopScreen
import app.croptop.feature.editor.EditorDocumentTab
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.EditorScreen
import app.croptop.feature.editor.canvas.LocalCanvasHost
import app.croptop.feature.home.HomeEntry
import app.croptop.feature.home.HomeRoute
import app.croptop.feature.home.NewDocumentDialog
import app.croptop.feature.library.LibraryRoute
import app.croptop.feature.library.LibraryScreen
import app.croptop.feature.settings.LicensesRoute
import app.croptop.feature.settings.LicensesScreen
import app.croptop.feature.settings.SettingsRoute
import app.croptop.feature.settings.SettingsScreen
import app.croptop.feature.settings.ShortcutsRoute
import app.croptop.feature.settings.ShortcutsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** A document the OS asked us to open (VIEW / EDIT / SEND). */
data class PendingOpen(val uri: String)

@Serializable
data object CatalogRoute : NavKey

@Composable
fun CropTopApp(
    pendingOpen: MutableStateFlow<PendingOpen?>,
    actions: ActionDispatcher,
    onOpenInNewWindow: (String?) -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val prefs by viewModel.ui.collectAsStateWithLifecycle()
    val onboardingVm = hiltViewModel<OnboardingViewModel>()
    // Created here so the local MCP server follows its setting for the whole app session.
    hiltViewModel<app.croptop.core.agent.AgentViewModel>()
    val theme = when (prefs.theme) {
        ThemeMode.SYSTEM -> ThemePreference.System
        ThemeMode.DARK -> ThemePreference.Dark
        ThemeMode.LIGHT -> ThemePreference.Light
    }
    val density = rememberAeDensity(
        when (prefs.density) {
            UiDensityMode.AUTO -> DensityPreference.Auto
            UiDensityMode.POINTER -> DensityPreference.Pointer
            UiDensityMode.TOUCH -> DensityPreference.Touch
        },
    )
    CropTopTheme(theme = theme, density = density) {
        // Caption bar buttons (minimize/maximize/close) and status bar icons must contrast with our header.
        val lightChrome = app.croptop.core.designsystem.theme.Ae.colors.app.luminance() > 0.5f
        val chromeView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (chromeView.context as? android.app.Activity)?.window ?: return@SideEffect
            val caption = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                android.view.WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
            } else {
                0
            }
            val light = caption or android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightChrome) light else 0, light)
        }
        CompositionLocalProvider(
            LocalActionDispatcher provides actions,
            LocalCanvasHost provides FlavorCanvasHost,
            LocalCaptionBar provides rememberCaptionBar(),
            LocalOpenCommandPalette provides { actions.perform(ActionId("app.palette")) },
        ) {
            val backStack = rememberNavBackStack(HomeRoute)
            val routeLauncher = remember(backStack) { EditorRouteLauncher(backStack) }
            val editorTabOrder = remember { mutableStateListOf<EditorRoute>() }
            val editorTabInfo = remember { mutableStateMapOf<EditorRoute, Pair<String, Boolean>>() }
            val editorRouteTabs = remember { EditorRouteTabs(backStack, editorTabOrder) }
            var pendingTabClose by remember { mutableStateOf<EditorRoute?>(null) }
            var showEditorNewDocument by remember { mutableStateOf(false) }
            val editorRoutes = backStack.filterIsInstance<EditorRoute>()
            LaunchedEffect(editorRoutes) {
                editorRouteTabs.sync()
                editorTabInfo.keys.filter { it !in editorRoutes }.forEach(editorTabInfo::remove)
            }
            val documentTabs = editorTabOrder.mapNotNull { route ->
                if (route !in editorRoutes) return@mapNotNull null
                val fallbackName = route.recoveryName
                    ?: route.newSpec?.name
                    ?: route.sourceUri?.substringAfterLast('/')?.ifBlank { null }
                    ?: if (route.openDocumentId != null) "Smart Object Contents" else "Untitled"
                val info = editorTabInfo[route]
                EditorDocumentTab(route, info?.first ?: fallbackName, info?.second ?: false)
            }
            fun selectEditorTab(route: EditorRoute) {
                editorRouteTabs.activate(route)
            }
            fun closeEditorTab(route: EditorRoute) {
                if (editorTabInfo[route]?.second == true) {
                    pendingTabClose = route
                    selectEditorTab(route)
                    return
                }
                editorRouteTabs.remove(route)
                editorTabInfo.remove(route)
            }
            fun moveEditorTab(from: EditorRoute, to: EditorRoute) {
                editorRouteTabs.move(from, to)
            }
            fun closeEditorRoute(route: EditorRoute) {
                editorRouteTabs.remove(route)
                editorTabInfo.remove(route)
                if (pendingTabClose == route) pendingTabClose = null
            }
            // Tag jank reports with the visible screen (JankStats state, debug builds).
            val view = androidx.compose.ui.platform.LocalView.current
            val screen = backStack.lastOrNull()?.let { it::class.simpleName } ?: "Home"
            LaunchedEffect(screen) {
                androidx.metrics.performance.PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("screen", screen)
            }
            val pending by pendingOpen.collectAsStateWithLifecycle()
            LaunchedEffect(pending) {
                pending?.let {
                    backStack.add(EditorRoute(sourceUri = it.uri))
                    pendingOpen.value = null
                }
            }
            fun back() {
                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            }
            var paletteOpen by remember { mutableStateOf(false) }
            val editorFileActions = EditorFileActionHandlers(
                onNewDocument = { showEditorNewDocument = true },
                onOpenDocument = rememberOpenImageLauncher { uri -> routeLauncher.openUri(uri.toString()) },
            )
            ActionScope(ShortcutScope.GLOBAL) {
                editorFileActions.handlers.forEach { (id, handler) -> on(id, handler) }
                on("app.palette") { paletteOpen = !paletteOpen }
                on("app.settings") { if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute) }
                on("app.newWindow") { onOpenInNewWindow(null) }
            }
            if (showEditorNewDocument) {
                NewDocumentDialog(
                    onCreate = { spec ->
                        showEditorNewDocument = false
                        routeLauncher.create(spec)
                    },
                    onCancel = { showEditorNewDocument = false },
                )
            }
            NavDisplay(
                backStack = backStack,
                onBack = ::back,
                modifier = Modifier
                    .imageDropTarget { uri -> backStack.add(EditorRoute(sourceUri = uri.toString())) }
                    .fillMaxSize().background(
                        Ae.colors.app,
                    ).windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.captionBar)),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        val context = LocalContext.current
                        val openImage = rememberOpenImageLauncher { uri -> backStack.add(EditorRoute(sourceUri = uri.toString())) }
                        HomeEntry(
                            onNewDocument = { spec -> backStack.add(EditorRoute(newSpec = spec)) },
                            onOpen = openImage,
                            onOpenUri = { uri -> backStack.add(EditorRoute(sourceUri = uri)) },
                            onOpenUriInNewWindow = onOpenInNewWindow,
                            onOpenSettings = { backStack.add(SettingsRoute) },
                            onOpenLibrary = { backStack.add(LibraryRoute) },
                            onPaste = { clipboardImageUri(context)?.let { backStack.add(EditorRoute(sourceUri = it.toString())) } },
                        )
                        app.croptop.app.recovery.RecoveryPrompt(
                            onRecover = { document -> backStack.add(EditorRoute(recoveryId = document.id, recoveryName = document.name)) },
                        )
                    }
                    entry<EditorRoute> { route ->
                        EditorScreen(
                            route,
                            onClose = { closeEditorRoute(route) },
                            onOpenInNewWindow = { onOpenInNewWindow(it) },
                            onOpenSmartObjectContents = { backStack.add(it) },
                            onOpenDocumentInTab = { backStack.add(it) },
                            documentTabs = documentTabs,
                            onDocumentTabInfo = { tabRoute, title, dirty ->
                                val next = title to dirty
                                if (editorTabInfo[tabRoute] != next) editorTabInfo[tabRoute] = next
                            },
                            onSelectDocumentTab = ::selectEditorTab,
                            onCloseDocumentTab = ::closeEditorTab,
                            onMoveDocumentTab = ::moveEditorTab,
                            closeTabOnActivation = pendingTabClose == route,
                            onCloseTabRequestConsumed = { if (pendingTabClose == route) pendingTabClose = null },
                        )
                    }
                    entry<DevelopRoute> { route ->
                        DevelopScreen(
                            route,
                            onBack = ::back,
                            onOpenInEditor = { uri, settings ->
                                backStack.add(EditorRoute(sourceUri = uri, rawDevelopSettings = settings, rawAsSmartObject = true))
                            },
                            onOpenFlattened = { uri, settings ->
                                backStack.add(EditorRoute(sourceUri = uri, rawDevelopSettings = settings))
                            },
                            onOpenSibling = { uri ->
                                back()
                                backStack.add(route.copy(uri = uri, copyId = null))
                            },
                            onOpenCopy = { copyId ->
                                back()
                                backStack.add(route.copy(copyId = copyId))
                            },
                        )
                    }
                    entry<LibraryRoute> {
                        LibraryScreen(
                            onOpenInEditor = { uri -> backStack.add(EditorRoute(sourceUri = uri, rawAsSmartObject = true)) },
                            onOpenFlattened = { uri -> backStack.add(EditorRoute(sourceUri = uri)) },
                            onBack = ::back,
                            onDevelop = { uri, siblings -> backStack.add(DevelopRoute(uri, siblings)) },
                        )
                    }
                    entry<SettingsRoute> {
                        SettingsScreen(
                            onBack = ::back,
                            onOpenCatalog = if (BuildConfig.DEBUG) ({ backStack.add(CatalogRoute) }) else null,
                            onOpenShortcuts = { backStack.add(ShortcutsRoute) },
                            onOpenLicenses = { backStack.add(LicensesRoute) },
                            appVersion = BuildConfig.VERSION_NAME,
                            onShowWelcome = { onboardingVm.showAgain() },
                        )
                    }
                    entry<LicensesRoute> { LicensesScreen(onBack = ::back) }
                    entry<ShortcutsRoute> { ShortcutsScreen(onBack = ::back) }
                    entry<CatalogRoute> {
                        // A way out like every other settings page: desktop windows have no system back gesture.
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Ae.colors.app)) {
                            app.croptop.core.designsystem.component.AeAppHeader(startPadding = 8.dp) {
                                app.croptop.core.designsystem.component.AeIconButton(
                                    app.croptop.core.designsystem.icon.AeIcons.Close,
                                    "Back to settings",
                                    ::back,
                                )
                                app.croptop.core.designsystem.component.AeText("Design system", style = Ae.type.title)
                            }
                            app.croptop.core.designsystem.component.AeDivider()
                            AeCatalog(Modifier.weight(1f))
                        }
                    }
                },
            )
            val showWelcome by onboardingVm.showWelcome.collectAsStateWithLifecycle()
            if (showWelcome == true) WelcomeDialog(onFinish = onboardingVm::finish)
            if (paletteOpen) {
                val recent by viewModel.recent.collectAsStateWithLifecycle()
                CommandPalette(
                    actions,
                    onDismiss = { paletteOpen = false },
                    ask = viewModel.ask,
                    extra = recent.map { doc ->
                        PaletteItem("Open Recent: ${doc.name}", "Recent") { backStack.add(EditorRoute(sourceUri = doc.uri)) }
                    },
                )
            }
        }
    }
}
