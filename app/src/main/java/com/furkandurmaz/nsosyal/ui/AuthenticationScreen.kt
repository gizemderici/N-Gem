package com.furkandurmaz.nsosyal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.furkandurmaz.nsosyal.ui.components.*
import com.furkandurmaz.nsosyal.ui.theme.*

private enum class AuthPage { WELCOME, SIGN_IN, SIGN_UP, FORGOT, VERIFY, RESET }
private enum class VerificationPurpose { ACCOUNT, PASSWORD }

@Composable
fun AuthenticationScreen(onAuthenticated: () -> Unit) {
    var page by remember { mutableStateOf(AuthPage.WELCOME) }
    var purpose by remember { mutableStateOf(VerificationPurpose.ACCOUNT) }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var acceptsTerms by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(true) }

    fun go(target: AuthPage) {
        feedback = null
        page = target
    }

    fun back() {
        when (page) {
            AuthPage.VERIFY -> go(if (purpose == VerificationPurpose.ACCOUNT) AuthPage.SIGN_UP else AuthPage.FORGOT)
            AuthPage.RESET -> go(AuthPage.VERIFY)
            AuthPage.FORGOT -> go(AuthPage.SIGN_IN)
            AuthPage.WELCOME -> Unit
            else -> go(AuthPage.WELCOME)
        }
    }

    BackHandler(enabled = page != AuthPage.WELCOME, onBack = ::back)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        Box(
            Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .offset(x = 145.dp, y = (-120).dp)
                .background(Cyan.copy(alpha = 0.09f), CircleShape)
        )
        Box(
            Modifier
                .size(300.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-170).dp, y = 160.dp)
                .background(Violet.copy(alpha = 0.07f), CircleShape)
        )

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 5 } + fadeOut())
            },
            label = "authPage"
        ) { currentPage ->
            when (currentPage) {
                AuthPage.WELCOME -> AuthWelcome(
                    onSignUp = { go(AuthPage.SIGN_UP) },
                    onSignIn = { go(AuthPage.SIGN_IN) },
                    onGoogle = onAuthenticated
                )

                AuthPage.SIGN_IN -> AuthFormPage(onBack = ::back) {
                    AuthTitle("TEKRAR HOŞ GELDİN", "Hesabına giriş yap.", "Topluluğun ve sana göre şekillenen akışın seni bekliyor.")
                    AuthField("E-posta", "ornek@eposta.com", "@", email, { email = it }, KeyboardType.Email)
                    AuthField("Şifre", "Şifren", "●", password, { password = it }, secure = true)
                    Pressable(onClick = { go(AuthPage.FORGOT) }, modifier = Modifier.align(Alignment.End)) {
                        Text("Şifremi unuttum", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Feedback(feedback, feedbackIsError)
                    PrimaryButton("Giriş yap", onClick = {
                        when {
                            !validEmail(email) -> {
                                feedback = "Geçerli bir e-posta adresi yazmalısın."
                                feedbackIsError = true
                            }
                            password.length < 6 -> {
                                feedback = "Şifren en az 6 karakter olmalı."
                                feedbackIsError = true
                            }
                            else -> onAuthenticated()
                        }
                    })
                    AuthDivider()
                    SocialButton("G", "Google ile devam et", onAuthenticated)
                    SwitchPrompt("Henüz hesabın yok mu?", "Hesap oluştur") { go(AuthPage.SIGN_UP) }
                }

                AuthPage.SIGN_UP -> AuthFormPage(onBack = ::back) {
                    AuthTitle("NSOSYAL’E KATIL", "Kendine ait alanı oluştur.", "Gerçek insanlarla, ilgi duyduğun konular etrafında buluş.")
                    AuthField("Ad soyad", "Adın ve soyadın", "○", fullName, { fullName = it })
                    AuthField("Kullanıcı adı", "kullaniciadi", "@", username, { username = it })
                    AuthField("E-posta", "ornek@eposta.com", "@", email, { email = it }, KeyboardType.Email)
                    AuthField("Şifre", "En az 8 karakter", "●", password, { password = it }, secure = true)
                    PasswordStrength(password)
                    Pressable(onClick = { acceptsTerms = !acceptsTerms }) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (acceptsTerms) "▣" else "□", color = if (acceptsTerms) Blue else SubtleInk, fontSize = 20.sp)
                            Text(
                                "Kullanım Koşulları’nı ve Gizlilik Politikasını okudum, kabul ediyorum.",
                                color = MutedInk,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Feedback(feedback, feedbackIsError)
                    PrimaryButton("Hesap oluştur", onClick = {
                        when {
                            fullName.trim().length < 3 -> feedback = "Adını ve soyadını eksiksiz yazmalısın."
                            username.length < 3 || username.contains(" ") -> feedback = "Boşluk içermeyen geçerli bir kullanıcı adı seçmelisin."
                            !validEmail(email) -> feedback = "Geçerli bir e-posta adresi yazmalısın."
                            password.length < 8 -> feedback = "Şifren en az 8 karakter olmalı."
                            !acceptsTerms -> feedback = "Devam etmek için koşulları kabul etmelisin."
                            else -> {
                                purpose = VerificationPurpose.ACCOUNT
                                code = ""
                                go(AuthPage.VERIFY)
                            }
                        }
                        feedbackIsError = true
                    })
                    SwitchPrompt("Zaten hesabın var mı?", "Giriş yap") { go(AuthPage.SIGN_IN) }
                }

                AuthPage.FORGOT -> AuthFormPage(onBack = ::back) {
                    StatusOrb("⌁", Amber)
                    AuthTitle("HESAP KURTARMA", "Şifreni yenileyelim.", "Hesabına bağlı e-posta adresini yaz; sana altı haneli bir doğrulama kodu gönderelim.")
                    AuthField("E-posta", "ornek@eposta.com", "@", email, { email = it }, KeyboardType.Email)
                    Feedback(feedback, feedbackIsError)
                    PrimaryButton("Doğrulama kodu gönder", onClick = {
                        if (!validEmail(email)) {
                            feedback = "Hesabına bağlı geçerli e-posta adresini yazmalısın."
                            feedbackIsError = true
                        } else {
                            purpose = VerificationPurpose.PASSWORD
                            code = ""
                            go(AuthPage.VERIFY)
                        }
                    })
                }

                AuthPage.VERIFY -> AuthFormPage(onBack = ::back) {
                    StatusOrb("✓", Green)
                    AuthTitle("GÜVENLİK KONTROLÜ", "E-postanı doğrula.", "${maskedEmail(email)} adresine gönderdiğimiz altı haneli kodu gir.")
                    AuthField("Doğrulama kodu", "000000", "#", code, {
                        code = it.filter(Char::isDigit).take(6)
                    }, KeyboardType.Number)
                    Feedback(feedback, feedbackIsError)
                    PrimaryButton("Kodu doğrula", enabled = code.length == 6, onClick = {
                        if (purpose == VerificationPurpose.ACCOUNT) onAuthenticated()
                        else {
                            password = ""
                            repeatedPassword = ""
                            go(AuthPage.RESET)
                        }
                    })
                    SwitchPrompt("Kod gelmedi mi?", "Tekrar gönder") {
                        feedback = "Yeni doğrulama kodu gönderildi."
                        feedbackIsError = false
                    }
                }

                AuthPage.RESET -> AuthFormPage(onBack = ::back) {
                    StatusOrb("●", Violet)
                    AuthTitle("YENİ ŞİFRE", "Güçlü bir şifre belirle.", "Daha önce kullanmadığın, en az sekiz karakterli bir şifre seç.")
                    AuthField("Yeni şifre", "En az 8 karakter", "●", password, { password = it }, secure = true)
                    AuthField("Yeni şifre tekrar", "Şifreni tekrar yaz", "●", repeatedPassword, { repeatedPassword = it }, secure = true)
                    PasswordStrength(password)
                    Feedback(feedback, feedbackIsError)
                    PrimaryButton("Şifreyi yenile", onClick = {
                        when {
                            password.length < 8 -> feedback = "Yeni şifren en az 8 karakter olmalı."
                            password != repeatedPassword -> feedback = "Yazdığın şifreler birbiriyle eşleşmiyor."
                            else -> {
                                password = ""
                                repeatedPassword = ""
                                go(AuthPage.SIGN_IN)
                                feedback = "Şifren yenilendi. Yeni şifrenle giriş yapabilirsin."
                                feedbackIsError = false
                            }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun AuthWelcome(onSignUp: () -> Unit, onSignIn: () -> Unit, onGoogle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader(false, {})
        Spacer(Modifier.weight(0.7f))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(216.dp).border(1.dp, Blue.copy(alpha = 0.12f), CircleShape))
            Box(Modifier.size(158.dp).border(1.dp, Violet.copy(alpha = 0.13f), CircleShape))
            BrandMark(size = 96.dp)
        }
        Spacer(Modifier.height(34.dp))
        Text("Sana ait bir sosyal alan.", color = Ink, fontSize = 37.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(13.dp))
        Text("Gündemi takip et, topluluğunu bul ve akışının kontrolünü elinde tut.", color = MutedInk, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.weight(1f))
        PrimaryButton("Hesap oluştur", onSignUp)
        Spacer(Modifier.height(11.dp))
        SecondaryButton("Giriş yap", onSignIn)
        AuthDivider(Modifier.padding(vertical = 11.dp))
        SocialButton("G", "Google ile devam et", onGoogle)
        Text(
            "Devam ederek Kullanım Koşulları’nı ve Gizlilik Politikasını kabul etmiş olursun.",
            color = SubtleInk,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun AuthFormPage(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        BrandHeader(true, onBack)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrandHeader(back: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (back) {
            RoundIconButton("‹", "Geri", onBack, Modifier.size(40.dp))
            BrandMark(size = 32.dp)
        } else BrandMark(size = 40.dp)
        Text("NSosyal", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (back) {
            Text("Güvenli giriş", color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(Green.copy(alpha = 0.09f), CircleShape).padding(horizontal = 9.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun AuthTitle(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(title, color = Ink, fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MutedInk, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun AuthField(
    title: String,
    placeholder: String,
    glyph: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    secure: Boolean = false
) {
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = MutedInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = SubtleInk, fontSize = 14.sp) },
            leadingIcon = { Text(glyph, color = SubtleInk, fontWeight = FontWeight.SemiBold) },
            trailingIcon = if (secure) ({
                Pressable(onClick = { reveal = !reveal }, modifier = Modifier.size(38.dp)) {
                    Text(if (reveal) "◉" else "○", color = SubtleInk, modifier = Modifier.align(Alignment.Center))
                }
            }) else null,
            visualTransformation = if (secure && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = Blue,
                unfocusedBorderColor = Border,
                cursorColor = Blue,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink
            )
        )
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp).background(Color.White, CircleShape).border(1.dp, StrongBorder, CircleShape)) {
        Text(text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun SocialButton(glyph: String, text: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp).background(Color.White.copy(alpha = 0.82f), CircleShape).border(1.dp, Border, CircleShape)) {
        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AuthDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.height(1.dp).weight(1f).background(Border))
        Text("veya", color = SubtleInk, fontSize = 10.sp)
        Box(Modifier.height(1.dp).weight(1f).background(Border))
    }
}

@Composable
private fun SwitchPrompt(text: String, action: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MutedInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(5.dp))
        Pressable(onClick = onClick) { Text(action, color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Feedback(message: String?, error: Boolean) {
    message ?: return
    Row(
        modifier = Modifier.fillMaxWidth().background((if (error) Coral else Green).copy(alpha = 0.08f), RoundedCornerShape(15.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top
    ) {
        Text(if (error) "!" else "✓", color = if (error) Coral else Green, fontWeight = FontWeight.Bold)
        Text(message, color = Ink, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusOrb(glyph: String, color: Color) {
    Box(Modifier.size(74.dp).background(color.copy(alpha = 0.1f), CircleShape).border(1.dp, color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(glyph, color = color, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PasswordStrength(password: String) {
    val level = when {
        password.length < 8 -> 0
        password.any(Char::isUpperCase) && password.any(Char::isDigit) && password.any { !it.isLetterOrDigit() } -> 3
        password.any(Char::isUpperCase) && password.any(Char::isDigit) -> 2
        else -> 1
    }
    val color = when (level) { 1 -> Amber; 2 -> Blue; 3 -> Green; else -> SubtleInk }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { index -> Box(Modifier.height(5.dp).weight(1f).background(if (index < level) color else Border, CircleShape)) }
        }
        Text(when (level) { 1 -> "Şifre gücü: Orta"; 2 -> "Şifre gücü: İyi"; 3 -> "Şifre gücü: Güçlü"; else -> "En az 8 karakter kullan" }, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun validEmail(value: String) = value.contains("@") && value.substringAfter("@", "").contains(".")
private fun maskedEmail(value: String): String {
    val parts = value.split("@")
    return if (parts.size == 2 && parts[0].isNotEmpty()) "${parts[0].first()}•••@${parts[1]}" else "e-posta"
}
