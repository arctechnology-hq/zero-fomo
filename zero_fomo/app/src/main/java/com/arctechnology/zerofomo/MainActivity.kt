package com.arctechnology.zerofomo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.data.location.UserLocationStore
import com.arctechnology.zerofomo.ui.detail.DetailScreen
import javax.inject.Inject
import com.arctechnology.zerofomo.ui.feed.FeedScreen
import com.arctechnology.zerofomo.ui.saved.SavedScreen
import com.arctechnology.zerofomo.ui.theme.ZeroFomoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var locationStore: UserLocationStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every screen puts dark masthead/hero color under the status bar, so
        // the icons must stay light in BOTH themes — the auto style would
        // paint them dark (invisible) in light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            // The selected country tints the mark and accents app-wide.
            val userLocation by locationStore.state.collectAsStateWithLifecycle()
            ZeroFomoTheme(country = userLocation?.country) {
                ZeroFomoNavHost()
            }
        }
    }
}

private object Routes {
    const val FEED = "feed"
    const val SAVED = "saved"
    const val DETAIL = "event/{eventId}"
    fun detail(eventId: String) = "event/$eventId"
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { /* declining is fine */ }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun ZeroFomoNavHost() {
    RequestNotificationPermission()
    val navController = rememberNavController()

    // singleTop delivery: a zerofomo:// tap while the app is already open
    // arrives via onNewIntent, which NavController does not see by itself.
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(navController, activity) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute == Routes.FEED || currentRoute == Routes.SAVED

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.FEED,
                        onClick = { navController.navigateTab(Routes.FEED) },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.FEED) Icons.Default.Celebration
                                else Icons.Outlined.Celebration, null)
                        },
                        label = { Text("Events") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SAVED,
                        onClick = { navController.navigateTab(Routes.SAVED) },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.SAVED) Icons.Default.Favorite
                                else Icons.Default.FavoriteBorder, null)
                        },
                        label = { Text("Saved") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FEED,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.FEED) {
                FeedScreen(onEventClick = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.SAVED) {
                SavedScreen(onEventClick = { navController.navigate(Routes.detail(it)) })
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("eventId") { }),
                deepLinks = listOf(navDeepLink { uriPattern = "zerofomo://event/{eventId}" }),
            ) {
                DetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
