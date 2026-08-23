package com.furkandurmaz.nsosyal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.data.MockSocialData
import com.furkandurmaz.nsosyal.model.Interest
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(state: AppState, onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    var showFilters by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        Box(Modifier.size(320.dp).align(Alignment.TopEnd).offset(x = 145.dp, y = (-125).dp).background(Cyan.copy(alpha = 0.10f), CircleShape))
        Box(Modifier.size(280.dp).align(Alignment.BottomStart).offset(x = (-160).dp, y = 150.dp).background(Violet.copy(alpha = 0.07f), CircleShape))

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            OnboardingHeader(step)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                },
                label = "onboardingStep",
                modifier = Modifier.weight(1f)
            ) { currentStep ->
                when (currentStep) {
                    0 -> WelcomeStep { step = 1 }
                    1 -> InterestStep(selected, { showFilters = true }) { step = 2 }
                    else -> PreviewStep {
                        state.completeOnboarding(selected.ifEmpty { listOf("technology", "design", "education") })
                        onComplete()
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { if (selected.size >= 3 || selected.isEmpty()) showFilters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Canvas,
            dragHandle = null
        ) {
            InterestFilterContent(selected) { showFilters = false }
        }
    }
}

@Composable
private fun OnboardingHeader(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        BrandMark(size = 38.dp)
        Text("NSosyal", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        repeat(3) { index ->
            Box(
                Modifier
                    .width(if (index == step) 22.dp else 7.dp)
                    .height(7.dp)
                    .background(if (index <= step) Ink else Border, CircleShape)
            )
            if (index < 2) Spacer(Modifier.width(1.dp))
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.7f))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(210.dp).border(1.dp, Blue.copy(alpha = 0.12f), CircleShape))
            Box(Modifier.size(154.dp).border(1.dp, Violet.copy(alpha = 0.12f), CircleShape))
            NexiOrb(92.dp)
        }
        Spacer(Modifier.height(34.dp))
        Text("Akışın sana göre\nşekillensin.", color = Ink, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(13.dp))
        Text("İlgi alanlarını sen seç. Nexi yalnızca uygulama içindeki tercihlerini ölçülü biçimde öğrensin.", color = MutedInk, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PromisePill("☷", "Kontrol")
            PromisePill("◉", "Şeffaflık")
            PromisePill("✦", "Keşif")
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Akışımı oluştur", onNext)
        Text("Tercihlerini istediğin zaman değiştirebilirsin.", color = SubtleInk, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun PromisePill(glyph: String, text: String) {
    Row(Modifier.background(Color.White.copy(alpha = 0.78f), CircleShape).border(1.dp, Border, CircleShape).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(glyph, color = Ink, fontSize = 11.sp)
        Text(text, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InterestStep(selected: List<String>, onOpenFilters: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Neler ilgini çekiyor?", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("En az üç alan seç. Seçim sıran ilk akışının önceliğini belirler.", color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Pressable(onClick = onOpenFilters) {
                SurfaceCard(Modifier.fillMaxWidth(), 22.dp, true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(46.dp).background(Blue.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) { Text("☷", color = Blue, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("İlgi alanı filtresi", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(if (selected.isEmpty()) "Kategorileri tek bir yerden seç" else "${selected.size} alan seçildi", color = MutedInk, fontSize = 12.sp)
                            }
                            Text("›", color = SubtleInk, fontSize = 24.sp)
                        }
                        if (selected.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                selected.take(4).forEach { id ->
                                    MockSocialData.interests.firstOrNull { it.id == id }?.let { interest ->
                                        Box(Modifier.size(31.dp).background(interest.color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) { Text(interest.glyph, color = interest.color, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                                if (selected.size > 4) Box(Modifier.size(31.dp).background(ElevatedSurface, CircleShape), contentAlignment = Alignment.Center) { Text("+${selected.size - 4}", color = MutedInk, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Öncelik sıran", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        selected.take(4).forEachIndexed { index, id ->
                            val interest = MockSocialData.interests.first { it.id == id }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(22.dp).background(interest.color, CircleShape), contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                Text(interest.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        PrimaryButton(if (selected.size >= 3) "Akış önizlemesini gör" else "${3 - selected.size} seçim daha yap", onNext, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), selected.size >= 3)
    }
}

@Composable
private fun PreviewStep(onComplete: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("İlk akışın hazır.", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Bu denge başlangıç varsayımıdır. Akış kullanım bağlamına göre arka planda dengeli biçimde güncellenir.", color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp)
        SurfaceCard(Modifier.fillMaxWidth(), 24.dp, true) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Görünür akış dengesi", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("İlk gün için önerilen dağılım", color = MutedInk, fontSize = 12.sp) }
                    NexiOrb(42.dp)
                }
                DistributionRow("Ana ilgi alanların", .70f, Cyan)
                DistributionRow("İlgili kategoriler", .20f, Blue)
                DistributionRow("Keşif ve çeşitlilik", .10f, Violet)
            }
        }
        PreviewPromise("◉", Cyan, "Neden gösterildiğini gör", "Her önerinin kısa ve anlaşılır bir nedeni olacak.")
        PreviewPromise("↻", Blue, "Akış zamanla uyum sağlar", "Ayrıca bir akış seçmeden kullanım bağlamına göre güncellenir.")
        PreviewPromise("⌫", Violet, "Öğrenileni sen yönet", "Duraklat, düzelt veya tamamen sıfırla.")
        PrimaryButton("NSosyal’e başla", onComplete)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DistributionRow(title: String, value: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row { Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("%${(value * 100).toInt()}", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth().height(9.dp).background(ElevatedSurface, CircleShape)) { Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(color, CircleShape)) }
    }
}

@Composable
private fun PreviewPromise(glyph: String, color: Color, title: String, detail: String) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(42.dp).background(color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) { Text(glyph, color = color, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(detail, color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp) }
        }
    }
}

@Composable
private fun InterestFilterContent(selected: MutableList<String>, onDone: () -> Unit) {
    Column(Modifier.fillMaxHeight().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("İlgi alanı filtresi", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("En az üç alan seç. Seçim sıran önceliği belirler.", color = MutedInk, fontSize = 13.sp) }
            Text("${selected.size} seçili", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(15.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(MockSocialData.interests, key = Interest::id) { interest -> InterestRow(interest, selected) }
        }
        PrimaryButton(if (selected.size >= 3) "Seçimleri uygula" else "En az 3 alan seç", onDone, Modifier.padding(18.dp), selected.size >= 3)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun InterestRow(interest: Interest, selected: MutableList<String>) {
    val index = selected.indexOf(interest.id)
    Pressable(onClick = { if (index >= 0) selected.removeAt(index) else selected.add(interest.id) }) {
        SurfaceCard(Modifier.fillMaxWidth(), 18.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).background(interest.color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) { Text(interest.glyph, color = interest.color, fontWeight = FontWeight.Bold) }
                Text(interest.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(Modifier.size(26.dp).background(if (index >= 0) interest.color else ElevatedSurface, CircleShape), contentAlignment = Alignment.Center) { Text(if (index >= 0) "${index + 1}" else "+", color = if (index >= 0) Color.White else SubtleInk, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
