package com.furkandurmaz.nsosyal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.model.SocialNotification
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

@Composable
fun NotificationsScreen(state: AppState) {
    var showOnlyUnread by remember { mutableStateOf(false) }
    val notifications = state.notifications.filter { !showOnlyUnread || it.unread }
    LaunchedEffect(Unit) { state.loadNotifications() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(top = 8.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Bildirimler", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Topluluğundaki son hareketler.", color = MutedInk, fontSize = 12.sp) }
                Pressable(onClick = { showOnlyUnread = !showOnlyUnread }, modifier = Modifier.background(if (showOnlyUnread) Ink else ElevatedSurface, CircleShape).padding(horizontal = 13.dp, vertical = 9.dp)) {
                    Text(if (showOnlyUnread) "Tümü" else "Okunmamış", color = if (showOnlyUnread) androidx.compose.ui.graphics.Color.White else Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(7.dp))
                RoundIconButton("✓", "Tümünü okundu işaretle", state::markAllNotificationsRead)
            }
            Spacer(Modifier.height(18.dp))
        }
        item { Text("YENİ", color = SubtleInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp)) }
        items(notifications, key = SocialNotification::id) { notification -> NotificationRow(notification, state) }
        if (notifications.isEmpty()) {
            item {
                SurfaceCard(Modifier.padding(18.dp).fillMaxWidth()) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("✓", color = Green, fontSize = 28.sp); Text("Hepsini gördün", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("Yeni bildirimlerin burada görünecek.", color = MutedInk, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: SocialNotification, state: AppState) {
    Pressable(onClick = { state.showToast("Bildirim açıldı") }, modifier = Modifier.fillMaxWidth().background(if (notification.unread) Blue.copy(alpha = .045f) else Canvas)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                Avatar(notification.creator, 48.dp)
                Box(Modifier.size(22.dp).align(Alignment.BottomEnd).background(notification.kind.color, CircleShape), contentAlignment = Alignment.Center) { Text(notification.kind.glyph, color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${notification.creator.name} ${notification.message}", color = Ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (notification.unread) FontWeight.SemiBold else FontWeight.Normal)
                Text(notification.time, color = SubtleInk, fontSize = 10.sp)
            }
            if (notification.unread) Box(Modifier.size(8.dp).background(Blue, CircleShape))
        }
        HorizontalDivider(color = Border, modifier = Modifier.align(Alignment.BottomCenter).padding(start = 78.dp))
    }
}
