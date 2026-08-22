import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var step = 0
    @State private var selectedInterestIDs: [String] = []

    let onComplete: () -> Void

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()

            decorativeBackground

            VStack(spacing: 0) {
                onboardingHeader

                Group {
                    switch step {
                    case 0:
                        welcomeStep
                    case 1:
                        interestStep
                    default:
                        previewStep
                    }
                }
                .id(step)
                .transition(
                    reduceMotion
                        ? .opacity
                        : .asymmetric(
                            insertion: .move(edge: .trailing).combined(with: .opacity),
                            removal: .move(edge: .leading).combined(with: .opacity)
                        )
                )
            }
        }
        .preferredColorScheme(.light)
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : NSTheme.spring, value: step)
    }

    private var decorativeBackground: some View {
        GeometryReader { proxy in
            Circle()
                .fill(NSTheme.cyan.opacity(0.11))
                .frame(width: proxy.size.width * 0.86)
                .blur(radius: 18)
                .offset(x: proxy.size.width * 0.54, y: -proxy.size.height * 0.11)

            Circle()
                .fill(NSTheme.violet.opacity(0.08))
                .frame(width: proxy.size.width * 0.74)
                .blur(radius: 22)
                .offset(x: -proxy.size.width * 0.42, y: proxy.size.height * 0.72)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private var onboardingHeader: some View {
        HStack(spacing: 12) {
            BrandMark(size: 38)

            Text("NSosyal")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(NSTheme.ink)

            Spacer()

            HStack(spacing: 6) {
                ForEach(0..<3, id: \.self) { index in
                    Capsule()
                        .fill(index <= step ? NSTheme.ink : NSTheme.border)
                        .frame(width: index == step ? 22 : 7, height: 7)
                }
            }
        }
        .padding(.horizontal, NSTheme.horizontalPadding)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private var welcomeStep: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 18)

            ZStack {
                Circle()
                    .stroke(NSTheme.blue.opacity(0.12), lineWidth: 1)
                    .frame(width: 210, height: 210)
                Circle()
                    .stroke(NSTheme.violet.opacity(0.12), lineWidth: 1)
                    .frame(width: 154, height: 154)
                NexiOrb(size: 92)
            }
            .padding(.bottom, 34)

            VStack(spacing: 13) {
                Text("Akışın sana göre\nşekillensin.")
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                    .multilineTextAlignment(.center)
                    .tracking(-1.2)

                Text("İlgi alanlarını sen seç. Nexi yalnızca uygulama içindeki tercihlerini ölçülü biçimde öğrensin.")
                    .font(.system(size: 16, weight: .regular))
                    .foregroundStyle(NSTheme.mutedInk)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 25)
            }

            Spacer(minLength: 30)

            HStack(spacing: 8) {
                promisePill(icon: "slider.horizontal.3", text: "Kontrol")
                promisePill(icon: "eye", text: "Şeffaflık")
                promisePill(icon: "sparkles", text: "Keşif")
            }
            .padding(.bottom, 24)

            Button("Akışımı oluştur") {
                step = 1
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, NSTheme.horizontalPadding)

            Text("Tercihlerini istediğin zaman değiştirebilirsin.")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(NSTheme.subtleInk)
                .padding(.top, 12)
                .padding(.bottom, 10)
        }
    }

    private var interestStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Neler ilgini çekiyor?")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)

                Text("En az üç alan seç. Seçim sıran ilk akışının önceliğini belirler.")
                    .font(.system(size: 14))
                    .foregroundStyle(NSTheme.mutedInk)
                    .lineSpacing(2)
            }
            .padding(.horizontal, NSTheme.horizontalPadding)
            .padding(.top, 20)
            .padding(.bottom, 18)

            ScrollView {
                LazyVGrid(
                    columns: [GridItem(.flexible()), GridItem(.flexible())],
                    spacing: 11
                ) {
                    ForEach(MockSocialData.interests) { interest in
                        interestButton(interest)
                    }
                }
                .padding(.horizontal, NSTheme.horizontalPadding)
                .padding(.bottom, 18)

                if !selectedInterestIDs.isEmpty {
                    selectedOrderCard
                        .padding(.horizontal, NSTheme.horizontalPadding)
                        .padding(.bottom, 20)
                }
            }
            .scrollIndicators(.hidden)

            Button(selectedInterestIDs.count >= 3 ? "Akış önizlemesini gör" : "\(3 - selectedInterestIDs.count) seçim daha yap") {
                guard selectedInterestIDs.count >= 3 else { return }
                step = 2
            }
            .buttonStyle(PrimaryButtonStyle(isEnabled: selectedInterestIDs.count >= 3))
            .disabled(selectedInterestIDs.count < 3)
            .padding(.horizontal, NSTheme.horizontalPadding)
            .padding(.vertical, 12)
        }
    }

    private var previewStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("İlk akışın hazır.")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .foregroundStyle(NSTheme.ink)

                    Text("Bu denge başlangıç varsayımıdır. Sen değiştirmedikçe ilk tercihlerin korunur.")
                        .font(.system(size: 14))
                        .foregroundStyle(NSTheme.mutedInk)
                        .lineSpacing(2)
                }

                distributionCard

                VStack(spacing: 10) {
                    previewPromise(icon: "eye.fill", color: NSTheme.cyan, title: "Neden gösterildiğini gör", detail: "Her önerinin kısa ve anlaşılır bir nedeni olacak.")
                    previewPromise(icon: "arrow.uturn.backward", color: NSTheme.blue, title: "İstediğin an geri dön", detail: "Benim Akışım her zaman korunacak.")
                    previewPromise(icon: "trash.slash", color: NSTheme.violet, title: "Öğrenileni sen yönet", detail: "Duraklat, düzelt veya tamamen sıfırla.")
                }

                Button("NSosyal’e başla") {
                    let interests = selectedInterestIDs.isEmpty
                        ? ["technology", "design", "education"]
                        : selectedInterestIDs
                    store.completeOnboarding(with: interests)
                    onComplete()
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.horizontal, NSTheme.horizontalPadding)
            .padding(.top, 22)
            .padding(.bottom, 20)
        }
        .scrollIndicators(.hidden)
    }

    private func promisePill(icon: String, text: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
            Text(text)
        }
        .font(.system(size: 11, weight: .semibold))
        .foregroundStyle(NSTheme.ink)
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .overlay { Capsule().stroke(NSTheme.border, lineWidth: 1) }
    }

    private func interestButton(_ interest: Interest) -> some View {
        let selectedIndex = selectedInterestIDs.firstIndex(of: interest.id)

        return Button {
            withAnimation(NSTheme.spring) {
                if let selectedIndex {
                    selectedInterestIDs.remove(at: selectedIndex)
                } else {
                    selectedInterestIDs.append(interest.id)
                }
            }
        } label: {
            HStack(spacing: 11) {
                Image(systemName: interest.icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(selectedIndex == nil ? interest.color : .white)
                    .frame(width: 38, height: 38)
                    .background(
                        selectedIndex == nil
                            ? AnyShapeStyle(interest.color.opacity(0.11))
                            : AnyShapeStyle(interest.color),
                        in: Circle()
                    )

                Text(interest.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)

                Spacer(minLength: 0)

                if let selectedIndex {
                    Text("\(selectedIndex + 1)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 24, height: 24)
                        .background(NSTheme.ink, in: Circle())
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 17, style: .continuous)
                    .stroke(selectedIndex == nil ? NSTheme.border : interest.color.opacity(0.58), lineWidth: selectedIndex == nil ? 1 : 1.5)
            }
        }
        .pressScale()
    }

    private var selectedOrderCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Öncelik sıran")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(NSTheme.ink)

            HStack(spacing: 7) {
                ForEach(Array(selectedInterestIDs.prefix(5).enumerated()), id: \.element) { index, id in
                    if let interest = MockSocialData.interests.first(where: { $0.id == id }) {
                        HStack(spacing: 5) {
                            Text("\(index + 1)")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 18, height: 18)
                                .background(interest.color, in: Circle())
                            Text(interest.title)
                                .font(.system(size: 11, weight: .semibold))
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 7)
                        .background(NSTheme.elevatedSurface, in: Capsule())
                    }
                }
            }
        }
        .padding(15)
        .surfaceCard()
    }

    private var distributionCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Görünür akış dengesi")
                        .font(.system(size: 17, weight: .bold))
                    Text("İlk gün için önerilen dağılım")
                        .font(.system(size: 12))
                        .foregroundStyle(NSTheme.mutedInk)
                }
                Spacer()
                NexiOrb(size: 42)
            }

            distributionRow(title: "Ana ilgi alanların", value: 0.70, color: NSTheme.cyan)
            distributionRow(title: "İlgili kategoriler", value: 0.20, color: NSTheme.blue)
            distributionRow(title: "Keşif ve çeşitlilik", value: 0.10, color: NSTheme.violet)
        }
        .padding(18)
        .surfaceCard(radius: NSTheme.largeCornerRadius, shadow: true)
    }

    private func distributionRow(title: String, value: Double, color: Color) -> some View {
        VStack(spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
                Text("%\(Int(value * 100))")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(color)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(NSTheme.elevatedSurface)
                    Capsule()
                        .fill(color)
                        .frame(width: proxy.size.width * value)
                }
            }
            .frame(height: 9)
        }
    }

    private func previewPromise(icon: String, color: Color, title: String, detail: String) -> some View {
        HStack(spacing: 13) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 42, height: 42)
                .background(color.opacity(0.1), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .surfaceCard()
    }
}
