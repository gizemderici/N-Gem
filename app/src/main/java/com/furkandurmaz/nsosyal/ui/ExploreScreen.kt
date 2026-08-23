package com.furkandurmaz.nsosyal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.model.SocialPost
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selectedTopic by remember { mutableStateOf("Tümü") }
    var showFilters by remember { mutableStateOf(false) }
    val topics = listOf("Tümü", "Teknoloji", "Tasarım", "Yerel", "Mizah", "Eğitim")
    val filtered = state.posts.filter {
        (selectedTopic == "Tümü" || it.topic == selectedTopic) &&
            (query.isBlank() || it.body.contains(query, true) || it.creator.name.contains(query, true) || it.topic.contains(query, true))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(top = 8.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { ExploreHeader(state, Modifier.statusBarsPadding().padding(horizontal = 18.dp)) }
        item {
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Kişi, konu veya topluluk ara", color = SubtleInk, fontSize = 13.sp) },
                    leadingIcon = { Text("⌕", color = MutedInk, fontSize = 20.sp) },
                    trailingIcon = if (query.isNotEmpty()) ({ Pressable(onClick = { query = "" }, modifier = Modifier.size(34.dp)) { Text("×", color = SubtleInk, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center)) } }) else null,
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Blue, unfocusedBorderColor = Border, cursorColor = Blue, focusedTextColor = Ink, unfocusedTextColor = Ink)
                )
                Pressable(onClick = { showFilters = true }, modifier = Modifier.size(52.dp).background(if (selectedTopic == "Tümü") Color.White else Ink, CircleShape).border(1.dp, Border, CircleShape)) {
                    Text("☷", color = if (selectedTopic == "Tümü") Ink else Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                    if (selectedTopic != "Tümü") Box(Modifier.size(10.dp).align(Alignment.TopEnd).background(Coral, CircleShape).border(2.dp, Color.White, CircleShape))
                }
            }
        }
        if (query.isBlank() && selectedTopic == "Tümü") {
            item { TrendingHero { selectedTopic = "Teknoloji" } }
            item { CommunityStrip(state) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "Senin için keşif" else "Arama sonuçları", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${filtered.size} içerik", color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyExplore() }
        } else {
            filtered.chunked(2).forEach { rowPosts ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowPosts.forEach { post -> ExploreTile(post, state, Modifier.weight(1f)) }
                        if (rowPosts.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, containerColor = Canvas) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Keşfini daralt", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Tek seferde bir konu seç.", color = MutedInk, fontSize = 13.sp) }
                    Pressable(onClick = { selectedTopic = "Tümü" }) { Text("Temizle", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
                topics.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { topic ->
                            val selected = selectedTopic == topic
                            Pressable(onClick = { selectedTopic = topic }, modifier = Modifier.weight(1f).height(48.dp).background(if (selected) Ink else Color.White, RoundedCornerShape(16.dp)).border(1.dp, if (selected) Color.Transparent else Border, RoundedCornerShape(16.dp))) {
                                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(topicGlyph(topic), color = if (selected) Color.White else topicColor(topic)); Text(topic, color = if (selected) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                PrimaryButton("Sonuçları göster", { showFilters = false })
                Spacer(Modifier.navigationBarsPadding().height(10.dp))
            }
        }
    }
}

@Composable
private fun ExploreHeader(state: AppState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Keşfet", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Balonuna sıkışmadan yeni insanlarla tanış.", color = MutedInk, fontSize = 12.sp) }
        RoundIconButton("⌗", "Topluluk kodunu tara", { state.showToast("Topluluk kodu tarayıcısı yakında") })
    }
}

@Composable
private fun TrendingHero(onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(205.dp).background(DarkBrush, RoundedCornerShape(26.dp))) {
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("GÜNÜN KONUSU", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Yapay zekâyı\nkim yönetecek?", color = Color.White, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                Text("1,8 B gönderi • 12 topluluk", color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
            }
            NexiOrb(62.dp)
        }
    }
}

@Composable
private fun CommunityStrip(state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { Text("Canlı topluluklar", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("Tümü", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CommunityCard("Türk Tasarımcılar", "12,4 B üye", "✎", Violet, state)
            CommunityCard("Bağımsız Üreticiler", "8,1 B üye", "▶", Coral, state)
            CommunityCard("İstanbul Etkinlik", "21 B üye", "⌖", Green, state)
        }
    }
}

@Composable
private fun CommunityCard(title: String, detail: String, glyph: String, color: Color, state: AppState) {
    Pressable(onClick = { state.showToast("$title topluluğuna katıldın") }) {
        SurfaceCard(Modifier.width(205.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(42.dp).background(color.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text(glyph, color = color, fontWeight = FontWeight.Bold) }
                Column { Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(detail, color = MutedInk, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ExploreTile(post: SocialPost, state: AppState, modifier: Modifier) {
    Pressable(onClick = { state.openReason(post) }, modifier = modifier) {
        if (post.artwork != null && post.artworkTitle != null && post.artworkSubtitle != null) {
            MediaArtwork(post.artwork, post.artworkTitle, post.artworkSubtitle, compact = true, isVideo = post.isVideo, videoLength = post.videoLength)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Brush.linearGradient(post.creator.colors), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text("“", color = Color.White.copy(alpha = .88f), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyExplore() {
    SurfaceCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth()) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("⌕", color = Blue, fontSize = 28.sp); Text("Sonuç bulunamadı", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("Farklı bir kelime veya konu deneyebilirsin.", color = MutedInk, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

private fun topicGlyph(topic: String) = when (topic) { "Teknoloji" -> "⌘"; "Tasarım" -> "✎"; "Yerel" -> "⌖"; "Mizah" -> "☺"; "Eğitim" -> "▤"; else -> "▦" }
private fun topicColor(topic: String) = when (topic) { "Teknoloji" -> Blue; "Tasarım" -> Violet; "Yerel" -> Green; "Mizah" -> Coral; "Eğitim" -> Amber; else -> Ink }
