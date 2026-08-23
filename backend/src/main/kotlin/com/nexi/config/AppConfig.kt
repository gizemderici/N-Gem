package com.nexi.config

data class AppConfig(
    val environment: String,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    val storage: StorageConfig,
    val verificationCodeTtlMinutes: Long,
    val exposeDevelopmentCodes: Boolean,
) {
    val isProduction: Boolean get() = environment.equals("production", ignoreCase = true)

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = env["APP_ENV"] ?: "development"
            val jwtSecret = env["JWT_SECRET"] ?: "local-development-secret-change-before-production"
            require(!environment.equals("production", true) || jwtSecret.length >= 32) {
                "JWT_SECRET must contain at least 32 characters in production"
            }

            return AppConfig(
                environment = environment,
                database = DatabaseConfig(
                    url = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/nexi",
                    user = env["DATABASE_USER"] ?: "nexi",
                    password = env["DATABASE_PASSWORD"] ?: "nexi_dev_password",
                ),
                jwt = JwtConfig(
                    secret = jwtSecret,
                    issuer = env["JWT_ISSUER"] ?: "nexi-backend",
                    audience = env["JWT_AUDIENCE"] ?: "nexi-mobile",
                    accessTokenTtlMinutes = env.long("ACCESS_TOKEN_TTL_MINUTES", 15),
                    refreshTokenTtlDays = env.long("REFRESH_TOKEN_TTL_DAYS", 30),
                ),
                storage = StorageConfig(
                    endpoint = env["STORAGE_ENDPOINT"] ?: "http://localhost:9000",
                    publicEndpoint = env["STORAGE_PUBLIC_ENDPOINT"] ?: "http://localhost:9000",
                    region = env["STORAGE_REGION"] ?: "us-east-1",
                    accessKey = env["STORAGE_ACCESS_KEY"] ?: "nexi",
                    secretKey = env["STORAGE_SECRET_KEY"] ?: "nexi_dev_storage_password",
                    bucket = env["STORAGE_BUCKET"] ?: "nexi-media",
                    maxImageSizeBytes = env.long("MAX_IMAGE_SIZE_BYTES", 10 * 1024 * 1024),
                    maxVideoSizeBytes = env.long("MAX_VIDEO_SIZE_BYTES", 25 * 1024 * 1024),
                ),
                verificationCodeTtlMinutes = env.long("VERIFICATION_CODE_TTL_MINUTES", 10),
                exposeDevelopmentCodes = !environment.equals("production", true) &&
                    env.boolean("EXPOSE_DEVELOPMENT_CODES", true),
            )
        }
    }
}

data class DatabaseConfig(val url: String, val user: String, val password: String)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtlMinutes: Long,
    val refreshTokenTtlDays: Long,
)

data class StorageConfig(
    val endpoint: String,
    val publicEndpoint: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val maxImageSizeBytes: Long,
    val maxVideoSizeBytes: Long,
)

private fun Map<String, String>.long(name: String, default: Long): Long =
    get(name)?.toLongOrNull() ?: default

private fun Map<String, String>.boolean(name: String, default: Boolean): Boolean =
    get(name)?.toBooleanStrictOrNull() ?: default
