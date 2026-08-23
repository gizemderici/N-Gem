# Öneri olayı sözleşmesi

Bu belge `POST /api/v1/recommendations/events` uçunun kabul ettiği olayların
adlarını ve **anlamlarını** sabitler. Backend, iOS ve Android aynı adı aynı şeyi
kastederek kullanmak zorunda; eğitim verisi bu tablodan üretilecek.

Geçerli sürüm: **2** · En eski desteklenen: **1**

| Sürüm | Faz | Değişiklik |
|---|---|---|
| 1 | AI Faz 0 | Kanonik konu slug'ları; `feed_served` ile `content_impression` ayrıldı |
| 2 | AI Faz 1 | `schemaVersion`, `appVersion`, `platform` alanları; beğeni/kaydetme/şikâyet sunucu üretimli oldu |

İstemci `schemaVersion` göndermezse 1 varsayılır. Geçerli sürümden büyük bir
değer `400 UNSUPPORTED_SCHEMA_VERSION` ile reddedilir — anlamını bilmediğimiz
bir olayı yazmak veriyi sonradan ayıklanamaz hâle getirir. Eski ama desteklenen
sürümler kabul edilip loglanır ve `recommendation_events.schema_version`
kolonuna yazılır, böylece hangi istemci sürümünün ne ürettiği sorgulanabilir.

## Neden sürümlü

Olay adı bir kez üretime çıktığında anlamı değiştirilemez: veritabanındaki eski
satırlar yeni anlamla yeniden yorumlanamaz. Anlam değişecekse yeni bir ad
açılır ve sözleşme sürümü artar. Bu belgeye uymayan bir istemci sürümü,
topladığı veriyi sessizce bozar.

## Kimlikler

| Alan | Kanonik biçim |
|---|---|
| Konu | [`TopicCatalog`](../src/main/kotlin/com/nexi/topics/TopicCatalog.kt) slug'ı — `teknoloji`, `yapay-zeka`, … |
| Gönderi, oturum, kullanıcı | UUID |

`targetFeature` alanına slug ya da konu kimliği gönderilebilir; backend ikisini
de kanonik slug'a çevirip öyle saklar. Katalogda olmayan değer `400
UNKNOWN_TOPIC_FEATURE` döner — sessizce kabul edilmez.

## Olaylar

| Ad | Üreten | Anlamı | Zorunlu alanlar |
|---|---|---|---|
| `session_started` | istemci | Uygulama ön plana geldi, yeni oturum başladı | — |
| `interest_selected` | istemci | Kullanıcı bir ilgi alanını açıkça seçti | `targetFeature` |
| `feed_served` | **yalnızca backend** | Backend bu gönderiyi akışa koydu | `postId`, `position`, `feedRequestId` |
| `content_impression` | istemci | Gönderi ekranda gerçekten görünür oldu | `postId` |
| `content_view` | istemci | Kullanıcı gönderiyi görüntüledi | `postId`, `dwellMillis` |
| `content_complete` | istemci | Video/içerik sonuna kadar tüketildi | `postId`, `completionRatio` |
| `content_liked` | **yalnızca backend** | Beğendi | `postId` |
| `content_saved` | **yalnızca backend** | Kaydetti | `postId` |
| `content_shared` | istemci | Paylaşımı tamamladı | `postId` |
| `content_hidden` | istemci | Gönderiyi gizledi (güçlü olumsuz) | `postId` |
| `content_reported` | **yalnızca backend** | Şikâyet etti (en güçlü olumsuz) | `postId` |
| `recommendation_reason_opened` | istemci | "Neden bu içerik" açıklamasını açtı | `postId` |

### `feed_served` ile `content_impression` farkı

Bunlar aynı şey değil ve karıştırılmaları AI Faz 0'ın düzelttiği hatanın
kaynağıydı.

- `feed_served`: backend gönderiyi aday havuzundan seçip yanıta koydu.
  Kullanıcının gördüğünün **kanıtı değildir** — akışın 18. sırasındaki gönderiye
  hiç kaydırmamış olabilir. Sunucu üretir; istemciden gelirse `400
  SERVER_ONLY_EVENT` döner.
- `content_impression`: gönderi görünürlük eşiğini geçerek ekranda kaldı.
  Yalnızca istemci ölçebilir.

İkisi de tercih kanıtı sayılmaz; sıralama ödülleri sıfırdır. Ayrı tutulmalarının
sebebi eğitim verisi: "gösterildi ama etkileşim yok" negatif örneği ancak gerçek
gösterimle anlamlıdır, sunum kaydıyla değil.

### Sunucu üretimli etkileşimler

`content_liked`, `content_saved` ve `content_reported` backend'in kendi işlemi
içinde yazılır. Beğeni zaten sunucuda işleniyor; sinyali orada üretmek uygulama
kapansa ya da ağ kesilse bile kaybolmamasını sağlıyor. İstemci ayrıca
göndermeye çalışırsa aynı etkileşim iki kez sayılacağı için `400
SERVER_ONLY_EVENT` döner.

Sinyal yalnızca **gerçek geçişte** yazılır: beğenilmemişken beğenildiğinde.
Arayüzün tekrarladığı istek ya da çift dokunuş aynı beğeniyi ikinci kez
öğretmez; beğeniyi geri alıp yeniden beğenmek ise yeni bir sinyaldir.

## İstemci kimliği

| Alan | Zorunlu | Açıklama |
|---|---|---|
| `schemaVersion` | hayır (varsayılan 1) | İstemcinin uyduğu sözleşme sürümü |
| `platform` | hayır | `ios`, `android` ya da `web`. `backend` istemciden kabul edilmez |
| `appVersion` | hayır | En fazla 20 karakter |

Sunucunun ürettiği olaylarda `platform` her zaman `backend`'tir.

## Doğrulama kuralları

Backend her olayı reddedebilir; sessiz kabul yoktur.

| Kod | Sebep |
|---|---|
| `TOO_MANY_EVENTS` | Bir istekte 100'den fazla olay |
| `INVALID_ID` | UUID alanı ayrıştırılamadı |
| `INVALID_OCCURRED_AT` | ISO-8601 değil, ya da +5 dk / −30 gün aralığının dışında |
| `INVALID_LOCAL_HOUR` | `localHour` 0–23 dışında |
| `INVALID_TIMEZONE` | `timezoneOffsetMinutes` ±840 dışında |
| `INVALID_POSITION` | `position` 0–999 dışında |
| `INVALID_DWELL` | `dwellMillis` 0–86.400.000 dışında |
| `INVALID_COMPLETION` | `completionRatio` 0–1 dışında |
| `INVALID_SURFACE` | `surface` boş |
| `MISSING_TARGET_FEATURE` | `interest_selected` olayında hedef yok |
| `UNKNOWN_TOPIC_FEATURE` | Hedef katalogda yok |
| `MISSING_POST_ID` | İçerik olayında gönderi yok |
| `SERVER_ONLY_EVENT` | İstemci sunucu üretimli bir olay göndermeye çalıştı |
| `UNSUPPORTED_SCHEMA_VERSION` | `schemaVersion` desteklenen aralığın dışında |
| `INVALID_PLATFORM` | `platform` tanınmıyor ya da `backend` |
| `INVALID_APP_VERSION` | `appVersion` 20 karakterden uzun |
| `POST_NOT_FOUND` | Olayın gönderisi yok ya da görülemiyor |

## Tekrar gönderim

`clientEventId` kullanıcı bazında tekildir (`UNIQUE (user_id, client_event_id)`).
Aynı olayı yeniden göndermek güvenlidir; yanıttaki `accepted` yalnızca ilk
yazımı sayar. İstemci kuyruğu bu yüzden "başarılı yanıt alana kadar sakla,
sonra sil" mantığıyla çalışabilir.

