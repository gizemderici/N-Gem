# Öneri verisi ve gizlilik

Bu belge NEXI'nin kişiselleştirme için hangi veriyi topladığını, ne kadar
sakladığını ve kullanıcının bunun üzerinde hangi kontrole sahip olduğunu
anlatır. Hukuki görüş değildir; ürünleşmeden önce uzman incelemesi gerekir.

## Toplanan veri

| Tablo | İçerik | Neden |
|---|---|---|
| `recommendation_events` | Ürün olayları: oturum, ilgi seçimi, görüntüleme, beğeni, gizleme, şikâyet | Sıralamayı kişiselleştirmek |
| `feed_requests` | Sıralama isteği; model/politika/özellik sürümü, saat bağlamı | Bir sıralamanın neden öyle olduğunu açıklamak |
| `feed_candidates` | Değerlendirilen adaylar, puanları, istek anındaki sayaçları | Aynı; ayrıca eğitim verisi |
| `user_feature_snapshots` | Profilin istek anındaki yakınlık vektörü | Aynı |
| `hidden_posts` | Kullanıcının gizlediği gönderiler | Açık tercih; bir daha gösterilmesin |
| `recommendation_consents` | Rıza durumu ve kabul edilen sözleşme sürümü | Rızanın kanıtı |

**Toplanmayanlar:** ham dokunma koordinatı, tuş vuruşu, rehber, özel mesaj
içeriği, kesin konum, uygulama dışı izleme. Olay sözleşmesi
[`event-contract.md`](event-contract.md) bunu bağlayıcı hâle getiriyor: listede
olmayan bir olay reddediliyor.

## Rıza

Kaydı olmayan kullanıcı için varsayılan **rıza yok**. Sessizce "açık" saymak
kullanıcıyı hiç sorulmadan profillemek olurdu.

| Yöntem | Yol |
|---|---|
| `GET` | `/api/v1/recommendations/consent` |
| `PUT` | `/api/v1/recommendations/consent` — `{ "granted": true }` |

Rıza yokken:

- davranış olayı yazılmaz (uç `accepted: 0` döner, hata değil — rızayı geri
  çekmek istemcinin kuyruğunu hata döngüsüne sokmamalı);
- akış kişiselleştirilmez, kronolojik döner;
- soy kütüğü tutulmaz.

Rıza geri çekildiğinde öğrenilmiş profil de silinir; yalnızca bayrağı kapatıp
veriyi yerinde bırakmak kullanıcının beklediği şey değil.

## Erişim, dışa aktarma ve silme

| Yöntem | Yol | Ne yapar |
|---|---|---|
| `GET` | `/api/v1/recommendations/profile` | Okunabilir özet: hangi konulara, hangi saatlerde ilgi |
| `GET` | `/api/v1/recommendations/export` | Ham kaydın tamamı, JSON |
| `DELETE` | `/api/v1/recommendations/profile` | Olaylar, soy kütüğü ve anlık görüntüler silinir |

Özet erişim hakkını tek başına karşılamıyor; dışa aktarma satırların kendisini
veriyor. Silme `feed_requests`'i de kaldırıyor ve `ON DELETE CASCADE`
üzerinden `feed_candidates` ile `user_feature_snapshots`'a yayılıyor —
eskiden yalnızca olaylar siliniyordu ve "sildim" denen veri eğitim setine
girmeye devam ediyordu.

`hidden_posts` kasıtlı olarak silinmiyor: gizleme öğrenilmiş profil değil,
kullanıcının açık tercihi. Sıfırlamayla silinseydi gizlediği içerik akışa geri
dönerdi. Hesap tamamen silindiğinde `ON DELETE CASCADE` ile o da gidiyor.

## Saklama süresi

`RECOMMENDATION_RETENTION_DAYS` (varsayılan **180 gün**). Süpürme döngüsü saatte
bir çalışıp süresi geçmiş olayları ve soy kütüğünü partiler hâlinde siliyor;
tek bir `DELETE` milyonlarca satırı kilitleyip döngüyü bloke ederdi.

Süresiz saklamak hem saklama sınırı ilkesine aykırı olurdu hem de eğitim
setine yıllar önceki davranışı sokardı: kullanıcının iki yıl önceki ilgisi
bugünkü akışını belirlememeli.

## Eğitim verisinden silme

Bir model eğitildikten sonra tek bir kullanıcının katkısını ağırlıklardan
çıkarmak mümkün değil. Uygulanan politika:

1. **Kaynak veri silinir.** Silme talebi `feed_candidates` ve
   `user_feature_snapshots` satırlarını kaldırır, dolayısıyla o kullanıcı
   bundan sonraki hiçbir eğitim setine girmez
   ([`export_training_data.sql`](../recommender-lab/export_training_data.sql)
   bu tablolardan okuyor).
2. **Modeller yeniden eğitilir.** Silme, bir sonraki eğitim döngüsünde
   ağırlıklara yansır. Döngü sıklığı saklama süresini aşmamalı.
3. **Eski model dosyaları saklanır ama üretimde tutulmaz.** Geri alma için
   arşivlenir; silme talebi sonrası üretime yeniden alınacaksa önce yeniden
   eğitilmeli.

Bu, "unutulma hakkı"nın makine öğrenmesindeki bilinen sınırı. Kullanıcıya
verilen taahhüt "ağırlıklardan silindi" değil, "verin silindi ve bir sonraki
model sizin veriniz olmadan eğitilecek" olmalı.

## Henüz yapılmayanlar

Dürüstlük gereği açıkça: aşağıdakiler bu fazda **yapılmadı** ve ürün kararı
bekliyor.

| Konu | Durum |
|---|---|
| Yaş doğrulama ve yaşa göre içerik sınırı | `users` tablosunda doğum tarihi alanı yok; yaş kapısı önce ürün kararı, sonra migration gerektiriyor |
| Hassas kategori filtresi (sağlık, siyasi görüş, din, etnik köken) | Konu kataloğunda bu kategoriler yok; ama kullanıcı metni serbest, sınıflandırma AI Faz 9'un işi |
| Erişim kaydı (kim hangi kişisel veriye baktı) | Uygulama günlükleri var, denetim izi yok |
| Veri ihlali prosedürü | Yazılmadı |
| Kullanıcıya gösterilecek KVKK/GDPR aydınlatma metinleri | Hukuki metin; bu belge onun yerine geçmez |

Rıza sözleşmesinin sürümü `recommendation_consents.contract_version` içinde
tutuluyor; metin değiştiğinde sürüm artırılıp yeniden onay istenmeli.
