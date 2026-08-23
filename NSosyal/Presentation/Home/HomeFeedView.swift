import SwiftUI

struct HomeFeedView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selectedStory: SocialStory?
    @State private var feedHasAppeared = false
    @State private var showsMessages = false

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
                await store.refreshFromBackend()
                NSHaptics.notification(.success)
                store.showToast("Akışın güncellendi")
            }
        }
        .onAppear {
            feedHasAppeared = true
        }
        .fullScreenCover(item: $selectedStory) { story in
            StoryViewer(stories: store.stories, initialStory: story)
                .environmentObject(store)
        }
        .sheet(isPresented: $showsMessages) {
            MessagesInboxView()
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
                showsMessages = true
                NSHaptics.selection()
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
        case 5..<12: return "Günaydın, \(firstName)"
        case 12..<18: return "İyi günler, \(firstName)"
        default: return "İyi akşamlar, \(firstName)"
        }
    }

    private var firstName: String {
        store.currentUser?.fullName.split(separator: " ").first.map(String.init) ?? ""
    }
}

struct MessagesInboxView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if store.dataSourceMode != .backend {
                    ContentUnavailableView("Mesajlar örnek modda kapalı", systemImage: "envelope")
                } else if store.conversations.isEmpty {
                    ContentUnavailableView("Henüz mesajın yok", systemImage: "envelope.open")
                } else {
                    List(store.conversations) { conversation in
                        NavigationLink {
                            ConversationDetailView(conversation: conversation)
                                .environmentObject(store)
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(
                                    initials: conversation.other.fullName.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased(),
                                    size: 46,
                                    avatarURL: conversation.other.avatarUrl
                                )
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(conversation.other.fullName).font(.headline)
                                    Text(conversation.lastMessage?.text ?? "Sohbeti aç")
                                        .font(.subheadline)
                                        .foregroundStyle(NSTheme.mutedInk)
                                        .lineLimit(1)
                                }
                                Spacer()
                                if conversation.unreadCount > 0 {
                                    Text("\(conversation.unreadCount)")
                                        .font(.caption.bold())
                                        .foregroundStyle(.white)
                                        .padding(7)
                                        .background(NSTheme.blue, in: Circle())
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Mesajlar")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { dismiss() } } }
            .task { await store.loadConversations() }
        }
    }
}

struct ConversationDetailView: View {
    @EnvironmentObject private var store: AppStore
    @State private var draft = ""
    let conversation: APIConversation

    private var messages: [APIMessage] { store.messagesByConversation[conversation.id] ?? [] }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 9) {
                        ForEach(messages) { message in
                            HStack {
                                if message.mineByMe { Spacer(minLength: 54) }
                                Text(message.text ?? "Medya")
                                    .font(.system(size: 14))
                                    .foregroundStyle(message.mineByMe ? .white : NSTheme.ink)
                                    .padding(.horizontal, 13)
                                    .padding(.vertical, 10)
                                    .background(message.mineByMe ? NSTheme.blue : NSTheme.elevatedSurface, in: RoundedRectangle(cornerRadius: 17))
                                if !message.mineByMe { Spacer(minLength: 54) }
                            }
                            .id(message.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { _, _ in
                    if let id = messages.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                }
            }

            HStack(spacing: 10) {
                TextField("Mesaj yaz…", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .background(NSTheme.elevatedSurface, in: Capsule())
                Button {
                    Task { if await store.sendMessage(draft, conversationID: conversation.id) { draft = "" } }
                } label: {
                    Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 44, height: 44).background(NSTheme.blue, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding()
            .background(.ultraThinMaterial)
        }
        .background(NSTheme.canvas)
        .navigationTitle(conversation.other.fullName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await store.loadMessages(conversationID: conversation.id) }
    }
}
