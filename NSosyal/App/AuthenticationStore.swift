import Foundation
import Combine

@MainActor
final class AuthenticationStore: ObservableObject {
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isRestoringSession = true
    @Published private(set) var currentUser: APIUser?

    private let apiClient: APIClient
    private let tokenStore: KeychainTokenStore

    init(apiClient: APIClient = APIClient(), tokenStore: KeychainTokenStore = KeychainTokenStore()) {
        self.apiClient = apiClient
        self.tokenStore = tokenStore
    }

    func restoreSession() async {
        defer { isRestoringSession = false }
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
        try await apiClient.register(
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
        let response = try await apiClient.verifyEmail(VerifyEmailRequest(email: email, code: code))
        try tokenStore.save(response.tokens)
        currentUser = response.user
        isAuthenticated = true
    }

    func resendVerification(email: String) async throws -> VerificationDispatchResponse {
        try await apiClient.resendVerification(email: email)
    }

    func signIn(email: String, password: String) async throws {
        let response = try await apiClient.login(LoginRequest(email: email, password: password))
        try tokenStore.save(response.tokens)
        currentUser = response.user
        isAuthenticated = true
    }

    func requestPasswordReset(email: String) async throws -> VerificationDispatchResponse {
        try await apiClient.forgotPassword(email: email)
    }

    func resetPassword(email: String, code: String, newPassword: String) async throws {
        _ = try await apiClient.resetPassword(email: email, code: code, newPassword: newPassword)
        clearLocalSession()
    }

    func signOut() async {
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
}
