import SwiftUI

enum AppTab: String, CaseIterable, Identifiable {
    case home
    case explore
    case create
    case notifications
    case profile

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Akış"
        case .explore: "Keşfet"
        case .create: "Oluştur"
        case .notifications: "Bildirimler"
        case .profile: "Profil"
        }
    }

    var icon: String {
        switch self {
        case .home: "house"
        case .explore: "magnifyingglass"
        case .create: "plus"
        case .notifications: "bell"
        case .profile: "person"
        }
    }

    var selectedIcon: String {
        switch self {
        case .home: "house.fill"
        case .explore: "sparkle.magnifyingglass"
        case .create: "plus"
        case .notifications: "bell.fill"
        case .profile: "person.fill"
        }
    }
}

enum FeedVariant: String, CaseIterable, Identifiable {
    case mine
    case nexi

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mine: "Benim Akışım"
        case .nexi: "Nexi Akışı"
        }
    }
}

enum IntentMode: String, CaseIterable, Identifiable {
    case automatic
    case fun
    case agenda
    case learn
    case local

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: "Otomatik"
        case .fun: "Eğlen"
        case .agenda: "Gündem"
        case .learn: "Öğren"
        case .local: "Çevrem"
        }
    }

    var icon: String {
        switch self {
        case .automatic: "sparkles"
        case .fun: "face.smiling"
        case .agenda: "newspaper"
        case .learn: "lightbulb"
        case .local: "location"
        }
    }

    var color: Color {
        switch self {
        case .automatic: NSTheme.blue
        case .fun: NSTheme.violet
        case .agenda: NSTheme.coral
        case .learn: NSTheme.green
        case .local: NSTheme.amber
        }
    }
}

struct Interest: Identifiable, Hashable {
    let id: String
    let title: String
    let icon: String
    let color: Color
}

struct Creator: Identifiable, Hashable {
    let id: String
    let name: String
    let handle: String
    let initials: String
    let colors: [Color]
    let isVerified: Bool
}

enum ArtworkStyle: String, Hashable {
    case future
    case city
    case comedy
    case culture
    case sport
    case learning

    var colors: [Color] {
        switch self {
        case .future: [Color(red: 0.03, green: 0.10, blue: 0.22), NSTheme.blue, NSTheme.cyan]
        case .city: [Color(red: 0.08, green: 0.15, blue: 0.20), NSTheme.green, NSTheme.amber]
        case .comedy: [Color(red: 0.18, green: 0.08, blue: 0.29), NSTheme.violet, Color(red: 0.98, green: 0.37, blue: 0.66)]
        case .culture: [Color(red: 0.19, green: 0.06, blue: 0.08), NSTheme.coral, NSTheme.amber]
        case .sport: [Color(red: 0.02, green: 0.15, blue: 0.12), NSTheme.green, NSTheme.cyan]
        case .learning: [Color(red: 0.05, green: 0.09, blue: 0.19), NSTheme.violet, NSTheme.blue]
        }
    }

    var symbol: String {
        switch self {
        case .future: "sparkles"
        case .city: "building.2.crop.circle"
        case .comedy: "face.smiling.inverse"
        case .culture: "theatermasks.fill"
        case .sport: "figure.run.circle.fill"
        case .learning: "book.pages.fill"
        }
    }
}

struct SocialPost: Identifiable, Hashable {
    let id: String
    let creator: Creator
    let time: String
    let body: String
    let topic: String
    let artwork: ArtworkStyle?
    let artworkTitle: String?
    let artworkSubtitle: String?
    let isVideo: Bool
    let videoLength: String?
    let reason: String
    let reasonDetail: String
    var likeCount: Int
    var commentCount: Int
    var shareCount: Int
}

enum NotificationKind: String, Hashable {
    case liked
    case followed
    case replied
    case community

    var icon: String {
        switch self {
        case .liked: "heart.fill"
        case .followed: "person.badge.plus"
        case .replied: "bubble.left.fill"
        case .community: "person.3.fill"
        }
    }

    var color: Color {
        switch self {
        case .liked: NSTheme.coral
        case .followed: NSTheme.blue
        case .replied: NSTheme.violet
        case .community: NSTheme.green
        }
    }
}

struct SocialNotification: Identifiable, Hashable {
    let id: String
    let creator: Creator
    let message: String
    let time: String
    let kind: NotificationKind
    let isUnread: Bool
}

enum FeedEventName: String {
    case sessionStarted = "session_started"
    case intentSelected = "intent_selected"
    case feedChanged = "feed_changed"
    case contentImpression = "content_impression"
    case contentLiked = "content_liked"
    case contentSaved = "content_saved"
    case contentShared = "content_shared"
    case contentHidden = "content_hidden"
    case reasonOpened = "recommendation_reason_opened"
    case postPublished = "post_published"
}

struct FeedEvent {
    let id = UUID()
    let name: FeedEventName
    let contentID: String?
    let context: [String: String]
    let timestamp = Date()
}
