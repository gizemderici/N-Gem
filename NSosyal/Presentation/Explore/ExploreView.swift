import SwiftUI

struct ExploreView: View {
    @EnvironmentObject private var store: AppStore
    @State private var query = ""
    @State private var selectedTopic = "Tümü"
    @State private var showsFilters = false

    private let topics = ["Tümü", "Teknoloji", "Tasarım", "Yerel", "Mizah", "Eğitim"]

    private var filteredPosts: [SocialPost] {
        MockSocialData.posts.filter { post in
            let topicMatches = selectedTopic == "Tümü" || post.topic == selectedTopic
            let queryMatches = query.isEmpty
                || post.body.localizedCaseInsensitiveContains(query)
                || post.creator.name.localizedCaseInsensitiveContains(query)
                || post.topic.localizedCaseInsensitiveContains(query)
            return topicMatches && queryMatches
        }
    }

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 19) {
                    header
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 8)

                    searchAndFilterBar
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    if query.isEmpty && selectedTopic == "Tümü" {
                        trendingHero
                            .padding(.horizontal, NSTheme.horizontalPadding)

                        communityStrip
                    }

                    HStack {
                        Text(query.isEmpty ? "Senin için keşif" : "Arama sonuçları")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(NSTheme.ink)
                        Spacer()
                        Text("\(filteredPosts.count) içerik")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(NSTheme.mutedInk)
                    }
                    .padding(.horizontal, NSTheme.horizontalPadding)

                    if filteredPosts.isEmpty {
                        EmptyStateCard(
                            icon: "magnifyingglass",
                            title: "Sonuç bulunamadı",
                            message: "Farklı bir kelime veya konu deneyebilirsin."
                        )
                        .padding(.horizontal, NSTheme.horizontalPadding)
                    } else {
                        LazyVGrid(
                            columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())],
                            spacing: 10
                        ) {
                            ForEach(filteredPosts) { post in
                                exploreTile(post)
                            }
                        }
                        .padding(.horizontal, NSTheme.horizontalPadding)
                    }

                    Color.clear.frame(height: 104)
                }
            }
            .scrollIndicators(.hidden)
        }
        .sheet(isPresented: $showsFilters) {
            ExploreFilterSheet(selectedTopic: $selectedTopic, topics: topics)
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("Keşfet")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                Text("Balonuna sıkışmadan yeni insanlarla tanış.")
                    .font(.system(size: 12))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer()
            GlassIconButton(systemName: "qrcode.viewfinder", accessibilityLabel: "Topluluk kodunu tara") {
                store.showToast("Topluluk kodu tarayıcısı yakında")
            }
        }
    }

    private var searchAndFilterBar: some View {
        HStack(spacing: 9) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NSTheme.mutedInk)

                TextField("Kişi, konu veya topluluk ara", text: $query)
                    .font(.system(size: 14))
                    .textInputAutocapitalization(.never)

                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(NSTheme.subtleInk)
                    }
                    .accessibilityLabel("Aramayı temizle")
                }
            }
            .padding(.horizontal, 15)
            .frame(height: 50)
            .background(Color.white, in: Capsule())
            .overlay { Capsule().stroke(NSTheme.border, lineWidth: 1) }

            Button {
                showsFilters = true
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(selectedTopic == "Tümü" ? NSTheme.ink : .white)
                        .frame(width: 50, height: 50)
                        .background(selectedTopic == "Tümü" ? Color.white : NSTheme.ink, in: Circle())
                        .overlay { Circle().stroke(NSTheme.border, lineWidth: 1) }

                    if selectedTopic != "Tümü" {
                        Circle()
                            .fill(NSTheme.coral)
                            .frame(width: 10, height: 10)
                            .overlay { Circle().stroke(Color.white, lineWidth: 2) }
                            .offset(x: 1, y: 1)
                    }
                }
            }
            .pressScale()
            .accessibilityLabel("Keşfet filtreleri, \(selectedTopic)")
        }
    }

    private var trendingHero: some View {
        Button {
            selectedTopic = "Teknoloji"
        } label: {
            ZStack(alignment: .bottomLeading) {
                NSTheme.darkGradient

                Circle()
                    .stroke(NSTheme.cyan.opacity(0.36), lineWidth: 1)
                    .frame(width: 180, height: 180)
                    .offset(x: 245, y: -80)

                HStack(alignment: .bottom, spacing: 16) {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("GÜNÜN KONUSU")
                            .font(.system(size: 10, weight: .bold))
                            .tracking(1.2)
                            .foregroundStyle(NSTheme.cyan)

                        Text("Yapay zekâyı\nkim yönetecek?")
                            .font(.system(size: 27, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.leading)

                        Text("1,8 B gönderi • 12 topluluk")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.65))
                    }

                    Spacer()

                    NexiOrb(size: 62)
                        .padding(.bottom, 2)
                }
                .padding(20)
            }
            .frame(height: 205)
            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
        .pressScale()
    }

    private var communityStrip: some View {
        VStack(alignment: .leading, spacing: 11) {
            SectionHeader(title: "Canlı topluluklar", actionTitle: "Tümü") {
                store.showToast("Topluluklar yakında")
            }
            .padding(.horizontal, NSTheme.horizontalPadding)

            ScrollView(.horizontal) {
                HStack(spacing: 10) {
                    communityCard(title: "Türk Tasarımcılar", detail: "12,4 B üye", icon: "paintpalette", color: NSTheme.violet)
                    communityCard(title: "Bağımsız Üreticiler", detail: "8,1 B üye", icon: "video", color: NSTheme.coral)
                    communityCard(title: "İstanbul Etkinlik", detail: "21 B üye", icon: "mappin", color: NSTheme.green)
                }
                .padding(.horizontal, NSTheme.horizontalPadding)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func communityCard(title: String, detail: String, icon: String, color: Color) -> some View {
        Button {
            store.showToast("\(title) topluluğuna katıldın")
        } label: {
            HStack(spacing: 11) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(color)
                    .frame(width: 42, height: 42)
                    .background(color.opacity(0.1), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(NSTheme.ink)
                        .lineLimit(1)
                    Text(detail)
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.mutedInk)
                }
            }
            .padding(12)
            .frame(width: 205, alignment: .leading)
            .surfaceCard()
        }
        .pressScale()
    }

    private func exploreTile(_ post: SocialPost) -> some View {
        Button {
            store.openReason(for: post)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                if let artwork = post.artwork,
                   let title = post.artworkTitle,
                   let subtitle = post.artworkSubtitle {
                    MediaArtwork(
                        style: artwork,
                        title: title,
                        subtitle: subtitle,
                        isVideo: post.isVideo,
                        videoLength: post.videoLength,
                        compact: true
                    )
                } else {
                    ZStack(alignment: .bottomLeading) {
                        LinearGradient(
                            colors: post.creator.colors,
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                        Text("“\(post.body)”")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .lineLimit(5)
                            .padding(13)
                    }
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                HStack(spacing: 6) {
                    AvatarView(initials: post.creator.initials, colors: post.creator.colors, size: 25)
                    Text(post.creator.handle)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(NSTheme.mutedInk)
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "heart.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(NSTheme.subtleInk)
                    Text("\(post.likeCount)")
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(NSTheme.subtleInk)
                }
            }
        }
        .pressScale()
    }
}
