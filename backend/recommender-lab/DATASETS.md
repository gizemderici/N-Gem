# Araştırılan veri setleri ve kullanım kararı

İnceleme tarihi: 23 Ağustos 2026. Bu kayıt, klasördeki `sosyal_medya_veri_setleri_detayli_guncel.docx` raporundaki adayları davranışsal öneri ihtiyacına göre yeniden değerlendirir.

## Karar özeti

| Öncelik | Veri seti | Güçlü taraf | Lisans/kısıt | NSosyal kararı |
|---|---|---|---|---|
| 1 | [KuaiRand](https://github.com/chongminggao/KuaiRand) | Zaman damgalı kısa video akışı, izleme süresi, beğeni/paylaşım/olumsuz sinyal ve rastgele gösterim politikası | CC BY-SA 4.0 | Birincil açık çevrimdışı benchmark. `Pure` sürümü başlangıç için yeterli; türetilmiş artefaktın paylaşım koşulları hukukça doğrulanmalı. |
| 2 | [KuaiRec](https://github.com/chongminggao/KuaiRec) | Gerçek kısa video logları, `watch_ratio`, zaman ve kategori; yoğun kullanıcı-içerik matrisi | CC BY-SA 4.0 | Sıralama ve tamamlama oranı testleri için ikinci benchmark. |
| 3 | [MIND](https://learn.microsoft.com/en-us/azure/open-datasets/dataset-microsoft-news) | Anonim kullanıcı geçmişi, gösterim zamanı ve tık/tıklamama adayları | Microsoft Research License, araştırma amaçlı | Haber/gündem yüzeyi için araştırma testi; ticari üretim ağırlığına doğrudan dahil edilmez. |
| 4 | [EB-NeRD](https://recsys.eb.dk/) | Büyük ölçekli haber gösterimi ve etkileşimleri, içerik metni | Araştırma amaçlı genel lisans koşulları | Oturum ve haber çeşitliliği araştırması; genel sosyal akış için alan kayması yüksek. |
| 5 | [MovieLens](https://grouplens.org/datasets/movielens/) | Olgun öneri sistemi tabanı ve tekrar üretilebilir karşılaştırma | Ticari/gelir getirici kullanım izinsiz yasak | Yalnızca algoritma smoke testi; saat ve kısa-video davranışı taşımadığı için ana veri seti değil. |

## Neden rapordaki sentetik veri setleri ana eğitim verisi değil?

Rapor; gönderi etkileşimi, yaşam tarzı, Gen-Z kullanımı ve duygu analizi için çeşitli sentetik/etiketli tablolar içeriyor. Bunlar analitik ekranı, moderasyon veya sınıflandırma için yararlı olabilir. Fakat NSosyal'in istediği “aynı kullanıcı mesai saatinde iş, akşam mizah tercih ediyor” davranışı için sıralı oturum, yerel saat, gösterim, gerçek izleme süresi ve negatif geri bildirim birlikte gerekli. Satırları bağımsız veya sentetik olan tablolar bu nedensel/zamansal ilişkiyi güvenilir biçimde öğretmez.

## Üretim verisi ilkesi

Dış veri yalnızca şema, benchmark ve soğuk başlangıç araştırmasında kullanılır. Üretim modeli aşağıdaki birinci taraf olaylardan, kullanıcının bilgisi ve kontrolüyle öğrenir:

- içerik gösterimi ve semantik arayüz yüzeyi;
- içerikte kalma süresi ve varsa video tamamlama oranı;
- beğeni, kaydetme, paylaşma, gizleme ve bildirme;
- açık ilgi seçimi, yerel saat dilimi ve rastgele oturum kimliği.

Kamera, kesin konum, kişi listesi, özel mesaj içeriği ve ham dokunma koordinatı öneri eğitimi için toplanmaz. Kullanıcı profilini görebilmeli, dışa aktarabilmeli ve sıfırlayabilmelidir. Veri seti sürümü, kaynak, lisans, indirme tarihi ve dosya özeti (SHA-256) model kaydına eklenmelidir.

## Yerel Türkçe metin corpus'u

Türkçe duygu ve konu sınıflandırma hattını çalıştırmak için repoda küçük bir
kurgu corpus bulunur. Bu corpus dışarıdan indirilmemiştir; gerçek kullanıcı
içeriği ve kişisel veri içermez. Dolayısıyla bir ürün modeli eğitmek için değil,
yalnızca kodun ve değerlendirme protokolünün smoke testi için kullanılır.

| Dosya | Satır | Rol | SHA-256 | Kullanım durumu |
|---|---:|---|---|---|
| `corpus/train.csv` | 504 | Eğitim | `c9292c3ebccc14ffcbcade28dc793491bb1802c9b92b4584bff222690b7b6e0e` | Sentetik baseline |
| `corpus/holdout.csv` | 60 | Geliştirme | `bcbc5a408faf565cbdb687a8bbba711e5fcfd9ec8d709ce91bbfd5de2c32b889` | Final test değildir |
| `corpus/test.csv` | 36 | Eski test | `5088e98aa58dc9877f044c724f62782fc77591cb61e08cde1df7792dc3db8656` | Ayarlama bilgisi sızdığı için kirlenmiş |
| `corpus/blind_test_v1.csv` | 72 | Tek-seferlik iç doğrulama | `2f9633bbd9d5c72d62d1360e50016882abbda9be33778c17b6c9af5ea5bdae0a` | Artık ayar için kullanılamaz |

Kör iç testte duygu accuracy/macro-F1 `0.542/0.520`, konu
accuracy/macro-F1 `0.319/0.303` çıkmıştır. Bu sonuç ürün kullanımı için
yetersizdir. Tam karar ve protokol [TEXT_MODEL_CARD.md](TEXT_MODEL_CARD.md)
ve [TEXT_MODEL_EVALUATION.md](TEXT_MODEL_EVALUATION.md) dosyalarındadır.

## Uyum notu

Bu dosya hukuki görüş değildir. Özellikle CC BY-SA türetilmiş model/özellik artefaktlarının dağıtımı ile “research-only” veri setlerinin ticari kullanımı canlıya çıkmadan önce hukuk danışmanıyla onaylanmalıdır.
