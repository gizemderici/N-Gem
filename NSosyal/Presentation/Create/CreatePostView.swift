import SwiftUI

struct CreatePostView: View {
    @EnvironmentObject private var store: AppStore
    @FocusState private var isEditorFocused: Bool

    @State private var text = ""
    @State private var selectedAudience = "Herkes"
    @State private var selectedFormat = "Gönderi"
    @State private var allowsReplies = true

    private let formats = [
        ("Gönderi", "text.alignleft"),
        ("Fotoğraf", "photo"),
        ("Kısa video", "play.rectangle"),
        ("Uzun video", "rectangle.stack.badge.play")
    ]

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header

                    formatPicker

                    composerCard

                    VStack(spacing: 10) {
                        settingRow(
                            icon: "person.2",
                            color: NSTheme.blue,
                            title: "Kimler görebilir?",
                            value: selectedAudience
                        ) {
                            selectedAudience = selectedAudience == "Herkes" ? "Takipçilerim" : "Herkes"
                        }

                        Button {
                            allowsReplies.toggle()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "bubble.left")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(NSTheme.violet)
                                    .frame(width: 40, height: 40)
                                    .background(NSTheme.violet.opacity(0.1), in: Circle())
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("Yanıtlar")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(NSTheme.ink)
                                    Text(allowsReplies ? "Herkes yanıtlayabilir" : "Yanıtlar kapalı")
                                        .font(.system(size: 11))
                                        .foregroundStyle(NSTheme.mutedInk)
                                }
                                Spacer()
                                Toggle("", isOn: $allowsReplies)
                                    .labelsHidden()
                                    .tint(NSTheme.ink)
                            }
                            .padding(13)
                            .surfaceCard()
                        }
                        .buttonStyle(.plain)
                    }

                    Button("Yayınla") {
                        store.publish(text: text)
                        text = ""
                    }
                    .buttonStyle(PrimaryButtonStyle(isEnabled: !trimmedText.isEmpty))
                    .disabled(trimmedText.isEmpty)

                    Text("Gönderin yayınlanmadan önce topluluk kuralları ve güvenlik kontrollerinden geçer.")
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.subtleInk)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)

                    Color.clear.frame(height: 110)
                }
                .padding(.horizontal, NSTheme.horizontalPadding)
                .padding(.top, 8)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear {
            Task {
                try? await Task.sleep(for: .milliseconds(250))
                isEditorFocused = true
            }
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("Oluştur")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                Text("Fikrini sade ve doğrudan paylaş.")
                    .font(.system(size: 12))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer()
            Button("Taslaklar") {
                store.showToast("Henüz taslağın yok")
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(NSTheme.blue)
        }
    }

    private var formatPicker: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(formats, id: \.0) { format in
                    Button {
                        withAnimation(NSTheme.spring) {
                            selectedFormat = format.0
                        }
                    } label: {
                        HStack(spacing: 7) {
                            Image(systemName: format.1)
                            Text(format.0)
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(selectedFormat == format.0 ? .white : NSTheme.ink)
                        .padding(.horizontal, 13)
                        .frame(height: 38)
                        .background(
                            selectedFormat == format.0 ? NSTheme.ink : Color.white,
                            in: Capsule()
                        )
                        .overlay {
                            Capsule().stroke(selectedFormat == format.0 ? .clear : NSTheme.border, lineWidth: 1)
                        }
                    }
                    .pressScale()
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    private var composerCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                AvatarView(initials: "FD", colors: [NSTheme.blue, NSTheme.violet], size: 42, showsVerified: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Furkan Durmaz")
                        .font(.system(size: 14, weight: .bold))
                    Text("@furkandurmaz")
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.mutedInk)
                }
                Spacer()
                Text(selectedFormat)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NSTheme.blue)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(NSTheme.blue.opacity(0.08), in: Capsule())
            }

            TextEditor(text: $text)
                .font(.system(size: 18))
                .foregroundStyle(NSTheme.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: selectedFormat == "Gönderi" ? 145 : 100)
                .focused($isEditorFocused)
                .overlay(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Bugün ne paylaşmak istiyorsun?")
                            .font(.system(size: 18))
                            .foregroundStyle(NSTheme.subtleInk)
                            .padding(.top, 8)
                            .padding(.leading, 5)
                            .allowsHitTesting(false)
                    }
                }

            if selectedFormat != "Gönderi" {
                Button {
                    store.showToast("Medya seçici yakında bağlanacak")
                } label: {
                    VStack(spacing: 10) {
                        Image(systemName: selectedFormat.contains("video") ? "play.rectangle" : "photo.badge.plus")
                            .font(.system(size: 26, weight: .semibold))
                            .foregroundStyle(NSTheme.blue)
                        Text("\(selectedFormat) ekle")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(NSTheme.ink)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 128)
                    .background(NSTheme.blue.opacity(0.055), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(NSTheme.blue.opacity(0.24), style: StrokeStyle(lineWidth: 1, dash: [5]))
                    }
                }
                .pressScale()
            }

            HStack {
                HStack(spacing: 8) {
                    mediaTool(icon: "photo")
                    mediaTool(icon: "camera")
                    mediaTool(icon: "number")
                    mediaTool(icon: "chart.bar")
                }
                Spacer()
                Text("\(text.count)/500")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(text.count > 450 ? NSTheme.coral : NSTheme.subtleInk)
            }
        }
        .padding(16)
        .surfaceCard(radius: 24, shadow: true)
    }

    private func mediaTool(icon: String) -> some View {
        Button {
            store.showToast("Bu araç yakında hazır")
        } label: {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(NSTheme.mutedInk)
                .frame(width: 36, height: 36)
                .background(NSTheme.elevatedSurface, in: Circle())
        }
        .pressScale()
        .accessibilityLabel("İçerik aracı")
    }

    private func settingRow(
        icon: String,
        color: Color,
        title: String,
        value: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(color)
                    .frame(width: 40, height: 40)
                    .background(color.opacity(0.1), in: Circle())
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Spacer()
                Text(value)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(NSTheme.mutedInk)
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(13)
            .surfaceCard()
        }
        .pressScale()
    }

    private var trimmedText: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
