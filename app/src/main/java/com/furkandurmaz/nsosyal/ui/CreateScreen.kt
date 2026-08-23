package com.furkandurmaz.nsosyal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

@Composable
fun CreateScreen(state: AppState) {
    val formats = listOf("Gönderi" to "✎", "Fotoğraf" to "▧", "Kısa video" to "▶", "Uzun video" to "▤")
    var selectedFormat by remember { mutableStateOf(formats.first().first) }
    var text by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("Herkes") }
    val selectedTopicIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) { state.loadTopics() }

    Column(
        modifier = Modifier.fillMaxSize().background(Canvas).statusBarsPadding().padding(top = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Oluştur", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Fikrini sade ve güçlü biçimde paylaş.", color = MutedInk, fontSize = 12.sp) }
            RoundIconButton("×", "Vazgeç", { state.selectedTab = com.furkandurmaz.nsosyal.model.MainTab.HOME })
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            formats.forEach { (format, glyph) ->
                val selected = format == selectedFormat
                Pressable(onClick = { selectedFormat = format }, modifier = Modifier.height(42.dp).background(if (selected) Ink else Color.White, CircleShape).border(1.dp, if (selected) Color.Transparent else Border, CircleShape).padding(horizontal = 14.dp)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Text(glyph, color = if (selected) Color.White else Blue); Text(format, color = if (selected) Color.White else Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SurfaceCard(Modifier.fillMaxWidth(), 24.dp, true) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Avatar(state.currentCreator, 44.dp)
                        Column { Text(state.displayName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(state.displayUsername, color = MutedInk, fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 500) text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                        placeholder = { Text("Ne düşünüyorsun?", color = SubtleInk, fontSize = 17.sp) },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ElevatedSurface.copy(alpha = .55f), unfocusedContainerColor = ElevatedSurface.copy(alpha = .55f), focusedBorderColor = Blue.copy(alpha = .35f), unfocusedBorderColor = Color.Transparent, cursorColor = Blue, focusedTextColor = Ink, unfocusedTextColor = Ink)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${text.length}/500", color = if (text.length > 450) Coral else SubtleInk, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        listOf("#" to "Etiket", "@" to "Kişi", "⌖" to "Konum").forEach { (glyph, label) ->
                            Pressable(onClick = { state.showToast("$label ekleme yakında") }, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) { Text(glyph, color = Blue, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            if (state.topics.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("Konu ekle", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("${selectedTopicIds.size}/3", color = MutedInk, fontSize = 11.sp) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.topics.forEach { topic ->
                            val selected = topic.id in selectedTopicIds
                            Pressable(
                                onClick = {
                                    if (selected) selectedTopicIds.remove(topic.id)
                                    else if (selectedTopicIds.size < 3) selectedTopicIds.add(topic.id)
                                },
                                modifier = Modifier.height(36.dp).background(if (selected) Blue else Color.White, CircleShape).border(1.dp, if (selected) Color.Transparent else Border, CircleShape).padding(horizontal = 12.dp)
                            ) {
                                Text("${topic.icon}  ${topic.name}", color = if (selected) Color.White else Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
            if (selectedFormat != "Gönderi") {
                Pressable(onClick = { state.showToast("Medya seçici yakında") }) {
                    Box(Modifier.fillMaxWidth().height(128.dp).background(Blue.copy(alpha = .06f), RoundedCornerShape(22.dp)).border(1.dp, Blue.copy(alpha = .18f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (selectedFormat.contains("video", true)) "▶" else "▧", color = Blue, fontSize = 28.sp); Text("$selectedFormat ekle", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("Cihazından seç veya kamerayı aç", color = MutedInk, fontSize = 11.sp) }
                    }
                }
            }
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column {
                    CreateOption("◉", "Kimler görebilir?", audience) { audience = if (audience == "Herkes") "Takipçiler" else "Herkes" }
                    androidx.compose.material3.HorizontalDivider(color = Border)
                    CreateOption("□", "Kimler yanıtlayabilir?", "Herkes") { state.showToast("Yanıt ayarı güncellendi") }
                }
            }
            Spacer(Modifier.weight(1f))
        }
        PrimaryButton("Yayınla", { state.publish(text, selectedTopicIds.toList()); text = ""; selectedTopicIds.clear() }, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), text.isNotBlank())
        Spacer(Modifier.navigationBarsPadding().height(86.dp))
    }
}

@Composable
private fun CreateOption(glyph: String, title: String, value: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).background(Blue.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) { Text(glyph, color = Blue, fontWeight = FontWeight.Bold) }
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, color = MutedInk, fontSize = 11.sp)
            Text("›", color = SubtleInk, fontSize = 20.sp)
        }
    }
}
