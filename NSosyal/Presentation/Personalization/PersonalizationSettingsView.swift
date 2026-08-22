import SwiftUI

struct PersonalizationSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var personalizationEnabled = true
    @State private var activityNotifications = true
    @State private var recommendationNotifications = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 19) {
                    learnedSummary

                    settingsSection(title: "İlgi alanların") {
                        interestChips
                    }

                    settingsSection(title: "Bağlamsal tercihler") {
                        contextRow(icon: "sun.max", color: NSTheme.amber, title: "Mesai saatleri", value: "Teknoloji • Eğitim")
                        Divider().overlay(NSTheme.border)
                        contextRow(icon: "moon.stars", color: NSTheme.violet, title: "Akşam", value: "Mizah • Uzun video")
                        Divider().overlay(NSTheme.border)
                        contextRow(icon: "calendar", color: NSTheme.green, title: "Hafta sonu", value: "Yerel • Kültür")
                    }

                    settingsSection(title: "Kontroller") {
                        settingToggle(title: "Akıllı kişiselleştirme", subtitle: "Uygulama içindeki davranışlarını akışı iyileştirmek için kullanır.", isOn: $personalizationEnabled)
                        Divider().overlay(NSTheme.border)
                        settingToggle(title: "Etkileşim bildirimleri", subtitle: "Yanıt, takip ve topluluk gelişmeleri.", isOn: $activityNotifications)
                        Divider().overlay(NSTheme.border)
                        settingToggle(title: "Öneri bildirimleri", subtitle: "Sana uygun yeni içerik önerileri.", isOn: $recommendationNotifications)
                    }

                    settingsSection(title: "Verilerin") {
                        dataRow(icon: "doc.text", title: "Kişiselleştirme profilini görüntüle")
                        Divider().overlay(NSTheme.border)
                        dataRow(icon: "square.and.arrow.down", title: "Verilerimi dışa aktar")
                        Divider().overlay(NSTheme.border)
                        Button(role: .destructive) {
                            store.resetLearnedProfile()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "arrow.counterclockwise")
                                    .frame(width: 30)
                                Text("Öğrenilmiş modeli sıfırla")
                                    .font(.system(size: 14, weight: .semibold))
                                Spacer()
                            }
                            .foregroundStyle(NSTheme.coral)
                            .padding(.vertical, 5)
                        }
                    }

                    Text("Kamera, özel mesaj içerikleri, kişi listesi ve kesin konum kişiselleştirme için varsayılan olarak toplanmaz.")
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.mutedInk)
                        .lineSpacing(3)
                        .padding(15)
                        .background(NSTheme.elevatedSurface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .padding(NSTheme.horizontalPadding)
            }
            .background(NSTheme.canvas)
            .navigationTitle("Akışını yönet")
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

    private var learnedSummary: some View {
        HStack(spacing: 15) {
            NexiOrb(size: 62)
            VStack(alignment: .leading, spacing: 6) {
                Text("Nexi profili %\(Int(store.learningProgress * 100)) hazır")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Text("Açık tercihlerin korunuyor. Öğrenilmiş seçenek ayrı bir akış olarak sunuluyor.")
                    .font(.system(size: 12))
                    .foregroundStyle(NSTheme.mutedInk)
                    .lineSpacing(2)
            }
        }
        .padding(17)
        .surfaceCard(radius: 22, shadow: true)
    }

    @ViewBuilder
    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(NSTheme.subtleInk)
                .padding(.leading, 4)

            VStack(spacing: 11) {
                content()
            }
            .padding(14)
            .surfaceCard()
        }
    }

    private var interestChips: some View {
        FlowLayout(spacing: 7) {
            ForEach(MockSocialData.interests.filter { store.selectedInterestIDs.contains($0.id) }) { interest in
                Label(interest.title, systemImage: interest.icon)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(interest.color.opacity(0.1), in: Capsule())
            }
        }
    }

    private func contextRow(icon: String, color: Color, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 36, height: 36)
                .background(color.opacity(0.1), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Text(value)
                    .font(.system(size: 11))
                    .foregroundStyle(NSTheme.mutedInk)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(NSTheme.subtleInk)
        }
    }

    private func settingToggle(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Text(subtitle)
                    .font(.system(size: 10))
                    .foregroundStyle(NSTheme.mutedInk)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(NSTheme.ink)
        }
    }

    private func dataRow(icon: String, title: String) -> some View {
        Button {
            store.showToast("\(title) seçildi")
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NSTheme.blue)
                    .frame(width: 30)
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(.vertical, 5)
        }
        .pressScale()
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let result = layout(proposal: proposal, subviews: subviews)
        return result.size
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let result = layout(proposal: proposal, subviews: subviews)
        for (index, point) in result.points.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y),
                proposal: .unspecified
            )
        }
    }

    private func layout(proposal: ProposedViewSize, subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        let width = proposal.width ?? 300
        var points: [CGPoint] = []
        var cursor = CGPoint.zero
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if cursor.x + size.width > width, cursor.x > 0 {
                cursor.x = 0
                cursor.y += lineHeight + spacing
                lineHeight = 0
            }
            points.append(cursor)
            cursor.x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }

        return (CGSize(width: width, height: cursor.y + lineHeight), points)
    }
}
