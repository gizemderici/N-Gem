package com.furkandurmaz.nsosyal.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.furkandurmaz.nsosyal.model.ArtworkStyle
import com.furkandurmaz.nsosyal.model.Creator
import com.furkandurmaz.nsosyal.ui.theme.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    haptic: HapticFeedbackType = HapticFeedbackType.TextHandleMove,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressScale"
    )
    val feedback = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (pressed) 0.88f else 1f
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button
            ) {
                feedback.performHapticFeedback(haptic)
                onClick()
            },
        content = content
    )
}

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier = modifier.size(size)) {
        drawRoundRect(
            color = Ink,
            cornerRadius = CornerRadius(this.size.width * 0.31f)
        )
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.29f, this@Canvas.size.height * 0.72f)
            lineTo(this@Canvas.size.width * 0.29f, this@Canvas.size.height * 0.28f)
            lineTo(this@Canvas.size.width * 0.70f, this@Canvas.size.height * 0.72f)
            lineTo(this@Canvas.size.width * 0.70f, this@Canvas.size.height * 0.28f)
        }
        drawPath(
            path = path,
            brush = BrandBrush,
            style = Stroke(
                width = this.size.width * 0.105f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
fun NexiOrb(size: Dp = 54.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(12.dp, CircleShape, ambientColor = Blue.copy(alpha = 0.25f))
            .background(BrandBrush, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize(0.58f)
                .offset(x = (-6).dp, y = (-6).dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
        )
        Text("✦", color = Color.White, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Avatar(
    creator: Creator,
    size: Dp = 44.dp,
    showVerified: Boolean = creator.verified
) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(creator.colors),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                creator.initials,
                color = Color.White,
                fontSize = (size.value * 0.31f).sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (showVerified) {
            Box(
                modifier = Modifier
                    .size(size * 0.29f)
                    .align(Alignment.BottomEnd)
                    .background(Blue, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, fontSize = (size.value * 0.17f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    shadow: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .then(if (shadow) Modifier.shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.07f)) else Modifier)
            .background(Surface, shape)
            .border(1.dp, Border, shape)
            .clip(shape),
        content = content
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Pressable(
        onClick = { if (enabled) onClick() },
        haptic = HapticFeedbackType.LongPress,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(if (enabled) Ink else Ink.copy(alpha = 0.28f), CircleShape)
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun RoundIconButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Pressable(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .background(Color.White.copy(alpha = 0.86f), CircleShape)
            .border(1.dp, Border, CircleShape)
    ) {
        Text(
            glyph,
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MediaArtwork(
    style: ArtworkStyle,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    videoLength: String? = null,
    compact: Boolean = false
) {
    val colors = artworkColors(style)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (compact) 1f else 1.18f)
            .background(Brush.linearGradient(colors), RoundedCornerShape(if (compact) 16.dp else 22.dp))
            .clip(RoundedCornerShape(if (compact) 16.dp else 22.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.width * 0.25f,
                center = Offset(size.width * 0.86f, size.height * 0.22f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = size.width * 0.38f,
                center = Offset(size.width * 0.86f, size.height * 0.18f),
                style = Stroke(1.5f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 13.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
        ) {
            if (!compact) {
                Text(style.glyph, color = Color.White.copy(alpha = 0.92f), fontSize = 30.sp)
                Spacer(Modifier.height(9.dp))
            }
            Text(
                title,
                color = Color.White,
                fontSize = if (compact) 16.sp else 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.76f),
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.38f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("▶", color = Color.White, fontSize = 10.sp)
                videoLength?.let {
                    Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun RemotePostMedia(
    urlString: String,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    var failed by remember(urlString) { mutableStateOf(false) }
    var image by remember(urlString) { mutableStateOf<ImageBitmap?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(shape)
            .background(ElevatedSurface)
            .border(1.dp, Border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (mimeType.startsWith("video/")) {
            key(urlString) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(Uri.parse(urlString))
                            setOnPreparedListener { mediaPlayer ->
                                mediaPlayer.isLooping = true
                                mediaPlayer.setVolume(0f, 0f)
                                start()
                            }
                            setOnErrorListener { _, _, _ ->
                                failed = true
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    onRelease = VideoView::stopPlayback
                )
            }
        } else {
            LaunchedEffect(urlString) {
                image = withContext(Dispatchers.IO) {
                    val connection = URL(urlString).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 8_000
                        connection.readTimeout = 15_000
                        connection.setRequestProperty("Accept", "image/*")
                        if (connection.responseCode in 200..299) {
                            connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                        } else null
                    } catch (_: Exception) {
                        null
                    } finally {
                        connection.disconnect()
                    }
                }
                failed = image == null
            }

            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Gönderi görseli",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (mimeType.startsWith("video/")) "▶" else "▧", color = MutedInk, fontSize = 28.sp)
                Text("Medya açılamadı", color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (!mimeType.startsWith("video/") && image == null) {
            CircularProgressIndicator(color = Blue, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun ToastPill(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Ink.copy(alpha = 0.95f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("✓", color = Green, fontWeight = FontWeight.Bold)
        Text(message, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

fun artworkColors(style: ArtworkStyle): List<Color> = when (style) {
    ArtworkStyle.FUTURE -> listOf(Color(0xFF0A1A3A), Blue, Cyan)
    ArtworkStyle.CITY -> listOf(Color(0xFF142730), Green, Amber)
    ArtworkStyle.COMEDY -> listOf(Color(0xFF2E1649), Violet, Color(0xFFFA5FA8))
    ArtworkStyle.CULTURE -> listOf(Color(0xFF310F15), Coral, Amber)
    ArtworkStyle.SPORT -> listOf(Color(0xFF062A21), Green, Cyan)
    ArtworkStyle.LEARNING -> listOf(Color(0xFF0E1732), Violet, Blue)
}
