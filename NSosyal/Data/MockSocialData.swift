import SwiftUI

enum MockSocialData {
    static let interests: [Interest] = [
        .init(id: "technology", title: "Teknoloji", icon: "cpu", color: NSTheme.blue),
        .init(id: "design", title: "Tasarım", icon: "paintpalette", color: NSTheme.violet),
        .init(id: "education", title: "Eğitim", icon: "graduationcap", color: NSTheme.green),
        .init(id: "sports", title: "Spor", icon: "figure.run", color: NSTheme.cyan),
        .init(id: "culture", title: "Kültür", icon: "theatermasks", color: NSTheme.coral),
        .init(id: "gaming", title: "Oyun", icon: "gamecontroller", color: NSTheme.violet),
        .init(id: "agenda", title: "Gündem", icon: "newspaper", color: NSTheme.amber),
        .init(id: "science", title: "Bilim", icon: "atom", color: NSTheme.blue),
        .init(id: "local", title: "Yerel", icon: "mappin.and.ellipse", color: NSTheme.green),
        .init(id: "comedy", title: "Mizah", icon: "face.smiling", color: NSTheme.coral)
    ]

    static let ayse = Creator(
        id: "ayse",
        name: "Ayşe Yılmaz",
        handle: "@ayseyaziyor",
        initials: "AY",
        colors: [NSTheme.coral, NSTheme.amber],
        isVerified: true
    )

    static let mert = Creator(
        id: "mert",
        name: "Mert Arslan",
        handle: "@merttasarlar",
        initials: "MA",
        colors: [NSTheme.blue, NSTheme.violet],
        isVerified: true
    )

    static let teknoloji = Creator(
        id: "teknoloji",
        name: "Teknoloji Topluluğu",
        handle: "@teknoloji",
        initials: "TT",
        colors: [NSTheme.cyan, NSTheme.blue],
        isVerified: true
    )

    static let istanbul = Creator(
        id: "istanbul",
        name: "İstanbul Bugün",
        handle: "@istanbulbugun",
        initials: "İB",
        colors: [NSTheme.green, NSTheme.cyan],
        isVerified: true
    )

    static let posts: [SocialPost] = [
        SocialPost(
            id: "future-skills",
            creator: teknoloji,
            time: "12 dk",
            body: "Yapay zekâ çağında öne çıkacak beş beceriyi tek bir listede topladık. En önemlisi araç kullanmak değil, doğru problemi tarif edebilmek.",
            topic: "Teknoloji",
            artwork: .future,
            artworkTitle: "Geleceğin 5 becerisi",
            artworkSubtitle: "Yapay zekâ ile birlikte çalışmanın yeni yolu",
            isVideo: false,
            videoLength: nil,
            reason: "Teknoloji önceliğin nedeniyle",
            reasonDetail: "Teknoloji, başlangıç sıralamanda ilk sırada. Bu içerik ayrıca son kaydettiğin üretkenlik gönderileriyle benzer.",
            likeCount: 2840,
            commentCount: 186,
            shareCount: 420
        ),
        SocialPost(
            id: "istanbul-weekend",
            creator: istanbul,
            time: "28 dk",
            body: "Bu hafta sonu İstanbul’da ücretsiz gezebileceğiniz dört sergi ve iki açık hava etkinliği.",
            topic: "Yerel",
            artwork: .city,
            artworkTitle: "Şehir bu hafta sonu senin",
            artworkSubtitle: "6 ücretsiz etkinlik • İstanbul",
            isVideo: true,
            videoLength: "0:42",
            reason: "Çevrem moduna uygun",
            reasonDetail: "İstanbul’u seçtiğin ve yerel etkinlikleri daha önce kaydettiğin için gösterildi.",
            likeCount: 1290,
            commentCount: 74,
            shareCount: 231
        ),
        SocialPost(
            id: "design-note",
            creator: mert,
            time: "1 sa",
            body: "İyi bir arayüz kullanıcıya ne yapacağını anlatmaz; bir sonraki adımı görünür kılar. Bugünün küçük tasarım notu bu olsun.",
            topic: "Tasarım",
            artwork: nil,
            artworkTitle: nil,
            artworkSubtitle: nil,
            isVideo: false,
            videoLength: nil,
            reason: "Tasarım ilgin ve takiplerin nedeniyle",
            reasonDetail: "Tasarım ikinci ilgi alanın ve Mert Arslan’ı takip ediyorsun. Takip ilişkisi bu öneride en güçlü sinyal.",
            likeCount: 946,
            commentCount: 61,
            shareCount: 109
        ),
        SocialPost(
            id: "evening-comedy",
            creator: ayse,
            time: "2 sa",
            body: "Toplantıda ‘son bir şey daha’ dendiğinde zihnimde başlayan kapanış jeneriği…",
            topic: "Mizah",
            artwork: .comedy,
            artworkTitle: "Mesai bitti sanmıştım",
            artworkSubtitle: "Günün kısa molası",
            isVideo: true,
            videoLength: "0:18",
            reason: "Akşam eğlence tercihin nedeniyle",
            reasonDetail: "Akşam oturumlarında kısa mizah videolarını daha sık tamamlıyorsun. Saat sinyali düşük ağırlıkla kullanıldı.",
            likeCount: 7100,
            commentCount: 342,
            shareCount: 903
        ),
        SocialPost(
            id: "long-learning",
            creator: teknoloji,
            time: "3 sa",
            body: "Derinleş: Öneri sistemleri bizi nasıl tanıyor ve kontrolü kullanıcıya nasıl geri verebiliriz?",
            topic: "Eğitim",
            artwork: .learning,
            artworkTitle: "Algoritmayı anlamak",
            artworkSubtitle: "12 dakikalık açıklayıcı video",
            isVideo: true,
            videoLength: "12:08",
            reason: "Uzun içerik tercihin nedeniyle",
            reasonDetail: "Akşam saatlerinde 8 dakikadan uzun eğitim videolarını kaydedip tamamladığın için önerildi.",
            likeCount: 1670,
            commentCount: 128,
            shareCount: 285
        )
    ]

    static let notifications: [SocialNotification] = [
        .init(id: "n1", creator: ayse, message: "gönderini beğendi.", time: "5 dk", kind: .liked, isUnread: true),
        .init(id: "n2", creator: mert, message: "seni takip etmeye başladı.", time: "32 dk", kind: .followed, isUnread: true),
        .init(id: "n3", creator: teknoloji, message: "yorumuna yanıt verdi: ‘Kesinlikle, açıklanabilirlik burada çok önemli.’", time: "1 sa", kind: .replied, isUnread: false),
        .init(id: "n4", creator: istanbul, message: "İstanbul Tasarım topluluğunda yeni bir etkinlik paylaştı.", time: "3 sa", kind: .community, isUnread: false)
    ]
}
