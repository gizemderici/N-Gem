# NSosyal öneri modeli laboratuvarı

Bu klasör, üretimdeki `nexi-contextual-v1` sıralayıcısının çevrimdışı kontrolünü yapar. Amaç dış veri setinden kullanıcı profili kopyalamak değil; saat, oturum, izleme süresi, açık tercih ve olumsuz geri bildirim sinyallerinin sıralamayı doğru yönde değiştirip değiştirmediğini ölçmektir.

## Neden bu model?

İlk sürüm derin bir kara kutu yerine açıklanabilir ve çevrimiçi güncellenen bağlamsal bir sıralayıcıdır. Model:

- kullanıcıya ait açık ilgi seçimlerini, beğeni, kayıt, paylaşım, gizleme ve bildirim sinyallerini işler;
- izleme süresi ile tamamlama oranını tek başına değil diğer sinyallerle birlikte değerlendirir;
- sabah, mesai, akşam ve gece tercihlerini ayrı ağırlıklandırır;
- yenilik, topluluk ilgisi, keşif ve üretici çeşitliliğini dengeler;

- her öneri için kullanıcıya gösterilebilen kısa bir gerekçe üretir.

Ham ekran koordinatı, tuş vuruşu, rehber, özel mesaj, kesin konum veya uygulama dışı izleme bu sözleşmede yoktur. Mobil istemciler yalnızca anlamlı ürün olaylarını gönderir. Üretimde saklama süresi, veri dışa aktarma/silme ve açık rıza metinleri ayrıca uygulanmalıdır.

## Normalleştirilmiş olay sözleşmesi

CSV dosyası UTF-8 olmalı. Zorunlu kolonlar:

| Kolon | Açıklama |
|---|---|
| `user_id` | Takma/anonim kullanıcı kimliği |
| `item_id` | İçerik kimliği |
| `timestamp` | ISO-8601 veya Unix saniye/milisaniye |
| `local_hour` | Kullanıcının yerel saati, `0..23` |
| `event_type` | Backend olay adı |

İsteğe bağlı kolonlar: `topics` (noktalı virgülle ayrılmış), açık ilgi olayı için `target_feature`, `creator_id`, `media_type`, `dwell_ms`, `completion_ratio`. Geçerli olaylar `content_impression`, `content_view`, `content_complete`, `content_liked`, `content_saved`, `content_shared`, `content_hidden`, `content_reported` ve `recommendation_reason_opened` değerleridir.

## KuaiRand ve KuaiRec dönüştürme

Ham veri bu repoya eklenmez. İndirme ve lisans kararı için [DATASETS.md](DATASETS.md) dosyasını okuyun.

KuaiRand-Pure örneği:

```bash
python3 normalize_kuai.py kuairand \
  --interactions data/KuaiRand-Pure/data/log_standard_4_22_to_5_08_pure.csv \
  --items data/KuaiRand-Pure/data/video_features_basic_pure.csv \
  --output output/kuairand-events.csv
```

KuaiRec örneği:

```bash
python3 normalize_kuai.py kuairec \
  --interactions data/KuaiRec/data/big_matrix.csv \
  --categories data/KuaiRec/data/item_categories.csv \
  --output output/kuairec-events.csv
```

## Değerlendirme

Araç her uygun kullanıcının kronolojik olarak son olumlu etkileşimini test hedefi yapar. Hedef sonrasındaki olaylar eğitime girmez; hedef zamanından sonra ortaya çıkan içerikler aday havuzuna alınmaz. Böylece rastgele satır bölmenin neden olduğu zaman ve kullanıcı sızıntısı önlenir.

```bash
python3 offline_evaluate.py output/kuairand-events.csv --k 10 --max-users 1000 --output output/metrics.json
python3 -m unittest discover -s tests -v
```

Üretilen metrikler:

- `HitRate@K`: doğru içeriğin ilk K içinde bulunma oranı;
- `MRR@K`: doğru içeriğin sırasına daha hassas ölçüm;
- `NDCG@K`: üst sıralardaki başarıyı daha fazla ödüllendirir;
- `Coverage@K`: modelin katalogda ne kadar çeşitlilik sağladığı.

Bu ölçümler ürün başarısının tamamı değildir. Canlıya çıkışta ayrıca gizleme/bildirim oranı, üretici kapsaması, oturum memnuniyeti, gecikme, kalibrasyon ve yaş/güvenlik segmentleri izlenmelidir. Tıklama veya geçirilen süre tek başına optimizasyon hedefi yapılmamalıdır.

Komut satırı varsayılan olarak kullanıcı kimliğinin kararlı özetiyle seçilen 1.000 kullanıcıyı değerlendirir. Tam veri koşusu için `--max-users 0` kullanılabilir; raporda örneklem büyüklüğü mutlaka belirtilmelidir.

## Üretime geçiş kapısı

1. Önce fixture testi ve KuaiRand/KuaiRec karşılaştırması çalışır.
2. Ardından yalnızca izinli, anonimleştirilmiş NSosyal olaylarında kullanıcı-zaman ayrımlı değerlendirme yapılır.
3. Mevcut kronolojik akışa karşı gölge modda skorlar karşılaştırılır.
4. Küçük bir yüzdeyle A/B testi yapılır; olumsuz geri bildirim ve çeşitlilik koruma sınırları aşılırsa model otomatik kapatılır.
5. Yeni model sürümü ancak metrik, veri sürümü, lisans ve model kartı kaydedildikten sonra yayınlanır.

## Kotlin–Python eşitliği

`nexi_ranker.py`, backend'deki `ContextualRanker` ile **birebir aynı**
aritmetiği yürütür. Eskiden değerlendirici kendi basitleştirilmiş puanlamasını
kullanıyordu; güncellik bileşeni yoktu, kalite başka bir bölenle
hesaplanıyordu, keşif gürültüsü SHA-256 ile üretiliyordu ve çeşitlendirme hiç
uygulanmıyordu. Bu hâliyle çevrimdışı değerlendirme üretimdeki modeli değil,
ona benzeyen başka bir modeli ölçüyordu.

Türkçe kelime ayrıştırması bilerek Python'da yok: özellik çıkarımı Kotlin'de
kalıyor ve `fixtures/ranking_parity.json` dosyasına yazılıyor. İkinci bir
tokenizer kopyası bakımı imkânsız hâle getirirdi.

Dosyayı backend üretir:

```bash
gradle test --tests '*RankingParityTest*' -Dnexi.parity.write=true
```

Python tarafı aynı dosyadan aynı sıralamayı ve puanları (1e-12 toleransla)
üretmek zorunda:

```bash
python -m unittest discover -s tests
```

İki taraf ayrıştığı anda hem Kotlin hem Python testi kırılır. GitHub Actions
ikisini de her `Backend` gönderiminde çalıştırıyor.

## Karşılaştırılan politikalar

| Politika | Ne yapar |
|---|---|
| `chronological` | En yeni önce; kişiselleştirmenin aşması gereken alt sınır |
| `popularity` | En çok etkileşim alan önce |
| `interest_only` | Yalnızca kişiselleştirme bileşeni — güncellik, kalite ve keşif yok |
| `contextual` | Üretimdeki `nexi-contextual-v1` |
| `random` | Kararlı rastgele sıra; metriklerin taban gürültüsü |

`interest_only` ayrı duruyor çünkü bağlamsal modelin kazandığı farkın gerçekten
ilgi eşleşmesinden mi yoksa tazelik ve popülerlikten mi geldiğini ancak o
ayırıyor.

## Metrikler

İsabet metrikleri (`hit_rate`, `mrr`, `ndcg`) tek başına yanıltıcı: yalnızca
popüler içeriği döndüren bir politika isabette iyi görünüp katalogun küçük bir
bölümünü gösterir, yeni üreticiyi hiç göstermez. Bu yüzden her koşuda birlikte
raporlanıyor:

| Metrik | Ne söyler |
|---|---|
| `coverage@k` | Katalogun ne kadarı gösteriliyor |
| `creator_diversity@k` | Bir sayfadaki farklı üretici oranı |
| `topic_diversity@k` | Bir sayfadaki farklı konu oranı |
| `new_creator_share@k` | Az görünen üreticilere ayrılan gösterim payı |
| `negative_feedback_rate@k` | Kullanıcının gizlediği/şikâyet ettiği içeriğin tekrar gösterilme oranı |

**Kalibrasyon bilerek yok.** `nexi-contextual-v1` olasılık değil sıralama puanı
üretiyor; puanı olasılık gibi raporlamak uydurma bir sayı olurdu. İlk eğitilmiş
model (AI Faz 5) olasılık verdiğinde eklenecek.

## Tekrar üretilebilirlik

Çıktı `model_version`, `feature_version` ve girdi dosyasının SHA-256 özetini
taşır. Aynı veri ve aynı sürüm aynı sonucu üretmek zorunda; bunu
`test_offline_evaluate.py` doğruluyor.

## Eğitilmiş model (AI Faz 5)

Eğitim hattı hazır; üretime aday model **yok**.

```bash
# 1. Soy kütüğünden zaman ayrımlı eğitim seti
psql "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 -f export_training_data.sql > training.csv

# 2. Model
python train_ranker.py training.csv --output models/nexi-lr-v1.json

# 3. Taban modellerle karşılaştırma
python offline_evaluate.py output/events.csv --k 10 --output output/metrics.json
```

İlk model lojistik regresyon: açıklanabilir, geri alınabilir ve bağımlılıksız.
Ağaç tabanlı modeller, doğrusal taban gerçek veriyle kıyaslandıktan sonra
anlamlı olur. Ayrıntı ve sınırlar [MODEL_CARD.md](MODEL_CARD.md) dosyasında.

**Bu fazın kabul kriterleri karşılanamadı.** "Zaman bazlı ayrılmış birinci
taraf veride mevcut heuristik modeli geçmeli" ölçümü üretim ve kullanıcı
olmadan yapılamaz; `feed_candidates` bugün boş. Ölçülebilen tek şey hattın
kendisi: öğreniyor mu, tekrar üretilebilir mi, model dosyası bir tahmini
kaynağına bağlamaya yetiyor mu, özellik vektörü geleceğe bakıyor mu. Sentetik
veriyle eğitilmiş ağırlıklar depoya konmadı — sonradan gerçek sanılma riski
taşırlar.

## Model operasyonları (AI Faz 8)

### Kayıt defteri ve terfi kapısı

Bir modelin üretime çıkması dosya kopyalamak değil. `registry.py` her sürümün
SHA-256 özetini, eğitildiği veriyi, çevrimdışı metriklerini ve aşamasını tutar.

```bash
python registry.py register models/nexi-lr-v1.json --metrics output/metrics.json
python registry.py promote nexi-lr-v1 --to shadow
python registry.py promote nexi-lr-v1 --to production
python registry.py list
python registry.py verify
```

Aşamalar tek yönlü: `candidate → shadow → production`, geri dönüş
`archived`'a. Eski model dosyası silinmiyor; geri alma onunla yapılıyor.

**Üretim kapıları.** `--to production` şu koşullar sağlanmadan reddediliyor:

| Kapı | Neden |
|---|---|
| Gölge modda doğrulanmış olmalı | Kabul kriteri: gölge testinden geçmeden üretime çıkamaz |
| Çevrimdışı değerlendirme sonucu bulunmalı | Ölçülmemiş modelin iyi olduğu iddia edilemez |
| Kronolojik tabanı geçmeli | Geçmiyorsa ortada model değil, gürültü var |
| Olumsuz geri bildirim kontrolün iki katını aşmamalı | İsabet artarken güvenlik bozuluyorsa kapı kapalı |
| Yeni üretici payı kronolojiğin yarısının altına düşmemeli | Sistem yalnızca zaten görünür olanı güçlendirmemeli |

`verify` dosyaların durduğunu ve özetlerin tuttuğunu kontrol edip sıfırdan
farklı çıkış kodu döner; CI'da alarm kaynağı. Elle değiştirilmiş bir model
dosyası üretimde sessizce başka bir sıralama üretirdi.

### Kayma tespiti

```bash
python drift.py onceki_egitim.csv yeni_veri.csv
```

Model bozulmasının en sessiz biçimi: kod değişmez, veri değişir. Ölçüm
**Population Stability Index**; PSI > 0,25 olan bir özellik "yeniden eğit"
demek ve komut sıfırdan farklı çıkış kodu verir.

Kovalar yüzdelik sınırlarla kuruluyor, eşit genişlikle değil: beğeni sayısı ve
yakınlık gibi çarpık dağılan özelliklerde eşit genişlik neredeyse her şeyi tek
kovaya doldururdu.

### Otomatik hat

```bash
DATABASE_URL=postgres://... ./run_pipeline.sh
```

Dışa aktar → eğit → değerlendir → kayma → kaydet. Terfi bilerek hattın dışında:
üretime çıkış kapı kontrolü gerektiriyor ve otomatik yapılmamalı. Eğitim verisi
`MIN_TRAINING_ROWS` (varsayılan 1000) altındaysa hat duruyor — az veriyle
eğitilmiş model gürültü öğrenir.

### Sağlık panosu

```bash
psql "$DATABASE_URL" -v hours=24 -f model_health.sql
```

Sürüm başına p50/p95 gecikme, aday ve dönen sayıları; yedeğe düşme oranı
sebebe göre; saatlik hata eğrisi. `RANKING_ERROR` sıfırdan farklıysa alarm,
diğer sebepler beklenen durumlar.

### İki ayrı CSV şeması

Eğitim ve değerlendirme farklı sorular soruyor, o yüzden girdileri de ayrı:

| Dosya | Tüketici | İçerik |
|---|---|---|
| `export_training_data.sql` | `train_ranker.py` | Her satır sunulmuş bir aday + etiketi; özellikler istek anında dondurulmuş |
| `export_events.sql` | `offline_evaluate.py` | Ham etkileşim akışı; aday havuzunu ve zaman ayrımını değerlendirici kendisi kuruyor |

Aynı dosyayı ikisine de vermek hattı üçüncü adımda
`Eksik kolonlar: event_type, item_id, timestamp, user_id` ile öldürüyordu.

### Etiket penceresi ve atıf

`LABEL_WINDOW_HOURS` (varsayılan **24**). Etkileşim gösterimden bu kadar saat
içinde gelmezse etiketlenmiyor — üst sınır yokken bir aylık beğeni, o
gönderinin bütün geçmiş gösterimlerini olumlu etiketliyordu.

Her etkileşim **tek** bir gösterime atfediliyor: kendisinden önceki en yakın
olana. Aynı gönderi sabah ve akşam gösterilip akşam beğenilirse sabahki
gösterim etiketlenmiyor; aksi hâlde model "bu içerik sabah da iyi gitti" diye
öğrenirdi. Kuralları `TrainingExportIntegrationTest` gerçek SQL üzerinde
doğruluyor.

### Yerel demoda tek komutla

```bash
DATABASE_URL="postgres://nexi@localhost/nexi" \
  MIN_TRAINING_ROWS=100 MODEL_NAME=nexi-lr-demo \
  ./run_pipeline.sh
```

`PSQL` ve `PYTHON` değişkenleriyle yorumlayıcılar dışarıdan verilebilir;
`psql` yerel kurulumda yoksa compose konteynerine yönlendirilebilir.
`--model-version` her koşuya ayrı bir kayıt defteri anahtarı veriyor — sabit
bırakılırsa her eğitim bir öncekinin kaydını eziyordu.

## Türkçe duygu + konu taban modeli

Bu laboratuvar ayrıca gönderi metnini üç duygu sınıfına ve ürünün 12 konu
slug'ına ayıran açıklanabilir bir TF-IDF + lojistik regresyon tabanı içerir.
Yalnızca Python standart kütüphanesini kullanır; model dosyası üretilen artefakt
olduğu için `models/` altında kalır ve Git'e eklenmez.

```bash
python make_text_corpus.py --output corpus/train.csv --count 504
python train_text_classifier.py corpus/train.csv \
  --holdout corpus/holdout.csv \
  --output models/nexi-text-v1.json

python classify_text.py --model models/nexi-text-v1.json \
  --probabilities "Yeni güncelleme gerçekten başarılı."

python classify_text.py --model models/nexi-text-v1.json \
  --evaluate corpus/blind_test_v1.csv
```

`--evaluate --json` accuracy, macro-F1 ve sınıf bazlı precision/recall/F1
değerlerini makinece okunabilir biçimde yazar. Eğitim ve değerlendirme ayrı
komutlardır; sınıflandırma aracı değerlendirme sırasında model dosyasını
değiştirmez.

### Kanıtın sınırı

- `train.csv`: 504 kalıptan üretilmiş kurgu örnek; eğitim doğruluğu başarı
  ölçüsü değildir.
- `holdout.csv`: geliştirme sırasında görüldü ve ayar kararlarında kullanıldı;
  final test değildir.
- `test.csv`: ilk ölçümden sonra hataları görülerek eğitim konu sözlüğü
  genişletildi; genişletme sonrası sonucu bağımsız değildir.
- `blind_test_v1.csv`: 72 dengeli örnek, model çalıştırılmadan önce donduruldu;
  tek-seferlik iç doğrulamadır fakat gerçek kullanıcı örneklemi değildir.

Kör sette duygu accuracy/macro-F1 `0.542/0.520`, konu accuracy/macro-F1
`0.319/0.303` çıktı. Bu nedenle model **ürün özelliği veya otomatik etiketleme
servisi olarak etkinleştirilmez**. Şu anki görevi veri ve değerlendirme hattını
kanıtlamak, gerçek çift-etiketli Türkçe veri toplandığında karşılaştırma tabanı
olmaktır. Ayrıntı ve değişmez veri kökeni [TEXT_MODEL_CARD.md](TEXT_MODEL_CARD.md)
ile [TEXT_MODEL_EVALUATION.md](TEXT_MODEL_EVALUATION.md) dosyalarındadır.
