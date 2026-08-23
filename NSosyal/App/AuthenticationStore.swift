import Foundation
import Combine

@MainActor
final class AuthenticationStore: ObservableObject {
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isRestoringSession = true
    @Published private(set) var currentUser: APIUser?
    @Published private(set) var dataSourceMode: DataSourceMode = .checking

    private let apiClient: APIClient
    private let tokenStore: KeychainTokenStore

    init() {
        self.apiClient = APIClient()
        self.tokenStore = KeychainTokenStore()
    }

    func restoreSession() async {
        defer { isRestoringSession = false }

        guard await apiClient.isHealthy() else {
            dataSourceMode = .mock
            restoreMockSession()
            return
        }
        dataSourceMode = .backend

        guard let tokens = try? tokenStore.load() else {
            clearLocalSession()
            return
        }

        do {
            currentUser = try await apiClient.currentUser(accessToken: tokens.accessToken)
            isAuthenticated = true
        } catch let error as APIClientError where error.isUnauthorized {
            await refreshSession(using: tokens.refreshToken)
        } catch {
            // Geçici bağlantı hatasında tokenları silmeyiz; kullanıcı yeniden deneyebilir.
            isAuthenticated = false
        }
    }

    func register(
        fullName: String,
        username: String,
        email: String,
        password: String,
        acceptedTerms: Bool
    ) async throws -> RegistrationResponse {
        if dataSourceMode == .mock {
            return RegistrationResponse(
                user: mockUser(fullName: fullName, username: username, email: email),
                verificationRequired: true,
                developmentCode: "123456"
            )
        }
        return try await apiClient.register(
            RegisterRequest(
                fullName: fullName,
                username: username,
                email: email,
                password: password,
                acceptedTerms: acceptedTerms
            )
        )
    }

    func verifyEmail(email: String, code: String) async throws {
        if dataSourceMode == .mock {
            guard code == "123456" else {
                throw APIClientError.server(
                    statusCode: 400,
                    response: APIErrorResponse(code: "INVALID_CODE", message: "Örnek mod doğrulama kodu 123456.", field: "code")
                )
            }
            authenticateMockUser(email: email)
            return
        }
        let response = try await apiClient.verifyEmail(VerifyEmailRequest(email: email, code: code))
        try tokenStore.save(response.tokens)
        currentUser = response.user
        isAuthenticated = true
    }

    func resendVerification(email: String) async throws -> VerificationDispatchResponse {
        if dataSourceMode == .mock {
            return VerificationDispatchResponse(accepted: true, developmentCode: "123456")
        }
        return try await apiClient.resendVerification(email: email)
    }

    func signIn(email: String, password: String) async throws {
        if dataSourceMode == .mock {
            authenticateMockUser(email: email)
            return
        }
        let response = try await apiClient.login(LoginRequest(email: email, password: password))
        try tokenStore.save(response.tokens)
        currentUser = response.user
        isAuthenticated = true
    }

    func requestPasswordReset(email: String) async throws -> VerificationDispatchResponse {
        if dataSourceMode == .mock {
            return VerificationDispatchResponse(accepted: true, developmentCode: "123456")
        }
        return try await apiClient.forgotPassword(email: email)
    }

    func resetPassword(email: String, code: String, newPassword: String) async throws {
        if dataSourceMode == .mock {
            clearLocalSession()
            return
        }
        _ = try await apiClient.resetPassword(email: email, code: code, newPassword: newPassword)
        clearLocalSession()
    }

    func signOut() async {
        if dataSourceMode == .mock {
            clearMockSession()
            return
        }
        let refreshToken = (try? tokenStore.load())?.refreshToken
        clearLocalSession()
        guard let refreshToken else { return }
        _ = try? await apiClient.logout(refreshToken)
    }

    private func refreshSession(using refreshToken: String) async {
        do {
            let tokens = try await apiClient.refresh(refreshToken)
            try tokenStore.save(tokens)
            currentUser = try await apiClient.currentUser(accessToken: tokens.accessToken)
            isAuthenticated = true
        } catch {
            clearLocalSession()
        }
    }

    private func clearLocalSession() {
        try? tokenStore.clear()
        currentUser = nil
        isAuthenticated = false
    }

    private func restoreMockSession() {
        guard UserDefaults.standard.bool(forKey: "nsosyal.mock.authenticated") else {
            currentUser = nil
            isAuthenticated = false
            return
        }
        currentUser = mockUser(fullName: "Furkan Durmaz", username: "furkandurmaz", email: "furkan@nsosyal.local")
        isAuthenticated = true
    }

    private func authenticateMockUser(email: String) {
        UserDefaults.standard.set(true, forKey: "nsosyal.mock.authenticated")
        currentUser = mockUser(fullName: "Furkan Durmaz", username: "furkandurmaz", email: email)
        isAuthenticated = true
    }

    private func clearMockSession() {
        UserDefaults.standard.removeObject(forKey: "nsosyal.mock.authenticated")
        currentUser = nil
        isAuthenticated = false
    }

    private func mockUser(fullName: String, username: String, email: String) -> APIUser {
        APIUser(
            id: "mock-user",
            fullName: fullName,
            username: username,
            email: email,
            emailVerified: true,
            createdAt: ISO8601DateFormatter().string(from: Date())
        )
    }
}
