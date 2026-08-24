# NSosyal Türkçe metin taban modeli — model kartı

Son güncelleme: 24 Ağustos 2026

Model kimliği: `nexi-text-v1`

Durum: **Araştırma tabanı — ürün kullanımına uygun değil (NO-GO)**

## Amaç

Bu model bir sosyal medya gönderisinin metninden iki ayrı tahmin üretir:

1. duygu: `olumlu`, `olumsuz`, `notr`;
2. konu: `teknoloji`, `yapay-zeka`, `sanat`, `egitim`, `spor`, `gundem`,
   `bilim`, `oyun`, `muzik`, `saglik`, `girisimcilik`, `seyahat`.

Modelin bugünkü amacı ürün kararı vermek değil; veri hazırlama, eğitim,
serileştirme ve bağımsız değerlendirme hattının uçtan uca çalıştığını
göstermek ve ileride gerçek veride karşılaştırılacak açıklanabilir bir taban
oluşturmaktır.

## Teknik yapı

- Metin normalizasyonu Türkçe `I/İ/ı/i` kurallarını uygular.
- Özellikler kelime birlileri, kelime ikilileri ve 4–5 karakter n-gramlarından
  oluşan TF-IDF vektörleridir.
- Duygu ve konu için iki ayrı L2 düzenlileştirmeli çok sınıflı lojistik
  regresyon modeli eğitilir.
- Eğitim tohumu sabittir (`20260824`).
- Uygulama yalnızca Python standart kütüphanesini kullanır.
- Model dosyası JSON biçimindedir; `models/` üretilen artefakt olduğu için
  Git'e eklenmez ve bu sürüm yayınlanmaz.

## Veri kökeni ve bütünlük

| Dosya | Satır | Kullanım | SHA-256 | Durum |
|---|---:|---|---|---|
| `corpus/train.csv` | 504 | Eğitim | `c9292c3ebccc14ffcbcade28dc793491bb1802c9b92b4584bff222690b7b6e0e` | Kalıptan üretilmiş kurgu veri |
| `corpus/holdout.csv` | 60 | Geliştirme | `bcbc5a408faf565cbdb687a8bbba711e5fcfd9ec8d709ce91bbfd5de2c32b889` | Geliştirme sırasında görüldü; final test değil |
| `corpus/test.csv` | 36 | Geçmiş test | `5088e98aa58dc9877f044c724f62782fc77591cb61e08cde1df7792dc3db8656` | Hataları eğitimi değiştirdi; artık kirlenmiş |
| `corpus/blind_test_v1.csv` | 72 | Tek seferlik iç kontrol | `2f9633bbd9d5c72d62d1360e50016882abbda9be33778c17b6c9af5ea5bdae0a` | İlk tahminden önce donduruldu; bundan sonra ayar için kullanılmaz |

Bu dosyaların hiçbiri gerçek NSosyal kullanıcısından gelmez. Kişisel veri,
özel mesaj, kullanıcı adı veya üçüncü taraf sosyal medya içeriği içermez.
Sentetik corpus için dış veri seti lisansı gerekmez; kodun depo lisansı ayrı
olarak geçerlidir.

## Ölçüm sonucu

| Küme | Duygu accuracy | Duygu macro-F1 | Konu accuracy | Konu macro-F1 | Kanıt değeri |
|---|---:|---:|---:|---:|---|
| Eğitim | 1.000 | — | 1.000 | — | Genelleme kanıtı değil |
| Holdout | 0.817 | 0.815 | 0.550 | 0.544 | Yalnızca geliştirme ölçümü |
| Eski test, genişletme sonrası | 0.722 | 0.712 | 0.778 | 0.757 | Kirlenmiş; final metrik olarak kullanılamaz |
| Kör iç test v1 | **0.542** | **0.520** | **0.319** | **0.303** | Tek seferlik, dondurulmuş iç kontrol |

Kör testte 72 metnin 58'inde duygu veya konu eksenlerinden en az biri yanlış
tahmin edildi. Özellikle sağlık, sanat, teknoloji, spor, gündem ve müzik konu
sınıfları zayıftır. Ayrıntılar [TEXT_MODEL_EVALUATION.md](TEXT_MODEL_EVALUATION.md)
dosyasındadır.

## Kullanım kararı

### Uygun kullanım

- eğitim ve değerlendirme hattı smoke testi;
- gerçek veri geldiğinde karşılaştırılacak açıklanabilir baseline;
- hata analizi ve etiketleme şeması geliştirme;
- yalnızca geliştirici tarafından çalıştırılan yerel deneyler.

### Uygun olmayan kullanım

- gönderileri otomatik etiketlemek veya akış sıralamasını değiştirmek;
- moderasyon, güvenlik, şikâyet veya hesap yaptırımı kararı vermek;
- kullanıcı duygu durumunu, sağlığını veya kişiliğini çıkarsamak;
- güven puanı yüksek görünse bile insan denetimi olmadan sonuç göstermek;
- demoda “çalışan yapay zekâ” başarısı olarak kör testten daha yüksek,
  kirlenmiş geliştirme skorlarını sunmak.

Model duygu analizi ile toksisite/moderasyonu birbirinden ayırmaz; olumsuz bir
görüş zararlı içerik değildir. Bu nedenle moderasyon için kullanılması hem
teknik hem etik olarak yanlıştır.

## Bilinen sınırlamalar

- Eğitim dili doğal kullanıcı dağılımını değil, yazılmış kalıpları yansıtır.
- Argo, ironi, alay, emoji, yazım hatası, kod değiştirme ve çok uzun bağlam
  yeterince temsil edilmez.
- 12 konu birbirini dışlıyor; gerçek bir gönderi birden fazla konu taşıyabilir.
- Olasılık değerleri kalibre edilmemiştir ve güven olarak yorumlanamaz.
- Yazar, zaman ve kaynak bazlı sızıntı gerçek veri olmadığı için henüz
  ölçülmemiştir.
- Demografik alt gruplar ve güvenlik açısından adalet değerlendirmesi yoktur.

## Ürüne geçiş için zorunlu kapılar

1. Açık kullanım izni olan 300–500 gerçek Türkçe gönderi toplanmalı veya ekip
   tarafından yazılmalı; kişisel veriler ayıklanmalıdır.
2. Her örnek iki kişi tarafından bağımsız etiketlenmeli, anlaşmazlıklar üçüncü
   kararla çözülmeli ve etiketleyiciler arası uyum raporlanmalıdır.
3. Eğitim/geliştirme/test ayrımı modelleme başlamadan önce kullanıcı-yazar,
   zaman ve kaynak grupları gözetilerek dondurulmalıdır.
4. Macro-F1, sınıf bazlı recall, karışıklık matrisi, kalibrasyon ve çekimser
   kalma eşiği raporlanmalıdır.
5. Baseline yeterli kanıt üretirse BERTurk veya XLM-R aynı dondurulmuş testte
   karşılaştırılabilir. Daha büyük model, otomatik olarak daha iyi değildir.
6. Ürün entegrasyonu ayrı bir onay ve gölge çalışma gerektirir.

Bu kapılar karşılanana kadar karar değişmez: **NO-GO**.
