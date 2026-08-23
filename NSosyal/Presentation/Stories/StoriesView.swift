import SwiftUI

struct StoryStrip: View {
    @EnvironmentObject private var store: AppStore
    @Binding var selectedStory: SocialStory?
    @State private var hasAppeared = false

    private var stories: [SocialStory] { store.stories }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(Array(stories.enumerated()), id: \.element.id) { index, story in
                    storyButton(story)
                        .opacity(hasAppeared ? 1 : 0)
                        .scaleEffect(hasAppeared ? 1 : 0.74)
                        .offset(y: hasAppeared ? 0 : 8)
                        .animation(
                            NSTheme.bouncySpring.delay(Double(index) * 0.055),
                            value: hasAppeared
                        )
                }
            }
            .padding(.horizontal, NSTheme.horizontalPadding)
            .padding(.vertical, 4)
        }
        .scrollIndicators(.hidden)
        .onAppear {
            hasAppeared = true
        }
    }

    private func storyButton(_ story: SocialStory) -> some View {
        Button {
            if story.isOwn {
                store.showToast("Yeni hikâye oluşturma ekranı yakında")
                NSHaptics.impact(.medium)
            } else {
                selectedStory = story
            }
        } label: {
            VStack(spacing: 7) {
                ZStack(alignment: .bottomTrailing) {
                    Circle()
                        .fill(
                            story.isSeen
                                ? AnyShapeStyle(NSTheme.elevatedSurface)
                                : AnyShapeStyle(NSTheme.brandGradient)
                        )
                        .frame(width: 70, height: 70)

                    AvatarView(
                        initials: story.creator.initials,
                        colors: story.creator.colors,
                        size: 61,
                        showsVerified: false,
                        avatarURL: story.creator.avatarURL
                    )
                    .overlay {
                        Circle().stroke(NSTheme.canvas, lineWidth: 3)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)

                    if story.isOwn {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 22, height: 22)
                            .background(NSTheme.blue, in: Circle())
                            .overlay { Circle().stroke(NSTheme.canvas, lineWidth: 2) }
                            .offset(x: 1, y: 1)
                    }
                }
                .frame(width: 70, height: 70)

                Text(story.isOwn ? "Hikâyen" : firstName(story.creator.name))
                    .font(.system(size: 10, weight: story.isSeen ? .medium : .semibold))
                    .foregroundStyle(story.isSeen ? NSTheme.mutedInk : NSTheme.ink)
                    .lineLimit(1)
                    .frame(width: 72)
            }
        }
        .pressScale()
        .accessibilityLabel(story.isOwn ? "Hikâye ekle" : "\(story.creator.name) hikâyesi")
    }

    private func firstName(_ name: String) -> String {
        name.split(separator: " ").first.map(String.init) ?? name
    }
}

struct StoryViewer: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let stories: [SocialStory]

    @State private var currentIndex: Int
    @State private var progress: Double = 0
    @State private var reply = ""
    @State private var isPaused = false

    init(stories: [SocialStory], initialStory: SocialStory) {
        self.stories = stories.filter { !$0.isOwn }
        let index = self.stories.firstIndex(where: { $0.id == initialStory.id }) ?? 0
        _currentIndex = State(initialValue: index)
    }

    private var story: SocialStory {
        stories[currentIndex]
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: story.style.colors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            storyArtwork

            tapNavigationLayer

            VStack(spacing: 0) {
                progressBars
                    .padding(.top, 8)

                storyHeader
                    .padding(.top, 12)

                Spacer()

                storyCaption
                replyBar
                    .padding(.top, 18)
                    .padding(.bottom, 10)
            }
            .padding(.horizontal, 14)
        }
        .statusBarHidden()
        .onLongPressGesture(minimumDuration: 0.15, pressing: { isPressing in
            isPaused = isPressing
        }, perform: {})
        .task(id: currentIndex) {
            store.markStoryViewed(story)
            await runProgress()
        }
        .sensoryFeedback(.selection, trigger: currentIndex)
    }

    private var storyArtwork: some View {
        GeometryReader { proxy in
            ZStack {
                Circle()
                    .stroke(.white.opacity(0.14), lineWidth: 1)
                    .frame(width: proxy.size.width * 1.05)
                    .offset(x: proxy.size.width * 0.34, y: -proxy.size.height * 0.26)

                Circle()
                    .fill(.white.opacity(0.08))
                    .frame(width: proxy.size.width * 0.72)
                    .blur(radius: 2)
                    .offset(x: proxy.size.width * 0.38, y: -proxy.size.height * 0.12)

                Image(systemName: story.style.symbol)
                    .font(.system(size: 74, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.19))
                    .rotationEffect(.degrees(-9))
                    .offset(x: -proxy.size.width * 0.23, y: -proxy.size.height * 0.08)

                if let mediaURL = story.mediaURL, let mediaMimeType = story.mediaMimeType {
                    RemotePostMedia(urlString: mediaURL, mimeType: mediaMimeType)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 100)
                }
            }
            .animation(reduceMotion ? nil : NSTheme.gentleSpring, value: currentIndex)
        }
        .accessibilityHidden(true)
    }

    private var tapNavigationLayer: some View {
        HStack(spacing: 0) {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { previousStory() }

            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { nextStory() }
        }
        .padding(.top, 110)
        .padding(.bottom, 170)
    }

    private var progressBars: some View {
        HStack(spacing: 5) {
            ForEach(stories.indices, id: \.self) { index in
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.28))
                        Capsule()
                            .fill(.white)
                            .frame(width: progressWidth(for: index, totalWidth: proxy.size.width))
                    }
                }
                .frame(height: 3)
            }
        }
    }

    private var storyHeader: some View {
        HStack(spacing: 10) {
            AvatarView(
                initials: story.creator.initials,
                colors: story.creator.colors,
                size: 39,
                showsVerified: story.creator.isVerified,
                avatarURL: story.creator.avatarURL
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(story.creator.name)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                Text("\(story.time) · \(story.creator.handle)")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.72))
            }

            Spacer()

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.black.opacity(0.2), in: Circle())
            }
            .pressScale()
            .accessibilityLabel("Hikâyeyi kapat")
        }
    }

    private var storyCaption: some View {
        VStack(alignment: .leading, spacing: 9) {
            Image(systemName: story.style.symbol)
                .font(.system(size: 25, weight: .semibold))
                .foregroundStyle(.white.opacity(0.86))

            Text(story.headline)
                .font(.system(size: 32, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .tracking(-0.7)

            Text(story.detail)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.white.opacity(0.78))
                .lineSpacing(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .id(story.id)
        .transition(.opacity.combined(with: .move(edge: .bottom)))
    }

    private var replyBar: some View {
        HStack(spacing: 10) {
            TextField("Yanıt gönder...", text: $reply)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white)
                .tint(.white)
                .padding(.horizontal, 15)
                .frame(height: 46)
                .background(.black.opacity(0.2), in: Capsule())
                .overlay { Capsule().stroke(.white.opacity(0.42), lineWidth: 1) }

            Button {
                NSHaptics.notification(.success)
            } label: {
                Image(systemName: "heart")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(.black.opacity(0.2), in: Circle())
                    .overlay { Circle().stroke(.white.opacity(0.42), lineWidth: 1) }
            }
            .pressScale()
            .accessibilityLabel("Hikâyeyi beğen")
        }
    }

    private func progressWidth(for index: Int, totalWidth: CGFloat) -> CGFloat {
        if index < currentIndex { return totalWidth }
        if index > currentIndex { return 0 }
        return totalWidth * progress
    }

    private func runProgress() async {
        progress = 0
        while progress < 1 {
            if Task.isCancelled { return }
            if !isPaused {
                withAnimation(.linear(duration: 0.09)) {
                    progress = min(progress + 0.02, 1)
                }
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        if !Task.isCancelled {
            nextStory()
        }
    }

    private func previousStory() {
        guard currentIndex > 0 else {
            progress = 0
            return
        }
        withAnimation(reduceMotion ? nil : NSTheme.gentleSpring) {
            currentIndex -= 1
        }
    }

    private func nextStory() {
        guard currentIndex < stories.count - 1 else {
            dismiss()
            return
        }
        withAnimation(reduceMotion ? nil : NSTheme.gentleSpring) {
            currentIndex += 1
        }
    }
}
