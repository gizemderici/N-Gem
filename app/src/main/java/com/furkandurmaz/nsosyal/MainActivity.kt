package com.furkandurmaz.nsosyal

import android.os.Bundle
import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.furkandurmaz.nsosyal.network.BackendApiClient
import com.furkandurmaz.nsosyal.network.SessionStore
import com.furkandurmaz.nsosyal.ui.AuthenticationScreen
import com.furkandurmaz.nsosyal.ui.MainApp
import com.furkandurmaz.nsosyal.ui.OnboardingScreen
import com.furkandurmaz.nsosyal.ui.components.BrandMark
import com.furkandurmaz.nsosyal.ui.theme.Canvas
import com.furkandurmaz.nsosyal.ui.theme.Ink
import com.furkandurmaz.nsosyal.ui.theme.MutedInk
import com.furkandurmaz.nsosyal.ui.theme.NSosyalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent {
            NSosyalTheme {
                NSosyalRoot(
                    skipAuthentication = intent.getBooleanExtra("skipAuthentication", false) ||
                        intent.getBooleanExtra("skipOnboarding", false),
                    skipOnboarding = intent.getBooleanExtra("skipOnboarding", false)
                )
            }
        }
    }
}

private enum class RootStage { CHECKING, AUTHENTICATION, ONBOARDING, APP }

@Composable
private fun NSosyalRoot(skipAuthentication: Boolean, skipOnboarding: Boolean) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("nsosyal_preferences", Context.MODE_PRIVATE)
    }
    val appState = remember {
        AppState(
            apiClient = BackendApiClient(BuildConfig.NEXI_API_BASE_URL),
            sessionStore = SessionStore(preferences)
        )
    }

    var startupComplete by remember { mutableStateOf(false) }
    var authenticated by remember { mutableStateOf(false) }
    var onboardingCompleted by remember {
        mutableStateOf(preferences.getBoolean("onboarding_completed", false) || skipOnboarding)
    }

    LaunchedEffect(Unit) {
        authenticated = appState.initialize() || skipAuthentication
        startupComplete = true
    }

    val stage = when {
        !startupComplete -> RootStage.CHECKING
        !authenticated -> RootStage.AUTHENTICATION
        !onboardingCompleted -> RootStage.ONBOARDING
        else -> RootStage.APP
    }

    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.985f)) togetherWith
                (fadeOut() + scaleOut(targetScale = 0.99f))
        },
        label = "rootStage"
    ) { currentStage ->
        when (currentStage) {
            RootStage.CHECKING -> StartupCheckScreen()

            RootStage.AUTHENTICATION -> AuthenticationScreen(
                state = appState,
                onAuthenticated = {
                    authenticated = true
                }
            )

            RootStage.ONBOARDING -> OnboardingScreen(
                state = appState,
                onComplete = {
                    preferences.edit { putBoolean("onboarding_completed", true) }
                    onboardingCompleted = true
                }
            )

            RootStage.APP -> MainApp(
                state = appState,
                onLogout = {
                    appState.logout()
                    authenticated = false
                }
            )
        }
    }
}

@Composable
private fun StartupCheckScreen() {
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 76.dp)
            Spacer(Modifier.height(20.dp))
            Text("NSosyal", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("En iyi veri kaynağı hazırlanıyor…", color = MutedInk, fontSize = 12.sp)
        }
    }
}
