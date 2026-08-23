import Foundation

enum DataSourceMode: Equatable {
    case checking
    case backend
    case mock
}

struct HealthResponse: Decodable {
    let status: String
    let service: String
}

struct RegisterRequest: Encodable {
    let fullName: String
    let username: String
    let email: String
    let password: String
    let acceptedTerms: Bool
}

struct LoginRequest: Encodable {
    let email: String
    let password: String
}

struct VerifyEmailRequest: Encodable {
    let email: String
    let code: String
}

struct EmailRequest: Encodable {
    let email: String
}

struct ResetPasswordRequest: Encodable {
    let email: String
    let code: String
    let newPassword: String
}

struct RefreshTokenRequest: Encodable {
    let refreshToken: String
}

struct APIUser: Codable, Equatable {
    let id: String
    let fullName: String
    let username: String
    let email: String
    let emailVerified: Bool
    let createdAt: String
}

struct APITokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresInSeconds: Int
}

struct AuthenticationResponse: Decodable {
    let user: APIUser
    let tokens: APITokens
}

struct RegistrationResponse: Decodable {
    let user: APIUser
    let verificationRequired: Bool
    let developmentCode: String?
}

struct VerificationDispatchResponse: Decodable {
    let accepted: Bool
    let developmentCode: String?
}

struct MessageResponse: Decodable {
    let message: String
}

struct APIErrorResponse: Decodable {
    let code: String
    let message: String
    let field: String?
}

struct CreatePostRequest: Encodable {
    let text: String
    let mediaIds: [String]
}

struct APIPostAuthor: Decodable {
    let id: String
    let fullName: String
    let username: String
}

struct APIPostMedia: Decodable {
    let id: String
    let mimeType: String
    let width: Int?
    let height: Int?
    let url: String
    let urlExpiresInSeconds: Int
}

struct APIPost: Decodable {
    let id: String
    let text: String
    let author: APIPostAuthor
    let media: [APIPostMedia]
    let likeCount: Int
    let saveCount: Int
    let likedByMe: Bool
    let savedByMe: Bool
    let createdAt: String
    let recommendationReason: String?
}

struct APIFeedResponse: Decodable {
    let items: [APIPost]
    let nextCursor: String?
    let requestId: String?
    let modelVersion: String?
}

struct APIPostInteractionResponse: Decodable {
    let postId: String
    let active: Bool
    let count: Int
}

struct APIRecommendationEvent: Encodable {
    let clientEventId: String
    let sessionId: String
    let feedRequestId: String?
    let postId: String?
    let eventType: String
    let surface: String
    let position: Int?
    let dwellMillis: Int?
    let completionRatio: Double?
    let localHour: Int
    let timezoneOffsetMinutes: Int
    let targetFeature: String?
    let occurredAt: String
}

struct APIRecommendationEventBatch: Encodable {
    let events: [APIRecommendationEvent]
}

struct APIRecommendationEventBatchResponse: Decodable {
    let accepted: Int
    let ignored: Int
}
