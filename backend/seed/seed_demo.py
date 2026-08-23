#!/usr/bin/env python3
"""NSosyal yerel demosunu kurgu kullanıcı ve özgün medya verileriyle doldurur.

Betik yalnızca genel API'yi kullanır ve tekrar çalıştırılabilir: aynı gönderi,
yorum, mesaj, aktif hikâye veya rapor ikinci kez oluşturulmaz.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DEFAULT_PASSWORD = "Demo1234!"
ASSETS_DIR = Path(__file__).resolve().parent / "assets"


@dataclass(frozen=True)
class DemoUser:
    full_name: str
    username: str
    email: str
    bio: str
    topics: tuple[str, ...]
    avatar: str


USERS = (
    DemoUser("Deniz Test", "demo_deneme", "demo.deneme@nsosyal.local", "Yeni fikirleri, şehir hayatını ve teknolojiyi keşfediyorum.", ("teknoloji", "yapay-zeka", "seyahat", "egitim"), "istanbul-vapur.jpg"),
    DemoUser("Berk Yalın", "demo_berk", "demo.berk@nsosyal.local", "Mobil ürün geliştirici; küçük deneyler ve ölçülebilir sonuçlar.", ("teknoloji", "yapay-zeka", "girisimcilik", "bilim"), "teknoloji-yapay-zeka.jpg"),
    DemoUser("Mert Can", "demo_mert", "demo.mert@nsosyal.local", "Kahve, oyun ve günlük hayatın komik tarafları.", ("oyun", "muzik", "teknoloji", "sanat"), "mizah-kafe.jpg"),
    DemoUser("Ece Aydın", "demo_ece", "demo.ece@nsosyal.local", "Koşuyorum, geziyorum ve İstanbul notları biriktiriyorum.", ("spor", "seyahat", "saglik", "gundem"), "istanbul-vapur.jpg"),
    DemoUser("Aylin Demir", "demo_aylin", "demo.aylin@nsosyal.local", "Seramik, sinema ve yerel kültür üzerine notlar.", ("sanat", "muzik", "egitim", "seyahat"), "kultur-seramik.jpg"),
    DemoUser("Selin Akay", "demo_selin", "demo.selin@nsosyal.local", "Ürün tasarımcısı; sade arayüzler ve erişilebilir deneyimler.", ("teknoloji", "sanat", "egitim", "girisimcilik"), "teknoloji-yapay-zeka.jpg"),
    DemoUser("Onur Kaya", "demo_onur", "demo.onur@nsosyal.local", "Bilim, spor ve sürdürülebilir yaşam meraklısı.", ("bilim", "spor", "saglik", "gundem"), "mizah-kafe.jpg"),
    DemoUser("Zeynep Aras", "demo_zeynep", "demo.zeynep@nsosyal.local", "Müzik, yemek ve hafta sonu rotaları paylaşıyorum.", ("muzik", "seyahat", "sanat", "saglik"), "kultur-seramik.jpg"),
)


POSTS = (
    {"username": "demo_berk", "topic": "yapay-zeka", "text": "Yapay zeka destekli mobil uygulama prototipini bugün gerçek kullanıcı akışıyla denedik. Teknoloji ancak sade bir deneyime dönüştüğünde işe yarıyor. #teknoloji #yazılım", "asset": "teknoloji-yapay-zeka.jpg"},
    {"username": "demo_berk", "topic": "teknoloji", "text": "Altı saniyelik ürün günlüğü: yapay zeka, mobil arayüz ve küçük ama ölçülebilir bir iyileştirme. Mesai saatlerinde böyle kısa teknoloji videoları iyi gidiyor.", "asset": "teknoloji-demo.mp4"},
    {"username": "demo_berk", "topic": "egitim", "text": "Bugünün öğrenme notu: iyi yazılım önce problemi anlatır, sonra kodu gösterir. Eğitim içeriklerinde örnek, açıklamadan daha güçlü olabilir."},
    {"username": "demo_mert", "topic": "sanat", "text": "Kahveyi masaya değil de sohbete katınca ortaya çıkan an. Biraz mizah, biraz komik tesadüf; günün yorgunluğunu aldı. ☕️", "asset": "mizah-kafe.jpg"},
    {"username": "demo_mert", "topic": "oyun", "text": "Akşam molası için kısa mizah videosu: kahve köpüğü planı bozdu, arkadaşlar günü kurtardı. Eğlence bazen tam olarak budur.", "asset": "mizah-demo.mp4"},
    {"username": "demo_mert", "topic": "teknoloji", "text": "Toplantıda 'son bir madde' denince bilgisayarın pilinin yüzde bire düşmesi kadar gerçek bir espri yok. #mizah #komik"},
    {"username": "demo_ece", "topic": "seyahat", "text": "İstanbul vapurunda gün doğumu: yerel hayatın en güzel tarafı, şehrin telaşı başlamadan birkaç dakika suya bakabilmek. Bugün bu rota çok sakindi.", "asset": "istanbul-vapur.jpg"},
    {"username": "demo_ece", "topic": "spor", "text": "Sahilde otuz dakikalık koşu tamamlandı. Spor için büyük hedeflerden önce düzenli küçük adımların daha sürdürülebilir olduğunu yeniden gördüm."},
    {"username": "demo_aylin", "topic": "sanat", "text": "Atölyede bugün mavi ve toprak tonlarını bir araya getirdik. Kültür ve sanat, gündelik bir objeyi hikâyeye dönüştürebiliyor.", "asset": "kultur-seramik.jpg"},
    {"username": "demo_aylin", "topic": "muzik", "text": "Bu haftanın kültür listesi: bir kısa film, yeni bir müzik albümü ve mahalledeki küçük seramik sergisi. Yerel sanat üreticilerini keşfetmeyi seviyorum."},
    {"username": "demo_selin", "topic": "teknoloji", "text": "Arayüz tasarımında bugünkü ders: kullanıcıya beş seçenek vermek yerine doğru anda tek net eylem sunmak. Sadelik, eksiklik değil önceliklendirmedir."},
    {"username": "demo_deneme", "topic": "gundem", "text": "Bugün ana akışta farklı görüşleri dengeli biçimde görebilmek üzerine düşündüm. İyi bir keşif deneyimi yalnızca popüler olanı tekrar etmemeli."},
    {"username": "demo_deneme", "topic": "seyahat", "text": "Hafta sonu için plansız bir mahalle yürüyüşü yaptım; küçük kitapçı ve sakin bir park günün en iyi iki keşfiydi."},
    {"username": "demo_deneme", "topic": "egitim", "text": "Yeni bir konuyu öğrenirken on dakikalık günlük tekrarların uzun ama düzensiz çalışmadan daha kalıcı olduğunu fark ettim."},
    {"username": "demo_berk", "topic": "girisimcilik", "text": "Demo gününde en değerli ölçüm alkış değil, kullanıcının nerede duraksadığıydı. Ürün kararını gözleme bağlayınca tartışma kısalıyor."},
    {"username": "demo_berk", "topic": "bilim", "text": "Bir öneri sistemini değerlendirirken tek bir doğruluk sayısı yetmiyor; çeşitlilik, yenilik ve kullanıcı kontrolü de ölçülmeli."},
    {"username": "demo_mert", "topic": "oyun", "text": "Bu akşam ekipçe kısa bir strateji oyunu oynadık. Kazanan plan değil, son anda yapılan sakin iletişim oldu. #oyun"},
    {"username": "demo_mert", "topic": "muzik", "text": "Çalışma listesine lo-fi yerine eski film müzikleri ekledim; aynı masanın havası bir anda değişti."},
    {"username": "demo_ece", "topic": "saglik", "text": "Koşu sonrası dinlenmeyi antrenmanın parçası saymaya başladım. Uyku ve su takibi performanstan önce geliyor."},
    {"username": "demo_ece", "topic": "gundem", "text": "Mahallede araçsız ulaşım için yeni bir rota konuşuluyor. Kent kararlarında yürüyenlerin deneyimi de masada olmalı."},
    {"username": "demo_aylin", "topic": "sanat", "text": "Kısa film gösteriminden not: iyi bir sahne bazen tek cümle kurmadan karakterin bütün derdini anlatabiliyor."},
    {"username": "demo_aylin", "topic": "egitim", "text": "Seramik atölyesinde bugün hata diye ayırdığımız parçaları inceledik. El işi öğrenmenin güzel yanı, kusurun yönteme dönüşebilmesi."},
    {"username": "demo_selin", "topic": "girisimcilik", "text": "İlk prototipte üç ekranı kaldırdık. Kullanıcı hedefe daha hızlı ulaştı; bazen en iyi geliştirme, doğru şeyi silmek oluyor."},
    {"username": "demo_selin", "topic": "sanat", "text": "Erişilebilir tasarım için renk tek başına anlam taşımamalı. İkon, metin ve kontrast birlikte çalışınca arayüz herkes için güçleniyor."},
    {"username": "demo_onur", "topic": "bilim", "text": "Bugün gökyüzü gözlem grubunda ışık kirliliğini ölçtük. Küçük bir sensör bile mahalle ölçeğinde anlamlı veri üretebiliyor."},
    {"username": "demo_onur", "topic": "spor", "text": "Bisiklet rotasında hız yerine düzenli tempoya odaklandım. Eve daha az yorulup daha uzun mesafeyle döndüm."},
    {"username": "demo_onur", "topic": "saglik", "text": "Ekran molası için her saat kısa bir yürüyüş deniyorum. Küçük alışkanlıkların gün sonundaki etkisi şaşırtıcı."},
    {"username": "demo_zeynep", "topic": "muzik", "text": "Mahalle sahnesinde üç kişilik bir caz grubunu dinledim. Canlı müziğin en güzel yanı, aynı parçanın her seferinde değişmesi."},
    {"username": "demo_zeynep", "topic": "seyahat", "text": "Günübirlik rota: erken tren, sahil yürüyüşü ve yerel pazarda uzun bir kahvaltı. Plan az olunca keşfe daha çok yer kaldı."},
    {"username": "demo_zeynep", "topic": "saglik", "text": "Bugünün mutfak deneyi mevsim sebzeleriyle renkli bir tabak oldu. Sağlıklı yemek karmaşık tarif demek değil."},
)

FOLLOWS = {
    "demo_deneme": ("demo_berk", "demo_ece", "demo_aylin", "demo_selin"),
    "demo_berk": ("demo_deneme", "demo_selin", "demo_onur"),
    "demo_mert": ("demo_berk", "demo_zeynep"),
    "demo_ece": ("demo_deneme", "demo_onur", "demo_zeynep"),
    "demo_aylin": ("demo_ece", "demo_selin", "demo_zeynep"),
    "demo_selin": ("demo_berk", "demo_aylin"),
    "demo_onur": ("demo_ece", "demo_berk"),
    "demo_zeynep": ("demo_aylin", "demo_mert"),
}

COMMENTS = (
    (0, "demo_selin", "Akışı sadeleştiren kararları ayrıca görmek isterim."),
    (0, "demo_deneme", "Gerçek kullanıcı akışıyla ölçmek çok doğru bir başlangıç."),
    (6, "demo_zeynep", "Bu rota gün doğumunda gerçekten çok güzel görünüyor."),
    (7, "demo_onur", "Küçük ve düzenli adım yaklaşımı bende de çalışıyor."),
    (8, "demo_ece", "Renklerin birlikteliği çok sıcak olmuş."),
    (14, "demo_deneme", "Duraksama noktaları demo için güzel bir sinyal."),
    (16, "demo_zeynep", "Bir sonraki oyun akşamına ben de katılacağım."),
    (20, "demo_mert", "Sessiz anlatım gerçekten uzun süre akılda kalıyor."),
    (24, "demo_berk", "Mahalle ölçeğinde açık veri fikri harika."),
    (27, "demo_aylin", "Canlı performansın sürprizi kayıtta olmuyor."),
)

STORIES = (
    ("demo_berk", "Bugünkü prototip masasından kısa bir kare.", "teknoloji-yapay-zeka.jpg"),
    ("demo_ece", "Sabah vapuru, sakin şehir.", "istanbul-vapur.jpg"),
    ("demo_aylin", "Atölyede yeni renk denemeleri.", "kultur-seramik.jpg"),
    ("demo_mert", "Kahve molası beklenmedik şekilde uzadı.", "mizah-kafe.jpg"),
)

CONVERSATIONS = (
    ("demo_deneme", "demo_berk", ("Demo akışını yarın birlikte kontrol edelim mi?", "Olur, özellikle öneri nedenlerine bakalım.")),
    ("demo_ece", "demo_onur", ("Hafta sonu bisiklet rotası için saat kaç uygun?", "Sabah dokuzda sahil başlangıcı iyi olur.")),
    ("demo_aylin", "demo_zeynep", ("Cuma günkü kısa film gösterimine geliyor musun?", "Evet, çıkışta müzik listesini de konuşuruz.")),
)


class ApiError(RuntimeError):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"HTTP {status} {code}: {message}")
        self.status = status
        self.code = code


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None, token: str | None = None) -> dict[str, Any]:
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        if data is None and method in {"POST", "PUT", "PATCH"}:
            data = b""
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=20) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            raw = error.read()
            detail = json.loads(raw) if raw else {}
            raise ApiError(error.code, detail.get("code", "HTTP_ERROR"), detail.get("message", str(error))) from error
        except URLError as error:
            raise RuntimeError(f"Backend bağlantısı kurulamadı: {error.reason}") from error

    def upload(self, url: str, path: Path, required_headers: dict[str, str]) -> None:
        request = Request(url, data=path.read_bytes(), headers=required_headers, method="PUT")
        try:
            with urlopen(request, timeout=60) as response:
                if response.status not in range(200, 300):
                    raise RuntimeError(f"Depolama yüklemesi başarısız: HTTP {response.status}")
        except HTTPError as error:
            raise RuntimeError(f"Depolama yüklemesi başarısız: HTTP {error.code} {error.read().decode(errors='replace')}") from error


def authenticate(client: ApiClient, user: DemoUser) -> str:
    for attempt in range(2):
        try:
            return client.request("POST", "/api/v1/auth/login", {"email": user.email, "password": DEFAULT_PASSWORD})["tokens"]["accessToken"]
        except ApiError as error:
            if error.status == 429 and attempt == 0:
                print("[demo] Giriş hız sınırı doldu; 60 saniye bekleniyor...", file=sys.stderr)
                time.sleep(60)
                continue
            if error.status not in {401, 403}:
                raise
            break
    try:
        registration = client.request("POST", "/api/v1/auth/register", {"fullName": user.full_name, "username": user.username, "email": user.email, "password": DEFAULT_PASSWORD, "acceptedTerms": True})
        code = registration.get("developmentCode")
    except ApiError as error:
        if error.status != 409:
            raise
        code = client.request("POST", "/api/v1/auth/resend-verification", {"email": user.email}).get("developmentCode")
    if not code:
        raise RuntimeError("Geliştirme doğrulama kodu alınamadı. EXPOSE_DEVELOPMENT_CODES=true olmalı.")
    return client.request("POST", "/api/v1/auth/verify-email", {"email": user.email, "code": code})["tokens"]["accessToken"]


def feed(client: ApiClient, token: str, local_hour: int, personalized: bool) -> dict[str, Any]:
    query = urlencode({"limit": 50, "personalized": str(personalized).lower(), "sessionId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"nsosyal-demo-feed-{local_hour}")), "localHour": local_hour, "timezoneOffsetMinutes": 180})
    return client.request("GET", f"/api/v1/posts/feed?{query}", token=token)


def upload_media(client: ApiClient, token: str, asset_name: str) -> str:
    path = ASSETS_DIR / asset_name
    if not path.is_file():
        raise RuntimeError(f"Demo medya dosyası bulunamadı: {path}")
    mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    created = client.request("POST", "/api/v1/media/uploads", {"filename": path.name, "mimeType": mime_type, "sizeBytes": path.stat().st_size}, token)
    client.upload(created["uploadUrl"], path, created["requiredHeaders"])
    client.request("POST", f"/api/v1/media/{created['mediaId']}/complete", token=token)
    return created["mediaId"]


def configure_users(client: ApiClient, tokens: dict[str, str], topic_ids: dict[str, str]) -> int:
    avatars_added = 0
    for user in USERS:
        token = tokens[user.username]
        profile = client.request("GET", f"/api/v1/users/{user.username}", token=token)
        client.request("PATCH", "/api/v1/users/me/profile", {"fullName": user.full_name, "bio": user.bio}, token)
        client.request("PUT", "/api/v1/users/me/topics", {"topicIds": [topic_ids[slug] for slug in user.topics]}, token)
        if not profile.get("avatarUrl"):
            media_id = upload_media(client, token, user.avatar)
            client.request("PUT", "/api/v1/users/me/avatar", {"mediaId": media_id}, token)
            avatars_added += 1
    return avatars_added


def user_posts(client: ApiClient, token: str, username: str) -> list[dict[str, Any]]:
    return client.request("GET", f"/api/v1/users/{username}/posts?limit=50", token=token)["items"]


def all_demo_posts(client: ApiClient, tokens: dict[str, str]) -> list[dict[str, Any]]:
    viewer_token = tokens[USERS[0].username]
    return [post for user in USERS for post in user_posts(client, viewer_token, user.username)]


def create_missing_posts(client: ApiClient, tokens: dict[str, str], topic_ids: dict[str, str]) -> tuple[list[dict[str, Any]], int]:
    current = all_demo_posts(client, tokens)
    existing = {(item["author"]["username"], item["text"]) for item in current}
    created_count = 0
    for spec in POSTS:
        key = (spec["username"], spec["text"])
        if key in existing:
            continue
        media_ids = [upload_media(client, tokens[spec["username"]], spec["asset"])] if spec.get("asset") else []
        client.request("POST", "/api/v1/posts", {"text": spec["text"], "mediaIds": media_ids, "topicIds": [topic_ids[spec["topic"]]]}, tokens[spec["username"]])
        existing.add(key)
        created_count += 1
    return all_demo_posts(client, tokens), created_count


def add_follows(client: ApiClient, tokens: dict[str, str]) -> None:
    for follower, followed_users in FOLLOWS.items():
        for followed in followed_users:
            client.request("PUT", f"/api/v1/users/{followed}/follow", token=tokens[follower])


def add_social_proof(client: ApiClient, tokens: dict[str, str], posts: list[dict[str, Any]]) -> None:
    for user_index, (username, token) in enumerate(tokens.items()):
        for post_index, post in enumerate(posts):
            author = post["author"]["username"]
            blocked_pair = {username, author} == {"demo_zeynep", "demo_onur"}
            if author == username or blocked_pair:
                continue
            if (post_index + user_index) % 5 == 0:
                client.request("PUT", f"/api/v1/posts/{post['id']}/like", token=token)
            if (post_index + user_index) % 11 == 0:
                client.request("PUT", f"/api/v1/posts/{post['id']}/save", token=token)


def add_comments(client: ApiClient, tokens: dict[str, str], posts: list[dict[str, Any]]) -> int:
    by_key = {(item["author"]["username"], item["text"]): item for item in posts}
    created = 0
    for post_index, commenter, text in COMMENTS:
        spec = POSTS[post_index]
        post = by_key[(spec["username"], spec["text"])]
        current = client.request("GET", f"/api/v1/posts/{post['id']}/comments?limit=50", token=tokens[commenter])["items"]
        if any(item["author"]["username"] == commenter and item["text"] == text for item in current):
            continue
        client.request("POST", f"/api/v1/posts/{post['id']}/comments", {"text": text}, tokens[commenter])
        created += 1
    return created


def add_stories(client: ApiClient, tokens: dict[str, str]) -> tuple[list[str], int]:
    story_ids: list[str] = []
    created = 0
    for username, caption, asset in STORIES:
        response = client.request("GET", f"/api/v1/users/{username}/stories", token=tokens[USERS[0].username])
        current = [story for group in response["items"] for story in group["stories"]]
        matching = next((story for story in current if story.get("caption") == caption), None)
        if matching:
            story_ids.append(matching["id"])
            continue
        media_id = upload_media(client, tokens[username], asset)
        story = client.request("POST", "/api/v1/stories", {"mediaId": media_id, "caption": caption}, tokens[username])
        story_ids.append(story["id"])
        created += 1
    for viewer in ("demo_deneme", "demo_selin"):
        for story_id in story_ids:
            try:
                client.request("PUT", f"/api/v1/stories/{story_id}/view", token=tokens[viewer])
            except ApiError as error:
                if error.code != "STORY_NOT_FOUND":
                    raise
    return story_ids, created


def add_conversations(client: ApiClient, tokens: dict[str, str]) -> int:
    created = 0
    for first, second, texts in CONVERSATIONS:
        conversation = client.request("POST", "/api/v1/conversations", {"username": second}, tokens[first])
        conversation_id = conversation["id"]
        current = client.request("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=tokens[first])["items"]
        existing = {(item["sender"]["username"], item.get("text")) for item in current}
        for sender, message_text in ((first, texts[0]), (second, texts[1])):
            if (sender, message_text) not in existing:
                client.request("POST", f"/api/v1/conversations/{conversation_id}/messages", {"text": message_text}, tokens[sender])
                created += 1
        client.request("PUT", f"/api/v1/conversations/{conversation_id}/read", token=tokens[first])
        client.request("PUT", f"/api/v1/conversations/{conversation_id}/read", token=tokens[second])
    return created


def add_moderation_examples(client: ApiClient, tokens: dict[str, str]) -> dict[str, Any]:
    reporter = "demo_zeynep"
    target = "demo_onur"
    details = "Yerel demo için kurgu şikâyet kaydı."
    current_reports = client.request("GET", "/api/v1/users/me/reports?limit=50", token=tokens[reporter])["items"]
    report = next((item for item in current_reports if item["targetType"] == "USER" and item["reason"] == "SPAM" and item.get("details") == details), None)
    if report is None:
        target_profile = client.request("GET", f"/api/v1/users/{target}", token=tokens[reporter])
        report = client.request("POST", "/api/v1/reports", {"targetType": "USER", "targetId": target_profile["id"], "reason": "SPAM", "details": details}, tokens[reporter])
    block = client.request("PUT", f"/api/v1/users/{target}/block", token=tokens[reporter])
    return {"reportId": report["id"], "alreadyPresent": report in current_reports, "blocked": block["blocked"]}


def seed_context_events(client: ApiClient, viewer_token: str, posts: list[dict[str, Any]]) -> dict[str, Any]:
    by_text = {item["text"]: item for item in posts}
    session_id = uuid.uuid5(uuid.NAMESPACE_URL, "nsosyal-demo-context-session-v2")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    scenarios = ((0, "content_view", 11, 52_000, 0.86), (0, "content_saved", 11, None, None), (1, "content_complete", 14, 6_000, 1.0), (3, "content_liked", 21, None, None), (4, "content_complete", 21, 6_000, 1.0), (6, "content_view", 19, 40_000, 0.75), (8, "content_saved", 20, None, None))
    events = []
    for post_index, event_type, hour, dwell, completion in scenarios:
        post_id = by_text[POSTS[post_index]["text"]]["id"]
        events.append({"clientEventId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"nsosyal-demo-v2:{post_id}:{event_type}:{hour}")), "sessionId": str(session_id), "postId": post_id, "eventType": event_type, "surface": "seed_demo", "dwellMillis": dwell, "completionRatio": completion, "localHour": hour, "timezoneOffsetMinutes": 180, "occurredAt": now})
    batch = client.request("POST", "/api/v1/recommendations/events", {"events": events}, viewer_token)
    profile = client.request("GET", "/api/v1/recommendations/profile?localHour=21", token=viewer_token)
    profile["seedBatch"] = batch
    return profile


def compact_feed(result: dict[str, Any], limit: int = 5) -> list[dict[str, str]]:
    return [{"author": item["author"]["username"], "preview": item["text"][:72] + ("…" if len(item["text"]) > 72 else ""), "media": item["media"][0]["mimeType"] if item["media"] else "text/plain", "reason": item.get("recommendationReason") or "-"} for item in result["items"][:limit]]


def verify_media_downloads(posts: list[dict[str, Any]]) -> dict[str, int]:
    checked = images = videos = 0
    for post in posts:
        for media in post["media"]:
            request = Request(media["url"], headers={"Range": "bytes=0-31"}, method="GET")
            try:
                with urlopen(request, timeout=20) as response:
                    signature = response.read(32)
            except (HTTPError, URLError) as error:
                raise RuntimeError(f"Süreli medya URL'si indirilemedi: {media['id']} ({error})") from error
            mime_type = media["mimeType"]
            valid = (mime_type == "image/jpeg" and signature.startswith(b"\xff\xd8\xff") or mime_type == "image/png" and signature.startswith(b"\x89PNG\r\n\x1a\n") or mime_type == "image/webp" and signature.startswith(b"RIFF") and signature[8:12] == b"WEBP" or mime_type == "video/mp4" and signature[4:8] == b"ftyp")
            if not valid:
                raise RuntimeError(f"İndirilen medya imzası geçersiz: {media['id']} ({mime_type})")
            checked += 1
            images += int(mime_type.startswith("image/"))
            videos += int(mime_type.startswith("video/"))
    return {"checked": checked, "images": images, "videos": videos}


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    client = ApiClient(args.base_url)
    if client.request("GET", "/health").get("status") != "ok":
        raise RuntimeError("Backend sağlıklı yanıt vermedi.")

    print("[demo] Hesaplar doğrulanıyor...", file=sys.stderr)
    tokens = {user.username: authenticate(client, user) for user in USERS}
    catalog = client.request("GET", "/api/v1/topics")
    topic_ids = {item["slug"]: item["id"] for item in catalog["items"]}
    print("[demo] Profiller, avatarlar ve ilgi alanları hazırlanıyor...", file=sys.stderr)
    avatars_added = configure_users(client, tokens, topic_ids)
    print("[demo] Gönderiler ve sosyal etkileşimler hazırlanıyor...", file=sys.stderr)
    posts, created_posts = create_missing_posts(client, tokens, topic_ids)
    add_follows(client, tokens)
    add_social_proof(client, tokens, posts)
    created_comments = add_comments(client, tokens, posts)
    print("[demo] Hikâyeler, mesajlar ve moderasyon örnekleri hazırlanıyor...", file=sys.stderr)
    story_ids, created_stories = add_stories(client, tokens)
    created_messages = add_conversations(client, tokens)
    moderation = add_moderation_examples(client, tokens)

    viewer_token = tokens[USERS[0].username]
    profile = seed_context_events(client, viewer_token, posts)
    media_smoke_test = verify_media_downloads(posts)
    work_feed = feed(client, viewer_token, 11, True)
    evening_feed = feed(client, viewer_token, 21, True)
    notifications = client.request("GET", "/api/v1/notifications?limit=50", token=viewer_token)
    story_feed = client.request("GET", "/api/v1/stories/feed", token=viewer_token)

    report = {
        "backend": args.base_url,
        "demoAccounts": len(USERS),
        "posts": len(posts),
        "new": {"avatars": avatars_added, "posts": created_posts, "comments": created_comments, "stories": created_stories, "messages": created_messages},
        "stories": len(story_ids),
        "storyGroupsVisibleToTestUser": len(story_feed["items"]),
        "notificationsVisibleToTestUser": len(notifications["items"]),
        "imagePosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("image/")) for item in posts),
        "videoPosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("video/")) for item in posts),
        "modelVersion": work_feed.get("modelVersion"),
        "mediaSmokeTest": media_smoke_test,
        "moderation": moderation,
        "profile": profile,
        "workHoursTop5": compact_feed(work_feed),
        "eveningTop5": compact_feed(evening_feed),
        "testLogin": {"email": USERS[0].email, "password": DEFAULT_PASSWORD},
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ApiError, RuntimeError) as error:
        print(f"HATA: {error}", file=sys.stderr)
        sys.exit(1)
