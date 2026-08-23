import SwiftUI

struct PostCard: View {
    @EnvironmentObject private var store: AppStore

    let post: SocialPost

    private var isLiked: Bool { store.likedPostIDs.contains(post.id) }
    private var isSaved: Bool { store.savedPostIDs.contains(post.id) }
    private var isFollowing: Bool { store.followedCreatorIDs.contains(post.creator.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            postHeader
                .padding(.horizontal, 15)
                .padding(.top, 15)

            Text(post.body)
                .font(.system(size: 15))
                .foregroundStyle(NSTheme.ink)
                .lineSpacing(3)
                .padding(.horizontal, 15)
                .padding(.top, 13)

            if let mediaURL = post.mediaURL,
               let mediaMimeType = post.mediaMimeType {
                RemotePostMedia(urlString: mediaURL, mimeType: mediaMimeType)
                    .padding(.horizontal, 9)
                    .padding(.top, 15)
            } else if let artwork = post.artwork,
               let title = post.artworkTitle,
               let subtitle = post.artworkSubtitle {
                MediaArtwork(
                    style: artwork,
                    title: title,
                    subtitle: subtitle,
                    isVideo: post.isVideo,
                    videoLength: post.videoLength
                )
                .padding(.horizontal, 9)
                .padding(.top, 15)
            }

            reasonButton
                .padding(.horizontal, 15)
                .padding(.top, 13)

            Divider()
                .overlay(NSTheme.border)
                .padding(.top, 13)

            postActions
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
        }
        .surfaceCard(radius: 24, shadow: false)
        .onAppear { store.beginViewing(post) }
        .onDisappear { store.endViewing(post) }
    }

    private var postHeader: some View {
        HStack(spacing: 10) {
            AvatarView(
                initials: post.creator.initials,
                colors: post.creator.colors,
                size: 43,
                showsVerified: post.creator.isVerified
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(post.creator.name)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(NSTheme.ink)
                        .lineLimit(1)

                    Text("· \(post.time)")
                        .font(.system(size: 12))
                        .foregroundStyle(NSTheme.subtleInk)
                }

                Text("\(post.creator.handle)  •  \(post.topic)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(NSTheme.mutedInk)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            if !isFollowing {
                Button("Takip") {
                    store.toggleFollow(post.creator)
                }
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(NSTheme.ink)
                .padding(.horizontal, 12)
                .frame(height: 34)
                .background(NSTheme.elevatedSurface, in: Capsule())
                .pressScale()
            }

            Menu {
                Button("Bunu neden görüyorum?", systemImage: "info.circle") {
                    store.openReason(for: post)
                }
                Button("Daha az göster", systemImage: "hand.thumbsdown") {
                    store.hide(post)
                }
                Button("Gönderiyi bildir", systemImage: "exclamationmark.bubble", role: .destructive) {
                    store.report(post)
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(NSTheme.mutedInk)
                    .frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Gönderi seçenekleri")
        }
    }

    private var reasonButton: some View {
        Button {
            store.openReason(for: post)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: "sparkles")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(NSTheme.blue)
                Text(post.reason)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(NSTheme.mutedInk)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(NSTheme.blue.opacity(0.07), in: Capsule())
        }
        .pressScale()
        .accessibilityLabel("Neden karşıma çıktı? \(post.reason)")
    }

    private var postActions: some View {
        HStack {
            actionButton(
                icon: isLiked ? "heart.fill" : "heart",
                value: post.likeCount + (isLiked ? 1 : 0),
                color: isLiked ? NSTheme.coral : NSTheme.mutedInk,
                label: isLiked ? "Beğeniyi kaldır" : "Beğen"
            ) {
                store.toggleLike(post)
            }

            Spacer()

            actionButton(
                icon: "bubble.left",
                value: post.commentCount,
                color: NSTheme.mutedInk,
                label: "Yorumlar"
            ) {}

            Spacer()

            actionButton(
                icon: "arrowshape.turn.up.right",
                value: post.shareCount,
                color: NSTheme.mutedInk,
                label: "Paylaş"
            ) {
                store.share(post)
            }

            Spacer()

            Button {
                store.toggleSave(post)
            } label: {
                Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(isSaved ? NSTheme.blue : NSTheme.mutedInk)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, value: isSaved)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .pressScale()
            .accessibilityLabel(isSaved ? "Kaydedilenlerden çıkar" : "Kaydet")
        }
    }

    private func actionButton(
        icon: String,
        value: Int,
        color: Color,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .semibold))
                    .symbolEffect(.bounce, value: isLiked && icon.contains("heart"))
                Text(compactNumber(value))
                    .font(.system(size: 11, weight: .semibold))
                    .contentTransition(.numericText())
            }
            .foregroundStyle(color)
            .frame(minWidth: 47, minHeight: 34)
            .contentShape(Rectangle())
        }
        .pressScale()
        .accessibilityLabel("\(label), \(value)")
    }

    private func compactNumber(_ value: Int) -> String {
        if value >= 1000 {
            let result = Double(value) / 1000
            return String(format: result >= 10 ? "%.0fB" : "%.1fB", result)
                .replacingOccurrences(of: ".0", with: "")
        }
        return "\(value)"
    }
}

struct RecommendationReasonSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    let post: SocialPost

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 13) {
                        NexiOrb(size: 54)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Neden karşıma çıktı?")
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundStyle(NSTheme.ink)
                            Text("Nexi kısa konuşur, gerekçeyi saklamaz.")
                                .font(.system(size: 12))
                                .foregroundStyle(NSTheme.mutedInk)
                        }
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Label(post.reason, systemImage: "sparkles")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(NSTheme.blue)

                        Text(post.reasonDetail)
                            .font(.system(size: 15))
                            .foregroundStyle(NSTheme.ink)
                            .lineSpacing(4)
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(NSTheme.blue.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Kontrol sende")
                            .font(.system(size: 17, weight: .bold))

                        controlButton(icon: "hand.thumbsdown", title: "Bu konuyu daha az göster", color: NSTheme.coral) {
                            store.hide(post)
                            dismiss()
                        }

                        controlButton(icon: "clock", title: "Bu saatte gösterme", color: NSTheme.amber) {
                            store.avoidAtCurrentTime(post)
                            dismiss()
                        }

                    }

                    Text("Saat bilgisi ve içerikte kalma süresi tek başına karar vermez; açık tercihlerin her zaman daha güçlü sinyaldir.")
                        .font(.system(size: 12))
                        .foregroundStyle(NSTheme.mutedInk)
                        .lineSpacing(3)
                        .padding(16)
                        .surfaceCard()
                }
                .padding(NSTheme.horizontalPadding)
            }
            .background(NSTheme.canvas)
            .navigationTitle("Öneri açıklaması")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Bitti") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func controlButton(icon: String, title: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(color)
                    .frame(width: 38, height: 38)
                    .background(color.opacity(0.1), in: Circle())
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(12)
            .surfaceCard()
        }
        .pressScale()
    }
}
