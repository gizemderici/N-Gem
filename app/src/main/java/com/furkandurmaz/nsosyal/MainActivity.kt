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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.furkandurmaz.nsosyal.ui.AuthenticationScreen
import com.furkandurmaz.nsosyal.ui.MainApp
import com.furkandurmaz.nsosyal.ui.OnboardingScreen
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

private enum class RootStage { AUTHENTICATION, ONBOARDING, APP }

@Composable
private fun NSosyalRoot(skipAuthentication: Boolean, skipOnboarding: Boolean) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("nsosyal_preferences", Context.MODE_PRIVATE)
    }
    val appState = remember { AppState() }

    var authenticated by remember {
        mutableStateOf(preferences.getBoolean("authenticated", false) || skipAuthentication)
    }
    var onboardingCompleted by remember {
        mutableStateOf(preferences.getBoolean("onboarding_completed", false) || skipOnboarding)
    }

    val stage = when {
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
            RootStage.AUTHENTICATION -> AuthenticationScreen(
                onAuthenticated = {
                    preferences.edit { putBoolean("authenticated", true) }
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
                    preferences.edit { putBoolean("authenticated", false) }
                    authenticated = false
                }
            )
        }
    }
}
