# NEXI Backend

NEXI mobil uygulamalarının ortak Kotlin/Ktor backend'i. İlk dikey dilim kullanıcı kaydı, e-posta doğrulama, giriş, JWT oturumu, token yenileme, çıkış ve parola sıfırlamayı içerir.

## Teknoloji

- Kotlin 2.4 ve Ktor 3.5
- PostgreSQL 17
- Flyway veritabanı geçişleri
- Argon2id parola hash'i
- Kısa ömürlü JWT access token ve döndürülen refresh token
- Docker Compose

## Yerel çalıştırma

Gereksinimler: Docker Desktop veya JDK 21 + çalışan PostgreSQL.

En kolay yöntem:

```bash
docker compose up --build
```

Servis `http://localhost:8080`, PostgreSQL ise `localhost:5432` üzerinde açılır. Sağlık kontrolü:

```bash
curl http://localhost:8080/health
```

Docker olmadan çalıştırmak için `.env.example` içindeki değişkenleri terminal ortamına ekleyin ve:

```bash
./gradlew run
```

Windows:

```powershell
.\gradlew.bat run
```

Testler:

```powershell
.\gradlew.bat clean test
```

## Authentication akışı

### 1. Kayıt

`POST /api/v1/auth/register`

```json
{
  "fullName": "Gizem Derici",
  "username": "gizem",
  "email": "gizem@example.com",
  "password": "StrongPass123",
  "acceptedTerms": true
}
```

Geliştirme ortamında yanıtın `developmentCode` alanında altı haneli doğrulama kodu bulunur. Bu alan üretimde hiçbir zaman dönmez.

### 2. E-posta doğrulama

`POST /api/v1/auth/verify-email`

```json
{
  "email": "gizem@example.com",
  "code": "123456"
}
```

Başarılı doğrulama kullanıcıyı ve token çiftini döndürür.

### 3. Giriş

`POST /api/v1/auth/login`

```json
{
  "email": "gizem@example.com",
  "password": "StrongPass123"
}
```

### 4. Kullanıcı bilgisi

`GET /api/v1/users/me`

```text
Authorization: Bearer <accessToken>
```

### 5. Access token yenileme

`POST /api/v1/auth/refresh`

```json
{
  "refreshToken": "<refreshToken>"
}
```

Her yenilemede eski refresh token iptal edilir ve yenisi üretilir. Mobil uygulama iki token'ı da başarılı yanıt geldikten sonra atomik olarak değiştirmelidir.

### 6. Çıkış

`POST /api/v1/auth/logout`

```json
{
  "refreshToken": "<refreshToken>"
}
```

### 7. Parola sıfırlama

- `POST /api/v1/auth/forgot-password`
- `POST /api/v1/auth/reset-password`

Sıfırlama tamamlandığında kullanıcının bütün refresh oturumları iptal edilir.

## Diğer endpoint'ler

| Yöntem | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/auth/resend-verification` | Yeni e-posta doğrulama kodu ister |
| `GET` | `/health` | Servis sağlık kontrolü |

## Medya yükleme

Medya dosyaları backend üzerinden taşınmaz. Kimliği doğrulanmış uygulama backend'den süreli yükleme adresi alır ve dosyayı doğrudan MinIO/S3'e gönderir.

### 1. Yükleme başlat

`POST /api/v1/media/uploads`

```text
Authorization: Bearer <accessToken>
```

```json
{
  "filename": "nexi.png",
  "mimeType": "image/png",
  "sizeBytes": 245120
}
```

Yanıttaki `uploadUrl` adresine `PUT` isteği yapılır. `requiredHeaders` içindeki `Content-Type` başlığı değiştirilmeden kullanılmalıdır.

### 2. Yüklemeyi tamamla

`POST /api/v1/media/{mediaId}/complete`

Backend depolamadaki gerçek boyutu, Content-Type değerini ve dosya imzasını kontrol eder. Başarılı yanıt süreli bir `downloadUrl` içerir.

### 3. Medya bilgisi veya silme

- `GET /api/v1/media/{mediaId}`
- `DELETE /api/v1/media/{mediaId}`

JPEG, PNG ve WebP görseller için üst sınır 10 MB, MP4 videolar için 25 MB'dir. Bir kullanıcı başka bir kullanıcının hazırlık aşamasındaki medyasına erişemez veya onu silemez.

## Gönderiler ve akış

Hazır duruma gelen medya dosyaları `mediaId` ile bir gönderiye bağlanır. Bir medya dosyası yalnızca bir gönderide kullanılabilir ve gönderiyi oluşturan kullanıcıya ait olmalıdır.

### Gönderi oluştur

`POST /api/v1/posts`

```json
{
  "text": "NEXI ile ilk gönderim.",
  "mediaIds": ["<ready-media-id>"]
}
```

Metin 2000 karakterle, medya dosyaları dört adetle sınırlıdır. Metin veya en az bir medya zorunludur.

## Görsel ve videolu demo verisi

Backend, PostgreSQL ve MinIO çalışırken özgün demo varlıklarını gerçek API üzerinden yüklemek için:

```bash
python3 seed/seed_demo.py
```

Araç altı doğrulanmış demo hesap oluşturur, `seed/assets` altındaki dört JPEG ve iki MP4 dosyasını süreli yükleme adresleriyle MinIO'ya gönderir, örnek gönderi ve etkileşimleri ekler. Aynı içerikleri ikinci kez oluşturmaz. Sonunda iş saati ve akşam bağlamı için kişiselleştirilmiş ilk beş sonucu yazdırır.

Mobil uygulamada deneme hesabı:

```text
E-posta: demo.deneme@nsosyal.local
Şifre: Demo1234!
```

Demo varlıklarının üretim özeti ve prompt seti `seed/PROMPTS.md` dosyasındadır.

### Sayfalı akış

```text
GET /api/v1/posts/feed?limit=20
GET /api/v1/posts/feed?limit=20&cursor=<nextCursor>
GET /api/v1/posts/feed?limit=20&sessionId=<uuid>&localHour=21&timezoneOffsetMinutes=180
GET /api/v1/posts/feed?limit=20&personalized=false
```

İlk sayfada geçerli oturum ve saat bağlamı gönderildiğinde `nexi-contextual-v1` sıralayıcısı kullanılır. Yanıt `requestId`, `modelVersion` ve gönderi başına okunabilir `recommendationReason` alanlarını içerir. `personalized=false` olduğunda profil okunmaz, gösterim olayı yazılmaz ve kronolojik akış döner. Kişiselleştirme deposu yapılandırılmamış test/yerel bağlamlarda da kronolojik fallback korunur. Yazar e-postası akışta paylaşılmaz.

### Gönderi işlemleri

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/posts/{id}` | Tek gönderiyi getirir |
| `DELETE` | `/api/v1/posts/{id}` | Yalnızca sahibinin gönderisini siler |
| `PUT` | `/api/v1/posts/{id}/like` | Beğenir; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/posts/{id}/like` | Beğeniyi kaldırır |
| `PUT` | `/api/v1/posts/{id}/save` | Kaydeder; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/posts/{id}/save` | Kaydı kaldırır |

## Bağlamsal öneri modeli

Mobil istemciler ham dokunma koordinatı yerine anlamlı ürün olaylarını toplu olarak gönderir:

`POST /api/v1/recommendations/events`

```json
{
  "events": [{
    "clientEventId": "<uuid>",
    "sessionId": "<uuid>",
    "feedRequestId": "<uuid>",
    "postId": "<uuid>",
    "eventType": "content_view",
    "surface": "feed",
    "dwellMillis": 12500,
    "completionRatio": 0.72,
    "localHour": 21,
    "timezoneOffsetMinutes": 180,
    "occurredAt": "2026-08-23T18:00:00Z"
  }]
}
```

Desteklenen sinyaller oturum başlangıcı, açık ilgi seçimi, gösterim, görüntüleme/tamamlama, beğeni, kaydetme, paylaşma, gizleme, bildirme ve öneri gerekçesini açmadır. Olay kimlikleri kullanıcı bazında idempotenttir; süre, saat, gönderi sahipliği ve olay zamanı backendde doğrulanır.

| Yöntem | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/recommendations/events` | En fazla 100 öneri olayını kaydeder |
| `GET` | `/api/v1/recommendations/profile?localHour=21` | Okunabilir ilgi ve saat tercih özetini döndürür |
| `DELETE` | `/api/v1/recommendations/profile` | Kullanıcının öğrenilmiş olay profilini siler |

Model; konu, üretici ve medya türü yakınlıklarını günün dört zaman diliminde farklı ağırlıklandırır; yenilik, topluluk ilgisi, keşif ve çeşitlilikle birleştirir. Dış veri seti araştırması, dönüştürücüler, model kartı ve gerçek KuaiRand benchmark sonucu [`recommender-lab`](recommender-lab/README.md) klasöründedir.

Hatalar tutarlı bir biçimde döner:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "E-posta veya şifre hatalı.",
  "field": "email"
}
```

## Güvenlik notları

- `.env` ve gerçek sırlar Git'e eklenmez.
- Üretimde en az 32 karakterlik rastgele `JWT_SECRET` zorunludur.
- Parolalar Argon2id ile hash'lenir.
- Refresh token'ın yalnızca SHA-256 özeti veritabanında saklanır.
- Doğrulama kodları tek kullanımlık, süreli ve en fazla beş denemeliktir.
- Giriş ve doğrulama yollarında tek sunucu için temel IP hız sınırlaması vardır. Çoklu sunucuya geçerken bu sayaç Redis'e taşınmalıdır.
- iOS tarafında token'lar `UserDefaults` yerine Keychain'de tutulmalıdır.
- Üretime geçmeden önce gerçek e-posta sağlayıcısı `AuthService.dispatchCode` noktasına bağlanmalıdır.

## Sonraki backend dilimi

Authentication tamamlandıktan sonra kullanıcı sahipliğine bağlı görsel yükleme geliştirilecektir:

1. Mobil uygulama yükleme kaydı oluşturur.
2. Backend süreli S3/MinIO yükleme adresi verir.
3. Mobil uygulama görseli doğrudan depolamaya yükler.
4. Backend MIME türü, boyut ve sahipliği doğrular.
5. Tamamlanan görsel bir gönderiye bağlanır.

İlk dört adım uygulanmıştır. Sonraki dilim, hazır `mediaId` değerlerini gönderi kayıtlarına bağlayacaktır.

## iOS bağlantısı

Swift istemci `NEXI_API_BASE_URL` Info.plist değerini kullanır. Varsayılan geliştirme adresi `http://127.0.0.1:8080` olarak ayarlanmıştır.

- iOS Simulator ve backend aynı Mac'te çalışıyorsa varsayılan adres kullanılabilir.
- Backend başka bir bilgisayarda çalışıyorsa Xcode target ayarlarındaki `NEXI_API_BASE_URL`, o bilgisayarın yerel ağ adresiyle değiştirilmelidir (örneğin `http://192.168.1.20:8080`).
- Fiziksel iPhone ve backend bilgisayarı aynı ağda olmalı; güvenlik duvarı `8080` portuna izin vermelidir.
- Üretimde HTTPS adresi kullanılmalı ve yerel HTTP istisnası kaldırılmalıdır.
