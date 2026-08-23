package com.furkandurmaz.nsosyal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

class BackendApiException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : Exception(message)

data class BackendUser(
    val id: String,
    val fullName: String,
    val username: String,
    val email: String,
    val emailVerified: Boolean
)

data class BackendTokens(val accessToken: String, val refreshToken: String)
data class BackendAuthentication(val user: BackendUser, val tokens: BackendTokens)
data class BackendRegistration(val developmentCode: String?)
data class BackendDispatch(val developmentCode: String?)

data class BackendPost(
    val id: String,
    val text: String,
    val authorId: String,
    val authorName: String,
    val authorUsername: String,
    val authorFollowedByMe: Boolean,
    val authorAvatarUrl: String?,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val savedByMe: Boolean,
    val createdAt: String,
    val hasMedia: Boolean,
    val firstMimeType: String?,
    val firstMediaUrl: String?,
    val topicId: String?,
    val topicName: String?,
    val recommendationReason: String?
)

data class BackendFeed(
    val items: List<BackendPost>,
    val requestId: String?,
    val modelVersion: String?
)

data class BackendTopic(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: String,
    val colorHex: String
)

data class BackendAuthor(
    val id: String,
    val fullName: String,
    val username: String,
    val followedByMe: Boolean,
    val avatarUrl: String?
)

data class BackendComment(
    val id: String,
    val postId: String,
    val text: String,
    val author: BackendAuthor,
    val createdAt: String,
    val deletableByMe: Boolean
)

data class BackendProfile(
    val id: String,
    val fullName: String,
    val username: String,
    val bio: String?,
    val avatarUrl: String?,
    val createdAt: String,
    val postCount: Int,
    val followerCount: Int,
    val followingCount: Int,
    val followedByMe: Boolean,
    val isMe: Boolean
)

data class BackendStory(
    val id: String,
    val author: BackendAuthor,
    val caption: String?,
    val mediaUrl: String,
    val mediaMimeType: String,
    val publishedAt: String,
    val seenByMe: Boolean
)

data class BackendNotification(
    val id: String,
    val type: String,
    val actor: BackendAuthor?,
    val targetType: String?,
    val targetId: String?,
    val read: Boolean,
    val createdAt: String
)

data class BackendSearchUser(
    val id: String,
    val fullName: String,
    val username: String,
    val avatarUrl: String?,
    val followerCount: Int,
    val followedByMe: Boolean,
    val isMe: Boolean
)

data class BackendSearchResult(
    val users: List<BackendSearchUser>,
    val posts: List<BackendPost>
)

data class BackendMessage(
    val id: String,
    val conversationId: String,
    val sender: BackendAuthor,
    val text: String?,
    val createdAt: String,
    val mineByMe: Boolean,
    val seenByOther: Boolean
)

data class BackendConversation(
    val id: String,
    val other: BackendAuthor,
    val lastMessage: BackendMessage?,
    val unreadCount: Int
)

class BackendApiClient(private val baseUrl: String) {
    suspend fun isHealthy(): Boolean = runCatching {
        request("GET", "/health", connectTimeout = 2_500, readTimeout = 2_500)
            .optString("status") == "ok"
    }.getOrDefault(false)

    suspend fun register(
        fullName: String,
        username: String,
        email: String,
        password: String
    ): BackendRegistration {
        val response = request(
            "POST",
            "/api/v1/auth/register",
            JSONObject()
                .put("fullName", fullName)
                .put("username", username)
                .put("email", email)
                .put("password", password)
                .put("acceptedTerms", true)
        )
        return BackendRegistration(response.nullableString("developmentCode"))
    }

    suspend fun verifyEmail(email: String, code: String): BackendAuthentication =
        request("POST", "/api/v1/auth/verify-email", JSONObject().put("email", email).put("code", code)).authentication()

    suspend fun login(email: String, password: String): BackendAuthentication =
        request("POST", "/api/v1/auth/login", JSONObject().put("email", email).put("password", password)).authentication()

    suspend fun refresh(refreshToken: String): BackendTokens =
        request(
            "POST",
            "/api/v1/auth/refresh",
            JSONObject().put("refreshToken", refreshToken)
        ).tokens()

    suspend fun logout(refreshToken: String) {
        request(
            "POST",
            "/api/v1/auth/logout",
            JSONObject().put("refreshToken", refreshToken)
        )
    }

    suspend fun resendVerification(email: String): BackendDispatch = BackendDispatch(
        request("POST", "/api/v1/auth/resend-verification", JSONObject().put("email", email))
            .nullableString("developmentCode")
    )

    suspend fun forgotPassword(email: String): BackendDispatch = BackendDispatch(
        request("POST", "/api/v1/auth/forgot-password", JSONObject().put("email", email))
            .nullableString("developmentCode")
    )

    suspend fun resetPassword(email: String, code: String, password: String) {
        request(
            "POST",
            "/api/v1/auth/reset-password",
            JSONObject().put("email", email).put("code", code).put("newPassword", password)
        )
    }

    suspend fun currentUser(accessToken: String): BackendUser =
        request("GET", "/api/v1/users/me", accessToken = accessToken).user()

    suspend fun topics(): List<BackendTopic> =
        request("GET", "/api/v1/topics").getJSONArray("items").mapObjects { it.topic() }

    suspend fun userTopics(accessToken: String): List<BackendTopic> =
        request("GET", "/api/v1/users/me/topics", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.topic() }

    suspend fun updateUserTopics(topicIds: List<String>, accessToken: String): List<BackendTopic> =
        request(
            "PUT",
            "/api/v1/users/me/topics",
            JSONObject().put("topicIds", topicIds.toJsonArray()),
            accessToken
        ).getJSONArray("items").mapObjects { it.topic() }

    suspend fun feed(accessToken: String, sessionId: String, personalized: Boolean): BackendFeed {
        val response = request(
            "GET",
            "/api/v1/posts/feed?limit=50&personalized=$personalized&sessionId=$sessionId&localHour=${LocalTime.now().hour}" +
                "&timezoneOffsetMinutes=${OffsetDateTime.now().offset.totalSeconds / 60}",
            accessToken = accessToken
        )
        val items = response.getJSONArray("items")
        return BackendFeed(
            items = buildList {
                for (index in 0 until items.length()) add(items.getJSONObject(index).post())
            },
            requestId = response.nullableString("requestId"),
            modelVersion = response.nullableString("modelVersion")
        )
    }

    suspend fun recordRecommendationEvent(
        accessToken: String,
        sessionId: String,
        feedRequestId: String?,
        postId: String?,
        eventType: String,
        dwellMillis: Long? = null,
        completionRatio: Double? = null,
        targetFeature: String? = null,
        surface: String = "feed"
    ) {
        val event = JSONObject()
            .put("clientEventId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .putNullable("feedRequestId", feedRequestId)
            .putNullable("postId", postId)
            .put("eventType", eventType)
            .put("surface", surface)
            .putNullable("dwellMillis", dwellMillis)
            .putNullable("completionRatio", completionRatio)
            .put("localHour", LocalTime.now().hour)
            .put("timezoneOffsetMinutes", OffsetDateTime.now().offset.totalSeconds / 60)
            .putNullable("targetFeature", targetFeature)
            .put("occurredAt", Instant.now().toString())
        request(
            "POST",
            "/api/v1/recommendations/events",
            JSONObject().put("events", JSONArray().put(event)),
            accessToken
        )
    }

    suspend fun resetRecommendationProfile(accessToken: String) {
        request("DELETE", "/api/v1/recommendations/profile", accessToken = accessToken)
    }

    suspend fun createPost(text: String, topicIds: List<String> = emptyList(), accessToken: String): BackendPost =
        request(
            "POST",
            "/api/v1/posts",
            JSONObject().put("text", text).put("mediaIds", JSONArray()).put("topicIds", topicIds.toJsonArray()),
            accessToken
        ).post()

    suspend fun comments(postId: String, accessToken: String): List<BackendComment> =
        request("GET", "/api/v1/posts/$postId/comments?limit=100", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.comment() }

    suspend fun createComment(postId: String, text: String, accessToken: String): BackendComment =
        request(
            "POST",
            "/api/v1/posts/$postId/comments",
            JSONObject().put("text", text),
            accessToken
        ).comment()

    suspend fun deleteComment(id: String, accessToken: String) {
        request("DELETE", "/api/v1/comments/$id", accessToken = accessToken)
    }

    suspend fun profile(username: String, accessToken: String): BackendProfile =
        request("GET", "/api/v1/users/$username", accessToken = accessToken).profile()

    suspend fun userPosts(username: String, accessToken: String): List<BackendPost> =
        request("GET", "/api/v1/users/$username/posts?limit=50", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.post() }

    suspend fun updateProfile(fullName: String, bio: String, accessToken: String): BackendProfile =
        request(
            "PATCH",
            "/api/v1/users/me/profile",
            JSONObject().put("fullName", fullName).put("bio", bio),
            accessToken
        ).profile()

    suspend fun setFollow(username: String, active: Boolean, accessToken: String) {
        request(if (active) "PUT" else "DELETE", "/api/v1/users/$username/follow", accessToken = accessToken)
    }

    suspend fun reportPost(id: String, accessToken: String): Boolean =
        request(
            "POST",
            "/api/v1/reports",
            JSONObject().put("targetType", "POST").put("targetId", id).put("reason", "OTHER"),
            accessToken
        ).optBoolean("alreadyReported", false)

    suspend fun storyFeed(accessToken: String): List<BackendStory> =
        request("GET", "/api/v1/stories/feed", accessToken = accessToken)
            .getJSONArray("items")
            .mapObjects { group -> group.getJSONArray("stories").mapObjects { it.story() } }
            .flatten()

    suspend fun markStoryViewed(id: String, accessToken: String) {
        request("PUT", "/api/v1/stories/$id/view", accessToken = accessToken)
    }

    suspend fun notifications(accessToken: String): Pair<List<BackendNotification>, Int> {
        val response = request("GET", "/api/v1/notifications?limit=100", accessToken = accessToken)
        return response.getJSONArray("items").mapObjects { it.notification() } to response.optInt("unreadCount", 0)
    }

    suspend fun markAllNotificationsRead(accessToken: String): Int =
        request("PUT", "/api/v1/notifications/read-all", accessToken = accessToken).optInt("unreadCount", 0)

    suspend fun explore(accessToken: String): List<BackendPost> =
        request("GET", "/api/v1/explore?limit=50", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.post() }

    suspend fun search(query: String, accessToken: String): BackendSearchResult {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = request("GET", "/api/v1/search?q=$encoded", accessToken = accessToken)
        return BackendSearchResult(
            users = response.getJSONArray("users").mapObjects { it.searchUser() },
            posts = response.getJSONArray("posts").mapObjects { it.post() }
        )
    }

    suspend fun conversations(accessToken: String): List<BackendConversation> =
        request("GET", "/api/v1/conversations?limit=100", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.conversation() }

    suspend fun messages(conversationId: String, accessToken: String): List<BackendMessage> =
        request("GET", "/api/v1/conversations/$conversationId/messages?limit=100", accessToken = accessToken)
            .getJSONArray("items").mapObjects { it.message() }

    suspend fun sendMessage(conversationId: String, text: String, accessToken: String): BackendMessage =
        request(
            "POST",
            "/api/v1/conversations/$conversationId/messages",
            JSONObject().put("text", text),
            accessToken
        ).message()

    suspend fun markConversationRead(conversationId: String, accessToken: String) {
        request("PUT", "/api/v1/conversations/$conversationId/read", accessToken = accessToken)
    }

    suspend fun setLike(postId: String, active: Boolean, accessToken: String) {
        request(if (active) "PUT" else "DELETE", "/api/v1/posts/$postId/like", accessToken = accessToken)
    }

    suspend fun setSave(postId: String, active: Boolean, accessToken: String) {
        request(if (active) "PUT" else "DELETE", "/api/v1/posts/$postId/save", accessToken = accessToken)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        connectTimeout: Int = 8_000,
        readTimeout: Int = 15_000
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (payload.isBlank()) JSONObject() else JSONObject(payload)
            if (status !in 200..299) {
                throw BackendApiException(
                    status,
                    json.optString("code", "HTTP_ERROR"),
                    json.optString("message", "İşlem tamamlanamadı.")
                )
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.authentication(): BackendAuthentication = BackendAuthentication(
        user = getJSONObject("user").user(),
        tokens = getJSONObject("tokens").tokens()
    )

    private fun JSONObject.tokens(): BackendTokens =
        BackendTokens(getString("accessToken"), getString("refreshToken"))

    private fun JSONObject.user(): BackendUser = BackendUser(
        id = getString("id"),
        fullName = getString("fullName"),
        username = getString("username"),
        email = getString("email"),
        emailVerified = optBoolean("emailVerified", false)
    )

    private fun JSONObject.post(): BackendPost {
        val author = getJSONObject("author")
        val media = optJSONArray("media") ?: JSONArray()
        val topics = optJSONArray("topics") ?: JSONArray()
        return BackendPost(
            id = getString("id"),
            text = getString("text"),
            authorId = author.getString("id"),
            authorName = author.getString("fullName"),
            authorUsername = author.getString("username"),
            authorFollowedByMe = author.optBoolean("followedByMe", false),
            authorAvatarUrl = author.nullableString("avatarUrl")?.let(::normalizeMediaUrl),
            likeCount = optInt("likeCount", 0),
            commentCount = optInt("commentCount", 0),
            likedByMe = optBoolean("likedByMe", false),
            savedByMe = optBoolean("savedByMe", false),
            createdAt = optString("createdAt"),
            hasMedia = media.length() > 0,
            firstMimeType = if (media.length() > 0) media.getJSONObject(0).optString("mimeType") else null,
            firstMediaUrl = if (media.length() > 0) {
                media.getJSONObject(0).nullableString("url")?.let(::normalizeMediaUrl)
            } else null,
            topicId = if (topics.length() > 0) topics.getJSONObject(0).nullableString("id") else null,
            topicName = if (topics.length() > 0) topics.getJSONObject(0).nullableString("name") else null,
            recommendationReason = nullableString("recommendationReason")
        )
    }

    private fun JSONObject.topic() = BackendTopic(
        id = getString("id"),
        slug = getString("slug"),
        name = getString("name"),
        description = nullableString("description"),
        icon = optString("icon", "#"),
        colorHex = optString("colorHex", "#3878FA")
    )

    private fun JSONObject.author() = BackendAuthor(
        id = getString("id"),
        fullName = getString("fullName"),
        username = getString("username"),
        followedByMe = optBoolean("followedByMe", false),
        avatarUrl = nullableString("avatarUrl")?.let(::normalizeMediaUrl)
    )

    private fun JSONObject.comment() = BackendComment(
        id = getString("id"),
        postId = getString("postId"),
        text = getString("text"),
        author = getJSONObject("author").author(),
        createdAt = getString("createdAt"),
        deletableByMe = optBoolean("deletableByMe", false)
    )

    private fun JSONObject.profile() = BackendProfile(
        id = getString("id"),
        fullName = getString("fullName"),
        username = getString("username"),
        bio = nullableString("bio"),
        avatarUrl = nullableString("avatarUrl")?.let(::normalizeMediaUrl),
        createdAt = getString("createdAt"),
        postCount = optInt("postCount", 0),
        followerCount = optInt("followerCount", 0),
        followingCount = optInt("followingCount", 0),
        followedByMe = optBoolean("followedByMe", false),
        isMe = optBoolean("isMe", false)
    )

    private fun JSONObject.story(): BackendStory {
        val media = getJSONObject("media")
        return BackendStory(
            id = getString("id"),
            author = getJSONObject("author").author(),
            caption = nullableString("caption"),
            mediaUrl = normalizeMediaUrl(media.getString("url")),
            mediaMimeType = media.getString("mimeType"),
            publishedAt = getString("publishedAt"),
            seenByMe = optBoolean("seenByMe", false)
        )
    }

    private fun JSONObject.notification() = BackendNotification(
        id = getString("id"),
        type = getString("type"),
        actor = optJSONObject("actor")?.author(),
        targetType = nullableString("targetType"),
        targetId = nullableString("targetId"),
        read = optBoolean("read", false),
        createdAt = getString("createdAt")
    )

    private fun JSONObject.searchUser() = BackendSearchUser(
        id = getString("id"),
        fullName = getString("fullName"),
        username = getString("username"),
        avatarUrl = nullableString("avatarUrl")?.let(::normalizeMediaUrl),
        followerCount = optInt("followerCount", 0),
        followedByMe = optBoolean("followedByMe", false),
        isMe = optBoolean("isMe", false)
    )

    private fun JSONObject.message() = BackendMessage(
        id = getString("id"),
        conversationId = getString("conversationId"),
        sender = getJSONObject("sender").author(),
        text = nullableString("text"),
        createdAt = getString("createdAt"),
        mineByMe = optBoolean("mineByMe", false),
        seenByOther = optBoolean("seenByOther", false)
    )

    private fun JSONObject.conversation() = BackendConversation(
        id = getString("id"),
        other = getJSONObject("other").author(),
        lastMessage = optJSONObject("lastMessage")?.message(),
        unreadCount = optInt("unreadCount", 0)
    )

    private fun normalizeMediaUrl(value: String): String = runCatching {
        val mediaUri = URI(value)
        val apiUri = URI(baseUrl)
        val isLoopbackMedia = mediaUri.host == "localhost" || mediaUri.host == "127.0.0.1"
        val isRemoteApi = apiUri.host != null && apiUri.host != "localhost" && apiUri.host != "127.0.0.1"
        if (isLoopbackMedia && isRemoteApi) {
            URI(
                mediaUri.scheme,
                mediaUri.userInfo,
                apiUri.host,
                mediaUri.port,
                mediaUri.path,
                mediaUri.query,
                mediaUri.fragment,
            ).toString()
        } else value
    }.getOrDefault(value)

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = apply {
    if (value != null) put(key, value)
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }

private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }
