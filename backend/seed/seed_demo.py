#!/usr/bin/env python3
"""NSosyal'in çalışan API'sini özgün görsel/video içeren demo verileriyle doldurur."""

from __future__ import annotations

import argparse
import json
import mimetypes
import sys
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


USERS = (
    DemoUser("Deniz Test", "demo_deneme", "demo.deneme@nsosyal.local"),
    DemoUser("Berk Yalın", "demo_berk", "demo.berk@nsosyal.local"),
    DemoUser("Mert Can", "demo_mert", "demo.mert@nsosyal.local"),
    DemoUser("Ece Aydın", "demo_ece", "demo.ece@nsosyal.local"),
    DemoUser("Aylin Demir", "demo_aylin", "demo.aylin@nsosyal.local"),
    DemoUser("Selin Akay", "demo_selin", "demo.selin@nsosyal.local"),
)


POSTS = (
    {
        "username": "demo_berk",
        "text": "Yapay zeka destekli mobil uygulama prototipini bugün gerçek kullanıcı akışıyla denedik. Teknoloji ancak sade bir deneyime dönüştüğünde işe yarıyor. #teknoloji #yazılım",
        "asset": "teknoloji-yapay-zeka.jpg",
    },
    {
        "username": "demo_berk",
        "text": "Altı saniyelik ürün günlüğü: yapay zeka, mobil arayüz ve küçük ama ölçülebilir bir iyileştirme. Mesai saatlerinde böyle kısa teknoloji videoları iyi gidiyor.",
        "asset": "teknoloji-demo.mp4",
    },
    {
        "username": "demo_berk",
        "text": "Bugünün öğrenme notu: iyi yazılım önce problemi anlatır, sonra kodu gösterir. Eğitim içeriklerinde örnek, açıklamadan daha güçlü olabilir.",
    },
    {
        "username": "demo_mert",
        "text": "Kahveyi masaya değil de sohbete katınca ortaya çıkan an. Biraz mizah, biraz komik tesadüf; günün yorgunluğunu aldı. ☕️",
        "asset": "mizah-kafe.jpg",
    },
    {
        "username": "demo_mert",
        "text": "Akşam molası için kısa mizah videosu: kahve köpüğü planı bozdu, arkadaşlar günü kurtardı. Eğlence bazen tam olarak budur.",
        "asset": "mizah-demo.mp4",
    },
    {
        "username": "demo_mert",
        "text": "Toplantıda 'son bir madde' denince bilgisayarın pilinin yüzde bire düşmesi kadar gerçek bir espri yok. #mizah #komik",
    },
    {
        "username": "demo_ece",
        "text": "İstanbul vapurunda gün doğumu: yerel hayatın en güzel tarafı, şehrin telaşı başlamadan birkaç dakika suya bakabilmek. Bugün bu rota çok sakindi.",
        "asset": "istanbul-vapur.jpg",
    },
    {
        "username": "demo_ece",
        "text": "Sahilde otuz dakikalık koşu tamamlandı. Spor için büyük hedeflerden önce düzenli küçük adımların daha sürdürülebilir olduğunu yeniden gördüm.",
    },
    {
        "username": "demo_aylin",
        "text": "Atölyede bugün mavi ve toprak tonlarını bir araya getirdik. Kültür ve sanat, gündelik bir objeyi hikâyeye dönüştürebiliyor.",
        "asset": "kultur-seramik.jpg",
    },
    {
        "username": "demo_aylin",
        "text": "Bu haftanın kültür listesi: bir kısa film, yeni bir müzik albümü ve mahalledeki küçük seramik sergisi. Yerel sanat üreticilerini keşfetmeyi seviyorum.",
    },
    {
        "username": "demo_selin",
        "text": "Arayüz tasarımında bugünkü ders: kullanıcıya beş seçenek vermek yerine doğru anda tek net eylem sunmak. Sadelik, eksiklik değil önceliklendirmedir.",
    },
)


class ApiError(RuntimeError):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"HTTP {status} {code}: {message}")
        self.status = status
        self.code = code


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        token: str | None = None,
    ) -> dict[str, Any]:
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        if data is None and method in {"POST", "PUT"}:
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
    try:
        return client.request("POST", "/api/v1/auth/login", {"email": user.email, "password": DEFAULT_PASSWORD})["tokens"]["accessToken"]
    except ApiError as error:
        if error.status not in {401, 403}:
            raise

    try:
        registration = client.request(
            "POST",
            "/api/v1/auth/register",
            {
                "fullName": user.full_name,
                "username": user.username,
                "email": user.email,
                "password": DEFAULT_PASSWORD,
                "acceptedTerms": True,
            },
        )
        code = registration.get("developmentCode")
    except ApiError as error:
        if error.status != 409:
            raise
        code = client.request("POST", "/api/v1/auth/resend-verification", {"email": user.email}).get("developmentCode")

    if not code:
        raise RuntimeError("Geliştirme doğrulama kodu alınamadı. EXPOSE_DEVELOPMENT_CODES=true olmalı.")
    verified = client.request("POST", "/api/v1/auth/verify-email", {"email": user.email, "code": code})
    return verified["tokens"]["accessToken"]


def feed(client: ApiClient, token: str, local_hour: int, personalized: bool) -> dict[str, Any]:
    session_id = uuid.uuid5(uuid.NAMESPACE_URL, f"nsosyal-demo-feed-{local_hour}")
    query = urlencode(
        {
            "limit": 50,
            "personalized": str(personalized).lower(),
            "sessionId": str(session_id),
            "localHour": local_hour,
            "timezoneOffsetMinutes": 180,
        }
    )
    return client.request("GET", f"/api/v1/posts/feed?{query}", token=token)


def upload_media(client: ApiClient, token: str, asset_name: str) -> str:
    path = ASSETS_DIR / asset_name
    if not path.is_file():
        raise RuntimeError(f"Demo medya dosyası bulunamadı: {path}")
    mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    created = client.request(
        "POST",
        "/api/v1/media/uploads",
        {"filename": path.name, "mimeType": mime_type, "sizeBytes": path.stat().st_size},
        token,
    )
    client.upload(created["uploadUrl"], path, created["requiredHeaders"])
    client.request("POST", f"/api/v1/media/{created['mediaId']}/complete", token=token)
    return created["mediaId"]


def create_missing_posts(
    client: ApiClient,
    tokens: dict[str, str],
    viewer_token: str,
    current: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], int]:
    existing = {(item["author"]["username"], item["text"]) for item in current}
    created_count = 0
    for spec in POSTS:
        key = (spec["username"], spec["text"])
        if key in existing:
            continue
        media_ids = []
        if spec.get("asset"):
            media_ids.append(upload_media(client, tokens[spec["username"]], spec["asset"]))
        client.request(
            "POST",
            "/api/v1/posts",
            {"text": spec["text"], "mediaIds": media_ids},
            tokens[spec["username"]],
        )
        existing.add(key)
        created_count += 1
    return feed(client, viewer_token, 12, False)["items"], created_count


def add_social_proof(client: ApiClient, tokens: dict[str, str], posts: list[dict[str, Any]]) -> None:
    reacting_users = list(tokens.items())
    for user_index, (username, token) in enumerate(reacting_users):
        for post_index, post in enumerate(posts):
            if post["author"]["username"] == username:
                continue
            if (post_index + user_index) % 3 == 0:
                client.request("PUT", f"/api/v1/posts/{post['id']}/like", token=token)
            if (post_index + user_index) % 7 == 0:
                client.request("PUT", f"/api/v1/posts/{post['id']}/save", token=token)


def seed_context_events(client: ApiClient, viewer_token: str, posts: list[dict[str, Any]]) -> dict[str, Any]:
    by_text = {item["text"]: item for item in posts}
    session_id = uuid.uuid5(uuid.NAMESPACE_URL, "nsosyal-demo-context-session-v1")
    events: list[dict[str, Any]] = []
    scenarios = (
        (POSTS[0]["text"], "content_view", 11, 52_000, 0.86),
        (POSTS[0]["text"], "content_saved", 11, None, None),
        (POSTS[1]["text"], "content_complete", 14, 6_000, 1.0),
        (POSTS[3]["text"], "content_liked", 21, None, None),
        (POSTS[4]["text"], "content_complete", 21, 6_000, 1.0),
        (POSTS[4]["text"], "content_shared", 21, None, None),
        (POSTS[6]["text"], "content_view", 19, 40_000, 0.75),
        (POSTS[8]["text"], "content_saved", 20, None, None),
    )
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    for text, event_type, hour, dwell, completion in scenarios:
        post_id = by_text[text]["id"]
        event_id = uuid.uuid5(uuid.NAMESPACE_URL, f"nsosyal-demo-v1:{post_id}:{event_type}:{hour}")
        events.append(
            {
                "clientEventId": str(event_id),
                "sessionId": str(session_id),
                "postId": post_id,
                "eventType": event_type,
                "surface": "seed_demo",
                "dwellMillis": dwell,
                "completionRatio": completion,
                "localHour": hour,
                "timezoneOffsetMinutes": 180,
                "occurredAt": now,
            }
        )
    batch = client.request("POST", "/api/v1/recommendations/events", {"events": events}, viewer_token)
    profile = client.request("GET", "/api/v1/recommendations/profile?localHour=21", token=viewer_token)
    profile["seedBatch"] = batch
    return profile


def compact_feed(result: dict[str, Any], limit: int = 5) -> list[dict[str, str]]:
    return [
        {
            "author": item["author"]["username"],
            "preview": item["text"][:72] + ("…" if len(item["text"]) > 72 else ""),
            "media": item["media"][0]["mimeType"] if item["media"] else "text/plain",
            "reason": item.get("recommendationReason") or "-",
        }
        for item in result["items"][:limit]
    ]


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
            valid = (
                mime_type == "image/jpeg" and signature.startswith(b"\xff\xd8\xff")
                or mime_type == "image/png" and signature.startswith(b"\x89PNG\r\n\x1a\n")
                or mime_type == "image/webp" and signature.startswith(b"RIFF") and signature[8:12] == b"WEBP"
                or mime_type == "video/mp4" and signature[4:8] == b"ftyp"
            )
            if not valid:
                raise RuntimeError(f"İndirilen medya imzası geçersiz: {media['id']} ({mime_type})")
            checked += 1
            images += int(mime_type.startswith("image/"))
            videos += int(mime_type.startswith("video/"))
    return {"checked": checked, "images": images, "videos": videos}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    client = ApiClient(args.base_url)

    health = client.request("GET", "/health")
    if health.get("status") != "ok":
        raise RuntimeError("Backend sağlıklı yanıt vermedi.")

    viewer = USERS[0]
    viewer_token = authenticate(client, viewer)
    current = feed(client, viewer_token, 12, False)["items"]
    existing = {(item["author"]["username"], item["text"]) for item in current}
    missing_authors = {
        spec["username"]
        for spec in POSTS
        if (spec["username"], spec["text"]) not in existing
    }
    tokens = {viewer.username: viewer_token}
    tokens.update(
        {
            user.username: authenticate(client, user)
            for user in USERS[1:]
            if user.username in missing_authors
        }
    )
    posts, created_count = create_missing_posts(client, tokens, viewer_token, current)
    if created_count:
        tokens.update(
            {
                user.username: authenticate(client, user)
                for user in USERS[1:]
                if user.username not in tokens
            }
        )
        add_social_proof(client, tokens, posts)
    posts = feed(client, viewer_token, 12, False)["items"]
    profile = seed_context_events(client, viewer_token, posts)
    media_smoke_test = verify_media_downloads(posts)
    work_feed = feed(client, viewer_token, 11, True)
    evening_feed = feed(client, viewer_token, 21, True)

    report = {
        "backend": args.base_url,
        "demoAccounts": len(USERS),
        "posts": len(posts),
        "newPosts": created_count,
        "imagePosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("image/")) for item in posts),
        "videoPosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("video/")) for item in posts),
        "modelVersion": work_feed.get("modelVersion"),
        "mediaSmokeTest": media_smoke_test,
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
