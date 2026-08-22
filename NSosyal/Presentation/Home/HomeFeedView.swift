import SwiftUI

struct HomeFeedView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showsLearningDetails = false

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 14) {
                    header
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 8)

                    FeedSegmentedControl()
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    VStack(alignment: .leading, spacing: 9) {
                        HStack {
                            Text("Şu an ne istiyorsun?")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(NSTheme.mutedInk)
                            Spacer()
                            if store.intentMode == .automatic {
                                Text("Öneri: \(store.recommendedIntent.title)")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(store.recommendedIntent.color)
                            }
                        }
                        .padding(.horizontal, NSTheme.horizontalPadding)

                        IntentModeStrip()
                    }

                    learningCard
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    ForEach(store.visiblePosts) { post in
                        PostCard(post: post)
                            .padding(.horizontal, 10)
                    }

                    endOfFeedCard
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 4)
                        .padding(.bottom, 110)
                }
                .padding(.top, 2)
            }
            .scrollIndicators(.hidden)
            .refreshable {
                try? await Task.sleep(for: .milliseconds(650))
                store.showToast("Akışın güncellendi")
            }
        }
        .sheet(isPresented: $showsLearningDetails) {
            LearningStatusSheet()
                .environmentObject(store)
        }
    }

    private var header: some View {
        HStack(spacing: 11) {
            BrandMark(size: 40)

            VStack(alignment: .leading, spacing: 1) {
                Text("NSosyal")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                Text(greetingText)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(NSTheme.mutedInk)
            }

            Spacer()

            GlassIconButton(
                systemName: "envelope",
                accessibilityLabel: "Mesajlar"
            ) {
                store.showToast("Mesajlar yakında burada")
            }
        }
    }

    private var learningCard: some View {
        Button {
            showsLearningDetails = true
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(NSTheme.border, lineWidth: 5)
                    Circle()
                        .trim(from: 0, to: store.learningProgress)
                        .stroke(
                            NSTheme.brandGradient,
                            style: StrokeStyle(lineWidth: 5, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                    NexiOrb(size: 37)
                }
                .frame(width: 58, height: 58)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(store.feedVariant == .nexi ? "Nexi Akışı etkin" : "Nexi seni tanıyor")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(NSTheme.ink)
                        Text("%\(Int(store.learningProgress * 100))")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(NSTheme.blue)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 4)
                            .background(NSTheme.blue.opacity(0.09), in: Capsule())
                    }

                    Text(learningSubtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(NSTheme.mutedInk)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }

                Spacer(minLength: 2)

                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(15)
            .frame(maxWidth: .infinity, alignment: .leading)
            .surfaceCard(radius: 22, shadow: true)
        }
        .pressScale()
        .accessibilityLabel("Nexi öğrenme durumu yüzde \(Int(store.learningProgress * 100))")
    }

    private var endOfFeedCard: some View {
        VStack(spacing: 9) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(NSTheme.green)
            Text("Şimdilik hepsi bu")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(NSTheme.ink)
            Text("Sonsuz kaydırma yerine yeni içerikler geldiğinde sana haber vereceğiz.")
                .font(.system(size: 12))
                .foregroundStyle(NSTheme.mutedInk)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .surfaceCard()
    }

    private var greetingText: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12: return "Günaydın, Furkan"
        case 12..<18: return "İyi günler, Furkan"
        default: return "İyi akşamlar, Furkan"
        }
    }

    private var learningSubtitle: String {
        if store.feedVariant == .nexi {
            return "Öneriler öğrenilmiş tercihlerine göre sıralanıyor. Her nedeni görebilirsin."
        }
        return "İlk tercihlerini koruyorum; öğrenilmiş akış ayrı bir seçenek olarak hazır."
    }
}

private struct LearningStatusSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    VStack(spacing: 11) {
                        NexiOrb(size: 78)
                        Text("Öğrenme durumun")
                            .font(.system(size: 25, weight: .bold, design: .rounded))
                        Text("Nexi tek bir harekete değil, tekrar eden ve açıkça düzeltebildiğin örüntülere bakar.")
                            .font(.system(size: 14))
                            .foregroundStyle(NSTheme.mutedInk)
                            .multilineTextAlignment(.center)
                            .lineSpacing(3)
                    }

                    VStack(spacing: 12) {
                        statusRow(icon: "heart", color: NSTheme.coral, title: "Açık tercihlerin", value: "Güçlü sinyal")
                        statusRow(icon: "play.rectangle", color: NSTheme.blue, title: "İçerik tamamlama", value: "Bağlamla yorumlanır")
                        statusRow(icon: "clock", color: NSTheme.amber, title: "Günün saati", value: "Düşük ağırlık")
                        statusRow(icon: "arrow.triangle.2.circlepath", color: NSTheme.violet, title: "Keşif payı", value: "%10")
                    }

                    Button(store.feedVariant == .nexi ? "Benim Akışım’a dön" : "Nexi Akışı’nı dene") {
                        store.selectFeed(store.feedVariant == .nexi ? .mine : .nexi)
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())

                    Button("Öğrenmeyi sıfırla", role: .destructive) {
                        store.resetLearnedProfile()
                        dismiss()
                    }
                    .font(.system(size: 14, weight: .semibold))
                }
                .padding(NSTheme.horizontalPadding)
            }
            .background(NSTheme.canvas)
            .navigationTitle("Nexi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Bitti") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.large])
    }

    private func statusRow(icon: String, color: Color, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 40, height: 40)
                .background(color.opacity(0.1), in: Circle())
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(NSTheme.ink)
            Spacer()
            Text(value)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(NSTheme.mutedInk)
        }
        .padding(13)
        .surfaceCard()
    }
}
