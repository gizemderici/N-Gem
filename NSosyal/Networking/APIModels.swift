import Foundation

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
