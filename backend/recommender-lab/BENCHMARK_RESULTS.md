# KuaiRand-Pure doğrulama sonucu

Koşu tarihi: 23 Ağustos 2026. Resmî Zenodo arşivinin gözlenen MD5 özeti `0820331067a3784d9691136f772b35a7`; yayıncının verdiği özetle aynıdır. Tam makine-okunur sonuç [benchmarks/kuairand_pure_2026-08-23.json](benchmarks/kuairand_pure_2026-08-23.json) dosyasındadır.

| Politika / model | HitRate@10 | MRR@10 | NDCG@10 | Coverage@10 |
|---|---:|---:|---:|---:|
| Standart gösterim — bağlamsal | 0,142 | 0,060472 | 0,079404 | 0,424751 |
| Standart gösterim — popülerlik | 0,255 | 0,102844 | 0,138358 | 0,040647 |
| Rastgele gösterim — bağlamsal | 0,017 | 0,006003 | 0,008566 | 0,529869 |
| Rastgele gösterim — popülerlik | 0,016 | 0,004965 | 0,007474 | 0,099301 |
| Rastgele gösterim — rastgele sıra | 0,023 | 0,007773 | 0,011283 | 0,738362 |

Her koşu kararlı biçimde seçilmiş 1.000 kullanıcı, kullanıcı başına en fazla 500 aday ve kronolojik son-pozitif test ayrımı kullanır. Standart log 295.497, rastgele gösterim logu 1.186.059 etkileşim içerir.

## Yorum

Standart log, önceki Kuaishou önericisinin gösterdiği içeriklere bağlıdır. Bu yüzden salt popülerlik, isabet metriğinde güçlü fakat yalnızca küçük bir katalog bölümünü döndüren bir taban oluşturur. Bağlamsal model bu logda daha düşük isabetle yaklaşık on kat daha geniş kapsama sağlar.

Rastgele gösterim bölümünde bağlamsal model popülerlik tabanını HitRate, MRR ve NDCG'de az farkla geçer; ancak exact-next-item ölçümlerinde kararlı rastgele sıranın gerisindedir. Bu sonuç dış veriyle “nihai model hazır” demek için yeterli değildir. Kategori etiketlerinin sayısal ve alanın Çin kısa-video ürünü olması NSosyal'e önemli dağılım farkı taşır.

## Yayın kararı

`nexi-contextual-v1`, açıklanabilir ve geri alınabilir bir ilk/gölge model olarak uygundur; dış benchmark sonucu tam otomatik ana akışa tek başına geçiş onayı değildir. Üretim onayı için izinli NSosyal olaylarında aynı zaman ayrımı, kronolojik/popülerlik karşılaştırması, negatif geri bildirim sınırları ve küçük ölçekli A/B testi gereklidir.
