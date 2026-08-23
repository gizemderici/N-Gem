package com.furkandurmaz.nsosyal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
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
    val likeCount: Int,
    val likedByMe: Boolean,
    val savedByMe: Boolean,
    val createdAt: String,
    val hasMedia: Boolean,
    val firstMimeType: String?,
    val firstMediaUrl: String?,
    val recommendationReason: String?
)

data class BackendFeed(
    val items: List<BackendPost>,
    val requestId: String?,
    val modelVersion: String?
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

    suspend fun createPost(text: String, accessToken: String): BackendPost =
        request(
            "POST",
            "/api/v1/posts",
            JSONObject().put("text", text).put("mediaIds", JSONArray()),
            accessToken
        ).post()

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
        return BackendPost(
            id = getString("id"),
            text = getString("text"),
            authorId = author.getString("id"),
            authorName = author.getString("fullName"),
            authorUsername = author.getString("username"),
            likeCount = optInt("likeCount", 0),
            likedByMe = optBoolean("likedByMe", false),
            savedByMe = optBoolean("savedByMe", false),
            createdAt = optString("createdAt"),
            hasMedia = media.length() > 0,
            firstMimeType = if (media.length() > 0) media.getJSONObject(0).optString("mimeType") else null,
            firstMediaUrl = if (media.length() > 0) {
                media.getJSONObject(0).nullableString("url")?.let(::normalizeMediaUrl)
            } else null,
            recommendationReason = nullableString("recommendationReason")
        )
    }

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
