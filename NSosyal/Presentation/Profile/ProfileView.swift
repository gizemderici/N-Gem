import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selectedSection = "Gönderiler"
    @State private var showsPersonalization = false

    private let sections = ["Gönderiler", "Medya", "Kaydedilenler"]

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 17) {
                    profileHero

                    personalizationCard
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    statsRow
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    sectionPicker
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    profileGrid
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    Color.clear.frame(height: 110)
                }
            }
            .scrollIndicators(.hidden)
        }
        .sheet(isPresented: $showsPersonalization) {
            PersonalizationSettingsView()
                .environmentObject(store)
        }
    }

    private var profileHero: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                LinearGradient(
                    colors: [Color(red: 0.04, green: 0.08, blue: 0.16), NSTheme.blue, NSTheme.violet],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .frame(height: 165)

                Circle()
                    .stroke(.white.opacity(0.16), lineWidth: 1)
                    .frame(width: 180, height: 180)
                    .offset(x: 70, y: -75)

                HStack(spacing: 8) {
                    GlassIconButton(systemName: "square.and.arrow.up", accessibilityLabel: "Profili paylaş") {
                        store.showToast("Profil bağlantısı hazır")
                    }
                    GlassIconButton(systemName: "gearshape", accessibilityLabel: "Ayarlar") {
                        showsPersonalization = true
                    }
                }
                .padding(.top, 10)
                .padding(.trailing, NSTheme.horizontalPadding)
            }

            VStack(spacing: 13) {
                AvatarView(
                    initials: "FD",
                    colors: [NSTheme.cyan, NSTheme.blue, NSTheme.violet],
                    size: 92,
                    showsVerified: true
                )
                .padding(5)
                .background(NSTheme.canvas, in: Circle())
                .offset(y: -49)
                .padding(.bottom, -49)

                VStack(spacing: 4) {
                    Text("Furkan Durmaz")
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(NSTheme.ink)
                    Text("@furkandurmaz")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(NSTheme.mutedInk)
                }

                Text("Dijital ürünler, sade deneyimler ve Türkiye’den çıkan iyi fikirler üzerine düşünüyorum.")
                    .font(.system(size: 13))
                    .foregroundStyle(NSTheme.ink)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 38)

                HStack(spacing: 8) {
                    Label("İstanbul", systemImage: "mappin")
                    Label("Ağu 2026", systemImage: "calendar")
                }
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(NSTheme.mutedInk)

                HStack(spacing: 9) {
                    Button("Profili düzenle") {
                        store.showToast("Profil düzenleme yakında")
                    }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: 42)
                    .background(Color.white, in: Capsule())
                    .overlay { Capsule().stroke(NSTheme.strongBorder, lineWidth: 1) }

                    Button {
                        store.showToast("Başlangıç paketin hazır")
                    } label: {
                        Image(systemName: "person.2.badge.plus")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 46, height: 42)
                            .background(NSTheme.ink, in: Capsule())
                    }
                    .accessibilityLabel("Arkadaşlarını davet et")
                }
                .pressScale()
                .padding(.horizontal, NSTheme.horizontalPadding)
            }
            .padding(.bottom, 4)
        }
    }

    private var personalizationCard: some View {
        Button {
            showsPersonalization = true
        } label: {
            HStack(spacing: 13) {
                Image(systemName: "hand.raised.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(NSTheme.blue)
                    .frame(width: 50, height: 50)
                    .background(NSTheme.blue.opacity(0.1), in: Circle())

                VStack(alignment: .leading, spacing: 5) {
                    Text("Gizlilik ve tercihler")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(NSTheme.ink)
                    Text("İlgi alanlarını, bildirimleri ve veri kontrollerini yönet.")
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.mutedInk)
                        .multilineTextAlignment(.leading)
                }

                Spacer(minLength: 0)

                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(15)
            .surfaceCard(radius: 22, shadow: true)
        }
        .pressScale()
    }

    private var statsRow: some View {
        HStack(spacing: 0) {
            profileStat(value: "42", label: "Gönderi")
            Divider().frame(height: 30)
            profileStat(value: "12,8 B", label: "Takipçi")
            Divider().frame(height: 30)
            profileStat(value: "684", label: "Takip")
        }
        .padding(.vertical, 14)
        .surfaceCard()
    }

    private func profileStat(value: String, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(NSTheme.ink)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(NSTheme.mutedInk)
        }
        .frame(maxWidth: .infinity)
    }

    private var sectionPicker: some View {
        HStack(spacing: 4) {
            ForEach(sections, id: \.self) { section in
                Button {
                    withAnimation(NSTheme.spring) {
                        selectedSection = section
                    }
                } label: {
                    Text(section)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(selectedSection == section ? .white : NSTheme.mutedInk)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(selectedSection == section ? NSTheme.ink : .clear, in: Capsule())
                }
                .pressScale()
            }
        }
        .padding(4)
        .background(NSTheme.elevatedSurface, in: Capsule())
    }

    private var profileGrid: some View {
        let posts = selectedSection == "Kaydedilenler"
            ? MockSocialData.posts.filter { store.savedPostIDs.contains($0.id) }
            : MockSocialData.posts

        return Group {
            if posts.isEmpty {
                EmptyStateCard(
                    icon: "bookmark",
                    title: "Henüz kayıt yok",
                    message: "Kaydettiğin içerikler burada görünecek."
                )
            } else {
                LazyVGrid(
                    columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8), GridItem(.flexible())],
                    spacing: 8
                ) {
                    ForEach(posts) { post in
                        profileTile(post)
                    }
                }
            }
        }
    }

    private func profileTile(_ post: SocialPost) -> some View {
        Button {
            store.openReason(for: post)
        } label: {
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
                ZStack {
                    LinearGradient(colors: post.creator.colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: "quote.opening")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(.white.opacity(0.85))
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .pressScale()
    }
}
