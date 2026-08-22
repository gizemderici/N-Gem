import SwiftUI

struct MainTabView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottom) {
            currentScreen
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)

            bottomNavigation
                .padding(.horizontal, 12)
                .padding(.bottom, 7)

            if let toast = store.toastMessage {
                ToastView(message: toast)
                    .padding(.bottom, 84)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(5)
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.12) : NSTheme.spring, value: store.selectedTab)
        .animation(.easeInOut(duration: 0.2), value: store.toastMessage)
        .sheet(item: $store.selectedReasonPost) { post in
            RecommendationReasonSheet(post: post)
                .environmentObject(store)
        }
    }

    @ViewBuilder
    private var currentScreen: some View {
        switch store.selectedTab {
        case .home:
            HomeFeedView()
        case .explore:
            ExploreView()
        case .create:
            CreatePostView()
        case .notifications:
            NotificationsView()
        case .profile:
            ProfileView()
        }
    }

    private var bottomNavigation: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases) { tab in
                Button {
                    withAnimation(reduceMotion ? nil : NSTheme.spring) {
                        store.selectedTab = tab
                    }
                } label: {
                    if tab == .create {
                        Image(systemName: "plus")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 48, height: 48)
                            .background(NSTheme.ink, in: Circle())
                            .shadow(color: .black.opacity(0.16), radius: 12, y: 5)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel(tab.title)
                    } else {
                        VStack(spacing: 4) {
                            ZStack(alignment: .topTrailing) {
                                Image(systemName: store.selectedTab == tab ? tab.selectedIcon : tab.icon)
                                    .font(.system(size: 17, weight: .semibold))
                                    .symbolEffect(.bounce, value: store.selectedTab == tab)

                                if tab == .notifications {
                                    Circle()
                                        .fill(NSTheme.coral)
                                        .frame(width: 7, height: 7)
                                        .offset(x: 4, y: -2)
                                }
                            }

                            Text(tab.title)
                                .font(.system(size: 9, weight: .semibold))
                        }
                        .foregroundStyle(store.selectedTab == tab ? NSTheme.ink : NSTheme.subtleInk)
                        .frame(maxWidth: .infinity)
                        .frame(height: 49)
                        .contentShape(Rectangle())
                        .accessibilityLabel(tab.title)
                        .accessibilityAddTraits(store.selectedTab == tab ? .isSelected : [])
                    }
                }
                .pressScale()
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 5)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay { Capsule().stroke(Color.white.opacity(0.72), lineWidth: 1) }
        .shadow(color: .black.opacity(0.12), radius: 24, y: 9)
    }
}
