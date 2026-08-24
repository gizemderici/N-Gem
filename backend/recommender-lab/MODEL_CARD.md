# Model kartı — nexi-contextual-v1

## Amaç

NSosyal ana akışındaki uygun içerikleri kullanıcının açık ilgi alanları, anlamlı etkileşimleri ve günün bağlamına göre sıralamak. Model, genel amaçlı kullanıcı gözetimi veya hassas kişilik çıkarımı için tasarlanmamıştır.

## Model türü

Çevrimiçi güncellenen, içerik özellikli bağlamsal sıralayıcı. Konu, içerik üreticisi ve medya türü için zaman ağırlıklı yakınlıklar hesaplar; yenilik, topluluk ilgisi, deterministik keşif ve çeşitlendirme ile birleştirir. Sürüm backendde `ContextualRanker.modelVersion` alanıyla sabitlenir.

## Girdiler

- Açık ilgi seçimi
- Gösterim ve içerikte kalma süresi
- Video tamamlama oranı
- Beğeni, kaydetme, paylaşma
- Gizleme, bildirme
- Yerel saat dilimi içinde saat ve oturum kimliği
- Gönderi metninden sınırlı konu anahtarları, medya türü ve üretici kimliği

## Çıktılar

Her aday gönderi için sıralama skoru ve kısa bir öneri gerekçesi. Ham kullanıcı yakınlıkları mobil istemciye gönderilmez; profil endpoint'i yalnızca özet ve okunabilir üst ilgi alanlarını döndürür.

## Bilinen sınırlar

- Türkçe konu sözlüğü sınırlıdır; ironi, yeni argo ve çok dilli içerikte hata yapabilir.
- Yeni kullanıcıda kişiselleştirme yerine yenilik/topluluk ilgisi ağırlığı baskındır.
- Dış veri setleri Türkiye ve NSosyal dağılımını temsil etmez.
- Kalma süresi içerik kalitesiyle aynı şey değildir ve zorlayıcı kullanım hedefi yapılmamalıdır.
- Mevcut sürüm arkadaşlık grafı, çok modlu görsel/ses anlayışı veya uzun dönem memnuniyet tahmini içermez.

## Koruyucular

- Gizleme ve bildirme güçlü negatif sinyaldir.
- Aynı üreticinin ve benzer konunun art arda gelmesi çeşitlendirme cezası alır.
- Olay yazımı kullanıcı, gönderi, süre ve zaman aralığı doğrulamasından geçer; istemci kimliğiyle idempotenttir.
- Backend veya öneri deposu kullanılamazsa kronolojik örnek/akış fallback'i korunur.
- Kullanıcı öğrenilmiş profilini sıfırlayabilir.

## Gerekli canlı metrikler

HitRate/MRR/NDCG yanında gizleme, bildirme, içerik/üretici kapsaması, gecikme, yeni üretici görünürlüğü, oturum sonrası memnuniyet ve segment bazlı kalite farkları izlenmelidir. Canlı A/B deneyi ve geri alma eşiği olmadan varsayılan akış yapılmamalıdır.

## İlk dış benchmark

KuaiRand-Pure üzerindeki gerçek koşu ve sınırlamalar [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) dosyasında kayıtlıdır. Model standart logda popülerlik tabanından daha fazla katalog kapsaması sağlar fakat daha düşük exact-next isabeti alır; rastgele gösterim logunda popülerlik tabanını HitRate/MRR'da az farkla geçer. Bu nedenle sürüm yalnızca kontrollü ilk/gölge model olarak sınıflandırılmıştır.

---

# `nexi-lr-v1` — ilk eğitilmiş sıralayıcı

**Durum: eğitim hattı hazır, üretime aday model yok.**

## Neden lojistik regresyon

Derin öğrenmeyle ya da LightGBM ile başlanmadı. Sebep yalnızca bağımlılık
değil: bu aşamada birinci taraf veri yok, ve açıklanabilir bir doğrusal model
hem hangi özelliğin ne yönde etkilediğini gösteriyor hem de gölge modda geri
alınması kolay. Ağaç tabanlı modeller, doğrusal taban gerçek veriyle
kıyaslandıktan sonra anlamlı olur.

## Girdi

`features.py` içindeki tek özellik fonksiyonu; eğitim ve puanlama aynı kodu
kullanır. İkinci bir kopya, eğitim/servis sapmasının en yaygın sebebi.

| Grup | Özellikler |
|---|---|
| Yakınlık | konu, üretici, medya, jeton ortalaması — hepsi istek anındaki anlık görüntüden |
| İçerik | `log1p(beğeni)`, `log1p(yorum)`, güncellik, video/görsel |
| Bağlam | günün dört zaman dilimi |
| Aday | yedi kaynağın tek-sıcak kodlaması |
| Soğuk başlangıç | kullanıcının hiç sinyali yok mu |

Hepsi `feed_candidates` satırından ve o isteğin `user_feature_snapshots`
kaydından okunuyor. İstek anından sonraki hiçbir bilgi girdi değil.

## Etiket

Sunumdan **sonra** gelen olumlu etkileşim: tamamlama, beğeni, kaydetme,
paylaşma ya da 5 saniyeden uzun görüntüleme. Gizleme ve şikâyet ayrı bir
`negative` kolonunda tutulur; güvenlik sınırı onun üzerinden ölçülür.

Yalnızca gösterilen adaylar etiketlenir. Gösterilmeyeni "olumsuz" saymak,
kullanıcının tepki verme şansı hiç olmadığı için modeli yanlış eğitirdi.

## Model dosyası

Ağırlıkların yanında eğitim ayarları, veri dosyasının SHA-256 özeti, satır
sayısı, eğitim tarihi ve özellik sürümü yazılır. Bunlar olmadan bir tahminin
hangi modelden geldiği sonradan söylenemez. Özellik listesi ya da sürümü
uymayan bir dosya yüklenmez — eksik ağırlığa sessizce sıfır atamak modeli
gizlice bozardı.

## Sınırlar — üretim onayı yok

Planın kabul kriterleri **karşılanmadı** ve bugünkü veriyle karşılanamaz:

| Kriter | Durum |
|---|---|
| Zaman ayrımlı birinci taraf veride mevcut heuristiği geçmeli | **Ölçülemedi** — üretim yok, kullanıcı yok, `feed_candidates` boş |
| Popülerlik modelinden daha iyi kişisel isabet | **Ölçülemedi** — aynı sebep |
| Gizleme ve şikâyet oranını yükseltmemeli | **Ölçülemedi** — aynı sebep |
| Model dosyası, veri sürümü ve eğitim ayarları kayıtlı | Karşılandı |

Bu commit'te bir model dosyası **yayınlanmıyor**. Sentetik veriyle eğitilmiş
ağırlıkları depoya koymak, sonradan gerçek sanılma riski taşır.

Ek olarak, çevrimdışı veri setlerinde aday kaynağı bilgisi yok; o özellikler
tek değere sabitlendiği için ayırt edici güçleri dış veriyle ölçülemez.

## Üretim kapısı

1. `feed_candidates` gerçek trafikle dolar.
2. `export_training_data.sql` ile zaman ayrımlı eğitim seti çıkarılır.
3. `train_ranker.py` modeli üretir, `offline_evaluate.py` taban modellerle
   karşılaştırır.
4. Kabul kriterleri sağlanmazsa model yayınlanmaz.
5. Sağlanırsa AI Faz 6: gölge mod, sonra küçük yüzdeyle A/B.

---

# `nexi-lr-demo-v1` — yerel demo modeli

**Bu bir üretim modeli değil ve olamaz.** Kayıt defterinde `demo` işaretli;
üretim kapısı bunu metriklere bakmadan reddediyor.

## Ne ile eğitildi

Yerel demonun kurgu davranış verisi: 12 uydurma kullanıcı, 114 gönderi ve
`seed/demo_content.py` içindeki davranış profillerinden üretilmiş olaylar.
Gerçek kullanıcı yok, gerçek etkileşim yok.

| | |
|---|---|
| Eğitim satırı | 1164 (`feed_candidates`, gösterilmiş adaylar) |
| Değerlendirme olayı | 1416 |
| Olumlu etiket oranı | %1,03 |
| Özellik | 22 (`nexi-learned-features-v1`) |

## Karşılaştırma — ve neyi göstermediği

| Politika | HitRate@10 | NDCG@10 | Coverage@10 | Yeni üretici payı |
|---|---:|---:|---:|---:|
| chronological | **0,4000** | **0,2812** | 0,3165 | 0,0467 |
| popularity | 0,0000 | 0,0000 | 0,1899 | 0,0133 |
| interest_only | 0,0667 | 0,0287 | 0,4937 | 0,0200 |
| contextual | 0,0000 | 0,0000 | 0,4557 | 0,0467 |
| **learned (demo)** | 0,1333 | 0,0488 | 0,1835 | **0,6000** |
| random | 0,0000 | 0,0000 | 0,6266 | 0,3467 |

**Demo modeli kronolojik tabanı geçemiyor.** Terfi denemesi bunu da ayrıca
raporluyor:

```
HATA: üretime çıkış engellendi: demo modeli uretime cikamaz (kurgu veriyle
egitildi); kronolojik tabanı geçmiyor: 0.133333 <= 0.4
```

**Bu sayılar gerçek kullanıcı kalitesini göstermiyor.** Sebepleri:

1. **Örneklem 15 değerlendirme örneği.** Bu büyüklükte hiçbir fark anlamlı
   değil; tek bir örneğin yer değiştirmesi HitRate'i 0,07 oynatıyor.
2. **Kronolojik tabanın avantajı yapay.** Kurgu olaylar dar bir zaman
   penceresinde üretildiği için hedef neredeyse her zaman en yeni içerik.
   Gerçek trafikte bu avantaj yok.
3. **Etiket seyrek.** Olumlu oran %1; model çoğunlukla "hayır" demeyi
   öğreniyor, bu yüzden `bias` ağırlığı en güçlü terim.
4. **Davranış deseni kural tabanlı.** Seed betiği "mesai saatinde teknoloji"
   diye yazdığı için model o kuralı buluyor — kullanıcı tercihini değil,
   betiği öğreniyor.

Modelin yeni üretici payının yüksek çıkması (0,60) ilginç ama aynı sebeplerle
bir başarı iddiası değil.

## Ne işe yarıyor

Hattın uçtan uca çalıştığını gösteriyor: veri çıkarma, eğitim, değerlendirme,
kayma kontrolü, kayıt defteri ve terfi kapıları. Demo sırasında
"kişiselleştirme nasıl ölçülüyor" sorusuna somut cevap veriyor.

## Tekrar üretilebilirlik

Aynı CSV ile iki kez eğitildiğinde 22 ağırlığın hepsi birebir aynı çıkıyor;
rastgele karıştırma yok ve veri sırası sabit.

```bash
DATABASE_URL="postgres://nexi@localhost/nexi" \
  MIN_TRAINING_ROWS=100 MODEL_VERSION=nexi-lr-demo-v1 DEMO=1 \
  ./run_pipeline.sh
```
