package com.furkandurmaz.nsosyal.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.model.MainTab
import com.furkandurmaz.nsosyal.ui.components.Pressable
import com.furkandurmaz.nsosyal.ui.components.ToastPill
import com.furkandurmaz.nsosyal.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(state: AppState, onLogout: () -> Unit) {
    LaunchedEffect(Unit) {
        state.refreshFeed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        AnimatedContent(
            targetState = state.selectedTab,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.985f)) togetherWith
                    (fadeOut() + scaleOut(targetScale = 0.99f))
            },
            label = "mainTab"
        ) { tab ->
            when (tab) {
                MainTab.HOME -> HomeScreen(state)
                MainTab.EXPLORE -> ExploreScreen(state)
                MainTab.CREATE -> CreateScreen(state)
                MainTab.NOTIFICATIONS -> NotificationsScreen(state)
                MainTab.PROFILE -> ProfileScreen(state, onLogout)
            }
        }

        BottomNavigation(
            selected = state.selectedTab,
            onSelected = { state.selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        AnimatedVisibility(
            visible = state.toastMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 90.dp, start = 24.dp, end = 24.dp)
        ) {
            state.toastMessage?.let { ToastPill(it) }
        }

        state.selectedReasonPost?.let { post ->
            ModalBottomSheet(
                onDismissRequest = { state.selectedReasonPost = null },
                containerColor = Canvas
            ) {
                RecommendationReasonContent(post, state) {
                    state.selectedReasonPost = null
                }
            }
        }
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(2_000)
            state.toastMessage = null
        }
    }
}

@Composable
private fun BottomNavigation(
    selected: MainTab,
    onSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .fillMaxWidth()
            .shadow(22.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.16f))
            .background(Color.White.copy(alpha = 0.90f), RoundedCornerShape(50))
            .border(1.dp, Color.White, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.entries.forEach { tab ->
            Pressable(
                onClick = { onSelected(tab) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                if (tab == MainTab.CREATE) {
                    Box(
                        modifier = Modifier
                            .size(49.dp)
                            .align(Alignment.Center)
                            .background(Ink, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box {
                            Text(
                                tab.glyph,
                                color = if (selected == tab) Ink else SubtleInk,
                                fontSize = 22.sp,
                                fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Medium
                            )
                            if (tab == MainTab.NOTIFICATIONS) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Coral, CircleShape)
                                )
                            }
                        }
                        Text(
                            tab.title,
                            color = if (selected == tab) Ink else SubtleInk,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
