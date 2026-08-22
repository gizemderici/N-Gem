import SwiftUI

struct ExploreFilterSheet: View {
    @Binding var selectedTopic: String
    let topics: [String]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Keşfini daralt")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(NSTheme.ink)
                        Text("Tek seferde bir konu seç; arama sonuçların buna göre güncellensin.")
                            .font(.system(size: 13))
                            .foregroundStyle(NSTheme.mutedInk)
                            .lineSpacing(2)
                    }

                    LazyVGrid(
                        columns: [GridItem(.flexible()), GridItem(.flexible())],
                        spacing: 10
                    ) {
                        ForEach(topics, id: \.self) { topic in
                            topicButton(topic)
                        }
                    }

                    Button("Sonuçları göster") {
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, 6)
                }
                .padding(NSTheme.horizontalPadding)
            }
            .background(NSTheme.canvas)
            .navigationTitle("Filtreler")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Temizle") {
                        selectedTopic = "Tümü"
                    }
                    .font(.system(size: 13, weight: .semibold))
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func topicButton(_ topic: String) -> some View {
        let isSelected = selectedTopic == topic

        return Button {
            withAnimation(NSTheme.spring) {
                selectedTopic = topic
            }
        } label: {
            HStack(spacing: 9) {
                Image(systemName: topicIcon(topic))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(isSelected ? .white : topicColor(topic))

                Text(topic)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(isSelected ? .white : NSTheme.ink)

                Spacer(minLength: 0)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                }
            }
            .padding(.horizontal, 13)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(isSelected ? NSTheme.ink : Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(isSelected ? .clear : NSTheme.border, lineWidth: 1)
            }
        }
        .pressScale()
    }

    private func topicIcon(_ topic: String) -> String {
        switch topic {
        case "Teknoloji": "cpu"
        case "Tasarım": "paintpalette"
        case "Yerel": "mappin.and.ellipse"
        case "Mizah": "face.smiling"
        case "Eğitim": "book"
        default: "square.grid.2x2"
        }
    }

    private func topicColor(_ topic: String) -> Color {
        switch topic {
        case "Teknoloji": NSTheme.blue
        case "Tasarım": NSTheme.violet
        case "Yerel": NSTheme.green
        case "Mizah": NSTheme.coral
        case "Eğitim": NSTheme.amber
        default: NSTheme.ink
        }
    }
}

struct InterestFilterSheet: View {
    @Binding var selectedInterestIDs: [String]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("İlgi alanı filtresi")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(NSTheme.ink)
                        Text("En az üç alan seç. Seçim sıran ilk akışının önceliğini belirler.")
                            .font(.system(size: 13))
                            .foregroundStyle(NSTheme.mutedInk)
                            .lineSpacing(2)
                    }

                    VStack(spacing: 9) {
                        ForEach(MockSocialData.interests) { interest in
                            interestRow(interest)
                        }
                    }

                    Button(selectedInterestIDs.count >= 3 ? "Seçimleri uygula" : "En az 3 alan seç") {
                        guard selectedInterestIDs.count >= 3 else { return }
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle(isEnabled: selectedInterestIDs.count >= 3))
                    .disabled(selectedInterestIDs.count < 3)
                }
                .padding(NSTheme.horizontalPadding)
            }
            .background(NSTheme.canvas)
            .navigationTitle("Filtreler")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Text("\(selectedInterestIDs.count) seçili")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(NSTheme.blue)
                }
            }
        }
        .interactiveDismissDisabled(selectedInterestIDs.count < 3 && !selectedInterestIDs.isEmpty)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func interestRow(_ interest: Interest) -> some View {
        let index = selectedInterestIDs.firstIndex(of: interest.id)

        return Button {
            withAnimation(NSTheme.spring) {
                if let index {
                    selectedInterestIDs.remove(at: index)
                } else {
                    selectedInterestIDs.append(interest.id)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: interest.icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(interest.color)
                    .frame(width: 42, height: 42)
                    .background(interest.color.opacity(0.1), in: Circle())

                Text(interest.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)

                Spacer()

                if let index {
                    Text("\(index + 1)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 26, height: 26)
                        .background(interest.color, in: Circle())
                } else {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(NSTheme.subtleInk)
                        .frame(width: 26, height: 26)
                        .background(NSTheme.elevatedSurface, in: Circle())
                }
            }
            .padding(12)
            .surfaceCard(radius: 18)
        }
        .pressScale()
    }
}
