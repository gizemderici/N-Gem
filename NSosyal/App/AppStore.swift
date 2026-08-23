import Combine
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var selectedTab: AppTab = .home
    @Published var selectedInterestIDs: [String] = ["technology", "design", "education"]
    @Published var likedPostIDs: Set<String> = []
    @Published var savedPostIDs: Set<String> = ["long-learning"]
    @Published var followedCreatorIDs: Set<String> = ["mert", "teknoloji"]
    @Published var hiddenPostIDs: Set<String> = []
    @Published var selectedReasonPost: SocialPost?
    @Published var toastMessage: String?
    @Published var personalizationEnabled: Bool = true
    @Published private(set) var dataSourceMode: DataSourceMode = .checking
    @Published private(set) var posts: [SocialPost] = MockSocialData.posts

    let notifications = MockSocialData.notifications

    private let apiClient: APIClient
    private let tokenStore: KeychainTokenStore
    private var recommendationSessionID = UUID()
    private var lastFeedRequestID: String?
    private var viewStartedAt: [String: Date] = [:]

    init() {
        self.apiClient = APIClient()
        self.tokenStore = KeychainTokenStore()
        if UserDefaults.standard.object(forKey: "nsosyal_personalization_enabled") != nil {
            personalizationEnabled = UserDefaults.standard.bool(forKey: "nsosyal_personalization_enabled")
        }
    }

    var visiblePosts: [SocialPost] {
        let available = posts.filter { !hiddenPostIDs.contains($0.id) }
        return dataSourceMode == .backend
            ? available
            : available.sorted { score(for: $0) > score(for: $1) }
    }

    private var contextualIntent: IntentMode {
        let hour = Calendar.current.component(.hour, from: Date())
        return switch hour {
        case 6..<9: .agenda
        case 9..<18: .learn
        case 18..<22: .local
        default: .fun
        }
    }

    func completeOnboarding(with interests: [String]) {
        selectedInterestIDs = interests
        track(.sessionStarted, context: ["source": "onboarding_complete"])
        interests.forEach { interestID in
            track(.interestSelected, context: ["targetFeature": interestID])
        }
    }

    func configure(dataSourceMode: DataSourceMode) async {
        self.dataSourceMode = dataSourceMode
        if dataSourceMode == .backend {
            await refreshFromBackend()
        } else if dataSourceMode == .mock {
            useMockData()
        }
    }

    func refreshFromBackend() async {
        guard dataSourceMode == .backend,
              let accessToken = (try? tokenStore.load())?.accessToken else { return }
        do {
            let response = try await apiClient.feed(
                accessToken: accessToken,
                sessionID: recommendationSessionID,
                personalized: personalizationEnabled
            )
            lastFeedRequestID = response.requestId
            likedPostIDs = Set(response.items.filter(\.likedByMe).map(\.id))
            savedPostIDs = Set(response.items.filter(\.savedByMe).map(\.id))
            posts = response.items.map(mapPost)
        } catch {
            useMockData(message: "Backend bağlantısı kesildi; örnek akış açıldı")
        }
    }

    func toggleLike(_ post: SocialPost) {
        let shouldLike = !likedPostIDs.contains(post.id)
        withAnimation(NSTheme.bouncySpring) {
            if !shouldLike {
                likedPostIDs.remove(post.id)
                NSHaptics.selection()
            } else {
                likedPostIDs.insert(post.id)
                NSHaptics.notification(.success)
            }
        }
        guard dataSourceMode == .backend else { return }
        Task {
            guard let accessToken = (try? tokenStore.load())?.accessToken else { return }
            do {
                _ = try await apiClient.setLike(postID: post.id, active: shouldLike, accessToken: accessToken)
                if shouldLike { track(.contentLiked, contentID: post.id) }
            } catch {
                if shouldLike { likedPostIDs.remove(post.id) } else { likedPostIDs.insert(post.id) }
                showToast("Beğeni sunucuya kaydedilemedi")
            }
        }
    }

    func toggleSave(_ post: SocialPost) {
        let shouldSave = !savedPostIDs.contains(post.id)
        withAnimation(NSTheme.bouncySpring) {
            if !shouldSave {
                savedPostIDs.remove(post.id)
                NSHaptics.selection()
            } else {
                savedPostIDs.insert(post.id)
                NSHaptics.notification(.success)
                showToast("Gönderi kaydedildi")
            }
        }
        guard dataSourceMode == .backend else { return }
        Task {
            guard let accessToken = (try? tokenStore.load())?.accessToken else { return }
            do {
                _ = try await apiClient.setSave(postID: post.id, active: shouldSave, accessToken: accessToken)
                if shouldSave { track(.contentSaved, contentID: post.id) }
            } catch {
                if shouldSave { savedPostIDs.remove(post.id) } else { savedPostIDs.insert(post.id) }
                showToast("Kayıt tercihi sunucuya gönderilemedi")
            }
        }
    }

    func hide(_ post: SocialPost) {
        withAnimation(NSTheme.spring) {
            _ = hiddenPostIDs.insert(post.id)
        }
        NSHaptics.notification(.warning)
        showToast("Bu gönderiyi daha az göstereceğiz")
        track(.contentHidden, contentID: post.id)
    }

    func share(_ post: SocialPost) {
        showToast("Paylaşım bağlantısı hazır")
        track(.contentShared, contentID: post.id)
    }

    func report(_ post: SocialPost) {
        track(.contentReported, contentID: post.id)
        showToast("Gönderi incelemeye alındı")
    }

    func avoidAtCurrentTime(_ post: SocialPost) {
        track(.contentHidden, contentID: post.id, context: ["surface": "time_preference"])
        NSHaptics.selection()
        showToast("Saat tercihin güncellendi")
    }

    func beginViewing(_ post: SocialPost) {
        guard viewStartedAt[post.id] == nil else { return }
        viewStartedAt[post.id] = Date()
    }

    func endViewing(_ post: SocialPost) {
        guard let startedAt = viewStartedAt.removeValue(forKey: post.id) else { return }
        let dwellMillis = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        guard dwellMillis >= 750 else { return }
        track(.contentViewed, contentID: post.id, context: ["dwellMillis": String(dwellMillis)])
    }

    func openReason(for post: SocialPost) {
        selectedReasonPost = post
        track(.reasonOpened, contentID: post.id)
    }

    func toggleFollow(_ creator: Creator) {
        withAnimation(NSTheme.bouncySpring) {
            if followedCreatorIDs.contains(creator.id) {
                followedCreatorIDs.remove(creator.id)
                NSHaptics.selection()
            } else {
                followedCreatorIDs.insert(creator.id)
                NSHaptics.notification(.success)
                showToast("\(creator.name) takip edildi")
            }
        }
    }

    func publish(text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if dataSourceMode == .backend {
            Task {
                guard let accessToken = (try? tokenStore.load())?.accessToken else { return }
                do {
                    let response = try await apiClient.createPost(text: text, accessToken: accessToken)
                    posts.insert(mapPost(response), at: 0)
                    track(.postPublished, context: ["format": "text", "source": "backend"])
                    NSHaptics.notification(.success)
                    showToast("Gönderin yayınlandı")
                    selectedTab = .home
                } catch {
                    showToast(error.localizedDescription)
                }
            }
            return
        }
        track(.postPublished, context: ["format": "text"])
        NSHaptics.notification(.success)
        showToast("Gönderin yayınlandı")
        selectedTab = .home
    }

    func resetLearnedProfile() {
        selectedInterestIDs = []
        guard dataSourceMode == .backend else {
            NSHaptics.notification(.success)
            showToast("Öğrenilmiş profil sıfırlandı")
            return
        }
        Task {
            guard let accessToken = (try? tokenStore.load())?.accessToken else { return }
            do {
                try await apiClient.resetRecommendationProfile(accessToken: accessToken)
                recommendationSessionID = UUID()
                lastFeedRequestID = nil
                NSHaptics.notification(.success)
                showToast("Öğrenilmiş profil sıfırlandı")
                await refreshFromBackend()
            } catch {
                showToast(error.localizedDescription)
            }
        }
    }

    func setPersonalizationEnabled(_ enabled: Bool) {
        personalizationEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "nsosyal_personalization_enabled")
        showToast(enabled ? "Akıllı kişiselleştirme açıldı" : "Kişiselleştirme durduruldu")
        Task { await refreshFromBackend() }
    }

    func showToast(_ message: String) {
        toastMessage = message
        Task {
            try? await Task.sleep(for: .seconds(2))
            guard toastMessage == message else { return }
            withAnimation(.easeOut(duration: 0.2)) {
                toastMessage = nil
            }
        }
    }

    private func score(for post: SocialPost) -> Int {
        var value = 0
        let normalizedTopic = post.topic.lowercased()

        if selectedInterestIDs.contains(where: { normalizedTopic.contains(localizedTitle(for: $0).lowercased()) }) {
            value += 30
        }
        if followedCreatorIDs.contains(post.creator.id) { value += 25 }
        if savedPostIDs.contains(post.id) { value += 12 }

        switch contextualIntent {
        case .fun where post.topic == "Mizah": value += 45
        case .agenda where post.topic == "Gündem": value += 45
        case .learn where ["Teknoloji", "Eğitim", "Tasarım"].contains(post.topic): value += 35
        case .local where post.topic == "Yerel": value += 45
        default: break
        }
        return value
    }

    private func localizedTitle(for interestID: String) -> String {
        MockSocialData.interests.first(where: { $0.id == interestID })?.title ?? interestID
    }

    private func useMockData(message: String? = nil) {
        dataSourceMode = .mock
        posts = MockSocialData.posts
        likedPostIDs = []
        savedPostIDs = ["long-learning"]
        if let message { showToast(message) }
    }

    private func mapPost(_ post: APIPost) -> SocialPost {
        let initials = post.author.fullName
            .split(separator: " ")
            .prefix(2)
            .compactMap(\.first)
            .map(String.init)
            .joined()
            .uppercased()
        var mapped = SocialPost(
            id: post.id,
            creator: Creator(
                id: post.author.id,
                name: post.author.fullName,
                handle: "@\(post.author.username)",
                initials: initials.isEmpty ? "NS" : initials,
                colors: [NSTheme.cyan, NSTheme.blue, NSTheme.violet],
                isVerified: false
            ),
            time: relativeTime(post.createdAt),
            body: post.text,
            topic: post.media.isEmpty ? "Gönderi" : "Medya",
            artwork: nil,
            artworkTitle: nil,
            artworkSubtitle: nil,
            isVideo: post.media.first?.mimeType.hasPrefix("video/") == true,
            videoLength: nil,
            reason: post.recommendationReason ?? "Takip ettiğin akıştan",
            reasonDetail: "Bu içerik; açık ilgi seçimlerin, aynı saat aralığındaki etkileşimlerin, güncellik ve çeşitlilik birlikte değerlendirilerek sıralandı.",
            likeCount: max(0, post.likeCount - (post.likedByMe ? 1 : 0)),
            commentCount: 0,
            shareCount: 0
        )
        mapped.mediaURL = post.media.first?.url
        mapped.mediaMimeType = post.media.first?.mimeType
        return mapped
    }

    private func relativeTime(_ value: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        guard let date else { return "Şimdi" }
        let seconds = max(0, Int(Date().timeIntervalSince(date)))
        if seconds < 60 { return "Şimdi" }
        if seconds < 3_600 { return "\(seconds / 60) dk" }
        if seconds < 86_400 { return "\(seconds / 3_600) sa" }
        return "\(seconds / 86_400) g"
    }

    private func track(
        _ name: FeedEventName,
        contentID: String? = nil,
        context: [String: String] = [:]
    ) {
        let event = FeedEvent(name: name, contentID: contentID, context: context)
        #if DEBUG
        print("[NSosyalEvent] \(event.name.rawValue) \(event.contentID ?? "-") \(event.context)")
        #endif
        guard dataSourceMode == .backend,
              personalizationEnabled,
              name != .postPublished,
              let accessToken = (try? tokenStore.load())?.accessToken else { return }
        let apiEvent = APIRecommendationEvent(
            clientEventId: event.id.uuidString,
            sessionId: recommendationSessionID.uuidString,
            feedRequestId: lastFeedRequestID,
            postId: contentID,
            eventType: name.rawValue,
            surface: context["surface"] ?? "feed",
            position: context["position"].flatMap(Int.init),
            dwellMillis: context["dwellMillis"].flatMap(Int.init),
            completionRatio: context["completionRatio"].flatMap(Double.init),
            localHour: Calendar.current.component(.hour, from: event.timestamp),
            timezoneOffsetMinutes: TimeZone.current.secondsFromGMT(for: event.timestamp) / 60,
            targetFeature: context["targetFeature"],
            occurredAt: ISO8601DateFormatter().string(from: event.timestamp)
        )
        Task {
            try? await apiClient.recordRecommendationEvents([apiEvent], accessToken: accessToken)
        }
    }
}
