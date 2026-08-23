import Foundation

enum DataSourceMode: Equatable {
    case checking
    case backend
    case mock
}

struct HealthResponse: Decodable {
    let status: String
    let service: String
    let database: String?
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
    let topicIds: [String]
}

struct APIPostAuthor: Codable, Equatable {
    let id: String
    let fullName: String
    let username: String
    let followedByMe: Bool
    let avatarUrl: String?
    let avatarUrlExpiresInSeconds: Int?
}

struct APIPostMedia: Decodable {
    let id: String
    let mimeType: String
    let width: Int?
    let height: Int?
    let url: String
    let urlExpiresInSeconds: Int
}

struct APITopicSummary: Codable, Equatable, Identifiable {
    let id: String
    let slug: String
    let name: String
}

struct APITopic: Codable, Equatable, Identifiable {
    let id: String
    let slug: String
    let name: String
    let description: String?
    let icon: String
    let colorHex: String
}

struct APITopicListResponse: Decodable {
    let items: [APITopic]
    let minSelectable: Int
    let maxSelectable: Int
}

struct APIUserTopicsResponse: Decodable {
    let items: [APITopic]
    let minSelectable: Int
    let maxSelectable: Int
    let completed: Bool
    let updatedAt: String?
}

struct APIUpdateTopicsRequest: Encodable {
    let topicIds: [String]
}

struct APIPost: Decodable {
    let id: String
    let text: String
    let author: APIPostAuthor
    let media: [APIPostMedia]
    let topics: [APITopicSummary]
    let likeCount: Int
    let saveCount: Int
    let commentCount: Int
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

struct APICreateCommentRequest: Encodable { let text: String }

struct APIComment: Decodable, Identifiable {
    let id: String
    let postId: String
    let text: String
    let author: APIPostAuthor
    let createdAt: String
    let deletableByMe: Bool
}

struct APICommentPageResponse: Decodable {
    let items: [APIComment]
    let nextCursor: String?
    let totalCount: Int
}

struct APIUserProfile: Decodable, Equatable {
    let id: String
    let fullName: String
    let username: String
    let bio: String?
    let avatarUrl: String?
    let avatarUrlExpiresInSeconds: Int?
    let createdAt: String
    let postCount: Int
    let followerCount: Int
    let followingCount: Int
    let followedByMe: Bool
    let isMe: Bool
}

struct APIUpdateProfileRequest: Encodable {
    let fullName: String?
    let bio: String?
}

struct APIFollowResponse: Decodable {
    let username: String
    let following: Bool
    let followerCount: Int
}

struct APICreateReportRequest: Encodable {
    let targetType: String
    let targetId: String
    let reason: String
    let details: String?
}

struct APIReportResponse: Decodable {
    let id: String
    let alreadyReported: Bool
}

struct APIStoryMedia: Decodable {
    let id: String
    let mimeType: String
    let url: String
    let urlExpiresInSeconds: Int
    let durationSeconds: Double?
    let width: Int?
    let height: Int?
    let thumbnailUrl: String?
}

struct APIStory: Decodable, Identifiable {
    let id: String
    let author: APIPostAuthor
    let caption: String?
    let media: APIStoryMedia
    let publishedAt: String
    let expiresAt: String
    let seenByMe: Bool
    let viewCount: Int?
}

struct APIStoryGroup: Decodable, Identifiable {
    var id: String { author.id }
    let author: APIPostAuthor
    let stories: [APIStory]
    let hasUnseen: Bool
    let latestPublishedAt: String
}

struct APIStoryFeedResponse: Decodable { let items: [APIStoryGroup] }
struct APIStoryViewResponse: Decodable { let storyId: String; let seen: Bool }

struct APINotification: Decodable, Identifiable {
    let id: String
    let type: String
    let actor: APIPostAuthor?
    let targetType: String?
    let targetId: String?
    let read: Bool
    let createdAt: String
}

struct APINotificationPageResponse: Decodable {
    let items: [APINotification]
    let nextCursor: String?
    let unreadCount: Int
}

struct APINotificationReadResponse: Decodable {
    let id: String?
    let unreadCount: Int
}

struct APISearchUser: Decodable, Identifiable {
    let id: String
    let fullName: String
    let username: String
    let avatarUrl: String?
    let avatarUrlExpiresInSeconds: Int?
    let followerCount: Int
    let followedByMe: Bool
    let isMe: Bool
}

struct APISearchResponse: Decodable {
    let query: String
    let users: [APISearchUser]
    let posts: [APIPost]
    let topics: [APITopic]
}

struct APIExploreResponse: Decodable {
    let items: [APIPost]
    let nextCursor: String?
}

struct APIMessageMedia: Decodable {
    let id: String
    let mimeType: String
    let url: String
    let urlExpiresInSeconds: Int
}

struct APIMessage: Decodable, Identifiable {
    let id: String
    let conversationId: String
    let sender: APIPostAuthor
    let type: String
    let text: String?
    let media: APIMessageMedia?
    let createdAt: String
    let mineByMe: Bool
    let seenByOther: Bool
}

struct APIConversation: Decodable, Identifiable {
    let id: String
    let other: APIPostAuthor
    let lastMessage: APIMessage?
    let unreadCount: Int
    let lastMessageAt: String?
}

struct APIConversationPageResponse: Decodable {
    let items: [APIConversation]
    let nextCursor: String?
    let totalUnread: Int
}

struct APIMessagePageResponse: Decodable {
    let items: [APIMessage]
    let nextCursor: String?
}

struct APICreateConversationRequest: Encodable { let username: String }
struct APISendMessageRequest: Encodable { let text: String?; let mediaId: String? }
struct APIReadReceiptResponse: Decodable { let conversationId: String; let unreadCount: Int; let readAt: String }
