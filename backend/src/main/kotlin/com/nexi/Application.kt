package com.nexi

import com.nexi.auth.ApiException
import com.nexi.auth.AuthService
import com.nexi.auth.ErrorResponse
import com.nexi.auth.JdbcAuthRepository
import com.nexi.auth.PasswordHasher
import com.nexi.auth.RequestRateLimiter
import com.nexi.auth.TokenService
import com.nexi.auth.authRoutes
import com.nexi.config.AppConfig
import com.nexi.config.DatabaseFactory
import com.nexi.media.JdbcMediaRepository
import com.nexi.media.MediaService
import com.nexi.media.S3ObjectStorage
import com.nexi.media.mediaRoutes
import com.nexi.posts.JdbcPostRepository
import com.nexi.posts.PostService
import com.nexi.posts.postRoutes
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

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

    val mediaService = MediaService(JdbcMediaRepository(dataSource), objectStorage, config.storage)
    val postService = PostService(JdbcPostRepository(dataSource), objectStorage)

    monitor.subscribe(ApplicationStopped) {
        objectStorage.close()
        dataSource.close()
    }

    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            encodeDefaults = true
        })
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
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
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "nexi-backend"))
        }
        authRoutes(authService, RequestRateLimiter())
        mediaRoutes(mediaService)
        postRoutes(postService)
    }
}
