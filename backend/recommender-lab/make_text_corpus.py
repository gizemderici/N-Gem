#!/usr/bin/env python3
"""Kurgu Türkçe derlem üreticisi: duygu + konu etiketli gönderiler.

Veri **kurgu**. Lisansı belirsiz bir veri kümesi indirmek yerine üretiliyor;
DATASETS.md'deki lisans notu bunun sebebini anlatıyor.

Üretim iki parçalı: konu bir özne öbeğinden, duygu bir yüklem öbeğinden
geliyor ve ikisi birbirinden bağımsız seçiliyor. Her hücreye ayrı bir cümle
kalıbı yazsaydık model konuyu ve duyguyu tek bir kalıptan okur, doğruluk da
gerçek başarı değil ezber olurdu.

Bu yine de kalıptan üretilmiş veri. ``corpus/holdout.csv`` geliştirme sırasında
birden fazla kez görüldüğü için yalnızca geliştirme ölçümüdür. İlk ``test.csv``
hataları da konu sözlüğü genişletilirken görüldü; sonradan alınan skoru bağımsız
final sonuç saymak veri sızıntısı olur. Dondurulmuş tek-seferlik iç doğrulama
``corpus/blind_test_v1.csv`` dosyasındadır. O da gerçek kullanıcı dağılımının
yerini tutmaz.

    python make_text_corpus.py --output corpus/train.csv
"""

from __future__ import annotations

import argparse
import csv
import random
from pathlib import Path

# TopicCatalog.LABELS ile aynı slug listesi. Ayrı bir konu listesi uydurmak,
# sınıflandırıcının çıktısını ürünün taksonomisine bağlanamaz hâle getirirdi.
KONU_OZNELERI: dict[str, list[str]] = {
    "teknoloji": [
        "yeni telefonun kamerası", "son güncelleme", "bu dizüstü bilgisayar",
        "kablosuz kulaklığın pil ömrü", "işletim sisteminin arayüzü",
        "uygulamanın son sürümü", "akıllı saatin sensörleri",
        "bulut yedekleme özelliği", "şarj hızı", "ekranın renk doğruluğu",
        "şarj adaptörü", "cihazın yeni yazılımı", "klavyenin tuş hissi",
        "modemin kapsama alanı", "tabletin kalem desteği",
        "veri aktarım hızı", "güvenlik yaması", "yazıcının bağlantısı",
        "hoparlörün ses kalitesi", "depolama alanı", "parmak izi okuyucusu",
        "yazılım kurulumu", "donanım tasarımı", "kablo yönetimi",
    ],
    "yapay-zeka": [
        "dil modelinin cevapları", "görüntü üreten model",
        "otomatik çeviri kalitesi", "sesli asistanın anlaması",
        "öneri algoritması", "modelin eğitim süresi",
        "yapay zekâ destekli düzenleme", "sohbet botunun tonu",
        "modelin verdiği kaynaklar", "otomatik özetleme",
        "modelin uydurduğu kaynaklar", "sesli komut algılama",
        "kurumun yapay zekâ politikası", "veri etiketleme süreci",
        "model çıktısının tutarlılığı", "istem mühendisliği",
        "otomatik altyazı", "yüz tanıma doğruluğu", "modelin önyargı testi",
        "eğitim veri kümesi", "çıkarım hızı", "yapay zekâ etiği tartışması",
        "kod tamamlama aracı", "duygu analizi sonuçları",
    ],
    "sanat": [
        "serginin küratörlüğü", "bu tablonun renk kullanımı",
        "afiş tasarımı", "heykelin dokusu", "yeni tipografi çalışması",
        "galerinin aydınlatması", "illüstrasyon serisi", "seramik atölyesi",
        "fotoğrafın kompozisyonu", "kapak tasarımı", "serginin katalogu",
        "müzenin ziyaret saatleri", "eserlerin yerleşimi", "baskı kalitesi",
        "fırça darbeleri", "sanatçının yeni serisi", "atölye çalışması",
        "enstalasyonun ölçeği", "desenin detayı", "çerçeveleme işi",
        "sergi salonunun düzeni", "gravür baskılar", "renk paleti",
        "portre çalışması",
    ],
    "egitim": [
        "dersin anlatımı", "sınav soruları", "çevrimiçi kursun içeriği",
        "öğretmenin geri bildirimi", "ders programı", "kaynak kitap",
        "laboratuvar saatleri", "ödev yükü", "sınıf mevcudu",
        "uzaktan eğitim platformu", "sınav sonuçları",
        "müfredat değişikliği", "okulun kütüphanesi", "devamsızlık kuralı",
        "seminer düzeni", "öğrenci danışmanlığı", "not sistemi",
        "staj programı", "dönem projesi", "alıştırma soruları",
        "konu anlatım videoları", "sınıf içi tartışma",
        "kayıt yenileme süreci", "burs başvurusu",
    ],
    "spor": [
        "takımın ikinci yarı oyunu", "hakemin kararları",
        "yeni transferin performansı", "kalecinin refleksleri",
        "maçın temposu", "antrenman programı", "stadyumun atmosferi",
        "sakatlık sonrası dönüş", "savunmanın organizasyonu", "kupa maçı",
        "sahanın zemini", "fikstür çekimi", "devre arası değişikliği",
        "teknik direktörün taktiği", "forvetin bitiriciliği",
        "lig sıralaması", "taraftar tribünü", "kondisyon durumu",
        "penaltı kararı", "yedek kulübesi", "ligin yeni sezonu",
        "turnuva formatı", "oyuncu kadrosu", "maç sonu röportajı",
    ],
    "gundem": [
        "belediyenin açıklaması", "trafik düzenlemesi", "yeni yönetmelik",
        "toplu taşıma zammı", "seçim takvimi", "kentsel dönüşüm planı",
        "hava kirliliği raporu", "asgari ücret görüşmeleri",
        "afet hazırlığı", "imar kararı", "çöp toplama saatleri",
        "meclis toplantısının tutanakları", "yeni otobüs hattı",
        "su kesintisi duyurusu", "kaldırım çalışması", "park düzenlemesi",
        "vergi düzenlemesi", "kamu ihalesi", "mahalle muhtarlığı hizmeti",
        "yol yapım çalışması", "zabıta denetimi", "nüfus verileri",
        "bütçe görüşmeleri", "kamuoyu yoklaması",
    ],
    "bilim": [
        "yeni yayımlanan makale", "deneyin yöntemi", "teleskop görüntüleri",
        "iklim verileri", "aşı çalışmasının sonuçları", "genetik araştırma",
        "veri setinin büyüklüğü", "hipotezin test edilmesi",
        "laboratuvar bulguları", "fosil keşfi", "örneklem büyüklüğü",
        "ölçüm cihazının hassasiyeti", "araştırma verileri",
        "hakem değerlendirmesi", "istatistiksel anlamlılık", "kontrol grubu",
        "mikroskop görüntüleri", "kimyasal analiz", "saha çalışması",
        "yayının atıf sayısı", "deneyin tekrarlanabilirliği",
        "bilimsel toplantı bildirisi", "örnek toplama süreci",
        "simülasyon sonuçları",
    ],
    "oyun": [
        "oyunun hikâye anlatımı", "çok oyunculu modu",
        "kontrollerin tepkisi", "yapay zekâ düşmanlar", "grafik ayarları",
        "yeni bölüm", "oyun içi ekonomi", "yükleme süreleri",
        "harita tasarımı", "zorluk dengesi", "yeni sezon içeriği",
        "eşya taşıma sistemi", "yan görevler", "kare hızı",
        "sunucu kararlılığı", "karakter yaratma ekranı", "bulmaca tasarımı",
        "oyunun müzikleri", "kayıt sistemi", "boss dövüşü",
        "envanter arayüzü", "çok oyunculu eşleştirme", "yama notları",
        "hikâye sonu",
    ],
    "muzik": [
        "albümün prodüksiyonu", "canlı performans", "şarkının sözleri",
        "bas hattı", "konser ses düzeni", "yeni tekli",
        "grubun yeni davulcusu", "vokal kaydı", "aranjman", "klip çekimi",
        "kayıttaki enstrümanlar", "turne tarihleri", "solistin yorumu",
        "stüdyo mikslemesi", "gitar solosu", "şarkının nakaratı",
        "albüm kapağı", "festival sahnesi", "koro düzeni", "piyano bölümü",
        "parçanın temposu", "ses mühendisliği", "çalma listesi",
        "akustik versiyon",
    ],
    "saglik": [
        "uyku düzeni", "beslenme programı", "randevu sistemi",
        "fizik tedavi seansları", "yeni ilacın etkisi",
        "günlük yürüyüş rutini", "hastanenin poliklinik saatleri",
        "diyetisyen görüşmesi", "nefes egzersizleri", "kan tahlili süreci",
        "poliklinikteki sıra sistemi", "ilacın yan etkileri",
        "tarama programının kapsamı", "aşı takvimi", "reçete yenileme",
        "ameliyat sonrası bakım", "doktorun bilgilendirmesi",
        "hastane kayıt işlemleri", "egzersiz planı", "su tüketimi takibi",
        "göz muayenesi", "laboratuvar sonuç süresi", "psikolog görüşmesi",
        "ağız ve diş bakımı",
    ],
    "girisimcilik": [
        "yatırım turu", "ürünün ilk sürümü", "ekip içi iletişim",
        "müşteri geri bildirimi", "fiyatlandırma modeli",
        "pazar araştırması", "hızlandırma programı", "büyüme oranı",
        "kurucu ortak arayışı", "gelir modeli", "aylık gelir",
        "şirketin yeni ofisi", "yatırım süreci", "işe alım süreci",
        "müşteri kazanım maliyeti", "ürün yol haritası", "rakip analizi",
        "satış ekibinin performansı", "ortaklık görüşmeleri", "nakit akışı",
        "hisse dağılımı", "müşteri desteği", "pazarlama bütçesi",
        "şirket kültürü",
    ],
    "seyahat": [
        "otelin konumu", "uçuşun rötarı", "şehrin toplu taşıması",
        "rehberli tur", "konaklama fiyatları", "sahilin temizliği",
        "yerel mutfak", "vize süreci", "havalimanı transferi",
        "kamp alanının imkânları", "tur rehberi", "odanın durumu",
        "uçuş tarifesi", "bagaj teslimi", "tren yolculuğu",
        "müze giriş sırası", "araç kiralama işlemi", "pasaport kontrolü",
        "şehir merkezine mesafe", "kahvaltı seçenekleri", "yürüyüş rotası",
        "gece konaklaması", "yerel pazar", "deniz manzarası",
    ],
}

# Yüklemler konudan bağımsız: aynı yüklem her konuya takılabiliyor.
#
# Liste bilerek geniş. İlk sürümde sınıf başına 12 yüklem vardı ve elle
# yazılmış sınama setinde duygu doğruluğu 0,60'ta kaldı: model duyguyu değil
# o on iki kalıbı öğrenmişti. Konu tarafında aynı sorun yok, çünkü konu zaten
# içerik kelimelerinden okunuyor; duygu ise yalnızca bu öbeklerden.
DUYGU_YUKLEMLERI: dict[str, list[str]] = {
    "olumlu": [
        "gerçekten başarılı", "beklentimin çok üstünde", "harika olmuş",
        "uzun zamandır gördüğüm en iyisi", "beni çok memnun etti",
        "kesinlikle tavsiye ederim", "tam istediğim gibi", "emeğe değmiş",
        "şaşırtıcı derecede iyi", "gayet tatmin edici",
        "bu sefer çok iyi düşünülmüş", "sonunda hak ettiği yere geldi",
        "cidden çok iyi", "beni şaşırttı", "mükemmel iş çıkarmışlar",
        "kusursuzdu", "çok memnun kaldım", "aklımda kaldı",
        "her kuruşuna değdi", "hiç sorun çıkarmadı", "işimi fazlasıyla gördü",
        "beklediğimden çok daha güzeldi", "gurur duydum",
        "içim rahat ettirdi", "günümü güzelleştirdi", "keyifle takip ettim",
        "adamakıllı yapılmış", "eline sağlık diyorum", "bayıldım",
        "sonuç harikaydı", "çok başarılı bulduğumu söylemeliyim",
        "beni ekrana bağladı", "iyi ki denemişim", "rahatlıkla öneririm",
        "gözle görülür şekilde düzelmiş",
    ],
    "olumsuz": [
        "tam bir hayal kırıklığı", "hiç beklediğim gibi değil",
        "berbat olmuş", "resmen zaman kaybı", "beni çok sinirlendirdi",
        "kimseye tavsiye etmem", "işe yaramıyor",
        "paranın karşılığını vermiyor", "şaşırtıcı derecede kötü",
        "hiç tatmin etmedi", "bu sefer çok özensiz olmuş",
        "giderek daha da kötüleşiyor", "hiç beğenmedim", "iğrençti",
        "çok kötü düşünülmüş", "eskisi daha iyiydi", "kullanılmıyor bile",
        "sürekli sorun çıkarıyor", "bu fiyata alınmaz", "canımı sıktı",
        "yarım yamalak yapılmış", "hiç uğraşmamışlar", "sabrımı taşırdı",
        "izlemesi eziyetti", "bir işe yaramadı", "boşuna beklemişim",
        "keşke hiç denemeseydim", "rezalet", "hiç kimse ilgilenmiyor",
        "beni pişman etti", "artık dayanılmaz", "hayal kırıklığına uğradım",
        "hiçbir şey düzelmemiş", "iyice içinden çıkılmaz olmuş",
        "kesinlikle önermiyorum",
    ],
    "notr": [
        "dün duyuruldu", "önceki sürümle aynı görünüyor",
        "hakkında henüz bir yorum yapmayacağım", "bugün gündeme geldi",
        "detayları önümüzdeki hafta açıklanacak", "şu an inceleniyor",
        "üzerine bir yazı hazırlıyorum", "kaydı arşive eklendi",
        "geçen yılki ile benzer", "hakkında veri toplanıyor",
        "resmi kanaldan paylaşıldı", "listeye eklendi",
        "yarın açıklanacak", "gelecek ay yayımlanacak",
        "test aşamasında", "ayrıntılar sitede yer alıyor",
        "başvurular bu ay açılıyor", "tarihi belli oldu",
        "programa alındı", "duyuru metni yayımlandı",
        "üç bölümden oluşuyor", "iki gün sürecek",
        "kayıtlar devam ediyor", "sonuçlar sisteme yüklenecek",
        "geçen haftaki toplantıda konuşuldu", "henüz kesinleşmedi",
        "ekip tarafından değerlendiriliyor", "takvimi paylaşıldı",
        "arşivde bulunabiliyor", "resmen başladı",
        "önümüzdeki dönem devreye giriyor", "bilgilendirme yapıldı",
        "sürüm notlarında geçiyor", "kapsamı genişletildi",
        "ilgili birime iletildi",
    ],
}

ONEKLER = [
    "", "", "", "Bence ", "Açıkçası ", "Kısaca ", "Dün denedim, ",
    "İlk izlenimim: ",
]
SONEKLER = [
    ".", ".", ".", "!", ". Görüşmek üzere.", ". Not düşeyim.",
    ". Siz ne düşünüyorsunuz?",
]


def uret(hedef: int, seed: int) -> list[tuple[str, str, str]]:
    rastgele = random.Random(seed)
    hucreler = [(k, d) for k in KONU_OZNELERI for d in DUYGU_YUKLEMLERI]
    # Sınıflar dengeli olmalı: dengesiz derlemde model çoğunluk sınıfını
    # söyleyerek yüksek doğruluk alır ve hiçbir şey öğrenmemiş olur.
    hucre_basina = max(1, hedef // len(hucreler))

    satirlar: list[tuple[str, str, str]] = []
    gorulen: set[str] = set()
    for hucre_no, (konu, duygu) in enumerate(hucreler):
        ozneler = KONU_OZNELERI[konu]
        yuklemler = DUYGU_YUKLEMLERI[duygu]
        # Özneler ve yüklemler sırayla dolaşılıyor, rastgele seçilmiyor.
        # Rastgele seçimde bazı özneler derlemde bir kez bile geçmiyordu ve
        # TF-IDF'in `min_df` eşiği o kelimeleri tamamen atıyordu; konu
        # doğruluğu bu yüzden on puan aşağıdaydı. Kaydırma her hücrede farklı
        # olduğu için aynı özne hep aynı yüklemle eşleşmiyor.
        for adim in range(hucre_basina):
            ozne = ozneler[(adim + hucre_no) % len(ozneler)]
            yuklem = yuklemler[(adim * 7 + hucre_no) % len(yuklemler)]
            metin = (
                rastgele.choice(ONEKLER)
                + ozne
                + " "
                + yuklem
                + rastgele.choice(SONEKLER)
            )
            metin = metin[0].upper() + metin[1:]
            if metin in gorulen:
                continue
            gorulen.add(metin)
            satirlar.append((metin, duygu, konu))
    rastgele.shuffle(satirlar)
    return satirlar


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("corpus/train.csv"))
    parser.add_argument("--count", type=int, default=432, help="hedef satır sayısı")
    parser.add_argument("--seed", type=int, default=20260824)
    args = parser.parse_args()

    satirlar = uret(args.count, args.seed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["metin", "duygu", "konu"])
        writer.writerows(satirlar)
    print(f"{len(satirlar)} satir -> {args.output}")


if __name__ == "__main__":
    main()
