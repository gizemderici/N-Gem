import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selection = "Tümü"

    private let segments = ["Tümü", "Yanıtlar", "Topluluklar"]

    private var visibleNotifications: [SocialNotification] {
        switch selection {
        case "Yanıtlar": store.notifications.filter { $0.kind == .replied }
        case "Topluluklar": store.notifications.filter { $0.kind == .community }
        default: store.notifications
        }
    }

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 12) {
                    header
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 8)

                    segmentControl
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.bottom, 4)

                    if visibleNotifications.isEmpty {
                        EmptyStateCard(
                            icon: "bell.slash",
                            title: "Burada yenilik yok",
                            message: "Yeni etkileşimler olduğunda bu bölümde göreceksin."
                        )
                        .padding(.horizontal, NSTheme.horizontalPadding)
                    } else {
                        ForEach(visibleNotifications) { notification in
                            notificationRow(notification)
                                .padding(.horizontal, NSTheme.horizontalPadding)
                        }
                    }

                    preferenceCard
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.top, 8)

                    Color.clear.frame(height: 110)
                }
            }
            .scrollIndicators(.hidden)
        }
        .task { await store.loadNotifications() }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("Bildirimler")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                Text("Yalnızca önem verdiğin gelişmeler.")
                    .font(.system(size: 12))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer()
            GlassIconButton(systemName: "checkmark", accessibilityLabel: "Tümünü okundu işaretle") {
                Task { await store.markAllNotificationsRead() }
            }
        }
    }

    private var segmentControl: some View {
        HStack(spacing: 4) {
            ForEach(segments, id: \.self) { segment in
                Button {
                    withAnimation(NSTheme.spring) {
                        selection = segment
                    }
                } label: {
                    Text(segment)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(selection == segment ? .white : NSTheme.mutedInk)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(selection == segment ? NSTheme.ink : .clear, in: Capsule())
                }
                .pressScale()
            }
        }
        .padding(4)
        .background(NSTheme.elevatedSurface, in: Capsule())
        .overlay { Capsule().stroke(NSTheme.border, lineWidth: 1) }
    }

    private func notificationRow(_ notification: SocialNotification) -> some View {
        Button {
            store.showToast("Bildirim açıldı")
        } label: {
            HStack(alignment: .top, spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    AvatarView(
                        initials: notification.creator.initials,
                        colors: notification.creator.colors,
                        size: 46,
                        showsVerified: notification.creator.isVerified,
                        avatarURL: notification.creator.avatarURL
                    )

                    Image(systemName: notification.kind.icon)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 20, height: 20)
                        .background(notification.kind.color, in: Circle())
                        .overlay { Circle().stroke(Color.white, lineWidth: 2) }
                        .offset(x: 3, y: 3)
                }

                VStack(alignment: .leading, spacing: 5) {
                    Text(attributedMessage(notification))
                        .font(.system(size: 13))
                        .foregroundStyle(NSTheme.ink)
                        .multilineTextAlignment(.leading)
                        .lineSpacing(2)

                    Text(notification.time)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(NSTheme.subtleInk)
                }

                Spacer(minLength: 4)

                if notification.isUnread {
                    Circle()
                        .fill(NSTheme.blue)
                        .frame(width: 8, height: 8)
                        .padding(.top, 5)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                notification.isUnread ? NSTheme.blue.opacity(0.035) : Color.white,
                in: RoundedRectangle(cornerRadius: 19, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 19, style: .continuous)
                    .stroke(notification.isUnread ? NSTheme.blue.opacity(0.16) : NSTheme.border, lineWidth: 1)
            }
        }
        .pressScale()
    }

    private var preferenceCard: some View {
        HStack(spacing: 13) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(NSTheme.blue)
                .frame(width: 44, height: 44)
                .background(NSTheme.blue.opacity(0.1), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text("Bildirim kontrolü sende")
                    .font(.system(size: 14, weight: .bold))
                Text("Etkileşim, topluluk ve öneri bildirimlerini ayrı ayrı yönet.")
                    .font(.system(size: 11))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer(minLength: 2)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(NSTheme.subtleInk)
        }
        .padding(15)
        .surfaceCard()
    }

    private func attributedMessage(_ notification: SocialNotification) -> AttributedString {
        var name = AttributedString(notification.creator.name + " ")
        name.font = .system(size: 13, weight: .bold)
        var message = AttributedString(notification.message)
        message.font = .system(size: 13)
        return name + message
    }
}
