package com.ljyh.mei.parasite

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.NavigationBarHeight
import com.ljyh.mei.constants.NavigationBarBottomMargin
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.glass.rememberSharedGlassBackdrop
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ljyh.mei.ui.local.LocalDatabase
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.local.LocalSelectionToolbar
import com.ljyh.mei.ui.local.SelectionToolbarState
import com.ljyh.mei.ui.navigation.MeiNavEntryViewModelStoreOwner
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.navigationEntry
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Original page routing under the debug carrier. Playback/component takeover is a separate gate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostAccountProbe(activity: ComponentActivity, initialRoute: String = Screen.Setting.route) {
    val graph = AppGraph.component
    val account by graph.account().state.collectAsState()
    val backStack = rememberNavBackStack(MeiRoute(initialRoute))
    val navigator = remember { MeiNavigator(activity, backStack) }
    val owners = remember { mutableMapOf<String, MeiNavEntryViewModelStoreOwner>() }
    val decorator = remember {
        NavEntryDecorator<NavKey>(
            onPop = { owners.remove(it.toString())?.clear() },
            decorate = { entry ->
                val owner = owners.getOrPut(entry.contentKey.toString()) { MeiNavEntryViewModelStoreOwner(activity) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
            },
        )
    }
    DisposableEffect(Unit) { onDispose { owners.values.forEach { it.clear() }; owners.clear() } }
    LaunchedEffect(Unit) {
        val preferences = withContext(Dispatchers.IO) { graph.context().dataStore.data.first() }
        HostRuntimeProbe.report("account_preferences cookie_present=${!preferences[CookieKey].isNullOrBlank()} user_id_present=${!preferences[UserIdKey].isNullOrBlank()}")
    }
    LaunchedEffect(account) {
        HostRuntimeProbe.report("account_ui ready=${account.session != null} authenticated=${account.authenticated} profile=${account.profile != null} loading=${account.loading} error=${account.profileUnavailable}")
    }
    BackHandler { if (!navigator.popBackStack()) activity.finish() }
    val glassColors = defaultGlassColors(isSystemInDarkTheme(), MaterialTheme.colorScheme.primary)
    val baseBackdrop = rememberCanvasBackdrop { drawRect(glassColors.groupedBackground) }
    val pageBackdrop = rememberLayerBackdrop()
    val overlayBackdrop = rememberSharedGlassBackdrop(baseBackdrop, pageBackdrop)
    val selectionToolbar = remember { SelectionToolbarState() }
    val pageInsets = if (selectionToolbar.content.value != null) {
        WindowInsets.systemBars.add(WindowInsets(bottom = NavigationBarHeight))
    } else WindowInsets.systemBars
    CompositionLocalProvider(
        LocalNavController provides navigator,
        LocalDatabase provides graph.database(),
        LocalPlayerConnection provides null,
        LocalPlayerAwareWindowInsets provides pageInsets,
        LocalSelectionToolbar provides selectionToolbar,
        LocalGlassColors provides glassColors,
        LocalGlassBackdrop provides baseBackdrop,
        LocalBlurBackdrop provides overlayBackdrop,
    ) {
        // Record the rendered page for separate-window menus without letting page glass
        // sample itself. Match the production Activity's shared, screen-anchored backdrop.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize().graphicsLayer().layerBackdrop(pageBackdrop).trackBackdropPosition(pageBackdrop)) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { if (!navigator.popBackStack()) activity.finish() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), decorator),
                    entryProvider = { key ->
                        val route = (key as MeiRoute).route
                        NavEntry(key, contentKey = route) {
                            navigationEntry(route, TopAppBarDefaults.pinnedScrollBehavior(), isNavigationTab = route == initialRoute)
                        }
                    },
                )
            }
            selectionToolbar.content.value?.let { toolbar ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + NavigationBarBottomMargin)) {
                    CompositionLocalProvider(LocalGlassBackdrop provides overlayBackdrop) { toolbar() }
                }
            }
        }
    }
}
