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

## Görsel yükleme

Görseller backend üzerinden taşınmaz. Kimliği doğrulanmış uygulama backend'den süreli yükleme adresi alır ve dosyayı doğrudan MinIO/S3'e gönderir.

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

### 3. Görsel bilgisi veya silme

- `GET /api/v1/media/{mediaId}`
- `DELETE /api/v1/media/{mediaId}`

Bu ilk sürüm JPEG, PNG ve WebP kabul eder; üst sınır 10 MB'dir. Bir kullanıcı başka bir kullanıcının hazırlık aşamasındaki görseline erişemez veya onu silemez.

## Gönderiler ve akış

Hazır duruma gelen görseller `mediaId` ile bir gönderiye bağlanır. Bir görsel yalnızca bir gönderide kullanılabilir ve gönderiyi oluşturan kullanıcıya ait olmalıdır.

### Gönderi oluştur

`POST /api/v1/posts`

```json
{
  "text": "NEXI ile ilk gönderim.",
  "mediaIds": ["<ready-media-id>"]
}
```

Metin 2000 karakterle, görseller dört adetle sınırlıdır. Metin veya en az bir görsel zorunludur.

### Sayfalı akış

```text
GET /api/v1/posts/feed?limit=20
GET /api/v1/posts/feed?limit=20&cursor=<nextCursor>
```

Yanıt her gönderi için herkese açık yazar bilgilerini, süreli görsel adreslerini, beğeni/kayıt sayılarını ve görüntüleyen kullanıcının etkileşim durumunu içerir. Yazar e-postası akışta paylaşılmaz.

### Gönderi işlemleri

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/posts/{id}` | Tek gönderiyi getirir |
| `DELETE` | `/api/v1/posts/{id}` | Yalnızca sahibinin gönderisini siler |
| `PUT` | `/api/v1/posts/{id}/like` | Beğenir; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/posts/{id}/like` | Beğeniyi kaldırır |
| `PUT` | `/api/v1/posts/{id}/save` | Kaydeder; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/posts/{id}/save` | Kaydı kaldırır |

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
