# Kol karşılaştırması (Demo Faz 17)

Dört sıralama kolunun aynı demo verisi üzerinde ölçülmesi. Sayılar
`scripts/compare-arms.sh` ile üretildi, rapor `recommender-lab/arm_comparison.sql`.

```bash
./scripts/compare-arms.sh
```

## Kurulum: neden "her kola bir kullanıcı" değil

Planın önerdiği kurulum kullanıcı A'yı kronolojik, B'yi heuristik, D'yi
güvenlik, E'yi üretici sınırı koluna koymaktı. Bu kurulumda kol başına n=1
olur ve **kol etkisi kullanıcı farkından ayrılamaz**: A ile B arasındaki her
fark, kolun mu yoksa iki kişinin farklı takip listesi ve ilgi alanlarının mı
sonucu olduğu söylenemez.

Bunun yerine **aynı 12 demo kullanıcısı bütün kollardan geçiriliyor**. Her
kullanıcı için üç yerel saatte (09, 14, 21) istek atılıyor: kol başına 36
istek, toplam 144. Karşılaştırma kişi içi olduğu için kullanıcı farkı
ortadan kalkıyor.

Kollar arasında geçiş, backend'i `FEED_EXPERIMENT=<kol>:1` ile yeniden
başlatarak yapılıyor. Oturumlar bir kez açılıp bütün kollarda yeniden
kullanılıyor; JWT yeniden başlatmadan etkilenmiyor ve her kolda yeniden giriş
yapmak giriş hız sınırına (adres başına 60 saniyede 10 deneme) takılıyordu.

## Sonuçlar

144 istek, 12 kullanıcı, istek başına 10 slot.

| kol | üretici çeşitliliği | konu çeşitliliği | olumsuz konu oranı | p50 ms | p95 ms |
|---|---|---|---|---|---|
| control (kronolojik) | 0.7000 | 0.2000 | 0.0667 | 5 | 10 |
| heuristic | 0.8722 | 0.6694 | 0.0472 | 3423 | 4036 |
| safe | 0.8722 | 0.6694 | 0.0472 | 3742 | 4138 |
| fair | 0.8861 | 0.6750 | 0.0472 | 3446 | 3881 |

Sayılar tek bir koşudan. Tekrar koşulduğunda kişiselleştirilmiş kolların
değerleri aynı çıkıyor; kontrol kolununkiler birkaç puan oynuyor, çünkü
kronolojik akış demo veritabanına eklenen her yeni gönderiden doğrudan
etkileniyor.

İlk on içeriğin kollar arası örtüşmesi (aynı kullanıcı, aynı yerel saat):

| karşılaştırma | örtüşme |
|---|---|
| control – heuristic | 0.0417 |
| control – safe | 0.0417 |
| control – fair | 0.0417 |
| fair – heuristic | 0.9861 |
| fair – safe | 0.9861 |
| heuristic – safe | 0.9972 |

## Okuma

**Sıralama kronolojikten gerçekten farklı.** Kontrol koluyla örtüşme 0.04:
ilk on içeriğin pratikte tamamı değişiyor. Çeşitlilik de artıyor — konu
çeşitliliği 0.20'den 0.67'ye, üretici çeşitliliği 0.70'ten 0.87'ye. Kronolojik
akış tek bir konuya ve az sayıda üreticiye yığılıyor, sıralama onu dağıtıyor.

**Üç kişiselleştirilmiş kol birbirinden neredeyse ayırt edilemiyor.**
heuristic–safe örtüşmesi 0.9972; 360 slotta yaklaşık bir tanesi değişiyor.
`SAFE` ve `FAIR` hedefleri bu veri kümesinde ölçülebilir bir fark
üretmiyor. Sebep hedeflerin bozuk olması değil, verinin onları
tetiklememesi: 29 olumsuz olay 15 kullanıcıya dağılmış ve gizlenen gönderi
zaten aday havuzuna hiç girmiyor, dolayısıyla güvenlik cezasının
uygulanacağı içerik neredeyse hiç kalmıyor. Bu koşullarda A/B testine
çıkmanın anlamı yok; önce farkı yaratacak veri gerekiyor.

**Gecikme kabul edilebilir değil.** Kişiselleştirilmiş kollarda p50 ~3.4
saniye, kronolojikte 5 ms — yaklaşık 700 kat. Bu demo makinesinde tek
kullanıcılık yükle ölçüldü, yani üretimde daha da kötü olur. 300 adaylık
havuzu her istekte yeniden üretip sıralamak bu maliyetin kaynağı; kolları
karşılaştırmadan önce çözülmesi gereken şey bu.

**`fair` üretici çeşitliliğini biraz artırıyor** (0.8722 → 0.8861) ve
gecikmeyi artırmıyor. Fark küçük ama tutarlı yönde.

## Ölçülemeyenler

Aşağıdakiler bilerek raporlanmadı; sayı üretmek mümkündü ama anlamsız
olurdu.

**Olumlu etkileşim ve gizleme/şikâyet oranı kol başına ölçülemez.** Demo
verisindeki tepkiler tohumlama sırasında, kollar daha var olmadan üretildi.
`experiment_report.sql` bu oranları hesaplayabiliyor ama burada kollara
atfetmek uydurma olurdu: kullanıcı o içeriği o kol gösterdiği için
beğenmedi.

**`yeni_uretici_payi` bu veri kümesinde hiçbir şey ölçmüyor.** Dört kolda da
1.0000 çıkıyor çünkü demo veritabanındaki 58 kullanıcının **hepsinin** 5 veya
daha az takipçisi var; "az takipçili üretici" tanımı herkesi kapsıyor.
Metrik doğru, veri onu ayırt edici kılmıyor.

**Demo modeli kolu (planın C kullanıcısı) kurulamadı.** `LEARNED` kolu
`FeedVariant.IMPLEMENTED` içinde değil; backend eğitilmiş modeli
çalıştıramıyor, model yalnızca `recommender-lab` içinde çevrimdışı
değerlendiriliyor. Bu kolu ölçmek için önce modelin sunum yoluna bağlanması
gerekiyor.

## Bu faz sırasında düzeltilen hata

Kontrol kolu **soy kütüğüne hiç yazılmıyordu**. `CONTROL` kronolojik yola
düşüyor ve yalnızca `feed_fallbacks`'e bir satır bırakıyordu; `feed_requests`
tablosunda hiç görünmüyordu. Sonuç: kolları karşılaştıran her rapor
—`experiment_report.sql` dahil— temel çizgisiz çalışıyordu. İlk koşuda
kontrol kolunun 36 isteği tamamen kayboldu ve fark ancak sayılar tutmadığı
için görüldü.

Artık kronolojik akış da kaydediliyor (V21). Kaydın sınırları:

- Yalnızca **çalışan bir deneyin** kontrol kolu için. Öldürme anahtarı da
  `CONTROL` döndürüyor ama o bir kol değil, öneri verisi yazmayı durdurma
  kararı.
- Yalnızca **rıza vermiş** kullanıcı için. `feed_requests` kişisel veri
  taşıyor.
- Sıralama hatası sonrası kronolojiğe düşen istekler kaydedilmiyor; arıza
  trafiğini temel çizgiye karıştırmak yanlış olurdu.
- `raw_score` ve `final_score` `NULL`. Kronolojik akışta puan diye bir şey
  yok; 0.0 yazmak puan dağılımına bakan her sorguyu yanıltırdı.
