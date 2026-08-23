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
    @Published private(set) var explorePosts: [SocialPost] = MockSocialData.posts
    @Published private(set) var searchPosts: [SocialPost] = []
    @Published private(set) var searchUsers: [APISearchUser] = []
    @Published private(set) var topics: [APITopic] = []
    @Published private(set) var stories: [SocialStory] = MockSocialData.stories
    @Published private(set) var notifications: [SocialNotification] = MockSocialData.notifications
    @Published private(set) var unreadNotificationCount = 0
    @Published private(set) var commentsByPost: [String: [APIComment]] = [:]
    @Published private(set) var currentUser: APIUser?
    @Published private(set) var profile: APIUserProfile?
    @Published private(set) var profilePosts: [SocialPost] = []
    @Published private(set) var conversations: [APIConversation] = []
    @Published private(set) var messagesByConversation: [String: [APIMessage]] = [:]

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
        guard dataSourceMode == .backend else { return }
        Task {
            guard let accessToken = accessToken else { return }
            if topics.isEmpty { await loadTopics() }
            let ids = interests.compactMap { selected in
                topics.first(where: { $0.id == selected || $0.slug == selected })?.id
            }
            guard !ids.isEmpty else { return }
            if let response = try? await apiClient.updateUserTopics(ids, accessToken: accessToken) {
                selectedInterestIDs = response.items.map(\.slug)
            }
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
              let accessToken else { return }
        do {
            let response = try await apiClient.feed(
                accessToken: accessToken,
                sessionID: recommendationSessionID,
                personalized: personalizationEnabled
            )
            lastFeedRequestID = response.requestId
            likedPostIDs = Set(response.items.filter(\.likedByMe).map(\.id))
            savedPostIDs = Set(response.items.filter(\.savedByMe).map(\.id))
            followedCreatorIDs = Set(response.items.filter(\.author.followedByMe).map(\.author.id))
            posts = response.items.map(mapPost)
        } catch {
            useMockData(message: "Backend bağlantısı kesildi; örnek akış açıldı")
            return
        }
        await refreshBackendFeatures(accessToken: accessToken)
    }

    func loadTopics() async {
        guard dataSourceMode == .backend else { return }
        do {
            let catalog = try await apiClient.topics()
            topics = catalog.items
            guard let accessToken else { return }
            if let selected = try? await apiClient.userTopics(accessToken: accessToken), selected.completed {
                selectedInterestIDs = selected.items.map(\.slug)
            }
        } catch {
            showToast("İlgi alanları alınamadı")
        }
    }

    func loadComments(for post: SocialPost) async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            let response = try await apiClient.comments(postID: post.id, accessToken: accessToken)
            commentsByPost[post.id] = response.items
        } catch {
            showToast(error.localizedDescription)
        }
    }

    func addComment(_ text: String, to post: SocialPost) async -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, dataSourceMode == .backend, let accessToken else { return false }
        do {
            let comment = try await apiClient.createComment(postID: post.id, text: clean, accessToken: accessToken)
            commentsByPost[post.id, default: []].append(comment)
            changeCommentCount(postID: post.id, delta: 1)
            NSHaptics.notification(.success)
            return true
        } catch {
            showToast(error.localizedDescription)
            return false
        }
    }

    func deleteComment(_ comment: APIComment) async {
        guard let accessToken else { return }
        do {
            try await apiClient.deleteComment(id: comment.id, accessToken: accessToken)
            commentsByPost[comment.postId]?.removeAll { $0.id == comment.id }
            changeCommentCount(postID: comment.postId, delta: -1)
        } catch {
            showToast(error.localizedDescription)
        }
    }

    func loadExplore() async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            explorePosts = try await apiClient.explore(accessToken: accessToken).items.map(mapPost)
        } catch {
            showToast("Keşfet akışı yenilenemedi")
        }
    }

    func search(_ query: String) async {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 2, dataSourceMode == .backend, let accessToken else {
            searchPosts = []
            searchUsers = []
            return
        }
        do {
            let result = try await apiClient.search(query: clean, accessToken: accessToken)
            searchPosts = result.posts.map(mapPost)
            searchUsers = result.users
        } catch {
            showToast("Arama tamamlanamadı")
        }
    }

    func refreshProfile() async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            let user = try await apiClient.currentUser(accessToken: accessToken)
            currentUser = user
            async let profileResponse = apiClient.profile(username: user.username, accessToken: accessToken)
            async let postsResponse = apiClient.posts(username: user.username, accessToken: accessToken)
            profile = try await profileResponse
            profilePosts = try await postsResponse.items.map(mapPost)
        } catch {
            showToast("Profil güncellenemedi")
        }
    }

    func updateProfile(fullName: String, bio: String) async -> Bool {
        guard dataSourceMode == .backend, let accessToken else { return false }
        do {
            profile = try await apiClient.updateProfile(fullName: fullName, bio: bio, accessToken: accessToken)
            showToast("Profil güncellendi")
            return true
        } catch {
            showToast(error.localizedDescription)
            return false
        }
    }

    func loadNotifications() async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            let response = try await apiClient.notifications(accessToken: accessToken)
            notifications = response.items.map(mapNotification)
            unreadNotificationCount = response.unreadCount
        } catch {
            showToast("Bildirimler yenilenemedi")
        }
    }

    func markAllNotificationsRead() async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            let response = try await apiClient.markAllNotificationsRead(accessToken: accessToken)
            unreadNotificationCount = response.unreadCount
            notifications = notifications.map {
                SocialNotification(id: $0.id, creator: $0.creator, message: $0.message, time: $0.time, kind: $0.kind, isUnread: false, targetType: $0.targetType, targetID: $0.targetID)
            }
            NSHaptics.selection()
        } catch {
            showToast(error.localizedDescription)
        }
    }

    func loadConversations() async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            conversations = try await apiClient.conversations(accessToken: accessToken).items
        } catch {
            showToast("Mesajlar yenilenemedi")
        }
    }

    func loadMessages(conversationID: String) async {
        guard dataSourceMode == .backend, let accessToken else { return }
        do {
            messagesByConversation[conversationID] = try await apiClient.messages(conversationID: conversationID, accessToken: accessToken).items
            _ = try? await apiClient.markConversationRead(id: conversationID, accessToken: accessToken)
            await loadConversations()
        } catch {
            showToast(error.localizedDescription)
        }
    }

    func sendMessage(_ text: String, conversationID: String) async -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, let accessToken else { return false }
        do {
            let message = try await apiClient.sendMessage(conversationID: conversationID, text: clean, accessToken: accessToken)
            messagesByConversation[conversationID, default: []].append(message)
            await loadConversations()
            return true
        } catch {
            showToast(error.localizedDescription)
            return false
        }
    }

    func markStoryViewed(_ story: SocialStory) {
        guard dataSourceMode == .backend, let accessToken else { return }
        Task { _ = try? await apiClient.markStoryViewed(id: story.id, accessToken: accessToken) }
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
        guard dataSourceMode == .backend, let accessToken else {
            showToast("Gönderi incelemeye alındı")
            return
        }
        Task {
            do {
                let response = try await apiClient.reportPost(id: post.id, accessToken: accessToken)
                showToast(response.alreadyReported ? "Bu gönderiyi daha önce bildirmiştin" : "Gönderi incelemeye alındı")
            } catch {
                showToast(error.localizedDescription)
            }
        }
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
        let shouldFollow = !followedCreatorIDs.contains(creator.id)
        withAnimation(NSTheme.bouncySpring) {
            if !shouldFollow {
                followedCreatorIDs.remove(creator.id)
                NSHaptics.selection()
            } else {
                followedCreatorIDs.insert(creator.id)
                NSHaptics.notification(.success)
                showToast("\(creator.name) takip edildi")
            }
        }
        guard dataSourceMode == .backend, let accessToken else { return }
        Task {
            do {
                _ = try await apiClient.setFollow(
                    username: creator.handle.replacingOccurrences(of: "@", with: ""),
                    active: shouldFollow,
                    accessToken: accessToken
                )
            } catch {
                if shouldFollow { followedCreatorIDs.remove(creator.id) } else { followedCreatorIDs.insert(creator.id) }
                showToast("Takip tercihi kaydedilemedi")
            }
        }
    }

    func publish(text: String, topicIDs: [String] = []) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if dataSourceMode == .backend {
            Task {
                guard let accessToken = (try? tokenStore.load())?.accessToken else { return }
                do {
                    let response = try await apiClient.createPost(text: text, topicIDs: topicIDs, accessToken: accessToken)
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
        explorePosts = MockSocialData.posts
        stories = MockSocialData.stories
        notifications = MockSocialData.notifications
        unreadNotificationCount = notifications.filter(\.isUnread).count
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
                isVerified: false,
                avatarURL: post.author.avatarUrl
            ),
            time: relativeTime(post.createdAt),
            body: post.text,
            topic: post.topics.first?.name ?? (post.media.isEmpty ? "Gönderi" : "Medya"),
            artwork: nil,
            artworkTitle: nil,
            artworkSubtitle: nil,
            isVideo: post.media.first?.mimeType.hasPrefix("video/") == true,
            videoLength: nil,
            reason: post.recommendationReason ?? "Takip ettiğin akıştan",
            reasonDetail: "Bu içerik; açık ilgi seçimlerin, aynı saat aralığındaki etkileşimlerin, güncellik ve çeşitlilik birlikte değerlendirilerek sıralandı.",
            likeCount: max(0, post.likeCount - (post.likedByMe ? 1 : 0)),
            commentCount: post.commentCount,
            shareCount: 0
        )
        mapped.mediaURL = post.media.first?.url
        mapped.mediaMimeType = post.media.first?.mimeType
        return mapped
    }

    private func refreshBackendFeatures(accessToken: String) async {
        if let user = try? await apiClient.currentUser(accessToken: accessToken) {
            currentUser = user
        }
        await loadTopics()
        if let storyResponse = try? await apiClient.storyFeed(accessToken: accessToken) {
            stories = storyResponse.items.flatMap { group in
                group.stories.map { mapStory($0, isOwn: $0.author.id == currentUser?.id) }
            }
        }
        await loadNotifications()
        await loadExplore()
        await refreshProfile()
        await loadConversations()
    }

    private func mapStory(_ story: APIStory, isOwn: Bool) -> SocialStory {
        SocialStory(
            id: story.id,
            creator: mapCreator(story.author),
            style: .culture,
            headline: story.caption ?? "Hikâye",
            detail: story.caption ?? "",
            time: relativeTime(story.publishedAt),
            isSeen: story.seenByMe,
            isOwn: isOwn,
            mediaURL: story.media.url,
            mediaMimeType: story.media.mimeType
        )
    }

    private func mapNotification(_ notification: APINotification) -> SocialNotification {
        let creator = notification.actor.map(mapCreator) ?? Creator(
            id: "system",
            name: "N Sosyal",
            handle: "@nsosyal",
            initials: "NS",
            colors: [NSTheme.blue, NSTheme.violet],
            isVerified: true
        )
        let kind: NotificationKind
        let message: String
        switch notification.type {
        case "FOLLOW":
            kind = .followed
            message = "seni takip etmeye başladı."
        case "POST_LIKE":
            kind = .liked
            message = "gönderini beğendi."
        case "POST_COMMENT":
            kind = .replied
            message = "gönderine yorum yaptı."
        case "MESSAGE":
            kind = .replied
            message = "sana yeni bir mesaj gönderdi."
        default:
            kind = .community
            message = "N Sosyal'den yeni bir bildirimin var."
        }
        return SocialNotification(
            id: notification.id,
            creator: creator,
            message: message,
            time: relativeTime(notification.createdAt),
            kind: kind,
            isUnread: !notification.read,
            targetType: notification.targetType,
            targetID: notification.targetId
        )
    }

    private func mapCreator(_ author: APIPostAuthor) -> Creator {
        let initials = author.fullName
            .split(separator: " ")
            .prefix(2)
            .compactMap(\.first)
            .map(String.init)
            .joined()
            .uppercased()
        return Creator(
            id: author.id,
            name: author.fullName,
            handle: "@\(author.username)",
            initials: initials.isEmpty ? "NS" : initials,
            colors: [NSTheme.cyan, NSTheme.blue, NSTheme.violet],
            isVerified: false,
            avatarURL: author.avatarUrl
        )
    }

    private func changeCommentCount(postID: String, delta: Int) {
        if let index = posts.firstIndex(where: { $0.id == postID }) {
            posts[index].commentCount = max(0, posts[index].commentCount + delta)
        }
        if let index = explorePosts.firstIndex(where: { $0.id == postID }) {
            explorePosts[index].commentCount = max(0, explorePosts[index].commentCount + delta)
        }
        if let index = profilePosts.firstIndex(where: { $0.id == postID }) {
            profilePosts[index].commentCount = max(0, profilePosts[index].commentCount + delta)
        }
    }

    private var accessToken: String? {
        (try? tokenStore.load())?.accessToken
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
