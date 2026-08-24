"""Yerel demonun kurgu içeriği.

Tamamı uydurma; hiçbir gerçek kullanıcı verisi yok. Orkestrasyon
`seed_demo.py` içinde, içerik burada: gönderi listesi büyüdükçe iki şeyi
aynı dosyada tutmak betiği okunamaz hâle getiriyordu.

Davranış profilleri bu dosyanın asıl işi. Kullanıcıların akışlarının
gerçekten farklılaşması için her birinin **hangi saatte hangi konuya**
ilgi gösterdiği ayrı ayrı tanımlı; sıralayıcı zaman dilimlerini ayrı
ağırlıklandırdığı için aynı kullanıcı sabah ve akşam farklı akış görüyor.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class DemoUser:
    full_name: str
    username: str
    email: str
    bio: str
    topics: tuple[str, ...]
    avatar: str


@dataclass(frozen=True)
class DemoBehaviour:
    """Bir kullanıcının günlük ritmi.

    `routine` içindeki her satır "şu saatte şu konulara bakıyor" demek.
    `deep` konularda uzun görüntüleme ve tamamlama, `skim` konularda kısa
    bakış üretiliyor. `disliked` konular gizleniyor — kullanıcının açık
    olumsuz tepkisi, sıralayıcının en güçlü negatif sinyali.
    """

    username: str
    # (yerel saat, derinlemesine bakılan konular, göz gezdirilen konular)
    routine: tuple[tuple[int, tuple[str, ...], tuple[str, ...]], ...]
    disliked: tuple[str, ...] = ()
    # En fazla kaç gönderi gizlensin; akışı tamamen boşaltmamak için.
    hide_limit: int = 2
    reports: tuple[str, ...] = field(default_factory=tuple)


USERS = (
    DemoUser("Deniz Test", "demo_deneme", "demo.deneme@nsosyal.local",
             "Yeni fikirleri, şehir hayatını ve teknolojiyi keşfediyorum.",
             ("teknoloji", "yapay-zeka", "seyahat", "egitim"), "istanbul-vapur.jpg"),
    DemoUser("Berk Yalın", "demo_berk", "demo.berk@nsosyal.local",
             "Mobil ürün geliştirici; küçük deneyler ve ölçülebilir sonuçlar.",
             ("teknoloji", "yapay-zeka", "girisimcilik", "bilim"), "teknoloji-yapay-zeka.jpg"),
    DemoUser("Mert Can", "demo_mert", "demo.mert@nsosyal.local",
             "Kahve, oyun ve günlük hayatın komik tarafları.",
             ("oyun", "muzik", "teknoloji", "sanat"), "mizah-kafe.jpg"),
    DemoUser("Ece Aydın", "demo_ece", "demo.ece@nsosyal.local",
             "Koşuyorum, geziyorum ve İstanbul notları biriktiriyorum.",
             ("spor", "seyahat", "saglik", "gundem"), "istanbul-vapur.jpg"),
    DemoUser("Aylin Demir", "demo_aylin", "demo.aylin@nsosyal.local",
             "Seramik, sinema ve yerel kültür üzerine notlar.",
             ("sanat", "muzik", "egitim", "seyahat"), "kultur-seramik.jpg"),
    DemoUser("Selin Akay", "demo_selin", "demo.selin@nsosyal.local",
             "Ürün tasarımcısı; sade arayüzler ve erişilebilir deneyimler.",
             ("teknoloji", "sanat", "egitim", "girisimcilik"), "teknoloji-yapay-zeka.jpg"),
    DemoUser("Onur Kaya", "demo_onur", "demo.onur@nsosyal.local",
             "Bilim, spor ve sürdürülebilir yaşam meraklısı.",
             ("bilim", "spor", "saglik", "gundem"), "mizah-kafe.jpg"),
    DemoUser("Zeynep Aras", "demo_zeynep", "demo.zeynep@nsosyal.local",
             "Müzik, yemek ve hafta sonu rotaları paylaşıyorum.",
             ("muzik", "seyahat", "sanat", "saglik"), "kultur-seramik.jpg"),
    DemoUser("Kaan Duru", "demo_kaan", "demo.kaan@nsosyal.local",
             "Espor takibi, konsol incelemeleri ve gece seansları.",
             ("oyun", "teknoloji", "muzik", "gundem"), "mizah-kafe.jpg"),
    DemoUser("Elif Şahin", "demo_elif", "demo.elif@nsosyal.local",
             "Sabah koşusu, beslenme notları ve uzun yürüyüşler.",
             ("spor", "saglik", "seyahat", "bilim"), "istanbul-vapur.jpg"),
    DemoUser("Barış Toprak", "demo_baris", "demo.baris@nsosyal.local",
             "Plak koleksiyonu, canlı sahne ve gece listeleri.",
             ("muzik", "sanat", "gundem", "oyun"), "kultur-seramik.jpg"),
    DemoUser("Nil Aksoy", "demo_nil", "demo.nil@nsosyal.local",
             "Öğretmenim; öğrenme yöntemleri ve sınıf içi denemeler.",
             ("egitim", "bilim", "girisimcilik", "saglik"), "teknoloji-yapay-zeka.jpg"),
)


def _post(username: str, topic: str, text: str, asset: str | None = None) -> dict[str, str]:
    spec = {"username": username, "topic": topic, "text": text}
    if asset:
        spec["asset"] = asset
    return spec


# Konu başına ~10 gönderi. Metinler konu kelimelerini gerçekten içeriyor:
# sıralayıcı etiketsiz gönderilerde metinden konu çıkarabiliyor ve demo o
# yedek yolu da göstermeli.
POSTS = (
    # ---------------------------------------------------------- teknoloji
    _post("demo_berk", "yapay-zeka", "Yapay zeka destekli mobil uygulama prototipini bugün gerçek kullanıcı akışıyla denedik. Teknoloji ancak sade bir deneyime dönüştüğünde işe yarıyor. #teknoloji #yazılım", "teknoloji-yapay-zeka.jpg"),
    _post("demo_berk", "teknoloji", "Altı saniyelik ürün günlüğü: yapay zeka, mobil arayüz ve küçük ama ölçülebilir bir iyileştirme. Mesai saatlerinde böyle kısa teknoloji videoları iyi gidiyor.", "teknoloji-demo.mp4"),
    _post("demo_berk", "teknoloji", "Kodlama sırasında en çok zaman kaybettiren şey yazılım değil, yanlış tanımlanmış problem. Bugün iki saat sadece soruyu netleştirmeye ayırdım."),
    _post("demo_selin", "teknoloji", "Arayüz tasarımında bugünkü ders: kullanıcıya beş seçenek vermek yerine doğru anda tek net eylem sunmak. Sadelik, eksiklik değil önceliklendirmedir."),
    _post("demo_selin", "teknoloji", "Mobil uygulamada donanım kaynaklı gecikmeyi yazılım tarafında gizlemeye çalışmak işe yaramıyor; ölçüp kabul etmek daha dürüst."),
    _post("demo_kaan", "teknoloji", "Yeni konsol güncellemesi sonrası yükleme süreleri gözle görülür kısaldı. Donanım aynı, yazılım tarafı iyileşmiş."),
    _post("demo_berk", "yapay-zeka", "Bir modelin ürettiği öneriyi açıklayamıyorsak, kullanıcıya güven veremiyoruz demektir. Algoritma kadar gerekçe de ürünün parçası."),
    _post("demo_nil", "yapay-zeka", "Sınıfta yapay zeka araçlarını ödev yapmak için değil, öğrencinin kendi cevabını sorgulamak için kullandık. Sonuç beklediğimden iyiydi."),
    _post("demo_selin", "yapay-zeka", "Zeka kelimesini pazarlama sloganı yapmak yerine modelin nerede yanıldığını göstermek daha ikna edici oluyor."),
    _post("demo_deneme", "teknoloji", "Telefonu bir gün boyunca sadece gerekli uygulamalarla kullandım. Teknoloji azaldıkça dikkat süresi uzuyor."),
    _post("demo_kaan", "yapay-zeka", "Oyun içi rakip yapay zekası artık hamlelerimi ezberliyor. Algoritma iyi çalışınca oyun da zorlaşıyor."),

    # --------------------------------------------------------------- oyun
    _post("demo_mert", "oyun", "Akşam molası için kısa mizah videosu: kahve köpüğü planı bozdu, arkadaşlar günü kurtardı. Eğlence bazen tam olarak budur.", "mizah-demo.mp4"),
    _post("demo_mert", "oyun", "Bu akşam ekipçe kısa bir strateji oyunu oynadık. Kazanan plan değil, son anda yapılan sakin iletişim oldu. #oyun"),
    _post("demo_kaan", "oyun", "Gece yarısı espor turnuvası izledim; konsol başında üç saat nasıl geçti anlamadım. Oyun temposu inanılmazdı."),
    _post("demo_kaan", "oyun", "Yeni bölümde harita tasarımı çok iyi: oyuncuyu yönlendiriyor ama zorlamıyor. Oyun tasarımı böyle olmalı."),
    _post("demo_kaan", "oyun", "Konsol kumandasının tuş dizilimine alışmak iki akşam sürdü. Şimdi eski oyunlara dönmek zor geliyor."),
    _post("demo_baris", "oyun", "Oyun müziklerini ayrı bir liste yaptım; bazıları film müziği kadar iyi. Akşam seansları böyle daha keyifli."),
    _post("demo_mert", "oyun", "Espor yayınında yorumcu takımın hatasını değil sebebini anlatınca izlemek çok daha öğretici oldu."),
    _post("demo_kaan", "oyun", "Bu hafta oyun süremi yarıya indirdim ve daha çok keyif aldım. Az ama odaklı oynamak işe yarıyor."),
    _post("demo_baris", "oyun", "Retro konsol akşamı: iki düğmeli bir oyun bile doğru tempoda inanılmaz eğlenceli olabiliyor."),
    _post("demo_mert", "oyun", "Yeni indie oyunun tek mekaniği var ama o mekaniği sonuna kadar kullanıyor. Sadelik burada da kazanıyor."),

    # --------------------------------------------------------------- spor
    _post("demo_ece", "spor", "Sahilde otuz dakikalık koşu tamamlandı. Spor için büyük hedeflerden önce düzenli küçük adımların daha sürdürülebilir olduğunu yeniden gördüm."),
    _post("demo_ece", "spor", "Sabah koşusunda tempoyu düşürünce mesafe kendiliğinden uzadı. Antrenman planı bazen sadece sabır demek."),
    _post("demo_elif", "spor", "Sabahın erken saatinde koşmak günün geri kalanını değiştiriyor. Spor rutini için en zor kısım ilk on dakika."),
    _post("demo_elif", "spor", "Yarı maraton hazırlığında bu hafta uzun koşuyu ikiye böldüm. Antrenman hacmi aynı, yorgunluk daha az."),
    _post("demo_onur", "spor", "Bisiklet rotasında hız yerine düzenli tempoya odaklandım. Eve daha az yorulup daha uzun mesafeyle döndüm."),
    _post("demo_onur", "spor", "Maç sonrası esneme rutinini atlamayı bıraktım; ertesi gün farkı çok net."),
    _post("demo_elif", "spor", "Koşu ayakkabısını değiştirince adım sıklığı da değişti. Küçük ekipman kararları antrenmanı etkiliyor."),
    _post("demo_ece", "spor", "Basketbol sahasında yarım saat; spor arkadaşla yapılınca antrenman gibi değil oyun gibi geçiyor."),
    _post("demo_elif", "spor", "Sabah yürüyüşünü koşuya çevirmek için üç hafta gerekti. Acele etmemek en iyi antrenman kararıydı."),
    _post("demo_onur", "spor", "Futbol maçında kondisyon değil pozisyon aldı işi. Spor zekâsı da antrenmanla gelişiyor."),

    # -------------------------------------------------------------- muzik
    _post("demo_zeynep", "muzik", "Mahalle sahnesinde üç kişilik bir caz grubunu dinledim. Canlı müziğin en güzel yanı, aynı parçanın her seferinde değişmesi."),
    _post("demo_zeynep", "muzik", "Gece listesine yavaş parçalar ekledim; müzik temposu düşünce gün de yavaşlıyor."),
    _post("demo_baris", "muzik", "Plakçıda bulduğum eski albüm beklediğimden temiz çıktı. Şarkı sıralaması bugün yapılsa böyle olmazdı."),
    _post("demo_baris", "muzik", "Gece yarısı konser çıkışı: sahne ışığı kapanınca kalabalığın sesi müzik kadar güzel."),
    _post("demo_baris", "muzik", "Yeni albümü baştan sona dinledim, tek tek şarkı değil. Albüm formatı hâlâ anlamlı."),
    _post("demo_mert", "muzik", "Çalışma listesine lo-fi yerine eski film müzikleri ekledim; aynı masanın havası bir anda değişti."),
    _post("demo_zeynep", "muzik", "Konser öncesi prova sesini duymak bile keyifli. Sahne kurulurken müzik başlıyor aslında."),
    _post("demo_baris", "muzik", "Vinil ile dijitali aynı şarkıda karşılaştırdım; fark ses değil, dinleme biçimiydi."),
    _post("demo_kaan", "muzik", "Gece kodlarken enstrümantal müzik dışında hiçbir şey işe yaramıyor. Sözlü şarkı dikkati bölüyor."),
    _post("demo_zeynep", "muzik", "Küçük sahnede tanımadığım bir grup dinledim; albümünü aynı gece aldım."),

    # ------------------------------------------------------------ seyahat
    _post("demo_ece", "seyahat", "İstanbul vapurunda gün doğumu: yerel hayatın en güzel tarafı, şehrin telaşı başlamadan birkaç dakika suya bakabilmek. Bugün bu rota çok sakindi.", "istanbul-vapur.jpg"),
    _post("demo_deneme", "seyahat", "Hafta sonu için plansız bir mahalle yürüyüşü yaptım; küçük kitapçı ve sakin bir park günün en iyi iki keşfiydi."),
    _post("demo_zeynep", "seyahat", "Günübirlik rota: erken tren, sahil yürüyüşü ve yerel pazarda uzun bir kahvaltı. Plan az olunca keşfe daha çok yer kaldı."),
    _post("demo_elif", "seyahat", "Şehirler arası otobüs yerine sabah trenini denedim; gezi başlamadan dinlenmiş oluyorsun."),
    _post("demo_aylin", "seyahat", "Küçük bir kasabada üç gün: rota yoktu, sadece yürüdük. Seyahat bazen plan yapmamaktır."),
    _post("demo_ece", "seyahat", "Tatil dönüşü en çok özlediğim şey manzara değil, o şehirdeki yürüme temposuydu."),
    _post("demo_zeynep", "seyahat", "Rota planlarken yerel öneri sormak haritadan daha iyi çalışıyor."),
    _post("demo_deneme", "seyahat", "Gezi çantasını yarıya indirdim ve seyahat iki kat rahat geçti."),
    _post("demo_elif", "seyahat", "Sahil yolunda uzun yürüyüş; rota kısa ama manzara her adımda değişiyor."),
    _post("demo_aylin", "seyahat", "Tren penceresinden geçen köyleri saymak da bir tür gezi sayılır."),

    # -------------------------------------------------------------- sanat
    _post("demo_aylin", "sanat", "Atölyede bugün mavi ve toprak tonlarını bir araya getirdik. Kültür ve sanat, gündelik bir objeyi hikâyeye dönüştürebiliyor.", "kultur-seramik.jpg"),
    _post("demo_mert", "sanat", "Kahveyi masaya değil de sohbete katınca ortaya çıkan an. Biraz mizah, biraz komik tesadüf; günün yorgunluğunu aldı.", "mizah-kafe.jpg"),
    _post("demo_aylin", "sanat", "Kısa film gösteriminden not: iyi bir sahne bazen tek cümle kurmadan karakterin bütün derdini anlatabiliyor."),
    _post("demo_selin", "sanat", "Erişilebilir tasarım için renk tek başına anlam taşımamalı. İkon, metin ve kontrast birlikte çalışınca arayüz herkes için güçleniyor."),
    _post("demo_baris", "sanat", "Sergide en çok ilgimi çeken eser en büyüğü değildi; köşedeki küçük illüstrasyon uzun süre aklımda kaldı."),
    _post("demo_aylin", "sanat", "Seramikte fırın çıkışı her zaman sürpriz. Tasarım kadar kabul etmeyi de öğretiyor."),
    _post("demo_zeynep", "sanat", "Tiyatroda oyuncunun sessiz kaldığı otuz saniye bütün oyundan daha etkiliydi."),
    _post("demo_selin", "sanat", "Arayüz de bir tasarım işi ama sanat değil; farkı amaç belirliyor."),
    _post("demo_aylin", "sanat", "Sinemada eski bir filmi tekrar izledim; on yıl sonra bambaşka bir sahne öne çıktı."),
    _post("demo_baris", "sanat", "Konser afişlerini biriktiriyorum; her biri dönemin tasarım dilini anlatıyor."),

    # -------------------------------------------------------------- bilim
    _post("demo_onur", "bilim", "Bugün gökyüzü gözlem grubunda ışık kirliliğini ölçtük. Küçük bir sensör bile mahalle ölçeğinde anlamlı veri üretebiliyor."),
    _post("demo_berk", "bilim", "Bir öneri sistemini değerlendirirken tek bir doğruluk sayısı yetmiyor; çeşitlilik, yenilik ve kullanıcı kontrolü de ölçülmeli."),
    _post("demo_nil", "bilim", "Sınıfta basit bir fizik deneyi yaptık; formülü ezberlemek yerine sonucu tahmin etmeye çalıştılar."),
    _post("demo_onur", "bilim", "Uzay teleskobu görüntülerini açık veri olarak incelemek şaşırtıcı derecede kolay. Araştırma artık laboratuvarla sınırlı değil."),
    _post("demo_elif", "bilim", "Uyku araştırmalarını okudukça antrenman planımı değiştirdim; bilim sporu da kapsıyor."),
    _post("demo_nil", "bilim", "Biyoloji dersinde mikroskop yerine telefon kamerası kullandık; deney yine çalıştı."),
    _post("demo_onur", "bilim", "Bir araştırmanın tekrar edilebilir olması, sonucundan daha önemli. Bunu öğrencilere de anlatmak gerek."),
    _post("demo_berk", "bilim", "Ölçüm yapmadan iyileştirme iddiasında bulunmak mühendislik değil temenni."),
    _post("demo_nil", "bilim", "Fizik sorusunu tersten kurunca sınıfın yarısı doğru cevabı buldu. Soru biçimi cevabı belirliyor."),
    _post("demo_onur", "bilim", "Doğa gözlemi için basit bir günlük tutmaya başladım; veri toplamak bu kadar sade olabiliyor."),

    # ------------------------------------------------------------- egitim
    _post("demo_berk", "egitim", "Bugünün öğrenme notu: iyi yazılım önce problemi anlatır, sonra kodu gösterir. Eğitim içeriklerinde örnek, açıklamadan daha güçlü olabilir."),
    _post("demo_deneme", "egitim", "Yeni bir konuyu öğrenirken on dakikalık günlük tekrarların uzun ama düzensiz çalışmadan daha kalıcı olduğunu fark ettim."),
    _post("demo_nil", "egitim", "Ders planını öğrencilerle birlikte yaptık; katılım kendiliğinden arttı."),
    _post("demo_nil", "egitim", "Sınav yerine küçük haftalık ödevler denedik. Öğrenme daha düzenli, kaygı daha az."),
    _post("demo_aylin", "egitim", "Seramik atölyesinde bugün hata diye ayırdığımız parçaları inceledik. El işi öğrenmenin güzel yanı, kusurun yönteme dönüşebilmesi."),
    _post("demo_nil", "egitim", "Kitap listesi vermek yerine tek bir bölüm verdim; herkes okudu."),
    _post("demo_selin", "egitim", "Yeni ekip üyesine dokümantasyon değil, iki saatlik eşli çalışma daha çok şey öğretti."),
    _post("demo_nil", "egitim", "Ders sonunda öğrencilere 'ne anlamadın' diye sormak, 'anladınız mı' demekten çok daha işe yarıyor."),
    _post("demo_deneme", "egitim", "Öğrenme günlüğü tutmaya başladım; bir hafta sonra tekrar okumak dersten daha faydalı."),
    _post("demo_berk", "egitim", "Bir konuyu anlatabiliyorsan öğrenmişsindir; anlatamıyorsan sadece okumuşsundur."),

    # ------------------------------------------------------------ saglik
    _post("demo_ece", "saglik", "Koşu sonrası dinlenmeyi antrenmanın parçası saymaya başladım. Uyku ve su takibi performanstan önce geliyor."),
    _post("demo_onur", "saglik", "Ekran molası için her saat kısa bir yürüyüş deniyorum. Küçük alışkanlıkların gün sonundaki etkisi şaşırtıcı."),
    _post("demo_elif", "saglik", "Beslenme günlüğü tutmak diyet yapmaktan daha çok işe yaradı; sadece görmek yetti."),
    _post("demo_zeynep", "saglik", "Bugünün mutfak deneyi mevsim sebzeleriyle renkli bir tabak oldu. Sağlıklı yemek karmaşık tarif demek değil."),
    _post("demo_elif", "saglik", "Uyku saatini sabitleyince sabah egzersizi kendiliğinden kolaylaştı."),
    _post("demo_nil", "saglik", "Ders arasında iki dakikalık nefes egzersizi sınıfın enerjisini değiştiriyor."),
    _post("demo_elif", "saglik", "Su içmeyi hatırlamak için masaya sürahi koydum; teknolojiye gerek kalmadı."),
    _post("demo_ece", "saglik", "Yürüyüş sonrası esneme rutinini kısalttım ama her gün yapıyorum. Süreklilik süreden önemli."),
    _post("demo_onur", "saglik", "Sağlıklı beslenme için market listesini akşamdan yazmak en etkili yöntem oldu."),
    _post("demo_zeynep", "saglik", "Kahvaltıyı erkene almak günün geri kalanındaki iştahı da düzenledi."),

    # ------------------------------------------------------- girisimcilik
    _post("demo_berk", "girisimcilik", "Demo gününde en değerli ölçüm alkış değil, kullanıcının nerede duraksadığıydı. Ürün kararını gözleme bağlayınca tartışma kısalıyor."),
    _post("demo_selin", "girisimcilik", "İlk prototipte üç ekranı kaldırdık. Kullanıcı hedefe daha hızlı ulaştı; bazen en iyi geliştirme, doğru şeyi silmek oluyor."),
    _post("demo_nil", "girisimcilik", "Okul içi küçük bir proje için bütçe değil, gönüllü zaman planı hazırladık. Startup mantığı burada da işliyor."),
    _post("demo_berk", "girisimcilik", "Yatırım görüşmesinde en zor soru 'kim kullanmayacak' oldu. Cevabı hazırlamak ürünü netleştirdi."),
    _post("demo_selin", "girisimcilik", "Kariyer değişikliği düşünenlere: önce bir hafta o işi taklit edin, sonra karar verin."),
    _post("demo_berk", "girisimcilik", "Girişimde ilk yıl öğrenilen en pahalı ders: yanlış müşteriye doğru ürün yapmak."),
    _post("demo_nil", "girisimcilik", "Ekip toplantısını yarıya indirdik, karar sayısı arttı."),
    _post("demo_selin", "girisimcilik", "Ürün yol haritasını üç aylıktan altı haftalığa çekince tahminler gerçekçileşti."),
    _post("demo_berk", "girisimcilik", "Startup için en iyi ölçüm, kullanıcının geri dönüp dönmediği. Gerisi hikâye."),
    _post("demo_nil", "girisimcilik", "Küçük bir atölye düzenledik; kayıt formu yerine sohbetle katılımcı bulduk."),

    # ------------------------------------------------------------- gundem
    _post("demo_deneme", "gundem", "Bugün ana akışta farklı görüşleri dengeli biçimde görebilmek üzerine düşündüm. İyi bir keşif deneyimi yalnızca popüler olanı tekrar etmemeli."),
    _post("demo_ece", "gundem", "Mahallede araçsız ulaşım için yeni bir rota konuşuluyor. Kent kararlarında yürüyenlerin deneyimi de masada olmalı."),
    _post("demo_onur", "gundem", "Haber okurken kaynağa bakma alışkanlığı edindim; gündem çok daha sakin görünüyor."),
    _post("demo_baris", "gundem", "Şehirde yeni açılan kültür merkezi gündem oldu; mahalleliye sorulmadan yapılmış olması tartışılıyor."),
    _post("demo_kaan", "gundem", "Sondakika bildirimlerini kapattım; günde bir kez haber okumak yetiyor."),
    _post("demo_deneme", "gundem", "Aynı haberi üç farklı kaynaktan okumak, yorumdan çok bilgi kazandırıyor."),
    _post("demo_ece", "gundem", "Toplu taşıma düzenlemesi hakkında açıklama bekleniyor; yerel yönetimin takvimi henüz net değil."),
    _post("demo_onur", "gundem", "Gündem hızlı akıyor ama önemli konular yavaş ilerliyor. İkisini ayırmak gerek."),
    _post("demo_baris", "gundem", "Bugünün haberi yarının arşivi; not almak işe yarıyor."),
    _post("demo_kaan", "gundem", "Sosyal medyada gündem takibi için tek bir zaman dilimi ayırmak dikkati topluyor."),
)


FOLLOWS = {
    "demo_deneme": ("demo_berk", "demo_ece", "demo_aylin", "demo_selin"),
    "demo_berk": ("demo_deneme", "demo_selin", "demo_onur", "demo_nil"),
    "demo_mert": ("demo_berk", "demo_zeynep", "demo_kaan"),
    "demo_ece": ("demo_deneme", "demo_onur", "demo_zeynep", "demo_elif"),
    "demo_aylin": ("demo_ece", "demo_selin", "demo_zeynep", "demo_baris"),
    "demo_selin": ("demo_berk", "demo_aylin", "demo_nil"),
    "demo_onur": ("demo_ece", "demo_berk", "demo_elif"),
    "demo_zeynep": ("demo_aylin", "demo_mert", "demo_baris"),
    "demo_kaan": ("demo_mert", "demo_berk", "demo_baris"),
    "demo_elif": ("demo_ece", "demo_onur", "demo_nil"),
    "demo_baris": ("demo_zeynep", "demo_aylin", "demo_kaan"),
    "demo_nil": ("demo_selin", "demo_onur", "demo_elif"),
}


COMMENTS = (
    (0, "demo_selin", "Akışı sadeleştiren kararları ayrıca görmek isterim."),
    (0, "demo_deneme", "Gerçek kullanıcı akışıyla ölçmek çok doğru bir başlangıç."),
    (11, "demo_kaan", "Bu akşam seansına ben de varım."),
    (21, "demo_elif", "Tempoyu düşürme tavsiyesi bende de işe yaradı."),
    (31, "demo_baris", "Bu grubun kaydı var mı acaba?"),
    (41, "demo_zeynep", "Bu rota gün doğumunda gerçekten çok güzel görünüyor."),
    (51, "demo_ece", "Renklerin birlikteliği çok sıcak olmuş."),
    (61, "demo_berk", "Mahalle ölçeğinde açık veri fikri harika."),
    (71, "demo_selin", "Eşli çalışma gerçekten en hızlı öğretme yolu."),
    (81, "demo_elif", "Uyku saatini sabitlemek bende de her şeyi değiştirdi."),
    (91, "demo_nil", "Yanlış müşteri dersi çok tanıdık geldi."),
    (101, "demo_deneme", "Kaynağa bakma alışkanlığı gerçekten sakinleştiriyor."),
)


STORIES = (
    ("demo_berk", "Bugünkü prototip masasından kısa bir kare.", "teknoloji-yapay-zeka.jpg"),
    ("demo_ece", "Sabah vapuru, sakin şehir.", "istanbul-vapur.jpg"),
    ("demo_aylin", "Atölyede yeni renk denemeleri.", "kultur-seramik.jpg"),
    ("demo_mert", "Kahve molası beklenmedik şekilde uzadı.", "mizah-kafe.jpg"),
    ("demo_kaan", "Gece seansı başlamak üzere.", "mizah-kafe.jpg"),
)


CONVERSATIONS = (
    ("demo_deneme", "demo_berk", ("Demo akışını yarın birlikte kontrol edelim mi?", "Olur, özellikle öneri nedenlerine bakalım.")),
    ("demo_ece", "demo_onur", ("Hafta sonu bisiklet rotası için saat kaç uygun?", "Sabah dokuzda sahil başlangıcı iyi olur.")),
    ("demo_aylin", "demo_zeynep", ("Cuma günkü kısa film gösterimine geliyor musun?", "Evet, çıkışta müzik listesini de konuşuruz.")),
    ("demo_kaan", "demo_mert", ("Akşam turnuvaya bakacak mısın?", "Bakarım, ilk maçtan sonra yazarım.")),
)


# Planın istediği davranış deseni: mesai saatinde yazılım, akşam oyun,
# sabah spor, gece müzik ve seyahat içeriklerine olumsuz tepki.
#
# Her kullanıcının ritmi ayrı olduğu için akışları da farklılaşıyor;
# sıralayıcı zaman dilimlerini ayrı ağırlıklandırdığından aynı kullanıcı
# sabah ve akşam farklı sıralama görüyor.
BEHAVIOURS = (
    DemoBehaviour(
        username="demo_deneme",
        routine=(
            (11, ("teknoloji", "yapay-zeka"), ("girisimcilik",)),
            (20, ("egitim",), ("gundem",)),
        ),
        disliked=("seyahat",),
        reports=("gundem",),
    ),
    DemoBehaviour(
        username="demo_berk",
        routine=(
            (10, ("teknoloji", "yapay-zeka"), ("bilim",)),
            (14, ("girisimcilik",), ("egitim",)),
        ),
        disliked=("muzik",),
    ),
    DemoBehaviour(
        username="demo_mert",
        routine=(
            (20, ("oyun",), ("muzik",)),
            (22, ("oyun", "muzik"), ("sanat",)),
        ),
        disliked=("spor",),
    ),
    DemoBehaviour(
        username="demo_ece",
        routine=(
            (7, ("spor",), ("saglik",)),
            (18, ("gundem",), ("seyahat",)),
        ),
        disliked=("oyun",),
    ),
    DemoBehaviour(
        username="demo_aylin",
        routine=(
            (13, ("sanat",), ("egitim",)),
            (21, ("muzik", "sanat"), ()),
        ),
        disliked=("spor",),
    ),
    DemoBehaviour(
        username="demo_selin",
        routine=(
            (10, ("teknoloji",), ("sanat",)),
            (16, ("girisimcilik", "egitim"), ()),
        ),
        disliked=("oyun",),
    ),
    DemoBehaviour(
        username="demo_onur",
        routine=(
            (7, ("spor",), ("saglik",)),
            (15, ("bilim",), ("gundem",)),
        ),
        disliked=("muzik",),
    ),
    DemoBehaviour(
        username="demo_zeynep",
        routine=(
            (23, ("muzik",), ("sanat",)),
            (12, ("saglik",), ("seyahat",)),
        ),
        disliked=("oyun",),
    ),
    DemoBehaviour(
        username="demo_kaan",
        routine=(
            (21, ("oyun",), ("teknoloji",)),
            (2, ("oyun", "muzik"), ()),
        ),
        disliked=("seyahat",),
        reports=("seyahat",),
    ),
    DemoBehaviour(
        username="demo_elif",
        routine=(
            (6, ("spor",), ("saglik",)),
            (13, ("bilim",), ("seyahat",)),
        ),
        disliked=("oyun",),
    ),
    DemoBehaviour(
        username="demo_baris",
        routine=(
            (23, ("muzik",), ("oyun",)),
            (19, ("sanat",), ("gundem",)),
        ),
        disliked=("spor",),
    ),
    DemoBehaviour(
        username="demo_nil",
        routine=(
            (9, ("egitim",), ("bilim",)),
            (17, ("girisimcilik",), ("saglik",)),
        ),
        disliked=("oyun",),
    ),
)


# Engelleme örneği. İki yönlü etkisi var: engelleyen kullanıcı engellenenin
# gönderilerini hiç göremiyor, dolayısıyla davranış üreteci de o gönderilere
# olay yazamaz. Aynı bilgi iki ayrı yerde tutulursa biri unutulur ve seed
# "Olayın gönderisi bulunamadı" ile durur.
BLOCKS = (("demo_zeynep", "demo_onur"),)


def is_blocked(viewer: str, author: str) -> bool:
    return any({viewer, author} == set(pair) for pair in BLOCKS)
