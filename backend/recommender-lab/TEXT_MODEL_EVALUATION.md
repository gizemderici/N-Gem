# Türkçe metin modeli değerlendirme kaydı

Değerlendirme tarihi: 24 Ağustos 2026

Model: `nexi-text-v1`

Karar: **NO-GO — otomatik etiketleme veya ürün entegrasyonu yok**

## Değerlendirme protokolü

`blind_test_v1.csv`, mevcut model bu dosyada bir kez bile çalıştırılmadan önce
hazırlandı ve donduruldu. Dosya 72 benzersiz Türkçe metinden oluşur:

- her duygu sınıfında 24 örnek;
- 12 konu sınıfının her birinde 6 örnek;
- eğitim, holdout ve eski testle birebir metin çakışması yok;
- SHA-256:
  `2f9633bbd9d5c72d62d1360e50016882abbda9be33778c17b6c9af5ea5bdae0a`.

İlk ve tek kör koşudan sonra model ağırlıkları, eğitim corpus'u ve metin
işleme kodu bu sonuca göre değiştirilmedi. `blind_test_v1.csv` artık geliştirme
verisi olarak kullanılamaz; üzerinde yeni kelime ekleme veya hiperparametre
ayarı yapılmayacaktır.

Çalıştırılan komut:

```bash
python classify_text.py --model models/nexi-text-v1.json \
  --evaluate corpus/blind_test_v1.csv --json
```

## Sonuçlar

| Hedef | Doğru / toplam | Accuracy | Macro-F1 |
|---|---:|---:|---:|
| Duygu | 39 / 72 | 0.542 | 0.520 |
| Konu | 23 / 72 | 0.319 | 0.303 |

58 satırda iki hedeften en az biri yanlıştır.

### Duygu sınıfları

| Sınıf | Precision | Recall | F1 | Destek |
|---|---:|---:|---:|---:|
| Nötr | 0.49 | 0.88 | 0.63 | 24 |
| Olumlu | 0.67 | 0.42 | 0.51 | 24 |
| Olumsuz | 0.57 | 0.33 | 0.42 | 24 |

Model nötr sınıfına aşırı yöneliyor; olumlu ve özellikle olumsuz içeriklerin
çoğunu kaçırıyor. Bu davranış sosyal medya analizi için kabul edilebilir
değildir.

### Konu sınıfları

Konu macro-F1 değerinin 0.303 olması, genel doğruluğun tek başına saklayacağı
sınıf dengesizliğini gösterir. En zayıf sınıflar sağlık (`0.00`), sanat
(`0.15`), teknoloji (`0.17`), spor (`0.18`), gündem (`0.20`) ve müzik
(`0.20`) olmuştur. Altı örneklik sınıf desteği kesin ürün sonucu vermek için
küçüktür; buna rağmen başarısızlığın yönünü göstermek için yeterlidir.

## Önceki ölçümlerin neden final olmadığı

| Aşama | Duygu accuracy / macro-F1 | Konu accuracy / macro-F1 | Yorum |
|---|---:|---:|---|
| Eski test, ilk koşu | 0.694 / ölçülmedi | 0.444 / ölçülmedi | Hatalar incelenmeden önceki dürüst ara ölçüm |
| Holdout | 0.817 / 0.815 | 0.550 / 0.544 | Geliştirme sırasında birden çok kez görüldü |
| Eski test, corpus genişletildikten sonra | 0.722 / 0.712 | 0.778 / 0.757 | Test hataları eğitim sözlüğüne taşındığı için kirlenmiş |
| Kör test v1 | 0.542 / 0.520 | 0.319 / 0.303 | Dondurulmuş tek-seferlik iç kontrol |

“Yeni güncelleme gerçekten başarılı.” örneğinin doğru ve yüksek olasılıkla
sınıflandırılması yalnızca bir kabul/smoke örneğidir; model kalitesine kanıt
değildir.

## Karar gerekçesi

Kör test, sentetik kalıplarda iyi görünen modelin yeni ifadelerde
genelleşmediğini gösterdi. Bu nedenle:

- model artefaktı yayınlanmaz;
- backend endpoint'ine bağlanmaz;
- kullanıcı gönderilerine otomatik etiket yazmaz;
- öneri akışına özellik sağlamaz;
- moderasyon kararında kullanılmaz.

Bu çalışma başarısız sayılmamalıdır: değerlendirme hattı tam olarak yapması
gerekeni yapıp yanıltıcı bir modelin ürüne geçmesini engellemiştir.

## Sonraki değerlendirme planı

1. Etiket tanımları ve sınır örnekleri yazılı hâle getirilecek.
2. İzinli 300–500 gerçekçi Türkçe sosyal medya metni iki etiketleyiciye
   bağımsız verilecek.
3. Etiketleyiciler arası uyum (Cohen's kappa ve ham anlaşma) ölçülecek;
   anlaşmazlıklar karara bağlanacak.
4. Aynı yazarın veya aynı şablonun farklı bölümlere düşmesini engelleyen
   grup bazlı; mümkünse zaman bazlı train/dev/test ayrımı ilk başta
   dondurulacak.
5. TF-IDF baseline yalnızca train/dev üzerinde geliştirilecek. Final test bir
   kez koşulacak; başarısızsa sonuç raporlanacak, sete göre ayar yapılmayacak.
6. Accuracy yanında macro-F1, her sınıf için recall, karışıklık matrisi ve
   olasılık kalibrasyonu ölçülecek. Belirsiz tahminde sonuç vermemek için
   çekimser kalma eşiği belirlenecek.
7. Baseline doğrulandıktan sonra BERTurk/XLM-R denenebilir; aynı dondurulmuş
   protokol altında iyileşme kanıtlanmadan seçilmez.

Yeni bir model veya veri sürümü geliştirildiğinde `blind_test_v1.csv` yeniden
“kör” kabul edilmeyecektir. Mümkünse bağımsız bir kişi tarafından hazırlanmış
ve modeli geliştirenlerin görmediği `blind_test_v2.csv` kullanılmalıdır.
