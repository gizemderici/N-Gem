import Foundation

enum APIClientError: LocalizedError {
    case invalidConfiguration
    case invalidResponse
    case transport(URLError)
    case server(statusCode: Int, response: APIErrorResponse)
    case decoding(Error)

    var isUnauthorized: Bool {
        if case let .server(statusCode, _) = self { return statusCode == 401 }
        return false
    }

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration:
            "Sunucu adresi yapılandırılamadı."
        case .invalidResponse:
            "Sunucudan geçersiz bir yanıt alındı."
        case let .transport(error):
            switch error.code {
            case .notConnectedToInternet:
                "İnternet bağlantını kontrol edip tekrar dene."
            case .cannotConnectToHost, .timedOut:
                "NEXI sunucusuna ulaşılamıyor. Lütfen biraz sonra tekrar dene."
            default:
                "Bağlantı sırasında bir sorun oluştu."
            }
        case let .server(_, response):
            response.message
        case .decoding:
            "Sunucu yanıtı okunamadı."
        }
    }
}

struct APIClient {
    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: URL = APIConfiguration.baseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func isHealthy() async -> Bool {
        guard let url = URL(string: "/health", relativeTo: baseURL) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 2.5
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let response: HealthResponse = try await execute(request)
            return response.status == "ok"
        } catch {
            return false
        }
    }

    func register(_ request: RegisterRequest) async throws -> RegistrationResponse {
        try await send(path: "/api/v1/auth/register", method: "POST", body: request)
    }

    func verifyEmail(_ request: VerifyEmailRequest) async throws -> AuthenticationResponse {
        try await send(path: "/api/v1/auth/verify-email", method: "POST", body: request)
    }

    func resendVerification(email: String) async throws -> VerificationDispatchResponse {
        try await send(path: "/api/v1/auth/resend-verification", method: "POST", body: EmailRequest(email: email))
    }

    func login(_ request: LoginRequest) async throws -> AuthenticationResponse {
        try await send(path: "/api/v1/auth/login", method: "POST", body: request)
    }

    func refresh(_ refreshToken: String) async throws -> APITokens {
        try await send(
            path: "/api/v1/auth/refresh",
            method: "POST",
            body: RefreshTokenRequest(refreshToken: refreshToken)
        )
    }

    func logout(_ refreshToken: String) async throws -> MessageResponse {
        try await send(
            path: "/api/v1/auth/logout",
            method: "POST",
            body: RefreshTokenRequest(refreshToken: refreshToken)
        )
    }

    func forgotPassword(email: String) async throws -> VerificationDispatchResponse {
        try await send(path: "/api/v1/auth/forgot-password", method: "POST", body: EmailRequest(email: email))
    }

    func resetPassword(email: String, code: String, newPassword: String) async throws -> MessageResponse {
        try await send(
            path: "/api/v1/auth/reset-password",
            method: "POST",
            body: ResetPasswordRequest(email: email, code: code, newPassword: newPassword)
        )
    }

    func currentUser(accessToken: String) async throws -> APIUser {
        try await send(path: "/api/v1/users/me", method: "GET", accessToken: accessToken)
    }

    func feed(
        accessToken: String,
        cursor: String? = nil,
        sessionID: UUID? = nil,
        personalized: Bool = true
    ) async throws -> APIFeedResponse {
        var components = URLComponents(string: "/api/v1/posts/feed")
        var items = [
            URLQueryItem(name: "limit", value: "50"),
            URLQueryItem(name: "personalized", value: String(personalized))
        ]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        if let sessionID {
            items.append(URLQueryItem(name: "sessionId", value: sessionID.uuidString))
            items.append(URLQueryItem(name: "localHour", value: String(Calendar.current.component(.hour, from: Date()))))
            items.append(URLQueryItem(name: "timezoneOffsetMinutes", value: String(TimeZone.current.secondsFromGMT() / 60)))
        }
        components?.queryItems = items
        return try await send(path: components?.string ?? "/api/v1/posts/feed?limit=50", method: "GET", accessToken: accessToken)
    }

    func recordRecommendationEvents(_ events: [APIRecommendationEvent], accessToken: String) async throws {
        guard !events.isEmpty else { return }
        let _: APIRecommendationEventBatchResponse = try await send(
            path: "/api/v1/recommendations/events",
            method: "POST",
            body: APIRecommendationEventBatch(events: events),
            accessToken: accessToken
        )
    }

    func resetRecommendationProfile(accessToken: String) async throws {
        let _: MessageResponse = try await send(
            path: "/api/v1/recommendations/profile",
            method: "DELETE",
            accessToken: accessToken
        )
    }

    func createPost(text: String, accessToken: String) async throws -> APIPost {
        try await send(
            path: "/api/v1/posts",
            method: "POST",
            body: CreatePostRequest(text: text, mediaIds: []),
            accessToken: accessToken
        )
    }

    func setLike(postID: String, active: Bool, accessToken: String) async throws -> APIPostInteractionResponse {
        try await send(
            path: "/api/v1/posts/\(postID)/like",
            method: active ? "PUT" : "DELETE",
            accessToken: accessToken
        )
    }

    func setSave(postID: String, active: Bool, accessToken: String) async throws -> APIPostInteractionResponse {
        try await send(
            path: "/api/v1/posts/\(postID)/save",
            method: active ? "PUT" : "DELETE",
            accessToken: accessToken
        )
    }

    private func send<Response: Decodable>(
        path: String,
        method: String,
        accessToken: String? = nil
    ) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIClientError.invalidConfiguration
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        configure(&request, accessToken: accessToken)
        return try await execute(request)
    }

    private func send<Body: Encodable, Response: Decodable>(
        path: String,
        method: String,
        body: Body,
        accessToken: String? = nil
    ) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIClientError.invalidConfiguration
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        configure(&request, accessToken: accessToken)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await execute(request)
    }

    private func configure(_ request: inout URLRequest, accessToken: String?) {
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
    }

    private func execute<Response: Decodable>(_ request: URLRequest) async throws -> Response {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            throw APIClientError.transport(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIClientError.invalidResponse
        }
        guard 200..<300 ~= httpResponse.statusCode else {
            let apiError = (try? decoder.decode(APIErrorResponse.self, from: data))
                ?? APIErrorResponse(code: "HTTP_ERROR", message: "İşlem tamamlanamadı.", field: nil)
            throw APIClientError.server(statusCode: httpResponse.statusCode, response: apiError)
        }

        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIClientError.decoding(error)
        }
    }
}

enum APIConfiguration {
    static var baseURL: URL {
        if let override = ProcessInfo.processInfo.environment["NEXI_API_BASE_URL"],
           let url = URL(string: override) {
            return url
        }
        if let configured = Bundle.main.object(forInfoDictionaryKey: "NEXI_API_BASE_URL") as? String,
           let url = URL(string: configured) {
            return url
        }
        return URL(string: "http://127.0.0.1:8080")!
    }
}
