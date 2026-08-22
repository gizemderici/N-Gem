import SwiftUI

struct HomeFeedView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selectedStory: SocialStory?
    @State private var feedHasAppeared = false

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 14) {
                    header
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 8)

                    StoryStrip(selectedStory: $selectedStory)
                        .environmentObject(store)

                    Divider()
                        .overlay(NSTheme.border)
                        .padding(.horizontal, NSTheme.horizontalPadding)

                    ForEach(Array(store.visiblePosts.enumerated()), id: \.element.id) { index, post in
                        PostCard(post: post)
                            .padding(.horizontal, 10)
                            .opacity(feedHasAppeared ? 1 : 0)
                            .offset(y: feedHasAppeared ? 0 : 18)
                            .animation(
                                NSTheme.gentleSpring.delay(Double(index) * 0.055),
                                value: feedHasAppeared
                            )
                            .scrollTransition(.animated(.smooth)) { content, phase in
                                content
                                    .opacity(phase.isIdentity ? 1 : 0.82)
                                    .scaleEffect(phase.isIdentity ? 1 : 0.97)
                            }
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
                NSHaptics.notification(.success)
                store.showToast("Akışın güncellendi")
            }
        }
        .onAppear {
            feedHasAppeared = true
        }
        .fullScreenCover(item: $selectedStory) { story in
            StoryViewer(stories: MockSocialData.stories, initialStory: story)
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

    private var endOfFeedCard: some View {
        VStack(spacing: 9) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(NSTheme.green)
                .symbolEffect(.breathe, options: .repeating.speed(0.5))
            Text("Şimdilik hepsi bu")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(NSTheme.ink)
            Text("Yeni içerikler geldiğinde akışın arka planda sana göre güncellenecek.")
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
}
