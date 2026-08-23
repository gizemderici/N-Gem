import SwiftUI

struct PersonalizationSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var authenticationStore: AuthenticationStore
    @Environment(\.dismiss) private var dismiss

    @State private var personalizationEnabled = true
    @State private var activityNotifications = true
    @State private var recommendationNotifications = false
    @State private var showsInterestFilter = false
    @State private var showsLogoutConfirmation = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 19) {
                    learnedSummary

                    settingsSection(title: "İlgi alanların") {
                        interestFilterControl
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

                    settingsSection(title: "Hesap") {
                        Button(role: .destructive) {
                            showsLogoutConfirmation = true
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                    .frame(width: 30)
                                Text("Hesaptan çıkış yap")
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
            .navigationTitle("Tercihler ve gizlilik")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Bitti") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.large])
        .sheet(isPresented: $showsInterestFilter) {
            InterestFilterSheet(selectedInterestIDs: $store.selectedInterestIDs)
        }
        .confirmationDialog(
            "Hesaptan çıkış yapılsın mı?",
            isPresented: $showsLogoutConfirmation,
            titleVisibility: .visible
        ) {
            Button("Çıkış yap", role: .destructive) {
                Task {
                    await authenticationStore.signOut()
                    dismiss()
                }
            }
            Button("Vazgeç", role: .cancel) {}
        } message: {
            Text("İlgi tercihlerin ve yerel ayarların bu cihazda korunur.")
        }
    }

    private var learnedSummary: some View {
        HStack(spacing: 15) {
            Image(systemName: "sparkles.rectangle.stack.fill")
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(NSTheme.blue)
                .frame(width: 58, height: 58)
                .background(NSTheme.blue.opacity(0.1), in: Circle())
            VStack(alignment: .leading, spacing: 6) {
                Text("Akışın otomatik olarak güncellenir")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(NSTheme.ink)
                Text("İlgi ve etkileşim sinyalleri arka planda birlikte değerlendirilir; ayrıca akış seçmen gerekmez.")
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

    private var interestFilterControl: some View {
        Button {
            showsInterestFilter = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NSTheme.blue)
                    .frame(width: 38, height: 38)
                    .background(NSTheme.blue.opacity(0.1), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text("İlgi filtresini düzenle")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(NSTheme.ink)
                    Text("\(store.selectedInterestIDs.count) alan seçili")
                        .font(.system(size: 11))
                        .foregroundStyle(NSTheme.mutedInk)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NSTheme.subtleInk)
            }
            .padding(.vertical, 2)
        }
        .pressScale()
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
