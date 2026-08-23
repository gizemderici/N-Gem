package com.furkandurmaz.nsosyal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.furkandurmaz.nsosyal.AppState
import com.furkandurmaz.nsosyal.model.SocialPost
import com.furkandurmaz.nsosyal.model.SocialStory
import com.furkandurmaz.nsosyal.network.BackendConversation
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.util.Locale

@Composable
fun HomeScreen(state: AppState) {
    var selectedStory by remember { mutableStateOf<SocialStory?>(null) }
    var appeared by remember { mutableStateOf(false) }
    var showsMessages by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { appeared = true }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(top = 8.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HomeHeader(state, Modifier.statusBarsPadding().padding(horizontal = 18.dp)) { showsMessages = true }
        }
        item {
            StoryRow(state) { selectedStory = it }
        }
        item {
            HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 18.dp))
        }
        itemsIndexed(state.visiblePosts, key = { _, post -> post.id }) { index, post ->
            AnimatedVisibility(
                visible = appeared,
                enter = fadeIn(tween(300, index * 55)) + slideInVertically(tween(360, index * 55)) { 26 },
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                PostCard(post, state)
            }
        }
        item {
            SurfaceCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("✓", color = Green, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Şimdilik hepsi bu", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Yeni içerikler geldiğinde akışın arka planda sana göre güncellenecek.", color = MutedInk, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }

    selectedStory?.let { story ->
        StoryViewer(state.stories, story, state) { selectedStory = null }
    }

    if (showsMessages) MessagesDialog(state) { showsMessages = false }

}

@Composable
private fun HomeHeader(state: AppState, modifier: Modifier = Modifier, onMessages: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        BrandMark(size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text("NSosyal", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val hour = LocalTime.now().hour
            Text(when (hour) { in 5..11 -> "Günaydın, ${state.firstName}"; in 12..17 -> "İyi günler, ${state.firstName}"; else -> "İyi akşamlar, ${state.firstName}" }, color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        RoundIconButton("✉", "Mesajlar", onMessages)
    }
}

@Composable
private fun StoryRow(state: AppState, onStory: (SocialStory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        state.stories.forEachIndexed { index, story ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(index * 55L); visible = true }
            val scale by animateFloatAsState(if (visible) 1f else .74f, spring(dampingRatio = .68f), label = "storyScale")
            val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "storyAlpha")
            Pressable(
                onClick = {
                    if (story.own) state.showToast("Yeni hikâye oluşturma ekranı yakında") else onStory(story)
                },
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(70.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(if (story.seen) ElevatedSurface else Color.Transparent, CircleShape)
                                .then(if (!story.seen) Modifier.background(BrandBrush, CircleShape) else Modifier)
                        )
                        Box(Modifier.size(63.dp).align(Alignment.Center).background(Canvas, CircleShape), contentAlignment = Alignment.Center) {
                            Avatar(story.creator, 57.dp, false)
                        }
                        if (story.own) {
                            Box(Modifier.size(22.dp).align(Alignment.BottomEnd).background(Blue, CircleShape).border(2.dp, Canvas, CircleShape), contentAlignment = Alignment.Center) {
                                Text("+", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(if (story.own) "Hikâyen" else story.creator.name.substringBefore(" "), color = if (story.seen) MutedInk else Ink, fontSize = 10.sp, fontWeight = if (story.seen) FontWeight.Medium else FontWeight.SemiBold, modifier = Modifier.width(72.dp), textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: SocialPost, state: AppState) {
    val liked = post.id in state.likedPostIds
    val saved = post.id in state.savedPostIds
    val following = post.creator.id in state.followedCreatorIds
    var showsComments by remember(post.id) { mutableStateOf(false) }

    DisposableEffect(post.id) {
        state.beginViewing(post)
        onDispose { state.endViewing(post) }
    }

    SurfaceCard(Modifier.fillMaxWidth(), 24.dp) {
        Column {
            Row(Modifier.padding(horizontal = 15.dp).padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(post.creator, 43.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(post.creator.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(" · ${post.time}", color = SubtleInk, fontSize = 12.sp)
                    }
                    Text("${post.creator.handle}  •  ${post.topic}", color = MutedInk, fontSize = 11.sp, maxLines = 1)
                }
                if (!following) {
                    Pressable(onClick = { state.toggleFollow(post.creator) }, modifier = Modifier.background(ElevatedSurface, CircleShape).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text("Takip", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Pressable(onClick = { state.openReason(post) }, modifier = Modifier.size(30.dp)) {
                    Text("•••", color = MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
            }
            Text(post.body, color = Ink, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 15.dp).padding(top = 13.dp))
            if (post.mediaUrl != null && post.mediaMimeType != null) {
                RemotePostMedia(post.mediaUrl, post.mediaMimeType, Modifier.padding(horizontal = 9.dp, vertical = 15.dp))
            } else if (post.artwork != null && post.artworkTitle != null && post.artworkSubtitle != null) {
                MediaArtwork(post.artwork, post.artworkTitle, post.artworkSubtitle, Modifier.padding(horizontal = 9.dp, vertical = 15.dp), post.isVideo, post.videoLength)
            }
            Pressable(onClick = { state.openReason(post) }, modifier = Modifier.padding(horizontal = 15.dp).background(Blue.copy(alpha = 0.07f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✦", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(post.reason, color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("›", color = SubtleInk, fontSize = 15.sp)
                }
            }
            HorizontalDivider(Modifier.padding(top = 13.dp), color = Border)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PostAction(if (liked) "♥" else "♡", compactNumber(post.likeCount + if (liked) 1 else 0), if (liked) Coral else MutedInk) { state.toggleLike(post) }
                PostAction("□", compactNumber(post.commentCount), MutedInk) { showsComments = true }
                PostAction("↗", compactNumber(post.shareCount), MutedInk) { state.share(post) }
                PostAction(if (saved) "▮" else "▯", "", if (saved) Blue else MutedInk) { state.toggleSave(post) }
            }
        }
    }
    if (showsComments) CommentsDialog(post, state) { showsComments = false }
}

@Composable
private fun PostAction(glyph: String, value: String, color: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.height(38.dp).padding(horizontal = 7.dp)) {
        Row(modifier = Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(glyph, color = color, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            if (value.isNotEmpty()) Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun RecommendationReasonContent(post: SocialPost, state: AppState, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            NexiOrb(54.dp)
            Column { Text("Neden karşıma çıktı?", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Nexi kısa konuşur, gerekçeyi saklamaz.", color = MutedInk, fontSize = 12.sp) }
        }
        Column(Modifier.fillMaxWidth().background(Blue.copy(alpha = 0.07f), RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("✦  ${post.reason}", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(post.reasonDetail, color = Ink, fontSize = 15.sp, lineHeight = 21.sp)
        }
        Text("Kontrol sende", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        ReasonAction("⌄", "Bu konuyu daha az göster", Coral) { state.hide(post); onDismiss() }
        ReasonAction("◷", "Bu saatte gösterme", Amber) { state.avoidAtCurrentTime(post); onDismiss() }
        Text("Saat bilgisi ve içerikte kalma süresi tek başına karar vermez; açık tercihlerin her zaman daha güçlü sinyaldir.", color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(16.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ReasonAction(glyph: String, title: String, color: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick) {
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(color.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Text(glyph, color = color, fontWeight = FontWeight.Bold) }
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("›", color = SubtleInk, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun StoryViewer(stories: List<SocialStory>, initial: SocialStory, state: AppState, onDismiss: () -> Unit) {
    val viewable = remember(stories) { stories.filterNot(SocialStory::own) }
    var currentIndex by remember { mutableIntStateOf(viewable.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    val story = viewable[currentIndex]

    fun next() {
        if (currentIndex < viewable.lastIndex) currentIndex++ else onDismiss()
    }
    fun previous() {
        if (currentIndex > 0) currentIndex-- else progress = 0f
    }

    LaunchedEffect(currentIndex) {
        state.markStoryViewed(story)
        progress = 0f
        while (progress < 1f) {
            delay(100)
            if (!paused) progress = (progress + .02f).coerceAtMost(1f)
        }
        next()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(artworkColors(story.style)))) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = .08f), size.width * .35f, Offset(size.width * .88f, size.height * .18f))
                drawCircle(Color.White.copy(alpha = .13f), size.width * .52f, Offset(size.width * .9f, size.height * .16f), style = Stroke(2f))
            }
            if (story.mediaUrl != null && story.mediaMimeType != null) {
                RemotePostMedia(
                    story.mediaUrl,
                    story.mediaMimeType,
                    Modifier.align(Alignment.Center).padding(horizontal = 8.dp)
                )
            }
            Row(Modifier.fillMaxSize().padding(top = 105.dp, bottom = 175.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(currentIndex) { detectTapGestures(onTap = { previous() }, onLongPress = { paused = !paused }) })
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(currentIndex) { detectTapGestures(onTap = { next() }, onLongPress = { paused = !paused }) })
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    viewable.indices.forEach { index ->
                        Row(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = .28f), CircleShape)) {
                            Box(Modifier.fillMaxWidth(when { index < currentIndex -> 1f; index > currentIndex -> 0f; else -> progress }).fillMaxHeight().background(Color.White, CircleShape))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(story.creator, 39.dp)
                    Column(Modifier.weight(1f)) { Text(story.creator.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("${story.time} · ${story.creator.handle}", color = Color.White.copy(alpha = .72f), fontSize = 10.sp) }
                    Pressable(onClick = onDismiss, modifier = Modifier.size(38.dp).background(Color.Black.copy(alpha = .2f), CircleShape)) { Text("×", color = Color.White, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center)) }
                }
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(story.style.glyph, color = Color.White.copy(alpha = .86f), fontSize = 28.sp)
                    Text(story.headline, color = Color.White, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
                    Text(story.detail, color = Color.White.copy(alpha = .78f), fontSize = 15.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        placeholder = { Text("Yanıt gönder...", color = Color.White.copy(alpha = .68f), fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Black.copy(alpha = .2f), unfocusedContainerColor = Color.Black.copy(alpha = .2f), focusedBorderColor = Color.White.copy(alpha = .45f), unfocusedBorderColor = Color.White.copy(alpha = .42f), focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White)
                    )
                    Pressable(onClick = {}, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = .2f), CircleShape).border(1.dp, Color.White.copy(alpha = .42f), CircleShape)) { Text("♡", color = Color.White, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center)) }
                }
            }
        }
    }
}

@Composable
private fun CommentsDialog(post: SocialPost, state: AppState, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(post.id) { state.loadComments(post) }
    val comments = state.commentsByPost[post.id].orEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(min = 420.dp, max = 700.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Yorumlar", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Bitti", color = Blue) }
                }
                if (comments.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("İlk yorumu sen yaz", color = MutedInk, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(comments, key = { it.id }) { comment ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(36.dp).background(BrandBrush, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(comment.author.fullName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${comment.author.fullName}  @${comment.author.username}", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(comment.text, color = Ink, fontSize = 14.sp, lineHeight = 19.sp)
                                }
                                if (comment.deletableByMe) TextButton(onClick = { state.deleteComment(comment) }) { Text("Sil", color = Coral, fontSize = 11.sp) }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(500) },
                        placeholder = { Text("Yorum yaz…") },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        singleLine = true
                    )
                    Pressable(
                        onClick = { state.addComment(post, draft) { draft = "" } },
                        modifier = Modifier.size(46.dp).background(if (draft.isBlank()) SubtleInk else Blue, CircleShape)
                    ) { Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)) }
                }
            }
        }
    }
}

@Composable
private fun MessagesDialog(state: AppState, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<BackendConversation?>(null) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { state.loadConversations() }
    LaunchedEffect(selected?.id) { selected?.let { state.loadMessages(it.id) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.95f).fillMaxHeight(.84f),
            shape = RoundedCornerShape(28.dp),
            color = Color.White
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) TextButton(onClick = { selected = null }) { Text("‹ Geri", color = Blue) }
                    Text(selected?.other?.fullName ?: "Mesajlar", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Bitti", color = Blue) }
                }
                if (selected == null) {
                    if (state.conversations.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Henüz mesajın yok", color = MutedInk) }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(state.conversations, key = { it.id }) { conversation ->
                                Pressable(onClick = { selected = conversation }, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                        Box(Modifier.size(44.dp).background(BrandBrush, CircleShape), contentAlignment = Alignment.Center) { Text(conversation.other.fullName.take(1), color = Color.White, fontWeight = FontWeight.Bold) }
                                        Column(Modifier.weight(1f)) {
                                            Text(conversation.other.fullName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text(conversation.lastMessage?.text ?: "Sohbeti aç", color = MutedInk, fontSize = 12.sp, maxLines = 1)
                                        }
                                        if (conversation.unreadCount > 0) Text("${conversation.unreadCount}", color = Color.White, fontSize = 11.sp, modifier = Modifier.background(Blue, CircleShape).padding(7.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val conversation = selected!!
                    val messages = state.messagesByConversation[conversation.id].orEmpty()
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages, key = { it.id }) { message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mineByMe) Arrangement.End else Arrangement.Start) {
                                Text(
                                    message.text ?: "Medya",
                                    color = if (message.mineByMe) Color.White else Ink,
                                    fontSize = 14.sp,
                                    modifier = Modifier.widthIn(max = 270.dp).background(if (message.mineByMe) Blue else ElevatedSurface, RoundedCornerShape(17.dp)).padding(horizontal = 13.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft, { draft = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Mesaj yaz…") }, singleLine = true, shape = CircleShape)
                        Pressable(onClick = { state.sendMessage(conversation.id, draft) { draft = "" } }, modifier = Modifier.size(46.dp).background(Blue, CircleShape)) {
                            Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
}

private fun compactNumber(value: Int): String = when {
    value >= 10_000 -> "${value / 1000}B"
    value >= 1000 -> String.format(Locale.ROOT, "%.1fB", value / 1000.0).replace(".0", "")
    else -> value.toString()
}
