package dev.gitpockit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.designsystem.theme.ThemeMode
import dev.gitpockit.core.designsystem.theme.ThemeSettings
import dev.gitpockit.data.SettingsRepository
import dev.gitpockit.ui.DeepLink
import dev.gitpockit.ui.GitPockitNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    private var deepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink = intent.toDeepLink()
        setContent {
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeSettings(mode = ThemeMode.Dark))
            val dark = when (theme.mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            GitPockitTheme(settings = theme) {
                GitPockitNavHost(deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toDeepLink()?.let { deepLink = it }
    }

    private fun Intent.toDeepLink(): DeepLink? =
        getLongExtra(EXTRA_REPO_ID, -1L).takeIf { it >= 0 }?.let { DeepLink(it, getBooleanExtra(EXTRA_OPEN_CONFLICTS, false)) }

    companion object {
        const val EXTRA_REPO_ID = "dev.gitpockit.extra.REPO_ID"
        const val EXTRA_OPEN_CONFLICTS = "dev.gitpockit.extra.OPEN_CONFLICTS"
    }
}
