package com.nexi

import com.nexi.auth.ApiException
import com.nexi.auth.AuthService
import com.nexi.auth.AuthThrottle
import com.nexi.auth.ErrorResponse
import com.nexi.auth.JdbcAuthRepository
import com.nexi.auth.PasswordHasher
import com.nexi.auth.TokenService
import com.nexi.auth.RequestRateLimiter
import com.nexi.auth.authRoutes
import com.nexi.comments.CommentService
import com.nexi.comments.JdbcCommentRepository
import com.nexi.comments.commentRoutes
import com.nexi.config.AppConfig
import com.nexi.config.DatabaseFactory
import com.nexi.feed.FeedPolicy
import com.nexi.feed.JdbcFeedLineageRepository
import com.nexi.feed.feedRoutes
import com.nexi.media.JdbcMediaRepository
import com.nexi.media.MediaJanitor
import com.nexi.media.MediaService
import com.nexi.moderation.JdbcModerationRepository
import com.nexi.moderation.ModerationService
import com.nexi.moderation.moderationRoutes
import com.nexi.notifications.JdbcNotificationRepository
import com.nexi.notifications.NotificationService
import com.nexi.notifications.notificationRoutes
import com.nexi.messaging.JdbcMessagingRepository
import com.nexi.messaging.MessagingService
import com.nexi.messaging.messagingRoutes
import com.nexi.media.S3ObjectStorage
import com.nexi.media.mediaRoutes
import com.nexi.posts.JdbcPostRepository
import com.nexi.posts.PostService
import com.nexi.posts.postRoutes
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.JdbcConsentRepository
import com.nexi.recommendations.JdbcRecommendationRepository
import com.nexi.recommendations.RecommendationService
import com.nexi.recommendations.recommendationRoutes
import com.nexi.profiles.JdbcProfileRepository
import com.nexi.profiles.ProfileService
import com.nexi.profiles.profileRoutes
import com.nexi.stories.JdbcStoryRepository
import com.nexi.stories.StoryJanitor
import com.nexi.stories.StoryService
import com.nexi.stories.storyRoutes
import com.nexi.search.JdbcSearchRepository
import com.nexi.search.SearchService
import com.nexi.search.searchRoutes
import com.nexi.topics.JdbcTopicRepository
import com.nexi.topics.TopicResolver
import com.nexi.topics.TopicService
import com.nexi.topics.topicRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

private const val HEALTH_CHECK_TIMEOUT_SECONDS = 2
private val SWEEP_INTERVAL_MILLIS = Duration.ofHours(1).toMillis()
private const val NOTIFICATION_SWEEP_BATCH = 1_000

private fun clockNow(): java.time.Instant = java.time.Instant.now()

fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val appLogger = environment.log
    val dataSource = DatabaseFactory.create(config.database)
    val tokenService = TokenService(config.jwt)
    val objectStorage = S3ObjectStorage(config.storage)
    val authService = AuthService(
        repository = JdbcAuthRepository(dataSource),
        passwordHasher = PasswordHasher(),
        tokenService = tokenService,
        config = config,
    )

    val postRepository = JdbcPostRepository(dataSource)
    val topicRepository = JdbcTopicRepository(dataSource)
    val mediaRepository = JdbcMediaRepository(dataSource)
    val recommendationRepository = JdbcRecommendationRepository(dataSource)
    val ranker = ContextualRanker()
    // Konu kimliklerinin tek kaynağı; ilgi olaylarını kanonik slug'a çevirir.
    val topicResolver = TopicResolver(topicRepository)
    val consentRepository = JdbcConsentRepository(dataSource)
    val feedLineageRepository = JdbcFeedLineageRepository(dataSource)

    // Bildirim üreten servislerden önce kurulmalı; hepsi bunu alıyor.
    val notificationRepository = JdbcNotificationRepository(dataSource)
    val notificationService = NotificationService(notificationRepository, objectStorage)

    val mediaService = MediaService(mediaRepository, objectStorage, config.storage)
    val postService = PostService(
        repository = postRepository,
        storage = objectStorage,
        recommendationRepository = recommendationRepository,
        notifications = notificationService,
    )
    val recommendationService = RecommendationService(
        repository = recommendationRepository,
        postRepository = postRepository,
        ranker = ranker,
        topics = topicResolver,
        consents = consentRepository,
    )
    val commentService = CommentService(JdbcCommentRepository(dataSource), objectStorage, notifications = notificationService)
    val profileRepository = JdbcProfileRepository(dataSource)
    val profileService = ProfileService(profileRepository, objectStorage, notifications = notificationService)
    val storyRepository = JdbcStoryRepository(dataSource)
    val storyService = StoryService(storyRepository, objectStorage)
    val messagingService = MessagingService(
        repository = JdbcMessagingRepository(dataSource),
        users = { username, viewerId -> profileRepository.findByUsername(username, viewerId)?.id },
        storage = objectStorage,
        notifications = notificationService,
    )
    val moderationService = ModerationService(
        repository = JdbcModerationRepository(dataSource),
        users = { username -> profileRepository.findIdByUsername(username) },
        storage = objectStorage,
        recommendations = { events -> recommendationRepository.append(events) },
    )
    val searchService = SearchService(postRepository, JdbcSearchRepository(dataSource), objectStorage)
    val topicService = TopicService(topicRepository)
    // Akisin tek politikasi; hem /api/v1/feed hem /api/v1/posts/feed buna bagli.
    val feedPolicy = FeedPolicy(
        posts = postRepository,
        recommendations = recommendationRepository,
        ranker = ranker,
        storage = objectStorage,
        lineage = feedLineageRepository,
        consents = consentRepository,
    )
    val authThrottle = AuthThrottle(trustProxyHeaders = config.trustProxyHeaders)

    val janitor = MediaJanitor(mediaRepository, objectStorage)
    val storyJanitor = StoryJanitor(storyRepository, objectStorage)
    val janitorJob = launch {
        val ttl = Duration.ofHours(config.abandonedUploadTtlHours)
        while (isActive) {
            runCatching { janitor.sweepAbandonedUploads(ttl) }
                .onFailure { appLogger.warn("Abandoned upload sweep failed", it) }
            runCatching { storyJanitor.sweepExpired() }
                .onFailure { appLogger.warn("Expired story sweep failed", it) }
            runCatching {
                val cutoff = clockNow().minus(Duration.ofDays(config.notificationRetentionDays))
                notificationRepository.deleteOlderThan(cutoff, NOTIFICATION_SWEEP_BATCH)
            }.onFailure { appLogger.warn("Notification retention sweep failed", it) }
            delay(SWEEP_INTERVAL_MILLIS)
        }
    }

    monitor.subscribe(ApplicationStopped) {
        janitorJob.cancel()
        objectStorage.close()
        dataSource.close()
    }

    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) {
        json(Json {
            // Bilinmeyen alanları yok sayıyoruz: aksi halde mobil taraf yeni bir
            // alan göndermeye başladığı an eski sunucu bütün istekleri 400'lerdi.
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        })
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (!config.isProduction) anyHost()
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "nexi"
            verifier(tokenService.verifier)
            validate { credential ->
                val isAccessToken = credential.payload.getClaim("type").asString() == "access"
                if (isAccessToken && !credential.payload.subject.isNullOrBlank()) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Geçerli bir oturum gerekli."))
            }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.code, cause.message, cause.field))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", "İstek gövdesi geçersiz."))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", "İstek gövdesi geçersiz."))
        }
        exception<Throwable> { call, cause ->
            appLogger.error("Unhandled request error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Beklenmeyen bir hata oluştu."))
        }
    }

    routing {
        // Gerçek kontrol: veritabanına ulaşamıyorsak sağlıklı değiliz. Yük
        // dengeleyici koşulsuz "ok" gören bir uçtan hiçbir şey öğrenemez.
        get("/health") {
            val databaseUp = runCatching {
                dataSource.connection.use { it.isValid(HEALTH_CHECK_TIMEOUT_SECONDS) }
            }.getOrDefault(false)

            call.respond(
                if (databaseUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to if (databaseUp) "ok" else "degraded",
                    "service" to "nexi-backend",
                    "database" to if (databaseUp) "up" else "down",
                ),
            )
        }
        authRoutes(authService, authThrottle)
        mediaRoutes(mediaService)
        postRoutes(postService, feedPolicy)
        recommendationRoutes(recommendationService)
        commentRoutes(commentService)
        // Profil yolları `/users/{username}` desenini kullanıyor; `/users/me`
        // literal olduğu için ondan önce eşleşir, çakışma yok.
        profileRoutes(profileService, postService)
        moderationRoutes(moderationService)
        storyRoutes(storyService)
        messagingRoutes(messagingService)
        notificationRoutes(notificationService)
        // Arama sorgulari pahali; dakikada 30 istekle sinirli.
        searchRoutes(searchService, RequestRateLimiter(maximumAttempts = 30))
        topicRoutes(topicService)
        feedRoutes(feedPolicy)
    }
}
