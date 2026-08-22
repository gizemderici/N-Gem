package com.nexi.auth

import io.ktor.http.HttpStatusCode

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val field: String? = null,
) : RuntimeException(message)

fun validation(code: String, message: String, field: String? = null) =
    ApiException(HttpStatusCode.UnprocessableEntity, code, message, field)
