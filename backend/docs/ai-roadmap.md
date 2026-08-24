# AI Faz 9 — ileri seviye modeller: hazırlık değerlendirmesi

Planın kendi ifadesi: *"Temel sıralama sistemi kanıtlandıktan sonra… Bu faza
erken başlanmamalı."*

**Temel sistem henüz kanıtlanmadı.** AI Faz 5'in kabul kriteri — "zaman bazlı
ayrılmış birinci taraf veride mevcut heuristik modeli geçmeli" — ölçülemedi:
üretim yok, kullanıcı yok, `feed_candidates` boş. Dolayısıyla Faz 9'un dokuz
maddesinden sekizi bugün başlatılmamalı.

Bu belge her maddenin **neye ihtiyaç duyduğunu** ve **neyin engellediğini**
yazıyor; "sonra bakarız" demek yerine sırayı ve maliyeti görünür kılmak için.

## Bu fazda yapılan tek madde

### Çok hedefli sıralama — ilgi + çeşitlilik + güvenlik + üretici adaleti

Yapıldı, çünkü dış model gerektirmiyor ve kanıtlanmış altyapının üzerine
biniyor. `RankingObjectives` dört hedefi adlandırılmış ve ayarlanabilir hâle
getiriyor:

| Hedef | Ne yapar | Varsayılan |
|---|---|---|
| İlgi | Kişiselleştirme, güncellik, kalite, keşif | Açık |
| Çeşitlilik | Aynı yazar ve konu örtüşmesi cezası | Açık (0,55 / 0,25) |
| Güvenlik | Kullanıcının gizlediği içeriğe benzeyene sert ceza | **Kapalı** |
| Üretici adaleti | Bir sayfada tek üreticiye slot sınırı | **Kapalı** |

Yeni iki hedef kapalı geliyor. Varsayılanlar önceki davranışı birebir
üretiyor; `RankingParityTest` referans dosyası değişmeden geçtiği için bu
iddia bit düzeyinde doğrulanmış durumda. Açılmaları deney kolu üzerinden
yapılmalı ve Faz 8 kapılarından geçmeli.

**Güvenlik hedefi neden gerekliydi.** Olumsuz yakınlık zaten kişiselleştirme
bileşenine giriyordu ama orada **seyreliyor**: puan özellik sayısının
kareköküne bölündüğü için uzun metinli bir gönderide üç olumsuz özellik yirmi
özelliğin içinde kayboluyor. Taze ve popüler olduğunda kullanıcının açıkça
gizlediği içeriğe benzeyen bir gönderi yine üste çıkabiliyordu; test bu vakayı
gösteriyor.

## Başlatılmaması gerekenler

### Metin embedding'leriyle anlamsal konu eşleştirme

- **Gerekli:** Türkçe cümle embedding modeli (~100–400 MB), vektör deposu
  (`pgvector`), gönderi başına çıkarım.
- **Engel:** Bugünkü konu eşleştirmesi `post_topics` etiketlerinden geliyor ve
  bu **doğru** çalışıyor (Faz 0). Embedding'in katacağı şey etiketsiz
  gönderilerde konu tahmini; bunun ne kadar sık gerektiği ölçülmedi.
- **Önce:** `feed_candidates` üzerinden "etiketsiz gönderi oranı" sorgusu.
  Oran düşükse bu madde hiç gerekmeyebilir.

### Görsel içerik sınıflandırma / video sahne ve konu analizi

- **Gerekli:** Görüntü modeli, GPU ya da barındırılan çıkarım servisi, medya
  işleme kuyruğu.
- **Engel:** İşleme kuyruğu **hiç yok**. `media_assets.processing_status`
  kolonu şimdiden duruyor ama bugün `status` ile birlikte ilerliyor; FFmpeg
  kuyruğu kurulmadan sınıflandırma takılacağı bir yer bulamaz.
- **Önce:** Medya işleme kuyruğu. Bu Faz 9 değil, altyapı işi.

### Spam ve zararlı içerik tespiti / yorum toksisite modeli

- **Gerekli:** Etiketli Türkçe veri. Elimizdeki katalog
  (`sosyal_medya_veri_setleri_detayli_guncel.docx`) buna aday setler sıralıyor
  ama **lisans engeli var**: Turkish Hate Speech V2 `CC BY-NC-SA`, RedCaps
  yalnızca ticari olmayan araştırma. Ticari bir üründe kullanılamazlar.
- **Engel:** Lisansı uygun tek Türkçe set duygu analizi için, toksisite için
  değil. Kendi etiketli verimizi üretmek gerekiyor ve o da moderasyon
  hacminin oluşmasını bekliyor.
- **Şimdilik:** `reports` tablosu ve şikâyet akışı çalışıyor; insan
  moderasyonu bu aşamada doğru araç.
- **Önce:** Şikâyet hacmi bir modeli eğitecek düzeye gelsin, sonra çift
  değerlendirmeli etiketleme.

### Aramada anlamsal sıralama

- **Gerekli:** Embedding + `pgvector`.
- **Engel:** Bugünkü arama `websearch_to_tsquery` + `unaccent` + `pg_trgm` ile
  çalışıyor ve Türkçe için makul. Anlamsal aramanın kazandıracağı fark
  ölçülmedi; arama sorgu logları da tutulmuyor.
- **Önce:** Arama sorgularını ve tıklamaları kaydetmek. Ölçmeden iyileştirme
  yapılamaz.

### Bildirim zamanı tahmini

- **Gerekli:** Kullanıcı başına aktif saat dağılımı — bu veri **var**
  (`recommendation_events.local_hour`).
- **Engel:** Bildirim gönderimi bir zamanlayıcıya bağlı değil; bildirimler
  olay anında yazılıyor ve push altyapısı (cihaz jetonları) yok.
- **Önce:** Push altyapısı. Tahmin kısmı ondan sonra küçük bir iş.

### Yeni kullanıcı için benzer içerik temelli cold-start

- **Gerekli:** İçerik benzerliği (embedding ya da konu örtüşmesi).
- **Durum:** Kısmen **zaten var**. Yeni kullanıcı ilgi alanı seçiyor
  (`interest_selected`), aday üretimi `PRIORITY_TOPIC` ve `RELATED_TOPIC`
  kaynaklarıyla o seçime göre içerik getiriyor, ve `cold_start` özelliği
  eğitilmiş modelde ayrı bir girdi.
- **Önce:** Mevcut soğuk başlangıç yolunun ne kadar iyi çalıştığını ölçmek.
  Bu, Faz 4 metriklerinde ayrı bir segment olarak raporlanabilir; iyileştirme
  gerekip gerekmediği oradan görülür.

## Sıra

1. Birinci taraf veri toplanana kadar bekle (`feed_candidates` dolsun).
2. Faz 5'in kabul kriterlerini gerçek veriyle ölç.
3. Temel sistem kanıtlandıktan **sonra** yukarıdaki maddelerden hangisinin
   gerçekten gerektiğine ölçümle karar ver.

Bu belgedeki her "önce" satırı bir ölçüm; hiçbiri model eğitmekle başlamıyor.
