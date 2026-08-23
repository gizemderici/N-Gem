package com.furkandurmaz.nsosyal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.data.MockSocialData
import com.furkandurmaz.nsosyal.model.SocialPost
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: AppState, onLogout: () -> Unit) {
    var selectedSection by remember { mutableStateOf("Gönderiler") }
    var showSettings by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ProfileHero(state) { showSettings = true } }
        item { PrivacyCard(Modifier.padding(horizontal = 18.dp)) { showSettings = true } }
        item { StatsRow() }
        item { ProfileSectionPicker(selectedSection) { selectedSection = it } }
        val posts = if (selectedSection == "Kaydedilenler") MockSocialData.posts.filter { it.id in state.savedPostIds } else MockSocialData.posts
        if (posts.isEmpty()) {
            item { SurfaceCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth()) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("▯", color = Blue, fontSize = 28.sp); Text("Henüz kayıt yok", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("Kaydettiğin içerikler burada görünecek.", color = MutedInk, fontSize = 13.sp) } } }
        } else {
            posts.chunked(3).forEach { row ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { post -> ProfileTile(post, state, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Canvas) {
            SettingsContent(state, onLogout) { showSettings = false }
        }
    }
}

@Composable
private fun ProfileHero(state: AppState, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(445.dp).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(165.dp).background(Brush.linearGradient(listOf(Color(0xFF0A1429), Blue, Violet))))
        Row(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton("↗", "Profili paylaş", { state.showToast("Profil bağlantısı hazır") })
            RoundIconButton("⚙", "Ayarlar", onSettings)
        }
        Box(Modifier.size(102.dp).align(Alignment.TopCenter).offset(y = 116.dp).background(Canvas, CircleShape).padding(5.dp), contentAlignment = Alignment.Center) { Avatar(MockSocialData.currentUser, 92.dp) }
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Furkan Durmaz", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("@furkandurmaz", color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("Dijital ürünler, sade deneyimler ve Türkiye’den çıkan iyi fikirler üzerine düşünüyorum.", color = Ink, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Text("⌖ İstanbul     ◷ Ağu 2026", color = MutedInk, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Pressable(onClick = { state.showToast("Profil düzenleme yakında") }, modifier = Modifier.weight(1f).height(42.dp).background(Color.White, CircleShape).border(1.dp, StrongBorder, CircleShape)) { Text("Profili düzenle", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)) }
                Pressable(onClick = { state.showToast("Başlangıç paketin hazır") }, modifier = Modifier.size(48.dp, 42.dp).background(Ink, CircleShape)) { Text("+♙", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center)) }
            }
        }
    }
}

@Composable
private fun PrivacyCard(modifier: Modifier, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = modifier) {
        SurfaceCard(Modifier.fillMaxWidth(), 22.dp, true) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(Modifier.size(50.dp).background(Blue.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text("♢", color = Blue, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Gizlilik ve tercihler", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("İlgi alanlarını, bildirimleri ve veri kontrollerini yönet.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp) }
                Text("›", color = SubtleInk, fontSize = 21.sp)
            }
        }
    }
}

@Composable
private fun StatsRow() {
    SurfaceCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth()) {
        Row(Modifier.padding(vertical = 14.dp)) {
            ProfileStat("42", "Gönderi", Modifier.weight(1f)); Box(Modifier.width(1.dp).height(30.dp).background(Border)); ProfileStat("12,8 B", "Takipçi", Modifier.weight(1f)); Box(Modifier.width(1.dp).height(30.dp).background(Border)); ProfileStat("684", "Takip", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(label, color = MutedInk, fontSize = 10.sp) }
}

@Composable
private fun ProfileSectionPicker(selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).background(ElevatedSurface, CircleShape).padding(4.dp)) {
        listOf("Gönderiler", "Medya", "Kaydedilenler").forEach { section ->
            Pressable(onClick = { onSelected(section) }, modifier = Modifier.weight(1f).height(38.dp).background(if (selected == section) Ink else Color.Transparent, CircleShape)) { Text(section, color = if (selected == section) Color.White else MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun ProfileTile(post: SocialPost, state: AppState, modifier: Modifier) {
    Pressable(onClick = { state.selectedReasonPost = post }, modifier = modifier) {
        if (post.artwork != null && post.artworkTitle != null && post.artworkSubtitle != null) MediaArtwork(post.artwork, post.artworkTitle, post.artworkSubtitle, compact = true, isVideo = post.isVideo, videoLength = post.videoLength)
        else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(post.creator.colors), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("“", color = Color.White.copy(alpha = .85f), fontSize = 32.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SettingsContent(state: AppState, onLogout: () -> Unit, onDismiss: () -> Unit) {
    var personalization by remember { mutableStateOf(true) }
    var activityNotifications by remember { mutableStateOf(true) }
    var recommendationNotifications by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var showInterestPicker by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxHeight(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Tercihler ve gizlilik", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Akışın otomatik olarak arka planda güncellenir.", color = MutedInk, fontSize = 12.sp) }; Pressable(onClick = onDismiss) { Text("Bitti", color = Blue, fontWeight = FontWeight.SemiBold) } }
        }
        item {
            SurfaceCard(Modifier.fillMaxWidth(), 22.dp, true) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(58.dp).background(Blue.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text("✦", color = Blue, fontSize = 23.sp) }
                    Column(Modifier.weight(1f)) { Text("Akışın otomatik olarak güncellenir", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("İlgi ve etkileşim sinyalleri birlikte değerlendirilir; ayrıca akış seçmen gerekmez.", color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            }
        }
        item {
            SettingsSection("İLGİ ALANLARIN") {
                Pressable(onClick = { showInterestPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(38.dp).background(Blue.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text("☷", color = Blue) }
                        Column(Modifier.weight(1f)) {
                            Text("İlgi filtresini düzenle", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${state.selectedInterestIds.size} alan seçili", color = MutedInk, fontSize = 11.sp)
                        }
                        Text("›", color = SubtleInk, fontSize = 20.sp)
                    }
                }
            }
        }
        item { SettingsSection("BAĞLAMSAL TERCİHLER") { Column { ContextRow("☀", Amber, "Mesai saatleri", "Teknoloji • Eğitim"); HorizontalDivider(color = Border); ContextRow("☾", Violet, "Akşam", "Mizah • Uzun video"); HorizontalDivider(color = Border); ContextRow("◷", Green, "Hafta sonu", "Yerel • Kültür") } } }
        item { SettingsSection("KONTROLLER") { Column { SettingToggle("Akıllı kişiselleştirme", "Uygulama içindeki davranışlarını akışı iyileştirmek için kullanır.", personalization) { personalization = it }; HorizontalDivider(color = Border); SettingToggle("Etkileşim bildirimleri", "Yanıt, takip ve topluluk gelişmeleri.", activityNotifications) { activityNotifications = it }; HorizontalDivider(color = Border); SettingToggle("Öneri bildirimleri", "Sana uygun yeni içerik önerileri.", recommendationNotifications) { recommendationNotifications = it } } } }
        item { SettingsSection("VERİLERİN") { Column { SettingsAction("▤", "Kişiselleştirme profilini görüntüle", Blue) { state.showToast("Profil açıldı") }; HorizontalDivider(color = Border); SettingsAction("⇩", "Verilerimi dışa aktar", Blue) { state.showToast("Dışa aktarma hazırlanıyor") }; HorizontalDivider(color = Border); SettingsAction("↻", "Öğrenilmiş modeli sıfırla", Coral) { state.resetLearnedProfile() } } } }
        item { SettingsSection("HESAP") { SettingsAction("↪", "Hesaptan çıkış yap", Coral) { showLogout = true } } }
        item { Text("Kamera, özel mesaj içerikleri, kişi listesi ve kesin konum kişiselleştirme için varsayılan olarak toplanmaz.", color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().background(ElevatedSurface, RoundedCornerShape(16.dp)).padding(15.dp)) }
        item { Spacer(Modifier.navigationBarsPadding().height(30.dp)) }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Hesaptan çıkış yapılsın mı?") },
            text = { Text("İlgi tercihlerin ve yerel ayarların bu cihazda korunur.") },
            confirmButton = { TextButton(onClick = { showLogout = false; onDismiss(); onLogout() }) { Text("Çıkış yap", color = Coral, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("Vazgeç", color = Ink) } },
            containerColor = Color.White
        )
    }

    if (showInterestPicker) {
        AlertDialog(
            onDismissRequest = { showInterestPicker = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("İlgi alanların", color = Ink, fontWeight = FontWeight.Bold)
                    Text("Akış bunları güçlü bir sinyal olarak kullanır.", color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(MockSocialData.interests, key = { it.id }) { interest ->
                        val selected = interest.id in state.selectedInterestIds
                        Pressable(
                            onClick = {
                                if (selected) {
                                    if (state.selectedInterestIds.size > 3) {
                                        state.selectedInterestIds.remove(interest.id)
                                    } else {
                                        state.showToast("En az 3 ilgi alanı seçili kalmalı")
                                    }
                                } else {
                                    state.selectedInterestIds.add(interest.id)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) interest.color.copy(alpha = .10f) else ElevatedSurface, RoundedCornerShape(16.dp))
                                .border(1.dp, if (selected) interest.color.copy(alpha = .28f) else Border, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                Box(Modifier.size(34.dp).background(interest.color.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(interest.glyph, color = interest.color, fontWeight = FontWeight.Bold)
                                }
                                Text(interest.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(if (selected) "✓" else "+", color = if (selected) interest.color else SubtleInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInterestPicker = false }) {
                    Text("Bitti", color = Blue, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color.White
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, color = SubtleInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp, modifier = Modifier.padding(start = 4.dp)); SurfaceCard(Modifier.fillMaxWidth()) { content() } }
}

@Composable
private fun ContextRow(glyph: String, color: Color, title: String, value: String) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.size(36.dp).background(color.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text(glyph, color = color) }; Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(value, color = MutedInk, fontSize = 11.sp) }; Text("›", color = SubtleInk, fontSize = 18.sp) }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = MutedInk, fontSize = 10.sp, lineHeight = 14.sp) }; Switch(checked, onChecked, colors = SwitchDefaults.colors(checkedTrackColor = Ink, checkedThumbColor = Color.White)) }
}

@Composable
private fun SettingsAction(glyph: String, title: String, color: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(glyph, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp)); Text(title, color = color.takeIf { it == Coral } ?: Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("›", color = SubtleInk, fontSize = 18.sp) } }
}
