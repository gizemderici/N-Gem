package com.furkandurmaz.nsosyal.data

import com.furkandurmaz.nsosyal.model.*
import com.furkandurmaz.nsosyal.ui.theme.*

object MockSocialData {
    val interests = listOf(
        Interest("technology", "Teknoloji", "⌘", Blue),
        Interest("design", "Tasarım", "✎", Violet),
        Interest("education", "Eğitim", "▤", Green),
        Interest("sports", "Spor", "●", Cyan),
        Interest("culture", "Kültür", "◈", Coral),
        Interest("gaming", "Oyun", "◇", Violet),
        Interest("agenda", "Gündem", "▥", Amber),
        Interest("science", "Bilim", "⚛", Blue),
        Interest("local", "Yerel", "⌖", Green),
        Interest("comedy", "Mizah", "☺", Coral)
    )

    val currentUser = Creator(
        "furkan", "Furkan Durmaz", "@furkandurmaz", "FD",
        listOf(Cyan, Blue, Violet), true
    )
    val ayse = Creator("ayse", "Ayşe Yılmaz", "@ayseyaziyor", "AY", listOf(Coral, Amber), true)
    val mert = Creator("mert", "Mert Arslan", "@merttasarlar", "MA", listOf(Blue, Violet), true)
    val teknoloji = Creator("teknoloji", "Teknoloji Topluluğu", "@teknoloji", "TT", listOf(Cyan, Blue), true)
    val istanbul = Creator("istanbul", "İstanbul Bugün", "@istanbulbugun", "İB", listOf(Green, Cyan), true)

    val stories = listOf(
        SocialStory("story-me", currentUser, ArtworkStyle.FUTURE, "Hikâyeni paylaş", "Günün anını topluluğunla paylaş.", "Şimdi", false, true),
        SocialStory("story-ayse", ayse, ArtworkStyle.COMEDY, "Kahve molasına yetişenler burada mı?", "Bugünün küçük gülümseme payı ☕", "8 dk", false, false),
        SocialStory("story-mert", mert, ArtworkStyle.LEARNING, "Bir ekranı sadeleştirmenin üç yolu", "Bugünkü tasarım masasından kısa bir not.", "24 dk", false, false),
        SocialStory("story-tech", teknoloji, ArtworkStyle.FUTURE, "Günün teknoloji özeti", "Beş gelişme, yalnızca iki dakika.", "41 dk", false, false),
        SocialStory("story-istanbul", istanbul, ArtworkStyle.CITY, "İstanbul bu akşam", "Şehirde bugün kaçırmaman gereken üç etkinlik.", "1 sa", true, false)
    )

    val posts = listOf(
        SocialPost(
            "future-skills", teknoloji, "12 dk",
            "Yapay zekâ çağında öne çıkacak beş beceriyi tek bir listede topladık. En önemlisi araç kullanmak değil, doğru problemi tarif edebilmek.",
            "Teknoloji", ArtworkStyle.FUTURE, "Geleceğin 5 becerisi",
            "Yapay zekâ ile birlikte çalışmanın yeni yolu", false, null,
            "Teknoloji önceliğin nedeniyle",
            "Teknoloji başlangıç sıralamanda ilk sırada. Bu içerik ayrıca son kaydettiğin üretkenlik gönderileriyle benzer.",
            2840, 186, 420
        ),
        SocialPost(
            "istanbul-weekend", istanbul, "28 dk",
            "Bu hafta sonu İstanbul’da ücretsiz gezebileceğiniz dört sergi ve iki açık hava etkinliği.",
            "Yerel", ArtworkStyle.CITY, "Şehir bu hafta sonu senin",
            "6 ücretsiz etkinlik • İstanbul", true, "0:42",
            "Yerel etkinlik ilgin nedeniyle",
            "İstanbul’u seçtiğin ve yerel etkinlikleri daha önce kaydettiğin için gösterildi.",
            1290, 74, 231
        ),
        SocialPost(
            "design-note", mert, "1 sa",
            "İyi bir arayüz kullanıcıya ne yapacağını anlatmaz; bir sonraki adımı görünür kılar. Bugünün küçük tasarım notu bu olsun.",
            "Tasarım", null, null, null, false, null,
            "Tasarım ilgin ve takiplerin nedeniyle",
            "Tasarım ikinci ilgi alanın ve Mert Arslan’ı takip ediyorsun. Takip ilişkisi bu öneride en güçlü sinyal.",
            946, 61, 109
        ),
        SocialPost(
            "evening-comedy", ayse, "2 sa",
            "Toplantıda ‘son bir şey daha’ dendiğinde zihnimde başlayan kapanış jeneriği…",
            "Mizah", ArtworkStyle.COMEDY, "Mesai bitti sanmıştım",
            "Günün kısa molası", true, "0:18",
            "Akşam eğlence tercihin nedeniyle",
            "Akşam oturumlarında kısa mizah videolarını daha sık tamamlıyorsun. Saat sinyali düşük ağırlıkla kullanıldı.",
            7100, 342, 903
        ),
        SocialPost(
            "long-learning", teknoloji, "3 sa",
            "Derinleş: Öneri sistemleri bizi nasıl tanıyor ve kontrolü kullanıcıya nasıl geri verebiliriz?",
            "Eğitim", ArtworkStyle.LEARNING, "Algoritmayı anlamak",
            "12 dakikalık açıklayıcı video", true, "12:08",
            "Uzun içerik tercihin nedeniyle",
            "Akşam saatlerinde 8 dakikadan uzun eğitim videolarını kaydedip tamamladığın için önerildi.",
            1670, 128, 285
        )
    )

    val notifications = listOf(
        SocialNotification("n1", ayse, "gönderini beğendi.", "5 dk", NotificationKind.LIKED, true),
        SocialNotification("n2", mert, "seni takip etmeye başladı.", "32 dk", NotificationKind.FOLLOWED, true),
        SocialNotification("n3", teknoloji, "yorumuna yanıt verdi: ‘Açıklanabilirlik burada çok önemli.’", "1 sa", NotificationKind.REPLIED, false),
        SocialNotification("n4", istanbul, "İstanbul Tasarım topluluğunda yeni bir etkinlik paylaştı.", "3 sa", NotificationKind.COMMUNITY, false)
    )
}
