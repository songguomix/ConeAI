package com.cone.agent.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cone.agent.remote.RemoteScreen
import com.cone.agent.ui.SystemActions
import com.cone.agent.ui.screens.chat.ChatScreen
import com.cone.agent.ui.screens.permissions.PermissionsScreen
import com.cone.agent.ui.screens.providers.ProviderEditScreen
import com.cone.agent.ui.screens.providers.ProvidersScreen
import com.cone.agent.ui.screens.settings.SettingsScreen
import com.cone.agent.watch.WatchSetupScreen

object Routes {
    const val CHAT = "chat"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDIT = "provider_edit"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val REMOTE = "remote"
    const val WATCH = "watch"

    fun providerEdit(id: Long) = "$PROVIDER_EDIT/$id"
}

/**
 * True only when this destination is the active, fully-settled one (not mid enter/exit transition).
 *
 * Every screen's back button and the Settings back button sit in the same top-left navigation-icon
 * slot, so two quick taps there — e.g. tap 返回 on Settings then immediately tap again, meaning to open
 * the drawer on Chat — used to fire a SECOND popBackStack() while the pop transition was still running.
 * That popped the start destination too, leaving the NavHost with an empty back stack and nothing to
 * render: a white screen. Gating every navigation on RESUMED makes the stray second event a no-op.
 */
private fun NavBackStackEntry.lifecycleIsResumed() =
    lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
fun ConeNavHost(
    navController: NavHostController,
    systemActions: SystemActions,
) {
    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) { entry ->
            ChatScreen(
                systemActions = systemActions,
                onOpenProviders = { if (entry.lifecycleIsResumed()) navController.navigate(Routes.PROVIDERS) },
                onOpenSettings = { if (entry.lifecycleIsResumed()) navController.navigate(Routes.SETTINGS) },
                onOpenPermissions = { if (entry.lifecycleIsResumed()) navController.navigate(Routes.PERMISSIONS) },
                onOpenRemote = { if (entry.lifecycleIsResumed()) navController.navigate(Routes.REMOTE) },
                onOpenWatch = { if (entry.lifecycleIsResumed()) navController.navigate(Routes.WATCH) },
            )
        }
        composable(Routes.REMOTE) { entry ->
            RemoteScreen(onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() })
        }
        composable(Routes.WATCH) { entry ->
            WatchSetupScreen(onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() })
        }
        composable(Routes.PROVIDERS) { entry ->
            ProvidersScreen(
                onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
                onEditProvider = { id ->
                    if (entry.lifecycleIsResumed()) navController.navigate(Routes.providerEdit(id))
                },
            )
        }
        composable(
            route = "${Routes.PROVIDER_EDIT}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            ProviderEditScreen(
                providerId = id,
                onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() })
        }
        composable(Routes.PERMISSIONS) { entry ->
            PermissionsScreen(
                systemActions = systemActions,
                onBack = { if (entry.lifecycleIsResumed()) navController.popBackStack() },
            )
        }
    }
}
