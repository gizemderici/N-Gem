import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selectedSection = "Gönderiler"
    @State private var showsPersonalization = false
    @State private var showsEditProfile = false

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
        .sheet(isPresented: $showsEditProfile) {
            EditProfileSheet()
                .environmentObject(store)
        }
        .task { await store.refreshProfile() }
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
                    initials: profileInitials,
                    colors: [NSTheme.cyan, NSTheme.blue, NSTheme.violet],
                    size: 92,
                    showsVerified: false,
                    avatarURL: store.profile?.avatarUrl
                )
                .padding(5)
                .background(NSTheme.canvas, in: Circle())
                .offset(y: -49)
                .padding(.bottom, -49)

                VStack(spacing: 4) {
                    Text(store.profile?.fullName ?? store.currentUser?.fullName ?? "N Sosyal")
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(NSTheme.ink)
                    Text("@\(store.profile?.username ?? store.currentUser?.username ?? "kullanici")")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(NSTheme.mutedInk)
                }

                Text(store.profile?.bio?.isEmpty == false ? store.profile?.bio ?? "" : "Henüz biyografi eklenmedi.")
                    .font(.system(size: 13))
                    .foregroundStyle(NSTheme.ink)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 38)

                HStack(spacing: 8) {
                    Label("N Sosyal", systemImage: "person.2")
                    Label(profileDate, systemImage: "calendar")
                }
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(NSTheme.mutedInk)

                HStack(spacing: 9) {
                    Button("Profili düzenle") {
                        showsEditProfile = true
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
            profileStat(value: "\(store.profile?.postCount ?? store.profilePosts.count)", label: "Gönderi")
            Divider().frame(height: 30)
            profileStat(value: compactNumber(store.profile?.followerCount ?? 0), label: "Takipçi")
            Divider().frame(height: 30)
            profileStat(value: compactNumber(store.profile?.followingCount ?? 0), label: "Takip")
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
            ? store.posts.filter { store.savedPostIDs.contains($0.id) }
            : store.profilePosts

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

    private var profileInitials: String {
        (store.profile?.fullName ?? store.currentUser?.fullName ?? "NS")
            .split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
    }

    private var profileDate: String {
        guard let value = store.profile?.createdAt,
              let date = ISO8601DateFormatter().date(from: value) else { return "Yeni üye" }
        return date.formatted(.dateTime.month(.abbreviated).year())
    }

    private func compactNumber(_ value: Int) -> String {
        value >= 1_000 ? String(format: "%.1f B", Double(value) / 1_000).replacingOccurrences(of: ".0", with: "") : "\(value)"
    }
}

struct EditProfileSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var fullName = ""
    @State private var bio = ""
    @State private var isSaving = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Profil bilgileri") {
                    TextField("Ad soyad", text: $fullName)
                    TextField("Biyografi", text: $bio, axis: .vertical)
                        .lineLimit(3...6)
                }
                Section {
                    Text("Adın 2–80, biyografin en fazla 280 karakter olabilir.")
                        .font(.caption)
                        .foregroundStyle(NSTheme.mutedInk)
                }
            }
            .navigationTitle("Profili düzenle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Vazgeç") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Kaydet") {
                        Task {
                            isSaving = true
                            if await store.updateProfile(fullName: fullName, bio: bio) { dismiss() }
                            isSaving = false
                        }
                    }
                    .disabled(isSaving || fullName.trimmingCharacters(in: .whitespacesAndNewlines).count < 2 || bio.count > 280)
                }
            }
            .onAppear {
                fullName = store.profile?.fullName ?? store.currentUser?.fullName ?? ""
                bio = store.profile?.bio ?? ""
            }
        }
    }
}
