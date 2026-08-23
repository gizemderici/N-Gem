package com.furkandurmaz.nsosyal.model

import androidx.compose.ui.graphics.Color

enum class MainTab(val title: String, val glyph: String) {
    HOME("Akış", "⌂"),
    EXPLORE("Keşfet", "⌕"),
    CREATE("Oluştur", "+"),
    NOTIFICATIONS("Bildirimler", "♢"),
    PROFILE("Profil", "♙")
}

data class Interest(
    val id: String,
    val title: String,
    val glyph: String,
    val color: Color
)

data class Creator(
    val id: String,
    val name: String,
    val handle: String,
    val initials: String,
    val colors: List<Color>,
    val verified: Boolean = false,
    val avatarUrl: String? = null
)

enum class ArtworkStyle(val glyph: String) {
    FUTURE("✦"),
    CITY("⌂"),
    COMEDY("☺"),
    CULTURE("◈"),
    SPORT("●"),
    LEARNING("▤")
}

data class SocialPost(
    val id: String,
    val creator: Creator,
    val time: String,
    val body: String,
    val topic: String,
    val artwork: ArtworkStyle? = null,
    val artworkTitle: String? = null,
    val artworkSubtitle: String? = null,
    val isVideo: Boolean = false,
    val videoLength: String? = null,
    val reason: String,
    val reasonDetail: String,
    val likeCount: Int,
    val commentCount: Int,
    val shareCount: Int,
    val mediaUrl: String? = null,
    val mediaMimeType: String? = null
)

data class SocialStory(
    val id: String,
    val creator: Creator,
    val style: ArtworkStyle,
    val headline: String,
    val detail: String,
    val time: String,
    val seen: Boolean,
    val own: Boolean,
    val mediaUrl: String? = null,
    val mediaMimeType: String? = null
)

enum class NotificationKind(val glyph: String, val color: Color) {
    LIKED("♥", Color(0xFFF25A5A)),
    FOLLOWED("+", Color(0xFF3878FA)),
    REPLIED("●", Color(0xFF7D47EF)),
    COMMUNITY("♟", Color(0xFF21AB73))
}

data class SocialNotification(
    val id: String,
    val creator: Creator,
    val message: String,
    val time: String,
    val kind: NotificationKind,
    val unread: Boolean,
    val targetType: String? = null,
    val targetId: String? = null
)
