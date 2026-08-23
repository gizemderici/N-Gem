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
import com.furkandurmaz.nsosyal.model.NotificationKind
import com.furkandurmaz.nsosyal.model.SocialNotification
import com.furkandurmaz.nsosyal.model.SocialPost
import com.furkandurmaz.nsosyal.model.SocialStory
import com.furkandurmaz.nsosyal.network.BackendApiClient
import com.furkandurmaz.nsosyal.network.BackendApiException
import com.furkandurmaz.nsosyal.network.BackendAuthentication
import com.furkandurmaz.nsosyal.network.BackendAuthor
import com.furkandurmaz.nsosyal.network.BackendComment
import com.furkandurmaz.nsosyal.network.BackendConversation
import com.furkandurmaz.nsosyal.network.BackendMessage
import com.furkandurmaz.nsosyal.network.BackendNotification
import com.furkandurmaz.nsosyal.network.BackendPost
import com.furkandurmaz.nsosyal.network.BackendProfile
import com.furkandurmaz.nsosyal.network.BackendSearchUser
import com.furkandurmaz.nsosyal.network.BackendStory
import com.furkandurmaz.nsosyal.network.BackendTopic
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
    var explorePosts by mutableStateOf(MockSocialData.posts)
        private set
    var searchPosts by mutableStateOf(emptyList<SocialPost>())
        private set
    var searchUsers by mutableStateOf(emptyList<BackendSearchUser>())
        private set
    var topics by mutableStateOf(emptyList<BackendTopic>())
        private set
    var stories by mutableStateOf(MockSocialData.stories)
        private set
    var notifications by mutableStateOf(MockSocialData.notifications)
        private set
    var unreadNotificationCount by mutableStateOf(0)
        private set
    var commentsByPost by mutableStateOf<Map<String, List<BackendComment>>>(emptyMap())
        private set
    var profile by mutableStateOf<BackendProfile?>(null)
        private set
    var profilePosts by mutableStateOf(emptyList<SocialPost>())
        private set
    var conversations by mutableStateOf(emptyList<BackendConversation>())
        private set
    var messagesByConversation by mutableStateOf<Map<String, List<BackendMessage>>>(emptyMap())
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
            verified = currentUser?.emailVerified ?: true,
            avatarUrl = profile?.avatarUrl
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
            loadBackendFeatures(accessToken)
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
        explorePosts = MockSocialData.posts
        stories = MockSocialData.stories
        notifications = MockSocialData.notifications
        topics = emptyList()
        commentsByPost = emptyMap()
        profile = null
        profilePosts = emptyList()
        conversations = emptyList()
        messagesByConversation = emptyMap()
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
        if (dataSourceMode == DataSourceMode.BACKEND) {
            val token = sessionStore?.accessToken ?: return
            scope.launch {
                runCatching {
                    if (topics.isEmpty()) topics = apiClientOrThrow().topics()
                    val ids = interests.mapNotNull { selected ->
                        topics.firstOrNull { it.id == selected || it.slug == selected }?.id
                    }
                    if (ids.isNotEmpty()) {
                        val selected = apiClientOrThrow().updateUserTopics(ids, token)
                        selectedInterestIds.clear()
                        selectedInterestIds.addAll(selected.map(BackendTopic::slug))
                    }
                }
            }
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

    fun toggleFollow(creator: Creator) {
        val active = creator.id !in followedCreatorIds
        if (!active) {
            followedCreatorIds.remove(creator.id)
        } else {
            followedCreatorIds.add(creator.id)
            showToast("${creator.name} takip edildi")
        }
        runRemoteInteraction(
            onFailure = {
                if (active) followedCreatorIds.remove(creator.id) else if (creator.id !in followedCreatorIds) followedCreatorIds.add(creator.id)
            }
        ) { api, token -> api.setFollow(creator.handle.removePrefix("@"), active, token) }
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
        if (dataSourceMode != DataSourceMode.BACKEND) return showToast("Bildirimin alındı")
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                val alreadyReported = apiClientOrThrow().reportPost(post.id, token)
                showToast(if (alreadyReported) "Bu gönderiyi daha önce bildirmiştin" else "Bildirimin alındı")
            } catch (error: Exception) {
                showToast(error.userMessage("Bildirim gönderilemedi."))
            }
        }
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

    fun publish(text: String, topicIds: List<String> = emptyList()) {
        if (text.isBlank()) return
        if (dataSourceMode != DataSourceMode.BACKEND) {
            posts = listOf(localPost(text)) + posts
            finishPublishing()
            return
        }

        val token = sessionStore?.accessToken ?: return showToast("Yayınlamak için yeniden giriş yapmalısın.")
        scope.launch {
            try {
                val created = apiClientOrThrow().createPost(text.trim(), topicIds, token).toSocialPost()
                posts = listOf(created) + posts.filterNot { it.id == created.id }
                finishPublishing()
            } catch (error: Exception) {
                showToast(error.userMessage("Gönderi yayınlanamadı."))
            }
        }
    }

    fun loadTopics() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching {
                topics = apiClientOrThrow().topics()
                val selected = apiClientOrThrow().userTopics(token)
                if (selected.isNotEmpty()) {
                    selectedInterestIds.clear()
                    selectedInterestIds.addAll(selected.map(BackendTopic::slug))
                }
            }.onFailure { showToast("İlgi alanları alınamadı") }
        }
    }

    fun loadComments(post: SocialPost) {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().comments(post.id, token) }
                .onSuccess { commentsByPost = commentsByPost + (post.id to it) }
                .onFailure { showToast(it.userMessage("Yorumlar alınamadı.")) }
        }
    }

    fun addComment(post: SocialPost, text: String, onSuccess: () -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty() || dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                val comment = apiClientOrThrow().createComment(post.id, clean, token)
                commentsByPost = commentsByPost + (post.id to (commentsByPost[post.id].orEmpty() + comment))
                changeCommentCount(post.id, 1)
                onSuccess()
            } catch (error: Exception) {
                showToast(error.userMessage("Yorum gönderilemedi."))
            }
        }
    }

    fun deleteComment(comment: BackendComment) {
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                apiClientOrThrow().deleteComment(comment.id, token)
                commentsByPost = commentsByPost + (comment.postId to commentsByPost[comment.postId].orEmpty().filterNot { it.id == comment.id })
                changeCommentCount(comment.postId, -1)
            } catch (error: Exception) {
                showToast(error.userMessage("Yorum silinemedi."))
            }
        }
    }

    fun loadExplore() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().explore(token).map { it.toSocialPost() } }
                .onSuccess { explorePosts = it }
                .onFailure { showToast("Keşfet akışı yenilenemedi") }
        }
    }

    fun search(query: String) {
        val clean = query.trim()
        if (clean.length < 2 || dataSourceMode != DataSourceMode.BACKEND) {
            searchPosts = emptyList()
            searchUsers = emptyList()
            return
        }
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().search(clean, token) }
                .onSuccess {
                    searchUsers = it.users
                    searchPosts = it.posts.map { post -> post.toSocialPost() }
                }
                .onFailure { showToast("Arama tamamlanamadı") }
        }
    }

    fun loadNotifications() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().notifications(token) }
                .onSuccess { (items, unread) ->
                    notifications = items.map { it.toSocialNotification() }
                    unreadNotificationCount = unread
                }
                .onFailure { showToast("Bildirimler yenilenemedi") }
        }
    }

    fun markAllNotificationsRead() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().markAllNotificationsRead(token) }
                .onSuccess {
                    unreadNotificationCount = it
                    notifications = notifications.map { notification -> notification.copy(unread = false) }
                }
                .onFailure { showToast("Bildirimler güncellenemedi") }
        }
    }

    fun refreshProfile() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        val username = currentUser?.username ?: return
        scope.launch {
            runCatching {
                profile = apiClientOrThrow().profile(username, token)
                profilePosts = apiClientOrThrow().userPosts(username, token).map { it.toSocialPost() }
            }.onFailure { showToast("Profil güncellenemedi") }
        }
    }

    fun updateProfile(fullName: String, bio: String, onSuccess: () -> Unit = {}) {
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                profile = apiClientOrThrow().updateProfile(fullName.trim(), bio.trim(), token)
                showToast("Profil güncellendi")
                onSuccess()
            } catch (error: Exception) {
                showToast(error.userMessage("Profil kaydedilemedi."))
            }
        }
    }

    fun loadConversations() {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching { apiClientOrThrow().conversations(token) }
                .onSuccess { conversations = it }
                .onFailure { showToast("Mesajlar yenilenemedi") }
        }
    }

    fun loadMessages(conversationId: String) {
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            runCatching {
                val messages = apiClientOrThrow().messages(conversationId, token)
                apiClientOrThrow().markConversationRead(conversationId, token)
                messages
            }.onSuccess { messagesByConversation = messagesByConversation + (conversationId to it) }
                .onFailure { showToast("Mesajlar alınamadı") }
        }
    }

    fun sendMessage(conversationId: String, text: String, onSuccess: () -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val token = sessionStore?.accessToken ?: return
        scope.launch {
            try {
                val message = apiClientOrThrow().sendMessage(conversationId, clean, token)
                messagesByConversation = messagesByConversation + (conversationId to (messagesByConversation[conversationId].orEmpty() + message))
                loadConversations()
                onSuccess()
            } catch (error: Exception) {
                showToast(error.userMessage("Mesaj gönderilemedi."))
            }
        }
    }

    fun markStoryViewed(story: SocialStory) {
        if (dataSourceMode != DataSourceMode.BACKEND) return
        val token = sessionStore?.accessToken ?: return
        scope.launch { runCatching { apiClientOrThrow().markStoryViewed(story.id, token) } }
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
            loadBackendFeatures(authentication.tokens.accessToken)
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
            loadBackendFeatures(tokens.accessToken)
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
        followedCreatorIds.clear()
        followedCreatorIds.addAll(backendPosts.filter(BackendPost::authorFollowedByMe).map(BackendPost::authorId).distinct())
    }

    private suspend fun loadBackendFeatures(accessToken: String) {
        runCatching {
            topics = apiClientOrThrow().topics()
            val selected = apiClientOrThrow().userTopics(accessToken)
            if (selected.isNotEmpty()) {
                selectedInterestIds.clear()
                selectedInterestIds.addAll(selected.map(BackendTopic::slug))
            }
        }
        runCatching {
            stories = apiClientOrThrow().storyFeed(accessToken).map { it.toSocialStory() }
        }
        runCatching {
            val (items, unread) = apiClientOrThrow().notifications(accessToken)
            notifications = items.map { it.toSocialNotification() }
            unreadNotificationCount = unread
        }
        runCatching {
            explorePosts = apiClientOrThrow().explore(accessToken).map { it.toSocialPost() }
        }
        currentUser?.username?.let { username ->
            runCatching {
                profile = apiClientOrThrow().profile(username, accessToken)
                profilePosts = apiClientOrThrow().userPosts(username, accessToken).map { it.toSocialPost() }
            }
        }
        runCatching { conversations = apiClientOrThrow().conversations(accessToken) }
    }

    private fun useMockData() {
        dataSourceMode = DataSourceMode.MOCK
        currentUser = null
        posts = MockSocialData.posts
        explorePosts = MockSocialData.posts
        stories = MockSocialData.stories
        notifications = MockSocialData.notifications
        unreadNotificationCount = notifications.count(SocialNotification::unread)
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
                verified = false,
                avatarUrl = authorAvatarUrl
            ),
            time = relativeTime(createdAt),
            body = text,
            topic = topicName ?: if (isVideo) "Video" else "Gönderi",
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
            commentCount = commentCount,
            shareCount = 0,
            mediaUrl = firstMediaUrl,
            mediaMimeType = firstMimeType
        )
    }

    private fun BackendAuthor.toCreator(): Creator = Creator(
        id = id,
        name = fullName,
        handle = if (username.startsWith("@")) username else "@$username",
        initials = initials(fullName),
        colors = listOf(Cyan, Blue, Violet),
        verified = false,
        avatarUrl = avatarUrl
    )

    private fun BackendStory.toSocialStory(): SocialStory = SocialStory(
        id = id,
        creator = author.toCreator(),
        style = ArtworkStyle.CULTURE,
        headline = caption ?: "Hikâye",
        detail = caption.orEmpty(),
        time = relativeTime(publishedAt),
        seen = seenByMe,
        own = author.id == currentUser?.id,
        mediaUrl = mediaUrl,
        mediaMimeType = mediaMimeType
    )

    private fun BackendNotification.toSocialNotification(): SocialNotification {
        val mappedCreator = actor?.toCreator() ?: Creator(
            id = "system",
            name = "N Sosyal",
            handle = "@nsosyal",
            initials = "NS",
            colors = listOf(Blue, Violet),
            verified = true
        )
        val (kind, message) = when (type) {
            "FOLLOW" -> NotificationKind.FOLLOWED to "seni takip etmeye başladı."
            "POST_LIKE" -> NotificationKind.LIKED to "gönderini beğendi."
            "POST_COMMENT" -> NotificationKind.REPLIED to "gönderine yorum yaptı."
            "MESSAGE" -> NotificationKind.REPLIED to "sana yeni bir mesaj gönderdi."
            else -> NotificationKind.COMMUNITY to "N Sosyal'den yeni bir bildirimin var."
        }
        return SocialNotification(
            id = id,
            creator = mappedCreator,
            message = message,
            time = relativeTime(createdAt),
            kind = kind,
            unread = !read,
            targetType = targetType,
            targetId = targetId
        )
    }

    private fun changeCommentCount(postId: String, delta: Int) {
        fun List<SocialPost>.changed() = map { post ->
            if (post.id == postId) post.copy(commentCount = (post.commentCount + delta).coerceAtLeast(0)) else post
        }
        posts = posts.changed()
        explorePosts = explorePosts.changed()
        searchPosts = searchPosts.changed()
        profilePosts = profilePosts.changed()
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

private fun Throwable.userMessage(fallback: String): String =
    (this as? BackendApiException)?.message?.takeIf(String::isNotBlank) ?: fallback
