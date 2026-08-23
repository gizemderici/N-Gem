# API sözleşmesi — Onboarding ve başlangıç akışı

Bu belge "onboarding dikey dilimi"nin backend tarafını tanımlar: ilgi alanı kataloğu,
kullanıcının sıralaması ve bu sıralamaya göre üretilen başlangıç akışı.

Taraflar: **backend** (Gizem) ve **Android mobil** (Furkan). Bir alan burada
yazmıyorsa henüz sözleşmenin parçası değildir; eklenmesi ortak karardır.

- Taban yol: `/api/v1`
- Kimlik doğrulama: `Authorization: Bearer <accessToken>` (aksi belirtilmedikçe zorunlu)
- Tarihler: ISO-8601 UTC (`2026-08-23T10:15:30Z`)
- Kimlikler: UUID metni

---

## 1. `GET /api/v1/topics`

İlgi alanı kataloğu. **Oturum gerektirmez**, böylece kayıt ekranında da gösterilebilir.

```json
{
  "items": [
    {
      "id": "00000000-0000-4000-8000-000000000001",
      "slug": "teknoloji",
      "name": "Teknoloji",
      "description": "Yazılım, donanım ve ürün haberleri",
      "icon": "cpu",
      "colorHex": "#38BDF8"
    }
  ],
  "minSelectable": 3,
  "maxSelectable": 10
}
```

- `items` gösterim sırasındadır; mobil taraf kendi sıralamasını uygulamaz.
- `icon` bir isimdir, dosya değil. Mobil taraf bu isimleri kendi ikon setine eşler.
  Bilinmeyen bir isim gelirse varsayılan ikona düşülmelidir.
- `minSelectable` / `maxSelectable` sunucudan gelir; mobil tarafta sabit yazılmaz.
- Katalogdan bir konu kaldırılırsa listeden düşer; kullanıcının eski seçimi bozulmaz.

---

## 2. `GET /api/v1/users/me/topics`

Kullanıcının kayıtlı sıralaması. Hiç seçim yapılmamışsa `items` boştur.

```json
{
  "items": [ /* TopicResponse, kullanıcının sırasıyla */ ],
  "minSelectable": 3,
  "maxSelectable": 10,
  "completed": true,
  "updatedAt": "2026-08-23T10:15:30Z"
}
```

- `completed`, onboarding'in tamamlanıp tamamlanmadığını söyler. Uygulama açılışında
  yönlendirme kararı bu alana bakılarak verilir (`false` → onboarding, `true` → akış).
- `updatedAt`, seçim hiç yapılmadıysa alan olarak gelmez.

---

## 3. `PUT /api/v1/users/me/topics`

Sıralamayı **tamamen** değiştirir. Kısmi güncelleme yoktur: gönderilen dizi neyse
kayıtlı sıralama o olur. Dizideki sıra doğrudan öncelik sırasıdır (ilk eleman = 1. sıra).

```json
{ "topicIds": ["<uuid>", "<uuid>", "<uuid>"] }
```

Cevap: `GET /api/v1/users/me/topics` ile aynı gövde.

Doğrulama hataları (hepsi `422`, `field: "topicIds"`):

| `code` | Ne zaman |
|---|---|
| `TOO_FEW_TOPICS` | 3'ten az konu gönderildi |
| `TOO_MANY_TOPICS` | 10'dan fazla konu gönderildi |
| `DUPLICATE_TOPIC` | Aynı konu birden fazla kez gönderildi |
| `INVALID_TOPIC_ID` | UUID biçimi bozuk |
| `UNKNOWN_TOPIC` | Konu yok veya katalogdan kaldırılmış |

Doğrulama başarısızsa **hiçbir şey kaydedilmez**; eski sıralama olduğu gibi kalır.

---

## 4. `GET /api/v1/feed`

Başlangıç akışı. Sorgu parametreleri: `limit` (1–50, varsayılan 20), `cursor`.

```json
{
  "items": [
    {
      "post": {
        "id": "<uuid>",
        "text": "…",
        "author": { "id": "<uuid>", "fullName": "…", "username": "…" },
        "media": [
          {
            "id": "<uuid>",
            "mimeType": "image/png",
            "url": "https://…",
            "urlExpiresInSeconds": 900
          }
        ],
        "topics": [{ "id": "<uuid>", "slug": "teknoloji", "name": "Teknoloji" }],
        "likeCount": 12,
        "saveCount": 3,
        "likedByMe": false,
        "savedByMe": false,
        "createdAt": "2026-08-23T10:15:30Z"
      },
      "source": "PRIMARY",
      "reason": {
        "code": "TOPIC_PRIORITY",
        "text": "Teknoloji, ilgi sıralamanda 1. sırada.",
        "topic": { "id": "<uuid>", "slug": "teknoloji", "name": "Teknoloji" }
      }
    }
  ],
  "mix": { "primary": 7, "related": 2, "discovery": 1 },
  "personalized": true,
  "nextCursor": "eyJ…"
}
```

### Karışım

Bir sayfa yaklaşık **%70 ana ilgi + %20 ilişkili + %10 keşif** dağılımıyla gelir.
Oran on slotluk sabit bir desenle uygulanır ve desen imleçte taşınır; bu yüzden
dağılım sayfa sınırlarında bozulmaz, ama **tek bir sayfada tam 7/2/1 olmayabilir**.
Mobil taraf oranı kendisi hesaplamamalı, `mix` alanını okumalıdır.

Bir katmanda içerik kalmazsa boş slotlar önce kullanıcının kendi ilgi alanlarından
doldurulur; yani içerik varken sayfa kısa dönmez.

### `source` ve `reason`

`source` üç değerden biridir: `PRIMARY`, `RELATED`, `DISCOVERY`.
`reason.text` doğrudan "Neden karşıma çıktı?" alanında gösterilebilecek tek cümledir.

| `reason.code` | Anlamı |
|---|---|
| `TOPIC_PRIORITY` | Sıralamanın ilk üç konusundan biriyle eşleşti |
| `TOPIC_MATCH` | Seçilen ama alt sıradaki bir konuyla eşleşti |
| `RELATED_TOPIC` | Seçilen bir konuyla ilişkili komşu konudan geldi |
| `DISCOVERY` | Keşif payından geldi |
| `NO_TOPICS_SELECTED` | Kullanıcı henüz ilgi alanı seçmemiş |

`reason.topic` bulunamadığı durumlarda gelmeyebilir; metin her zaman gelir.

### Sayfalama

- `nextCursor` varsa devamı olabilir; **yoksa akış bitmiştir**.
- İmleç opak bir metindir. Mobil taraf içeriğini ayrıştırmamalı, olduğu gibi geri göndermelidir.
- Son sayfa bazen boş `items` ile dönebilir (`nextCursor: null`). Bu bir hata değildir.
- Bozuk imleç: `400 INVALID_CURSOR`.

### Henüz seçim yapmamış kullanıcı

`personalized: false` döner, akış düz kronolojiktir ve her öğenin `reason.code`
değeri `NO_TOPICS_SELECTED` olur. Mobil taraf bu durumda kullanıcıyı onboarding'e
yönlendirebilir.

---

## 5. `POST /api/v1/posts` — konu etiketi

Mevcut gövdeye isteğe bağlı `topicIds` eklendi (en fazla 3):

```json
{
  "text": "…",
  "mediaIds": ["<ready-media-id>"],
  "topicIds": ["<topic-id>"]
}
```

Hatalar: `TOO_MANY_TOPICS`, `DUPLICATE_TOPIC`, `INVALID_TOPIC_ID`, `UNKNOWN_TOPIC` (hepsi `422`).

Konusuz gönderiler kabul edilir; akışta keşif katmanına düşerler.

`GET /api/v1/posts/feed` ve `GET /api/v1/posts/{id}` cevaplarına da `topics` alanı eklendi.
Bu iki uç kronolojik kalmaya devam eder; kişiselleştirilmiş akış `GET /api/v1/feed`'dir.

---

## 6. `GET /api/v1/users/me/posts`

Profil ekranının listesi: kullanıcının kendi gönderileri, en yeniden eskiye.
Sorgu parametreleri `GET /api/v1/posts/feed` ile aynıdır (`limit` 1–50, `cursor`).

Cevap gövdesi de aynıdır:

```json
{ "items": [ /* PostResponse */ ], "nextCursor": "eyJ…" }
```

- Silinmiş gönderiler listelenmez.
- `likedByMe` / `savedByMe` isteği yapan kullanıcıya göre hesaplanır.
- Başkasının profilini getiren uç henüz yok; takip dilimiyle birlikte gelecek.

---

## Hata gövdesi

Tüm hatalar aynı biçimdedir:

```json
{ "code": "TOO_FEW_TOPICS", "message": "En az 3 ilgi alanı seçmelisin.", "field": "topicIds" }
```

`message` Türkçedir ve kullanıcıya gösterilebilir; `field` yalnızca doğrulama
hatalarında bulunur. Mobil taraf karar verirken **`code` alanına** bakmalıdır.

---

## Sözleşmeye dahil olmayanlar

Bunlar bilinçli olarak sonraki dilime bırakıldı:

- Davranış olayları (`POST /api/v1/events`) ve olay sözlüğü
- Öğrenilmiş Nexi Akışı, iki akış arası geçiş ve "hazır" bildirimi
- Öğrenmeyi duraklatma, modeli sıfırlama, veri silme
- Konu ağırlıklarının kullanıcı tarafından yüzdeyle ayarlanması

`GET /api/v1/feed` bugün yalnızca kullanıcının **açık tercihine** bakar.
Öğrenilmiş sinyaller bu servisin üzerine eklenecek; sözleşme aynı kalacak,
`source` ve `reason.code` kümesi genişleyecektir.
