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

JDK kurulu değilse testler Docker üzerinden de koşturulabilir. Kalıcı bir Gradle
önbelleği bağlamak süreyi 10 dakikadan ~1 dakikaya indirir:

```bash
docker run --rm -v "$PWD:/app" -v nexi-gradle-cache:/home/gradle/.gradle -w /app gradle:8.14-jdk21 gradle test --no-daemon
```

## Uçtan uca doğrulama

`scripts/e2e-smoke.sh` gerçek PostgreSQL, MinIO ve backend üzerinde 139 kontrol
çalıştırır: kayıt, doğrulama, giriş, token yenileme, ilgi alanı seçimi, medya
yükleme (görsel ve gerçek MP4), gönderi, akış, beğeni/kaydetme, yorumlar, profil,
takip, takip içeriğinin akışa girmesi, avatar/biyografi, hikâyeler, mesajlaşma, bildirimler, arama/keşfet, engelleme/şikâyet ve öneri olayları.

```bash
docker compose up -d --build
./scripts/e2e-smoke.sh
```

### Migration uyarısı — eski `V4` veritabanları

Öneri olayları eklenirken migration'lar yeniden numaralandı:

| Eski | Yeni |
|---|---|
| — | `V4__create_recommendation_events.sql` |
| `V4__create_topics.sql` | `V5__create_topics.sql` |
| `V5__create_comments.sql` | `V6__create_comments.sql` |
| `V6__create_follows.sql` | `V7__create_follows.sql` |

`V1–V3`'te kalmış bir veritabanı sorunsuz `V7`'ye yükseliyor (test edildi).
Ancak **eski `V4` (topics) migration'ını çalıştırmış** bir veritabanı
yükseltilemez; Flyway `checksum mismatch for migration version 4` ile durur.

Böyle bir veritabanı varsa tek çözüm sıfırdan kurmaktır:

```bash
docker compose down -v && docker compose up -d --build
```

Üretim ve staging henüz kurulmadığı için bugün etkisi yok. Staging kurulduktan
sonra numaralandırma **kesinlikle değiştirilmemelidir**.

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
| `GET` | `/health` | Veritabanı bağlantısını da sınar; erişilemiyorsa `503` döner |

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

### Video

Tamamlama adımı MP4 kabını çözerek **süre ve çözünürlüğü** okur. Sunucuda FFmpeg
yok ve gerekmiyor: `moov/mvhd` süreyi, en büyük `moov/trak/tkhd` ise boyutu
veriyor. `moov` kutusu dosyanın sonunda olabildiği için (`faststart`
uygulanmamış dosyalar) hem baştan hem sondan 512 KB'lık pencere taranır.

| Sınır | Varsayılan | Değişken |
|---|---|---|
| Süre | 180 sn | `MAX_VIDEO_DURATION_SECONDS` |
| Çözünürlük | 4K (8.294.400 piksel) | `MAX_VIDEO_PIXELS` |
| Boyut | 25 MB | `MAX_VIDEO_SIZE_BYTES` |

Meta verisi okunamayan dosya reddedilir — imzası doğru olsa bile. Reddedilen
yükleme depodan silinir, `status=REJECTED` ve `processing_status=FAILED` olur,
`failure_reason` nedeni saklar.

**Kapak görseli.** Sunucu kare çıkaramadığı için istemci kareyi kendi üretip
normal bir görsel olarak yükler, sonra tamamlama isteğinde bağlar:

```json
POST /api/v1/media/{videoId}/complete
{ "thumbnailMediaId": "<hazır görsel>" }
```

Gövde isteğe bağlıdır. Kapak yalnızca videoya eklenebilir (`THUMBNAIL_NOT_ALLOWED`)
ve kendisi görsel olmalıdır (`THUMBNAIL_MUST_BE_IMAGE`).

**İki ayrı durum sütunu var ve karıştırılmamalı:**

- `status` — yüklemenin kabul edilip edilmediği: `PENDING → READY / REJECTED / DELETED`
- `processing_status` — içeriğin oynatılabilir olup olmadığı: `PENDING_UPLOAD → UPLOADED → PROCESSING → READY / FAILED`

Bugün işleme kuyruğu olmadığı için ikisi birlikte ilerliyor. FFmpeg kuyruğu
eklendiğinde video `status=READY, processing_status=PROCESSING` durumunda
bekleyebilecek; gönderiye bağlanabilmesi için **ikisinin de** `READY` olması
şart (bu kontrol şimdiden yerinde).

`GET /api/v1/media/{id}/status` istemcinin oynatılabilirliği yokladığı hafif uçtur:

```json
{
  "id": "<uuid>",
  "status": "READY",
  "processingStatus": "READY",
  "playable": true,
  "durationSeconds": 12.5,
  "width": 1280,
  "height": 720
}
```

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
| `GET` | `/api/v1/users/me/posts` | Kullanıcının kendi gönderileri, kronolojik ve imleçli |
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

## Yorumlar

| Yöntem | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/posts/{postId}/comments` | Yorum yazar; gövde `{ "text": "…" }` |
| `GET` | `/api/v1/posts/{postId}/comments` | Yorumları listeler; `limit` (1–50) ve `cursor` |
| `DELETE` | `/api/v1/comments/{id}` | Yorumu siler |

Yorumlar gönderi akışının aksine **eskiden yeniye** sıralanır; sohbet yukarıdan
aşağıya okunur ve imleç de bu yönde ilerler.

Bir yorumu **yorumun sahibi** ya da **gönderinin sahibi** silebilir. Yetkisiz
kullanıcıya `403` yerine `404` döner; aksi halde yorumun varlığı açığa çıkardı.

Cevap gövdesi sayfa bilgisinin yanında gönderinin **tamamındaki** yorum sayısını
da taşır:

```json
{
  "items": [
    {
      "id": "<uuid>",
      "postId": "<uuid>",
      "text": "…",
      "author": { "id": "<uuid>", "fullName": "…", "username": "…" },
      "createdAt": "2026-08-23T10:15:30Z",
      "deletableByMe": true
    }
  ],
  "nextCursor": "eyJ…",
  "totalCount": 12
}
```

Gönderi cevaplarına `commentCount` alanı eklendi.

Doğrulama: metin boş olamaz, en fazla 1000 karakter (`EMPTY_COMMENT`,
`COMMENT_TOO_LONG`). Silinmiş bir gönderiye yorum yazılamaz (`POST_NOT_FOUND`).

İç içe yanıtlar (thread) bilinçli olarak kapsam dışı: `comments` tablosunda
`parent_id` sütunu yok. Eklenecekse nullable bir sütun + `parentId` alanı yeterli.

## Profiller ve takip

Kullanıcılar yol içinde **kullanıcı adıyla** anılır. `/users/me` ile çakışma yoktur:
kullanıcı adı en az üç karakter olmak zorunda olduğu için kimse `me` adını alamaz.

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/users/{username}` | Herkese açık profil; gerçek sayaçlar ve `followedByMe` |
| `GET` | `/api/v1/users/{username}/posts` | Kullanıcının gönderileri, imleçli |
| `PUT` | `/api/v1/users/{username}/follow` | Takip eder; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/users/{username}/follow` | Takibi bırakır |
| `GET` | `/api/v1/users/{username}/followers` | Takipçiler, imleçli |
| `GET` | `/api/v1/users/{username}/following` | Takip edilenler, imleçli |

Kendini takip etmek `CANNOT_FOLLOW_SELF` ile reddedilir; veritabanında da bir
`CHECK` kısıtı vardır.

Gönderi ve yorum cevaplarındaki `author` nesnesi artık `followedByMe` taşır;
kartlardaki takip düğmesi bunu okur.

### Profil düzenleme, avatar ve biyografi

| Yöntem | Yol | Açıklama |
|---|---|---|
| `PATCH` | `/api/v1/users/me/profile` | `{ "fullName"?, "bio"? }` — gönderilmeyen alan değişmez |
| `PUT` | `/api/v1/users/me/avatar` | `{ "mediaId": "<hazır görsel>" }` |
| `DELETE` | `/api/v1/users/me/avatar` | Avatarı kaldırır |

Üçü de güncel `UserProfileResponse` döner.

**Biyografi.** En fazla 280 karakter (`BIO_TOO_LONG`). Alanı hiç göndermemek
"değiştirme" demektir; boş metin göndermek biyografiyi **siler**. Bu ayrım
olmasaydı `null` göndermenin ne anlama geldiği belirsiz kalırdı.

**Avatar.** Normal medya akışıyla yüklenmiş bir görsel gösterilir. Doğrulamalar:

| Durum | Kod |
|---|---|
| Medya yok ya da başkasına ait | `MEDIA_NOT_AVAILABLE` |
| Yükleme tamamlanmamış | `MEDIA_NOT_READY` |
| Görsel değil (örneğin MP4) | `AVATAR_MUST_BE_IMAGE` |

Başkasının medyası için "yok" ile aynı kod dönüyor; aksi halde o görselin
varlığı açığa çıkardı.

**Avatar adresleri süreli imzalı bağlantılardır** (15 dakika), tıpkı gönderi
görselleri gibi. Cevaplarda `avatarUrl` ve `avatarUrlExpiresInSeconds` birlikte
gelir; avatarı olmayan kullanıcıda ikisi de bulunmaz.

Avatar bilgisi şu cevaplarda taşınır: profil, gönderi yazarı, yorum yazarı,
takipçi ve takip listeleri. Bildirim ve mesaj cevapları henüz yok (Faz 11–12).

Depolama anahtarı hiçbir zaman dışarı çıkmaz; imzalı adres servis katmanında
üretilir (`AvatarUrls`).

## Hikâyeler

| Yöntem | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/stories` | `{ "mediaId", "caption"? }` |
| `GET` | `/api/v1/stories/feed` | Takip ettiklerin + kendin, yazara göre gruplu |
| `GET` | `/api/v1/users/{username}/stories` | Bir kullanıcının aktif hikâyeleri |
| `PUT` | `/api/v1/stories/{id}/view` | Görüntülemeyi kaydeder; tekrar çağrılması güvenlidir |
| `GET` | `/api/v1/stories/{id}/viewers` | **Yalnızca sahibi**, imleçli |
| `DELETE` | `/api/v1/stories/{id}` | Yalnızca sahibi siler |

Hikâye **24 saat** sonra sona erer (`StoryService.LIFETIME`). Süresi dolan
hikâye bütün sorgulardan düşer; ayrıca saatlik `StoryJanitor` onu kapatıp
medyasını depodan siler — aksi halde her hikâye kalıcı olarak birikirdi.

Akış yazara göre gruplu döner, çünkü arayüz kişi başına tek balon gösteriyor:

```json
{
  "items": [
    {
      "author": { "id": "…", "username": "…", "avatarUrl": "…" },
      "stories": [ { "id": "…", "media": { "url": "…" }, "seenByMe": false } ],
      "hasUnseen": true,
      "latestPublishedAt": "2026-08-23T10:15:30Z"
    }
  ]
}
```

Görülmemiş hikâyesi olan gruplar başa gelir. Şerit sayfalanmaz: içerik 24 saatte
yok oluyor ve takip grafiği sınırlı; yine de sorgunun büyümemesi için tavan var.

**Görünürlük kuralları**

- Görüntüleyen listesini yalnızca hikâyenin sahibi görebilir; başkasına `403`
  değil `STORY_NOT_FOUND` döner, aksi halde hikâyenin varlığı doğrulanmış olurdu.
- `viewCount` yalnızca sahibine gönderilir, başkasında hiç bulunmaz.
- Kendi hikâyene bakmak görüntüleme sayılmaz.
- Engellenen kullanıcıların hikâyeleri ne akışta ne profilinde görünür.

**Medya.** Görsel veya video olabilir; medyanın hazır (`status` ve
`processing_status` ikisi de `READY`) ve kullanıcıya ait olması gerekir. Bir
medya yalnızca tek bir yerde kullanılabilir: gönderide kullanılan hikâyeye,
hikâyede kullanılan başka bir hikâyeye eklenemez (`MEDIA_ALREADY_ATTACHED`).

## Arama ve Keşfet

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/search?q=…` | Birleşik: kullanıcı, gönderi ve konuların ilk 5'i |
| `GET` | `/api/v1/search/users?q=…` | Kullanıcılar, imleçli |
| `GET` | `/api/v1/search/posts?q=…` | Gönderiler, imleçli |
| `GET` | `/api/v1/explore` | Sorgu almaz; popülerlik + güncellik |

### Türkçe arama

PostgreSQL'in hazır Türkçe sözlüğü yok. `unaccent` üzerine `turkish_simple`
adında bir yapılandırma kuruldu; aksan duyarsız, kök bulmasız eşleşme sağlıyor:

| Yazılan | Bulduğu |
|---|---|
| `yazilim` | "Yazılım" |
| `CICEK` | "çiçek" |
| `istanbul` | "İSTANBUL" |
| `gelistirme` | "geliştirme" |

**Sınırı açık olsun: kök bulma yok.** "kitaplar" araması "kitap" içeren gönderiyi
bulmaz. Bunun için hunspell Türkçe sözlüğünün imaja eklenmesi gerekiyor; sonraki
adıma bırakıldı.

Etiketler (`#kotlin`) ayrıştırıcı `#`'i attığı için normal kelime olarak
indeksleniyor — ayrı bir hashtag tablosuna gerek kalmadı.

Kullanıcı araması iki yoldan eşleşiyor: tam kelime (`tsvector`) ve kullanıcı
adında parça (`pg_trgm`). İkincisi olmadan "giz" yazınca "gizem" bulunamaz ve
yazarken arama deneyimi bozulurdu.

### Sıralama ve sayfalama

Arama alaka puanına, keşfet ise etkileşim ve güncellik karışımına göre sıralanır:

```
puan = ln(1 + beğeni + yorum) × exp(-yaş / 1 hafta)
```

Etkileşim logaritmik: 1000 yerine 2000 beğeni almak sırayı iki katına çıkarmıyor.

İmleç `(puan, createdAt, id)` üçlüsünü taşır. **Puan tam yazılmalı** — sabit
ondalıkla yuvarlamak sessiz bir tekrar hatasına yol açıyordu: yuvarlama yukarı
gittiğinde imleçteki puan gerçek puandan büyük kalıyor ve aynı gönderi bir
sonraki sayfada ikinci kez çıkıyordu. `Double.toString` tam dönüşlü gösterim verir.

### Sınırlar

- Boş sorgu `EMPTY_QUERY`, 100 karakterden uzun sorgu `QUERY_TOO_LONG` ile reddedilir.
- Arama uçları kullanıcı başına dakikada 30 istekle sınırlı; tam metin sorguları
  diğer uçlardan pahalı.
- Silinmiş gönderiler ve engellenen kullanıcıların içeriği sonuçlarda görünmez.

## Bildirimler

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/notifications` | Yeniden eskiye, imleçli, `unreadCount` ile |
| `GET` | `/api/v1/notifications/unread-count` | Yalnızca sayaç |
| `PUT` | `/api/v1/notifications/read-all` | Hepsini okundu işaretler |
| `PUT` | `/api/v1/notifications/{id}/read` | Tekini okundu işaretler |
| `DELETE` | `/api/v1/notifications/{id}` | Bildirimi siler |

**Bugün üretilen türler:** `FOLLOW`, `POST_LIKE`, `POST_COMMENT`, `MESSAGE`.
`SYSTEM` tanımlı ama henüz üreteni yok.

Planda geçen "yorum cevabı", "hikâye etkileşimi" ve "şikâyet sonucu" türleri
eklenmedi çünkü dayandıkları özellikler yok: yorumlarda iç içe yanıt (Faz 4'te
bilinçli olarak kapsam dışı), hikâyelerde tepki (yalnızca görüntüleme var ve
görüntüleyen listesi zaten mevcut), moderatör uçları (Faz 9'da sonraya bırakıldı).

### Tekrarlanan olay yığılmıyor

`(alıcı, aktör, tür, hedef)` dörtlüsü üzerinde `UNIQUE NULLS NOT DISTINCT` kısıtı
var. Aynı kişi aynı gönderiyi beğenip kaldırıp tekrar beğenirse ya da arka arkaya
mesaj gönderirse **tek satır** tazelenir ve okunmamışa döner — yeni satır açılmaz.

`NULLS NOT DISTINCT` (PostgreSQL 15+) olmadan hedefsiz bildirimlerde (takip)
NULL'lar birbirinden farklı sayılır ve kısıt işlemezdi.

### Diğer kurallar

- **Kendi eylemin bildirim üretmez** — `NotificationSink` bunu tek yerde eliyor.
- **Engellenen kullanıcıların bildirimleri** ne listede ne sayaçta görünür.
- **Takibi bırakmak** ya da beğeniyi geri almak bildirim üretmez; yalnızca
  eylemin kendisi.
- Bildirim yazımı **ikincil bir yan etki**: hata olursa yutulur ve loglanır,
  asıl işlem (beğeni, yorum, mesaj) düşmez.
- `NOTIFICATION_RETENTION_DAYS` (varsayılan 30) süresini geçen bildirimler
  saatlik temizlik işinde siliniyor.

Push bildirimi henüz yok; cihaz jetonu sistemi eklendiğinde aynı üretim
noktalarından beslenebilir.

## Mesajlaşma

İlk sürüm **bire bir**; grup konuşması ve WebSocket sonraki sürümde. Bugün her
şey HTTP üzerinden çalışıyor.

| Yöntem | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/conversations` | `{ "username" }` — varsa getirir, yoksa açar |
| `GET` | `/api/v1/conversations` | Konuşma listesi, imleçli, `totalUnread` ile |
| `GET` | `/api/v1/conversations/{id}/messages` | Yeniden eskiye, imleçli |
| `POST` | `/api/v1/conversations/{id}/messages` | `{ "text"?, "mediaId"? }` |
| `PUT` | `/api/v1/conversations/{id}/read` | Okundu su seviyesini şimdiye çeker |
| `DELETE` | `/api/v1/messages/{id}` | Yalnızca gönderen siler |

**Konuşma tekilliği.** `conversations.direct_key` iki üyenin sıralı kimlik
çifti (`küçük:büyük`) ve UNIQUE. "Varsa getir, yoksa oluştur" bu sayede yarış
koşulundan etkilenmiyor: iki taraf aynı anda açmaya çalışsa bile tek konuşma
oluşuyor.

Henüz mesaj gönderilmemiş konuşma listede görünmez — boş sohbet balonları
birikmesin diye.

**Okundu bilgisi tek bir su seviyesinden geliyor.** `conversation_members.last_read_at`
hem okunmamış sayısını hem "görüldü" bilgisini veriyor:

- Okunmamış = karşı tarafın, benim su seviyemden sonra gönderdiği mesajlar
- Görüldü = karşı tarafın su seviyesi mesajın zamanını geçmiş

Bu yüzden mesaj başına ayrı bir okuma satırı tutulmuyor. Bire bir sohbette
per-mesaj tablo, aynı bilgiyi çok daha pahalıya verirdi.

`seenByOther` yalnızca **kendi** mesajların için doldurulur; başkasının mesajında
o bilginin bir anlamı yok.

**Mesaj tipi medyadan çıkarılıyor:** `image/*` → `IMAGE`, `video/*` → `VIDEO`,
medya yoksa `TEXT`. `SYSTEM` tipi ileride otomatik bildirimler için ayrıldı.

**Engelleme.** Engellenen kullanıcıyla konuşma başlatılamaz; mevcut konuşma
**iki tarafın da** listesinden düşer ve mesaj gönderilemez
(`CONVERSATION_NOT_FOUND`). Üye olmayan kişiye de aynı kod döner — "erişimin yok"
demek konuşmanın varlığını doğrulardı.

## Engelleme ve şikâyet

| Yöntem | Yol | Açıklama |
|---|---|---|
| `PUT` | `/api/v1/users/{username}/block` | Engeller; tekrar çağrılması güvenlidir |
| `DELETE` | `/api/v1/users/{username}/block` | Engeli kaldırır |
| `GET` | `/api/v1/users/me/blocked` | Engellediklerin, imleçli |
| `POST` | `/api/v1/reports` | Şikâyet oluşturur |
| `GET` | `/api/v1/users/me/reports` | Kendi şikâyetlerin, imleçli |

### Engelleme çift yönlüdür

Kayıt tek yönlü tutulur (kim kimi engelledi) ama **etki çift yönlüdür**: A, B'yi
engellediyse B de A'nın içeriğini görmez. Ortak koşul `BlockFilter.notBlocked`
içinde tek yerde tanımlı ve şu sorguların hepsine uygulanır:

- Kişiselleştirilmiş akış ve katmanları
- Kronolojik akış
- Tek gönderi (`GET /posts/{id}` → `POST_NOT_FOUND`)
- Kullanıcının gönderi listesi
- Profil (`GET /users/{username}` → `USER_NOT_FOUND`)
- Takipçi ve takip listeleri
- Yorum listesi **ve yorum sayacı**

Sayaç da süzülüyor; aksi halde "3 yorum" deyip iki tane gösterirdik.

Engelleme anında iki kullanıcı arasındaki takip ilişkisi **her iki yönde de**
silinir. Engel kaldırıldığında takip kendiliğinden geri gelmez.

Engellenmiş kullanıcı profil sorgusundan hiç dönmediği için onu takip etmeye
çalışmak da `USER_NOT_FOUND` alır — ayrı bir kontrol gerekmiyor.

### Şikâyetler

Hedef türleri: `USER`, `POST`, `COMMENT`, `STORY`, `MESSAGE`. Hikâye ve mesaj
henüz olmadığı için `UNSUPPORTED_REPORT_TARGET` ile reddedilir.

Nedenler: `SPAM`, `HARASSMENT`, `HATE_SPEECH`, `VIOLENCE`, `NUDITY`,
`SELF_HARM`, `MISINFORMATION`, `OTHER`. Tür ve neden büyük/küçük harfe duyarsız.

Durumlar: `OPEN → REVIEWING → ACTIONED / DISMISSED`. `reports.status` en son
durumu, `report_events` ise kim ne zaman ne yaptığını tutar.

Aynı kişi aynı hedefi ikinci kez şikâyet ederse yeni kayıt oluşmaz: mevcut kayıt
`alreadyReported: true` ve `200` ile döner (ilk şikâyet `201`).

Moderatör uçları bu fazın kapsamı dışında; `report_events` tablosu onlar
eklendiğinde denetim izini hazır bulacak.

### Akıştaki takip katmanı

`GET /api/v1/feed` yeni bir katman kazandı. Takip edilen bir yazarın gönderisi,
konusu ne olursa olsun **takip katmanına** düşer — takip en güçlü sinyaldir ve
katmanlar dışlayıcıdır, yani aynı gönderi iki katmanda birden görünmez.

On slotluk desen: **2 takip + 5 ana ilgi + 2 ilişkili + 1 keşif**. Pay ilgi
alanlarından alındı; ilişkili %20 ve keşif %10 oranlarına dokunulmadı. Kullanıcı
kimseyi takip etmiyorsa takip slotları geri ilgi alanlarına düşer ve dağılım
eski %70/%20/%10 hâline döner.

- `source` yeni bir değer alabilir: `FOLLOWING`.
- `mix` yeni bir alan taşır: `following`. Alanı tanımayan istemciler yok sayabilir.
- Yeni gerekçe kodu: `FOLLOWING` — "Takip ettiğin X paylaştı."

Hatalar tutarlı bir biçimde döner:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "E-posta veya şifre hatalı.",
  "field": "email"
}
```

## İlgi alanları ve başlangıç akışı

Onboarding, kullanıcının ilgi alanlarını **kendi sıraladığı** listeyle başlar; başlangıç
akışı bu sıralamaya göre üretilir. Endpoint ve JSON ayrıntıları için
[`docs/api/onboarding-ve-akis.md`](docs/api/onboarding-ve-akis.md).

| Yöntem | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/topics` | İlgi alanı kataloğu (oturum gerektirmez) |
| `GET` | `/api/v1/users/me/topics` | Kullanıcının kayıtlı sıralaması |
| `PUT` | `/api/v1/users/me/topics` | Sıralamayı baştan yazar (3–10 konu) |
| `GET` | `/api/v1/feed` | Sıralamaya göre karışık başlangıç akışı |

`GET /api/v1/feed` bir sayfayı yaklaşık **%70 ana ilgi + %20 ilişkili konu + %10 keşif**
dağılımıyla kurar. Oran on slotluk sabit bir desenle uygulanır ve desen imleçte taşınır,
böylece dağılım sayfa sınırlarında bozulmaz. Bir katman tükenirse boş slotlar önce
kullanıcının kendi ilgi alanlarından doldurulur.

Her öğe hangi katmandan geldiğini (`source`) ve kısa bir gerekçeyi (`reason.text`)
taşır; "Neden karşıma çıktı?" alanı doğrudan bu metni gösterebilir.

Bu oranlar bir ürün kararı değil, pilot verisiyle yeniden kalibre edilecek bir başlangıç
varsayımıdır. Desen `FeedService.PATTERN` içinde tek yerde tanımlıdır.

Gönderiler en fazla üç konuyla etiketlenebilir (`POST /api/v1/posts` → `topicIds`).
Konusuz gönderiler keşif katmanına düşer.

## Güvenlik notları

- `.env` ve gerçek sırlar Git'e eklenmez.
- Üretimde en az 32 karakterlik rastgele `JWT_SECRET` zorunludur.
- Parolalar Argon2id ile hash'lenir.
- Refresh token'ın yalnızca SHA-256 özeti veritabanında saklanır.
- Doğrulama kodları tek kullanımlık, süreli ve en fazla beş denemeliktir.
- iOS tarafında token'lar `UserDefaults` yerine Keychain'de tutulmalıdır.

### Hız sınırlaması

Kimlik uçlarında iki katman var:

- **Adres başına** — dakikada 10 istek. Süresi dolan pencereler süpürülür; sayaç sınırsız büyümez.
- **Hesap başına** — beş dakikada 5 deneme. Yalnızca adrese bakmak dağıtık deneme
  saldırısını durdurmuyor: saldırgan her denemeyi başka bir adresten yaparsa tek
  hesabı sınırsızca deneyebilirdi.

`TRUST_PROXY_HEADERS=true` yapılmadıkça `X-Forwarded-For` yok sayılır — istemci
o başlığı istediği gibi doldurabildiği için. Vekil sunucu arkasına kurulumda bu
bayrak açılmalı, yoksa bütün kullanıcılar yük dengeleyicinin adresiyle tek kovaya düşer.

Her iki sayaç da tek sunucunun belleğinde. Çoklu sunucuya geçerken ikisi de Redis'e taşınmalı.

### E-posta gönderimi

`VerificationMailer` arayüzü sağlayıcıdan bağımsızdır. Şu an devrede olan
`LoggingVerificationMailer` geliştirmede kodu log'a yazar, **üretimde yazmaz** ve
hata basar — çünkü sağlayıcı bağlanmadan kayıt akışı tamamlanamaz.

Gerçek sağlayıcı bağlamak için arayüzü uygulayan bir sınıf yazıp
`Application.module()` içinde geçmek yeterli; `AuthService` değişmez.

### Medya yaşam döngüsü

- Bir gönderi silindiğinde görselleri de silinmiş işaretlenir, `post_media`
  satırları kaldırılır ve dosyalar depodan atılır.
- `MediaJanitor` saatte bir çalışıp yarım kalan (`PENDING`) yüklemeleri toplar;
  eşik `ABANDONED_UPLOAD_TTL_HOURS` ile ayarlanır.

## Sonraki backend dilimi

Onboarding ve başlangıç akışı tamamlandı. Sıradaki dilim davranış verilerinin toplanması:

1. `POST /api/v1/events` — mobil uygulamadan gelen beğeni, kaydetme, atlama,
   içerik açma, görünür kalma ve video izleme olayları.
2. Benzersiz olay kimliğiyle tekrar gönderimin engellenmesi (idempotency).
3. Olayların kullanıcı, içerik, akış katmanı ve oturum bağlamıyla ilişkilendirilmesi.
4. Olaylardan konu bazlı ilgi skorlarının hesaplanması.
5. Öğrenilmiş Nexi Akışı ve iki akış arası geçiş.

Ondan sonra kullanıcı kontrolü tarafı gelir: öğrenmeyi duraklatma, modeli sıfırlama
ve verileri silme.

## iOS bağlantısı

Swift istemci `NEXI_API_BASE_URL` Info.plist değerini kullanır. Varsayılan geliştirme adresi `http://127.0.0.1:8080` olarak ayarlanmıştır.

- iOS Simulator ve backend aynı Mac'te çalışıyorsa varsayılan adres kullanılabilir.
- Backend başka bir bilgisayarda çalışıyorsa Xcode target ayarlarındaki `NEXI_API_BASE_URL`, o bilgisayarın yerel ağ adresiyle değiştirilmelidir (örneğin `http://192.168.1.20:8080`).
- Fiziksel iPhone ve backend bilgisayarı aynı ağda olmalı; güvenlik duvarı `8080` portuna izin vermelidir.
- Üretimde HTTPS adresi kullanılmalı ve yerel HTTP istisnası kaldırılmalıdır.
