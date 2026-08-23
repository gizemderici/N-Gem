package com.furkandurmaz.nsosyal

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.furkandurmaz.nsosyal.data.MockSocialData
import com.furkandurmaz.nsosyal.model.ArtworkStyle
import com.furkandurmaz.nsosyal.model.Creator
import com.furkandurmaz.nsosyal.model.MainTab
import com.furkandurmaz.nsosyal.model.SocialPost
import com.furkandurmaz.nsosyal.network.BackendApiClient
import com.furkandurmaz.nsosyal.network.BackendApiException
import com.furkandurmaz.nsosyal.network.BackendAuthentication
import com.furkandurmaz.nsosyal.network.BackendPost
import com.furkandurmaz.nsosyal.network.BackendUser
import com.furkandurmaz.nsosyal.network.SessionStore
import com.furkandurmaz.nsosyal.ui.theme.Blue
import com.furkandurmaz.nsosyal.ui.theme.Cyan
import com.furkandurmaz.nsosyal.ui.theme.Violet
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class DataSourceMode { CHECKING, BACKEND, MOCK }

class AppState(
    private val apiClient: BackendApiClient? = null,
    private val sessionStore: SessionStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recommendationSessionId = UUID.randomUUID().toString()
    private var lastFeedRequestId: String? = null
    private val viewStartedAt = mutableMapOf<String, Long>()

    var selectedTab by mutableStateOf(MainTab.HOME)
    var dataSourceMode by mutableStateOf(DataSourceMode.CHECKING)
        private set
    var currentUser by mutableStateOf<BackendUser?>(null)
        private set
    var posts by mutableStateOf(MockSocialData.posts)
        private set

    val selectedInterestIds = mutableStateListOf("technology", "design", "education")
    val likedPostIds = mutableStateListOf<String>()
    val savedPostIds = mutableStateListOf("long-learning")
    val followedCreatorIds = mutableStateListOf("mert", "teknoloji")
    val hiddenPostIds = mutableStateListOf<String>()
    var toastMessage by mutableStateOf<String?>(null)
    var selectedReasonPost by mutableStateOf<SocialPost?>(null)
    var personalizationEnabled by mutableStateOf(sessionStore?.personalizationEnabled ?: true)
        private set

    val displayName: String get() = currentUser?.fullName ?: MockSocialData.currentUser.name
    val displayUsername: String
        get() = currentUser?.username?.let { if (it.startsWith("@")) it else "@$it" }
            ?: MockSocialData.currentUser.handle
    val firstName: String get() = displayName.substringBefore(" ").ifBlank { "NSosyal" }
    val currentCreator: Creator
        get() = Creator(
            id = currentUser?.id ?: MockSocialData.currentUser.id,
            name = displayName,
            handle = displayUsername,
            initials = initials(displayName),
            colors = listOf(Cyan, Blue, Violet),
            verified = currentUser?.emailVerified ?: true
        )

    val visiblePosts: List<SocialPost>
        get() {
            val visible = posts.filterNot { it.id in hiddenPostIds }
            return if (dataSourceMode == DataSourceMode.BACKEND) visible else visible.sortedByDescending(::score)
        }

    /**
     * Uygulama açılışındaki tek veri-kaynağı kararı. Backend sağlıklıysa uzaktaki
     * oturum ve içerik kullanılır; ulaşılamıyorsa uygulama örnek veri moduna geçer.
     */
    suspend fun initialize(): Boolean {
        dataSourceMode = DataSourceMode.CHECKING
        val backendAvailable = apiClient?.isHealthy() == true
        if (!backendAvailable) {
            useMockData()
            return sessionStore?.mockAuthenticated == true
        }

        dataSourceMode = DataSourceMode.BACKEND
        val accessToken = sessionStore?.accessToken ?: return false
        return try {
            currentUser = apiClient.currentUser(accessToken)
            loadBackendFeed(accessToken)
            recordRecommendationEvent(eventType = "session_started", surface = "app")
            true
        } catch (error: BackendApiException) {
            if (error.statusCode == 401) refreshBackendSession() else false
        } catch (_: Exception) {
            useMockData()
            sessionStore.mockAuthenticated
        }
    }

    suspend fun signIn(email: String, password: String) {
        if (dataSourceMode == DataSourceMode.MOCK) {
            sessionStore?.saveMockAuthentication()
            return
        }
        authenticate(apiClientOrThrow().login(email.trim(), password))
    }

    suspend fun register(
        fullName: String,
        username: String,
        email: String,
        password: String
    ): String? {
        if (dataSourceMode == DataSourceMode.MOCK) return "123456"
        return apiClientOrThrow().register(
            fullName.trim(),
            username.trim().removePrefix("@"),
            email.trim(),
            password
        ).developmentCode
    }

    suspend fun verifyEmail(email: String, code: String) {
        if (dataSourceMode == DataSourceMode.MOCK) {
            require(code == "123456") { "Örnek veri modunda doğrulama kodu 123456'dır." }
            sessionStore?.saveMockAuthentication()
            return
        }
        authenticate(apiClientOrThrow().verifyEmail(email.trim(), code))
    }

    suspend fun forgotPassword(email: String): String? {
        if (dataSourceMode == DataSourceMode.MOCK) return "123456"
        return apiClientOrThrow().forgotPassword(email.trim()).developmentCode
    }

    suspend fun resendVerification(email: String): String? {
        if (dataSourceMode == DataSourceMode.MOCK) return "123456"
        return apiClientOrThrow().resendVerification(email.trim()).developmentCode
    }

    suspend fun resetPassword(email: String, code: String, password: String) {
        if (dataSourceMode == DataSourceMode.MOCK) {
            require(code == "123456") { "Örnek veri modunda doğrulama kodu 123456'dır." }
            return
        }
        apiClientOrThrow().resetPassword(email.trim(), code, password)
    }

    fun mockSocialSignIn() {
        check(dataSourceMode == DataSourceMode.MOCK) {
            "Google ile giriş backend tarafından henüz desteklenmiyor."
        }
        sessionStore?.saveMockAuthentication()
    }

    fun logout() {
        val refreshToken = sessionStore?.refreshToken
        sessionStore?.clear()
        currentUser = null
        posts = MockSocialData.posts
        likedPostIds.clear()
        savedPostIds.clear()
        if (dataSourceMode == DataSourceMode.MOCK) savedPostIds.add("long-learning")
        selectedTab = MainTab.HOME
        lastFeedRequestId = null
        viewStartedAt.clear()
        recommendationSessionId = UUID.randomUUID().toString()
        if (dataSourceMode == DataSourceMode.BACKEND && refreshToken != null) {
            scope.launch { runCatching { apiClientOrThrow().logout(refreshToken) } }
        }
    }

    fun refreshFeed() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                loadBackendFeed(token)
            } catch (error: BackendApiException) {
                if (error.statusCode == 401) showToast("Oturumun sona erdi. Lütfen yeniden giriş yap.")
            } catch (_: Exception) {
                showToast("Akış yenilenemedi; son içerikler gösteriliyor.")
            }
        }
    }

    fun completeOnboarding(interests: List<String>) {
        selectedInterestIds.clear()
        selectedInterestIds.addAll(interests)
        interests.forEach { interest ->
            recordRecommendationEvent(
                eventType = "interest_selected",
                targetFeature = interest,
                surface = "onboarding"
            )
        }
    }

    fun toggleLike(post: SocialPost) {
        val active = post.id !in likedPostIds
        if (active) likedPostIds.add(post.id) else likedPostIds.remove(post.id)
        runRemoteInteraction(
            onFailure = {
                if (active) likedPostIds.remove(post.id) else if (post.id !in likedPostIds) likedPostIds.add(post.id)
            }
        ) { api, token ->
            api.setLike(post.id, active, token)
            if (active) recordRecommendationEvent("content_liked", post)
        }
    }

    fun toggleSave(post: SocialPost) {
        val active = post.id !in savedPostIds
        if (active) {
            savedPostIds.add(post.id)
            showToast("Gönderi kaydedildi")
        } else {
            savedPostIds.remove(post.id)
        }
        runRemoteInteraction(
            onFailure = {
                if (active) savedPostIds.remove(post.id) else if (post.id !in savedPostIds) savedPostIds.add(post.id)
            }
        ) { api, token ->
            api.setSave(post.id, active, token)
            if (active) recordRecommendationEvent("content_saved", post)
        }
    }

    fun toggleFollow(creatorId: String, creatorName: String) {
        if (creatorId in followedCreatorIds) {
            followedCreatorIds.remove(creatorId)
        } else {
            followedCreatorIds.add(creatorId)
            showToast("$creatorName takip edildi")
        }
    }

    fun hide(post: SocialPost) {
        if (post.id !in hiddenPostIds) hiddenPostIds.add(post.id)
        recordRecommendationEvent("content_hidden", post)
        showToast("Bu gönderiyi daha az göstereceğiz")
    }

    fun openReason(post: SocialPost) {
        selectedReasonPost = post
        recordRecommendationEvent("recommendation_reason_opened", post)
    }

    fun share(post: SocialPost) {
        showToast("Paylaşım bağlantısı hazır")
        recordRecommendationEvent("content_shared", post)
    }

    fun report(post: SocialPost) {
        recordRecommendationEvent("content_reported", post)
        if (post.id !in hiddenPostIds) hiddenPostIds.add(post.id)
        showToast("Bildirimin alındı")
    }

    fun avoidAtCurrentTime(post: SocialPost) {
        recordRecommendationEvent("content_hidden", post, surface = "time_preference")
        showToast("Saat tercihin güncellendi")
    }

    fun beginViewing(post: SocialPost) {
        if (dataSourceMode == DataSourceMode.BACKEND) {
            viewStartedAt.putIfAbsent(post.id, SystemClock.elapsedRealtime())
        }
    }

    fun endViewing(post: SocialPost) {
        val startedAt = viewStartedAt.remove(post.id) ?: return
        val dwellMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        if (dwellMillis >= 750) {
            recordRecommendationEvent("content_view", post, dwellMillis = dwellMillis)
        }
    }

    fun showToast(message: String) {
        toastMessage = message
    }

    fun publish(text: String) {
        if (text.isBlank()) return
        if (dataSourceMode != DataSourceMode.BACKEND) {
            posts = listOf(localPost(text)) + posts
            finishPublishing()
            return
        }

        val token = sessionStore?.accessToken ?: return showToast("Yayınlamak için yeniden giriş yapmalısın.")
        scope.launch {
            try {
                val created = apiClientOrThrow().createPost(text.trim(), token).toSocialPost()
                posts = listOf(created) + posts.filterNot { it.id == created.id }
                finishPublishing()
            } catch (error: Exception) {
                showToast(error.userMessage("Gönderi yayınlanamadı."))
            }
        }
    }

    fun resetLearnedProfile() {
        if (dataSourceMode != DataSourceMode.BACKEND) {
            showToast("Öğrenilmiş profil sıfırlandı")
            return
        }
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                apiClientOrThrow().resetRecommendationProfile(token)
                recommendationSessionId = UUID.randomUUID().toString()
                lastFeedRequestId = null
                loadBackendFeed(token)
                showToast("Öğrenilmiş profil sıfırlandı")
            } catch (error: Exception) {
                showToast(error.userMessage("Profil sıfırlanamadı."))
            }
        }
    }

    fun updatePersonalizationEnabled(enabled: Boolean) {
        personalizationEnabled = enabled
        sessionStore?.savePersonalizationEnabled(enabled)
        showToast(if (enabled) "Akıllı kişiselleştirme açıldı" else "Kişiselleştirme durduruldu")
        refreshFeed()
    }

    private suspend fun authenticate(authentication: BackendAuthentication) {
        sessionStore?.save(authentication.tokens)
        currentUser = authentication.user
        try {
            loadBackendFeed(authentication.tokens.accessToken)
            recordRecommendationEvent(eventType = "session_started", surface = "app")
        } catch (_: Exception) {
            posts = emptyList()
            showToast("Giriş yapıldı; akış şu anda yüklenemiyor.")
        }
    }

    private suspend fun refreshBackendSession(): Boolean {
        val refreshToken = sessionStore?.refreshToken ?: return false
        return try {
            val tokens = apiClientOrThrow().refresh(refreshToken)
            sessionStore?.save(tokens)
            currentUser = apiClientOrThrow().currentUser(tokens.accessToken)
            loadBackendFeed(tokens.accessToken)
            true
        } catch (_: Exception) {
            sessionStore.clear()
            currentUser = null
            false
        }
    }

    private suspend fun loadBackendFeed(accessToken: String) {
        val backendFeed = apiClientOrThrow().feed(accessToken, recommendationSessionId, personalizationEnabled)
        val backendPosts = backendFeed.items
        lastFeedRequestId = backendFeed.requestId
        posts = backendPosts.map { it.toSocialPost() }
        likedPostIds.clear()
        likedPostIds.addAll(backendPosts.filter(BackendPost::likedByMe).map(BackendPost::id))
        savedPostIds.clear()
        savedPostIds.addAll(backendPosts.filter(BackendPost::savedByMe).map(BackendPost::id))
    }

    private fun useMockData() {
        dataSourceMode = DataSourceMode.MOCK
        currentUser = null
        posts = MockSocialData.posts
        likedPostIds.clear()
        savedPostIds.clear()
        savedPostIds.add("long-learning")
    }

    private fun runRemoteInteraction(
        onFailure: () -> Unit,
        operation: suspend (BackendApiClient, String) -> Unit
    ) {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                operation(apiClientOrThrow(), token)
            } catch (_: Exception) {
                onFailure()
                showToast("Etkileşim kaydedilemedi; bağlantını kontrol et.")
            }
        }
    }

    private fun recordRecommendationEvent(
        eventType: String,
        post: SocialPost? = null,
        dwellMillis: Long? = null,
        completionRatio: Double? = null,
        targetFeature: String? = null,
        surface: String = "feed"
    ) {
        if (dataSourceMode != DataSourceMode.BACKEND || !personalizationEnabled) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching {
                apiClientOrThrow().recordRecommendationEvent(
                    accessToken = token,
                    sessionId = recommendationSessionId,
                    feedRequestId = lastFeedRequestId,
                    postId = post?.id,
                    eventType = eventType,
                    dwellMillis = dwellMillis,
                    completionRatio = completionRatio,
                    targetFeature = targetFeature,
                    surface = surface
                )
            }
        }
    }

    private fun finishPublishing() {
        showToast("Gönderin yayınlandı")
        selectedTab = MainTab.HOME
    }

    private fun localPost(text: String) = SocialPost(
        id = "local-${System.currentTimeMillis()}",
        creator = currentCreator,
        time = "Şimdi",
        body = text.trim(),
        topic = "Gündem",
        reason = "Yeni gönderin",
        reasonDetail = "Bu gönderi yalnızca örnek veri modunda cihazında tutulur.",
        likeCount = 0,
        commentCount = 0,
        shareCount = 0
    )

    private fun BackendPost.toSocialPost(): SocialPost {
        val handle = if (authorUsername.startsWith("@")) authorUsername else "@$authorUsername"
        val isVideo = firstMimeType?.startsWith("video/") == true
        return SocialPost(
            id = id,
            creator = Creator(
                id = authorId,
                name = authorName,
                handle = handle,
                initials = initials(authorName),
                colors = listOf(Cyan, Blue, Violet),
                verified = true
            ),
            time = relativeTime(createdAt),
            body = text,
            topic = if (isVideo) "Video" else "Gündem",
            artwork = if (hasMedia) ArtworkStyle.FUTURE else null,
            artworkTitle = if (hasMedia) "NSosyal medya" else null,
            artworkSubtitle = if (hasMedia) "Topluluktan yeni paylaşım" else null,
            isVideo = isVideo,
            videoLength = null,
            reason = recommendationReason ?: "Yeni ve toplulukta ilgi gören içerik",
            reasonDetail = recommendationReason?.let {
                "$it. Bu açıklama saat, açık tercih ve etkileşim sinyallerinden üretildi."
            } ?: "Henüz yeterli kişisel sinyal olmadığı için yeni ve toplulukta ilgi gören içerikler dengeli gösteriliyor.",
            likeCount = (likeCount - if (likedByMe) 1 else 0).coerceAtLeast(0),
            commentCount = 0,
            shareCount = 0,
            mediaUrl = firstMediaUrl,
            mediaMimeType = firstMimeType
        )
    }

    private fun apiClientOrThrow(): BackendApiClient =
        checkNotNull(apiClient) { "Backend istemcisi yapılandırılmadı." }

    private fun score(post: SocialPost): Int {
        var score = 0
        val selectedTitles = MockSocialData.interests
            .filter { it.id in selectedInterestIds }
            .map { it.title }

        if (post.topic in selectedTitles) score += 30
        if (post.creator.id in followedCreatorIds) score += 25
        if (post.id in savedPostIds) score += 12

        when (LocalTime.now().hour) {
            in 6..8 -> if (post.topic == "Gündem") score += 45
            in 9..17 -> if (post.topic in listOf("Teknoloji", "Eğitim", "Tasarım")) score += 35
            in 18..21 -> if (post.topic == "Yerel") score += 45
            else -> if (post.topic == "Mizah") score += 45
        }
        return score
    }
}

private fun initials(name: String): String = name
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .take(2)
    .joinToString("") { it.take(1).uppercase() }
    .ifBlank { "NS" }

private fun relativeTime(value: String): String = runCatching {
    val elapsed = Duration.between(Instant.parse(value), Instant.now())
    when {
        elapsed.seconds < 60 -> "Şimdi"
        elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()} dk"
        elapsed.toHours() < 24 -> "${elapsed.toHours()} sa"
        else -> "${elapsed.toDays()} gün"
    }
}.getOrDefault("Şimdi")

private fun Exception.userMessage(fallback: String): String =
    (this as? BackendApiException)?.message?.takeIf(String::isNotBlank) ?: fallback
