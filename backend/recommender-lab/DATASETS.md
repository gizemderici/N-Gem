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

## Uyum notu

Bu dosya hukuki görüş değildir. Özellikle CC BY-SA türetilmiş model/özellik artefaktlarının dağıtımı ile “research-only” veri setlerinin ticari kullanımı canlıya çıkmadan önce hukuk danışmanıyla onaylanmalıdır.
