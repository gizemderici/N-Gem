import SwiftUI
import UIKit

private enum AuthenticationScreen: Hashable {
    case welcome
    case signIn
    case signUp
    case forgotPassword
    case verification
    case resetPassword
}

private enum VerificationPurpose: Equatable {
    case newAccount
    case passwordReset
}

struct AuthenticationView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var screen: AuthenticationScreen = .welcome
    @State private var verificationPurpose: VerificationPurpose = .newAccount
    @State private var fullName = ""
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var repeatedPassword = ""
    @State private var verificationCode = ""
    @State private var acceptsTerms = false
    @State private var errorMessage: String?
    @State private var infoMessage: String?

    let onAuthenticated: () -> Void

    var body: some View {
        ZStack {
            NSTheme.canvas.ignoresSafeArea()
            decorativeBackground

            screenContent
                .id(screen)
                .transition(
                    reduceMotion
                        ? .opacity
                        : .asymmetric(
                            insertion: .move(edge: .trailing).combined(with: .opacity),
                            removal: .move(edge: .leading).combined(with: .opacity)
                        )
                )
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : NSTheme.gentleSpring, value: screen)
        .preferredColorScheme(.light)
    }

    @ViewBuilder
    private var screenContent: some View {
        switch screen {
        case .welcome:
            welcomeScreen
        case .signIn:
            signInScreen
        case .signUp:
            signUpScreen
        case .forgotPassword:
            forgotPasswordScreen
        case .verification:
            verificationScreen
        case .resetPassword:
            resetPasswordScreen
        }
    }

    private var decorativeBackground: some View {
        GeometryReader { proxy in
            Circle()
                .fill(NSTheme.cyan.opacity(0.1))
                .frame(width: proxy.size.width * 0.9)
                .blur(radius: 24)
                .offset(x: proxy.size.width * 0.54, y: -proxy.size.height * 0.14)

            Circle()
                .fill(NSTheme.violet.opacity(0.075))
                .frame(width: proxy.size.width * 0.78)
                .blur(radius: 28)
                .offset(x: -proxy.size.width * 0.45, y: proxy.size.height * 0.76)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private var welcomeScreen: some View {
        VStack(spacing: 0) {
            brandHeader(showsBackButton: false)
                .padding(.horizontal, NSTheme.horizontalPadding)

            Spacer(minLength: 18)

            ZStack {
                Circle()
                    .stroke(NSTheme.blue.opacity(0.12), lineWidth: 1)
                    .frame(width: 222, height: 222)
                Circle()
                    .stroke(NSTheme.violet.opacity(0.13), lineWidth: 1)
                    .frame(width: 164, height: 164)
                BrandMark(size: 96)
            }
            .padding(.bottom, 34)

            VStack(spacing: 13) {
                Text("Sana ait bir sosyal alan.")
                    .font(.system(size: 37, weight: .bold, design: .rounded))
                    .foregroundStyle(NSTheme.ink)
                    .multilineTextAlignment(.center)
                    .tracking(-1.1)

                Text("Gündemi takip et, topluluğunu bul ve akışının kontrolünü elinde tut.")
                    .font(.system(size: 16))
                    .foregroundStyle(NSTheme.mutedInk)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 22)
            }

            Spacer(minLength: 28)

            VStack(spacing: 11) {
                Button("Hesap oluştur") {
                    go(to: .signUp)
                }
                .buttonStyle(PrimaryButtonStyle())

                Button {
                    go(to: .signIn)
                } label: {
                    Text("Giriş yap")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(NSTheme.ink)
                        .frame(maxWidth: .infinity)
                        .frame(height: NSTheme.controlHeight)
                        .background(Color.white, in: Capsule())
                        .overlay { Capsule().stroke(NSTheme.strongBorder, lineWidth: 1) }
                }
                .pressScale()

                dividerLabel("veya")

                Button {
                    onAuthenticated()
                } label: {
                    HStack(spacing: 9) {
                        Image(systemName: "apple.logo")
                            .font(.system(size: 18, weight: .semibold))
                        Text("Apple ile devam et")
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundStyle(NSTheme.ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(.regularMaterial, in: Capsule())
                    .overlay { Capsule().stroke(NSTheme.border, lineWidth: 1) }
                }
                .pressScale()
            }
            .padding(.horizontal, NSTheme.horizontalPadding)

            Text("Devam ederek Kullanım Koşulları’nı ve Gizlilik Politikasını kabul etmiş olursun.")
                .font(.system(size: 10))
                .foregroundStyle(NSTheme.subtleInk)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .padding(.horizontal, 34)
                .padding(.top, 15)
                .padding(.bottom, 12)
        }
    }

    private var signInScreen: some View {
        authScrollContainer {
            brandHeader(showsBackButton: true)

            authTitle(
                eyebrow: "TEKRAR HOŞ GELDİN",
                title: "Hesabına giriş yap.",
                subtitle: "Topluluğun ve sana göre şekillenen akışın seni bekliyor."
            )

            VStack(spacing: 13) {
                AuthenticationField(
                    title: "E-posta",
                    placeholder: "ornek@eposta.com",
                    icon: "envelope",
                    text: $email,
                    contentType: .emailAddress,
                    keyboardType: .emailAddress
                )

                AuthenticationField(
                    title: "Şifre",
                    placeholder: "Şifren",
                    icon: "lock",
                    text: $password,
                    isSecure: true,
                    contentType: .password
                )

                HStack {
                    Spacer()
                    Button("Şifremi unuttum") {
                        go(to: .forgotPassword)
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(NSTheme.blue)
                }
            }

            feedbackMessage

            Button("Giriş yap", action: submitSignIn)
                .buttonStyle(PrimaryButtonStyle())

            dividerLabel("veya")

            appleButton

            authSwitchPrompt(text: "Henüz hesabın yok mu?", actionTitle: "Hesap oluştur") {
                go(to: .signUp)
            }
        }
    }

    private var signUpScreen: some View {
        authScrollContainer {
            brandHeader(showsBackButton: true)

            authTitle(
                eyebrow: "NSOSYAL’E KATIL",
                title: "Kendine ait alanı oluştur.",
                subtitle: "Gerçek insanlarla, ilgi duyduğun konular etrafında buluş."
            )

            VStack(spacing: 13) {
                AuthenticationField(
                    title: "Ad soyad",
                    placeholder: "Adın ve soyadın",
                    icon: "person",
                    text: $fullName,
                    contentType: .name,
                    capitalization: .words
                )

                AuthenticationField(
                    title: "Kullanıcı adı",
                    placeholder: "kullaniciadi",
                    icon: "at",
                    text: $username,
                    contentType: .username
                )

                AuthenticationField(
                    title: "E-posta",
                    placeholder: "ornek@eposta.com",
                    icon: "envelope",
                    text: $email,
                    contentType: .emailAddress,
                    keyboardType: .emailAddress
                )

                AuthenticationField(
                    title: "Şifre",
                    placeholder: "En az 8 karakter",
                    icon: "lock",
                    text: $password,
                    isSecure: true,
                    contentType: .newPassword
                )

                passwordStrength

                Button {
                    acceptsTerms.toggle()
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: acceptsTerms ? "checkmark.square.fill" : "square")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(acceptsTerms ? NSTheme.blue : NSTheme.subtleInk)

                        Text("Kullanım Koşulları’nı ve Gizlilik Politikasını okudum, kabul ediyorum.")
                            .font(.system(size: 11))
                            .foregroundStyle(NSTheme.mutedInk)
                            .multilineTextAlignment(.leading)

                        Spacer(minLength: 0)
                    }
                }
                .pressScale()
            }

            feedbackMessage

            Button("Hesap oluştur", action: submitSignUp)
                .buttonStyle(PrimaryButtonStyle())

            authSwitchPrompt(text: "Zaten hesabın var mı?", actionTitle: "Giriş yap") {
                go(to: .signIn)
            }
        }
    }

    private var forgotPasswordScreen: some View {
        authScrollContainer {
            brandHeader(showsBackButton: true)

            statusIllustration(icon: "key.horizontal", color: NSTheme.amber)

            authTitle(
                eyebrow: "HESAP KURTARMA",
                title: "Şifreni yenileyelim.",
                subtitle: "Hesabına bağlı e-posta adresini yaz; sana altı haneli bir doğrulama kodu gönderelim."
            )

            AuthenticationField(
                title: "E-posta",
                placeholder: "ornek@eposta.com",
                icon: "envelope",
                text: $email,
                contentType: .emailAddress,
                keyboardType: .emailAddress
            )

            feedbackMessage

            Button("Doğrulama kodu gönder", action: submitForgotPassword)
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private var verificationScreen: some View {
        authScrollContainer {
            brandHeader(showsBackButton: true)

            statusIllustration(icon: "checkmark.shield", color: NSTheme.green)

            authTitle(
                eyebrow: "GÜVENLİK KONTROLÜ",
                title: "E-postanı doğrula.",
                subtitle: "\(maskedEmail) adresine gönderdiğimiz altı haneli kodu gir."
            )

            VerificationCodeField(code: $verificationCode)

            feedbackMessage

            Button("Kodu doğrula", action: submitVerification)
                .buttonStyle(PrimaryButtonStyle(isEnabled: verificationCode.count == 6))
                .disabled(verificationCode.count != 6)

            HStack(spacing: 5) {
                Text("Kod gelmedi mi?")
                    .foregroundStyle(NSTheme.mutedInk)
                Button("Tekrar gönder") {
                    infoMessage = "Yeni doğrulama kodu gönderildi."
                    errorMessage = nil
                }
                .foregroundStyle(NSTheme.blue)
            }
            .font(.system(size: 12, weight: .semibold))
            .frame(maxWidth: .infinity)
        }
    }

    private var resetPasswordScreen: some View {
        authScrollContainer {
            brandHeader(showsBackButton: true)

            statusIllustration(icon: "lock.rotation", color: NSTheme.violet)

            authTitle(
                eyebrow: "YENİ ŞİFRE",
                title: "Güçlü bir şifre belirle.",
                subtitle: "Daha önce kullanmadığın, en az sekiz karakterli bir şifre seç."
            )

            VStack(spacing: 13) {
                AuthenticationField(
                    title: "Yeni şifre",
                    placeholder: "En az 8 karakter",
                    icon: "lock",
                    text: $password,
                    isSecure: true,
                    contentType: .newPassword
                )

                AuthenticationField(
                    title: "Yeni şifre tekrar",
                    placeholder: "Şifreni tekrar yaz",
                    icon: "lock.fill",
                    text: $repeatedPassword,
                    isSecure: true,
                    contentType: .newPassword
                )

                passwordStrength
            }

            feedbackMessage

            Button("Şifreyi yenile", action: submitResetPassword)
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private func authScrollContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 21) {
                content()
            }
            .padding(.horizontal, NSTheme.horizontalPadding)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }

    private func brandHeader(showsBackButton: Bool) -> some View {
        HStack(spacing: 11) {
            if showsBackButton {
                Button {
                    navigateBack()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(NSTheme.ink)
                        .frame(width: 40, height: 40)
                        .background(.regularMaterial, in: Circle())
                        .overlay { Circle().stroke(NSTheme.border, lineWidth: 1) }
                }
                .pressScale()
                .accessibilityLabel("Geri")
            } else {
                BrandMark(size: 40)
            }

            if showsBackButton {
                BrandMark(size: 32)
            }

            Text("NSosyal")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(NSTheme.ink)

            Spacer()

            if showsBackButton {
                Text("Güvenli giriş")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(NSTheme.green)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(NSTheme.green.opacity(0.09), in: Capsule())
            }
        }
        .padding(.top, 10)
    }

    private func authTitle(eyebrow: String, title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(eyebrow)
                .font(.system(size: 10, weight: .bold))
                .tracking(1.1)
                .foregroundStyle(NSTheme.blue)

            Text(title)
                .font(.system(size: 31, weight: .bold, design: .rounded))
                .tracking(-0.6)
                .foregroundStyle(NSTheme.ink)

            Text(subtitle)
                .font(.system(size: 14))
                .foregroundStyle(NSTheme.mutedInk)
                .lineSpacing(3)
        }
    }

    private func statusIllustration(icon: String, color: Color) -> some View {
        Image(systemName: icon)
            .font(.system(size: 25, weight: .semibold))
            .foregroundStyle(color)
            .frame(width: 74, height: 74)
            .background(color.opacity(0.1), in: Circle())
            .overlay { Circle().stroke(color.opacity(0.16), lineWidth: 1) }
            .padding(.top, 8)
    }

    @ViewBuilder
    private var feedbackMessage: some View {
        if let errorMessage {
            FeedbackBanner(message: errorMessage, isError: true)
        } else if let infoMessage {
            FeedbackBanner(message: infoMessage, isError: false)
        }
    }

    private var passwordStrength: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { index in
                    Capsule()
                        .fill(index < passwordStrengthLevel ? passwordStrengthColor : NSTheme.border)
                        .frame(height: 5)
                }
            }

            Text(passwordStrengthLabel)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(passwordStrengthColor)
        }
    }

    private var passwordStrengthLevel: Int {
        guard !password.isEmpty else { return 0 }
        var level = password.count >= 8 ? 1 : 0
        if password.rangeOfCharacter(from: .uppercaseLetters) != nil,
           password.rangeOfCharacter(from: .decimalDigits) != nil {
            level += 1
        }
        if password.rangeOfCharacter(from: CharacterSet.punctuationCharacters) != nil {
            level += 1
        }
        return min(level, 3)
    }

    private var passwordStrengthLabel: String {
        switch passwordStrengthLevel {
        case 0: "En az 8 karakter kullan"
        case 1: "Şifre gücü: Orta"
        case 2: "Şifre gücü: İyi"
        default: "Şifre gücü: Güçlü"
        }
    }

    private var passwordStrengthColor: Color {
        switch passwordStrengthLevel {
        case 0: NSTheme.subtleInk
        case 1: NSTheme.amber
        case 2: NSTheme.blue
        default: NSTheme.green
        }
    }

    private var appleButton: some View {
        Button {
            onAuthenticated()
        } label: {
            HStack(spacing: 9) {
                Image(systemName: "apple.logo")
                    .font(.system(size: 18, weight: .semibold))
                Text("Apple ile devam et")
                    .font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(NSTheme.ink)
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Color.white, in: Capsule())
            .overlay { Capsule().stroke(NSTheme.strongBorder, lineWidth: 1) }
        }
        .pressScale()
    }

    private func dividerLabel(_ text: String) -> some View {
        HStack(spacing: 11) {
            Rectangle().fill(NSTheme.border).frame(height: 1)
            Text(text)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(NSTheme.subtleInk)
            Rectangle().fill(NSTheme.border).frame(height: 1)
        }
    }

    private func authSwitchPrompt(text: String, actionTitle: String, action: @escaping () -> Void) -> some View {
        HStack(spacing: 5) {
            Text(text)
                .foregroundStyle(NSTheme.mutedInk)
            Button(actionTitle, action: action)
                .foregroundStyle(NSTheme.blue)
        }
        .font(.system(size: 12, weight: .semibold))
        .frame(maxWidth: .infinity)
    }

    private var maskedEmail: String {
        let parts = email.split(separator: "@", maxSplits: 1).map(String.init)
        guard parts.count == 2, let first = parts[0].first else { return "e-posta adresine" }
        return "\(first)•••@\(parts[1])"
    }

    private func submitSignIn() {
        clearFeedback()
        guard isValidEmail(email) else {
            errorMessage = "Geçerli bir e-posta adresi yazmalısın."
            return
        }
        guard password.count >= 6 else {
            errorMessage = "Şifren en az 6 karakter olmalı."
            return
        }
        onAuthenticated()
    }

    private func submitSignUp() {
        clearFeedback()
        guard fullName.trimmingCharacters(in: .whitespacesAndNewlines).count >= 3 else {
            errorMessage = "Adını ve soyadını eksiksiz yazmalısın."
            return
        }
        guard username.count >= 3, !username.contains(" ") else {
            errorMessage = "Boşluk içermeyen geçerli bir kullanıcı adı seçmelisin."
            return
        }
        guard isValidEmail(email) else {
            errorMessage = "Geçerli bir e-posta adresi yazmalısın."
            return
        }
        guard password.count >= 8 else {
            errorMessage = "Şifren en az 8 karakter olmalı."
            return
        }
        guard acceptsTerms else {
            errorMessage = "Devam etmek için koşulları kabul etmelisin."
            return
        }

        verificationPurpose = .newAccount
        verificationCode = ""
        go(to: .verification)
    }

    private func submitForgotPassword() {
        clearFeedback()
        guard isValidEmail(email) else {
            errorMessage = "Hesabına bağlı geçerli e-posta adresini yazmalısın."
            return
        }
        verificationPurpose = .passwordReset
        verificationCode = ""
        go(to: .verification)
    }

    private func submitVerification() {
        clearFeedback()
        guard verificationCode.count == 6 else {
            errorMessage = "Altı haneli doğrulama kodunu eksiksiz yazmalısın."
            return
        }

        if verificationPurpose == .newAccount {
            onAuthenticated()
        } else {
            password = ""
            repeatedPassword = ""
            go(to: .resetPassword)
        }
    }

    private func submitResetPassword() {
        clearFeedback()
        guard password.count >= 8 else {
            errorMessage = "Yeni şifren en az 8 karakter olmalı."
            return
        }
        guard password == repeatedPassword else {
            errorMessage = "Yazdığın şifreler birbiriyle eşleşmiyor."
            return
        }

        password = ""
        repeatedPassword = ""
        go(to: .signIn)
        infoMessage = "Şifren yenilendi. Yeni şifrenle giriş yapabilirsin."
    }

    private func navigateBack() {
        switch screen {
        case .verification:
            go(to: verificationPurpose == .newAccount ? .signUp : .forgotPassword)
        case .resetPassword:
            go(to: .verification)
        case .forgotPassword:
            go(to: .signIn)
        default:
            go(to: .welcome)
        }
    }

    private func go(to target: AuthenticationScreen) {
        clearFeedback()
        withAnimation(reduceMotion ? nil : NSTheme.gentleSpring) {
            screen = target
        }
    }

    private func clearFeedback() {
        errorMessage = nil
        infoMessage = nil
    }

    private func isValidEmail(_ value: String) -> Bool {
        let parts = value.split(separator: "@")
        return parts.count == 2 && parts[1].contains(".")
    }
}

private struct AuthenticationField: View {
    let title: String
    let placeholder: String
    let icon: String
    @Binding var text: String
    var isSecure = false
    var contentType: UITextContentType? = nil
    var keyboardType: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .never

    @State private var revealsSecureText = false

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(NSTheme.mutedInk)

            HStack(spacing: 11) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NSTheme.subtleInk)
                    .frame(width: 20)

                Group {
                    if isSecure && !revealsSecureText {
                        SecureField(placeholder, text: $text)
                    } else {
                        TextField(placeholder, text: $text)
                    }
                }
                .font(.system(size: 14))
                .foregroundStyle(NSTheme.ink)
                .textContentType(contentType)
                .keyboardType(keyboardType)
                .textInputAutocapitalization(capitalization)
                .autocorrectionDisabled()

                if isSecure {
                    Button {
                        revealsSecureText.toggle()
                    } label: {
                        Image(systemName: revealsSecureText ? "eye.slash" : "eye")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(NSTheme.subtleInk)
                    }
                    .accessibilityLabel(revealsSecureText ? "Şifreyi gizle" : "Şifreyi göster")
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 52)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 17, style: .continuous)
                    .stroke(NSTheme.border, lineWidth: 1)
            }
        }
    }
}

private struct VerificationCodeField: View {
    @Binding var code: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Doğrulama kodu")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(NSTheme.mutedInk)

            ZStack {
                HStack(spacing: 8) {
                    ForEach(0..<6, id: \.self) { index in
                        Text(character(at: index))
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .foregroundStyle(NSTheme.ink)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 15, style: .continuous)
                                    .stroke(index == code.count ? NSTheme.blue : NSTheme.border, lineWidth: index == code.count ? 1.5 : 1)
                            }
                    }
                }

                TextField("", text: $code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .foregroundStyle(.clear)
                    .tint(.clear)
                    .onChange(of: code) { _, newValue in
                        code = String(newValue.filter(\.isNumber).prefix(6))
                    }
            }
        }
    }

    private func character(at index: Int) -> String {
        guard index < code.count else { return "" }
        let stringIndex = code.index(code.startIndex, offsetBy: index)
        return String(code[stringIndex])
    }
}

private struct FeedbackBanner: View {
    let message: String
    let isError: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(isError ? NSTheme.coral : NSTheme.green)

            Text(message)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(NSTheme.ink)
                .lineSpacing(2)

            Spacer(minLength: 0)
        }
        .padding(12)
        .background((isError ? NSTheme.coral : NSTheme.green).opacity(0.08), in: RoundedRectangle(cornerRadius: 15, style: .continuous))
    }
}
