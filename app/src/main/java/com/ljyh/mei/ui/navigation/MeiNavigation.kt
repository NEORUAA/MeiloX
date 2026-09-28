package com.ljyh.mei.ui.navigation

import android.content.Context
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.ljyh.mei.ui.screen.Screen
import kotlinx.serialization.Serializable

@Serializable
data class MeiRoute(val route: String) : NavKey

class MeiNavBackStackEntry(
    val route: String,
    val savedStateHandle: SavedStateHandle,
)

class MeiNavigator(
    val context: Context,
    val backStack: NavBackStack<NavKey>,
) {
    private val savedStateHandles = mutableMapOf<String, SavedStateHandle>()

    // NavDisplay reads this value during the recomposition caused by a stack update.
    var usesMiuixTransitionEffects: Boolean = true
        private set

    val currentRoute: String?
        get() = (backStack.lastOrNull() as? MeiRoute)?.route

    val currentBackStackEntry: MeiNavBackStackEntry?
        get() = currentRoute?.let { route -> MeiNavBackStackEntry(route, handleFor(route)) }

    val previousBackStackEntry: MeiNavBackStackEntry?
        get() = (backStack.getOrNull(backStack.lastIndex - 1) as? MeiRoute)?.let { entry ->
            MeiNavBackStackEntry(entry.route, handleFor(entry.route))
        }

    fun navigate(route: String) {
        if (currentRoute != route) {
            recordTransition(route)
            backStack.add(MeiRoute(route))
        }
    }

    fun navigateTopLevel(route: String) {
        if (currentRoute == route) return

        recordTransition(route)
        val root = backStack.firstOrNull() as? MeiRoute ?: MeiRoute(route = route)
        backStack.clear()
        backStack.add(root)
        if (root.route != route) {
            backStack.add(MeiRoute(route))
        }
    }

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) return false
        recordTransition((backStack.getOrNull(backStack.lastIndex - 1) as? MeiRoute)?.route)
        // Kotlin 2.3 may lower MutableList.removeLast() to the Java 21 API.
        // Android 14 does not expose that interface method yet.
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun navigateUp(): Boolean = popBackStack()

    private fun handleFor(route: String): SavedStateHandle =
        savedStateHandles.getOrPut(route) { SavedStateHandle() }

    private fun recordTransition(targetRoute: String?) {
        usesMiuixTransitionEffects = !isHomeNavigationRoute(currentRoute) ||
            !isHomeNavigationRoute(targetRoute)
    }

    private fun isHomeNavigationRoute(route: String?): Boolean =
        route != null && Screen.MainScreens.any { it.route == route }
}

/**
 * Keeps ViewModels scoped to a NavDisplay entry, matching NavHost behavior.
 */
class MeiNavEntryViewModelStoreOwner(
    private val activity: androidx.activity.ComponentActivity,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras
        get() = activity.defaultViewModelCreationExtras

    fun clear() {
        viewModelStore.clear()
    }
}
