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
