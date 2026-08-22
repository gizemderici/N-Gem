import Combine
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var selectedTab: AppTab = .home
    @Published var feedVariant: FeedVariant = .mine
    @Published var intentMode: IntentMode = .automatic
    @Published var selectedInterestIDs: [String] = ["technology", "design", "education"]
    @Published var likedPostIDs: Set<String> = []
    @Published var savedPostIDs: Set<String> = ["long-learning"]
    @Published var followedCreatorIDs: Set<String> = ["mert", "teknoloji"]
    @Published var hiddenPostIDs: Set<String> = []
    @Published var selectedReasonPost: SocialPost?
    @Published var toastMessage: String?
    @Published var learningProgress: Double = 0.64

    let posts = MockSocialData.posts
    let notifications = MockSocialData.notifications

    var visiblePosts: [SocialPost] {
        let available = posts.filter { !hiddenPostIDs.contains($0.id) }
        let reordered: [SocialPost]

        if feedVariant == .nexi || intentMode != .automatic {
            reordered = available.sorted { score(for: $0) > score(for: $1) }
        } else {
            reordered = available
        }

        return reordered
    }

    var recommendedIntent: IntentMode {
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
    }

    func selectIntent(_ intent: IntentMode) {
        withAnimation(NSTheme.spring) {
            intentMode = intent
        }
        track(.intentSelected, context: ["intent": intent.rawValue])
    }

    func selectFeed(_ feed: FeedVariant) {
        withAnimation(NSTheme.spring) {
            feedVariant = feed
        }
        track(.feedChanged, context: ["feed": feed.rawValue])
    }

    func toggleLike(_ post: SocialPost) {
        if likedPostIDs.contains(post.id) {
            likedPostIDs.remove(post.id)
        } else {
            likedPostIDs.insert(post.id)
            track(.contentLiked, contentID: post.id)
        }
    }

    func toggleSave(_ post: SocialPost) {
        if savedPostIDs.contains(post.id) {
            savedPostIDs.remove(post.id)
        } else {
            savedPostIDs.insert(post.id)
            showToast("Gönderi kaydedildi")
            track(.contentSaved, contentID: post.id)
        }
    }

    func hide(_ post: SocialPost) {
        withAnimation(NSTheme.spring) {
            _ = hiddenPostIDs.insert(post.id)
        }
        showToast("Bu gönderiyi daha az göstereceğiz")
        track(.contentHidden, contentID: post.id)
    }

    func openReason(for post: SocialPost) {
        selectedReasonPost = post
        track(.reasonOpened, contentID: post.id)
    }

    func toggleFollow(_ creator: Creator) {
        if followedCreatorIDs.contains(creator.id) {
            followedCreatorIDs.remove(creator.id)
        } else {
            followedCreatorIDs.insert(creator.id)
            showToast("\(creator.name) takip edildi")
        }
    }

    func publish(text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        track(.postPublished, context: ["format": "text"])
        showToast("Gönderin yayınlandı")
        selectedTab = .home
    }

    func resetLearnedProfile() {
        intentMode = .automatic
        feedVariant = .mine
        learningProgress = 0
        showToast("Öğrenilmiş profil sıfırlandı")
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

        let effectiveIntent = intentMode == .automatic ? recommendedIntent : intentMode
        switch effectiveIntent {
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

    private func track(
        _ name: FeedEventName,
        contentID: String? = nil,
        context: [String: String] = [:]
    ) {
        let event = FeedEvent(name: name, contentID: contentID, context: context)
        #if DEBUG
        print("[NSosyalEvent] \(event.name.rawValue) \(event.contentID ?? "-") \(event.context)")
        #endif
    }
}
