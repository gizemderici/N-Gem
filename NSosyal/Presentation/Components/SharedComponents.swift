import AVKit
import SwiftUI

struct RemotePostMedia: View {
    let urlString: String
    let mimeType: String

    @State private var player: AVPlayer?

    private var url: URL? { URL(string: urlString) }
    private var isVideo: Bool { mimeType.hasPrefix("video/") }

    var body: some View {
        Group {
            if isVideo {
                video
            } else if let url {
                AsyncImage(url: url, transaction: Transaction(animation: .easeInOut(duration: 0.25))) { phase in
                    switch phase {
                    case let .success(image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        mediaFallback(icon: "photo.badge.exclamationmark", text: "Görsel açılamadı")
                    default:
                        ZStack {
                            Color.black.opacity(0.04)
                            ProgressView().tint(NSTheme.blue)
                        }
                    }
                }
            } else {
                mediaFallback(icon: "link.badge.plus", text: "Medya adresi geçersiz")
            }
        }
        .aspectRatio(4 / 5, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .background(NSTheme.elevatedSurface)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(NSTheme.border, lineWidth: 1)
        }
        .clipped()
        .accessibilityLabel(isVideo ? "Video gönderisi" : "Görsel gönderisi")
    }

    @ViewBuilder
    private var video: some View {
        if let url {
            ZStack {
                Color.black
                VideoPlayer(player: player)
            }
            .onAppear {
                guard player == nil else { return }
                let newPlayer = AVPlayer(url: url)
                newPlayer.isMuted = true
                player = newPlayer
                newPlayer.play()
            }
            .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notification in
                guard notification.object as AnyObject? === player?.currentItem else { return }
                player?.seek(to: .zero)
                player?.play()
            }
            .onDisappear {
                player?.pause()
                player = nil
            }
        } else {
            mediaFallback(icon: "video.badge.exclamationmark", text: "Video açılamadı")
        }
    }

    private func mediaFallback(icon: String, text: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 28, weight: .semibold))
            Text(text)
                .font(.system(size: 12, weight: .semibold))
        }
        .foregroundStyle(NSTheme.mutedInk)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NSTheme.elevatedSurface)
    }
}

struct MediaArtwork: View {
    let style: ArtworkStyle
    let title: String
    let subtitle: String
    var isVideo = false
    var videoLength: String? = nil
    var compact = false

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(
                colors: style.colors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            decorativeLayer

            VStack(alignment: .leading, spacing: compact ? 4 : 8) {
                if !compact {
                    Image(systemName: style.symbol)
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.92))
                        .padding(.bottom, 16)
                }

                Text(title)
                    .font(.system(size: compact ? 16 : 25, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .lineLimit(compact ? 2 : 3)

                Text(subtitle)
                    .font(.system(size: compact ? 11 : 13, weight: .medium))
                    .foregroundStyle(.white.opacity(0.76))
                    .lineLimit(compact ? 1 : 2)
            }
            .padding(compact ? 13 : 20)

            if isVideo {
                HStack(spacing: 6) {
                    Image(systemName: "play.fill")
                    if let videoLength {
                        Text(videoLength)
                    }
                }
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(.black.opacity(0.42), in: Capsule())
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(12)
            }
        }
        .aspectRatio(compact ? 1 : 1.18, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: compact ? 16 : 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: compact ? 16 : 22, style: .continuous)
                .stroke(.white.opacity(0.12), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }

    private var decorativeLayer: some View {
        GeometryReader { proxy in
            ZStack {
                Circle()
                    .stroke(.white.opacity(0.12), lineWidth: 1)
                    .frame(width: proxy.size.width * 0.72)
                    .offset(x: proxy.size.width * 0.34, y: -proxy.size.height * 0.24)

                Circle()
                    .fill(.white.opacity(0.08))
                    .frame(width: proxy.size.width * 0.46)
                    .blur(radius: 1)
                    .offset(x: proxy.size.width * 0.38, y: -proxy.size.height * 0.1)

                ForEach(0..<4, id: \.self) { index in
                    Circle()
                        .fill(.white.opacity(0.52))
                        .frame(width: CGFloat(3 + index), height: CGFloat(3 + index))
                        .offset(
                            x: proxy.size.width * (0.16 + CGFloat(index) * 0.17),
                            y: proxy.size.height * (-0.31 + CGFloat(index % 2) * 0.13)
                        )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct ToastView: View {
    let message: String

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(NSTheme.green)
            Text(message)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(NSTheme.ink.opacity(0.94), in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 18, y: 9)
        .padding(.horizontal, 24)
        .accessibilityElement(children: .combine)
    }
}

struct SectionHeader: View {
    let title: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(NSTheme.ink)
            Spacer()
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(NSTheme.blue)
            }
        }
    }
}

struct EmptyStateCard: View {
    let icon: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(NSTheme.blue)
            Text(title)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(NSTheme.ink)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(NSTheme.mutedInk)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .surfaceCard()
    }
}
