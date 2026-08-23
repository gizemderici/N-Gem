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
