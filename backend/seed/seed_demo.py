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
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


sys.path.insert(0, str(Path(__file__).resolve().parent))

DEFAULT_PASSWORD = "Demo1234!"
ASSETS_DIR = Path(__file__).resolve().parent / "assets"

from demo_content import (  # noqa: E402
    BEHAVIOURS,
    BLOCKS,
    COMMENTS,
    CONVERSATIONS,
    FOLLOWS,
    POSTS,
    STORIES,
    USERS,
    DemoBehaviour,
    DemoUser,
    is_blocked,
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


def grant_consent(client: ApiClient, tokens: dict[str, str]) -> int:
    """Kişiselleştirme rızasını açar.

    Rıza varsayılan olarak **kapalı**. Bu adım olmadan öneri olayları
    `accepted: 0` ile sessizce yok sayılıyor, akış kronolojik kalıyor ve
    `feed_candidates` hiç dolmuyordu — yani demo hiçbir kişiselleştirme
    göstermiyordu ama hata da vermiyordu.
    """
    granted = 0
    for username, token in tokens.items():
        response = client.request("PUT", "/api/v1/recommendations/consent", {"granted": True}, token)
        if response.get("granted"):
            granted += 1
    return granted


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
            if author == username or is_blocked(username, author):
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


def seed_behaviour(
    client: ApiClient,
    tokens: dict[str, str],
    posts: list[dict[str, Any]],
) -> dict[str, Any]:
    """Her kullanıcı için kendi ritmine uygun davranış olayları üretir.

    Kullanıcıların akışları ancak davranışları farklıysa farklılaşır. Tek bir
    kullanıcıya birkaç olay yazmak akışı kişiselleştirilmiş **gösteriyor** ama
    kişiselleştirmenin işe yarayıp yaramadığını göstermiyordu.

    Olay kimlikleri içerikten türetiliyor, yani betik tekrar çalıştırıldığında
    aynı olaylar ikinci kez yazılmıyor.
    """
    by_topic: dict[str, list[dict[str, Any]]] = {}
    for post in posts:
        for topic in post.get("topics", []):
            by_topic.setdefault(topic["slug"], []).append(post)

    summary: dict[str, Any] = {"accepted": 0, "ignored": 0, "hidden": 0, "reported": 0}
    for behaviour in BEHAVIOURS:
        token = tokens.get(behaviour.username)
        if token is None:
            continue
        events, hidden, reported = _behaviour_events(behaviour, by_topic, tokens)
        for batch in _chunks(events, 100):
            response = client.request(
                "POST", "/api/v1/recommendations/events", {"events": batch}, token
            )
            summary["accepted"] += response.get("accepted", 0)
            summary["ignored"] += response.get("ignored", 0)
        summary["hidden"] += hidden
        summary["reported"] += reported
    return summary


def _behaviour_events(
    behaviour: DemoBehaviour,
    by_topic: dict[str, list[dict[str, Any]]],
    tokens: dict[str, str],
) -> tuple[list[dict[str, Any]], int, int]:
    session = str(uuid.uuid5(uuid.NAMESPACE_URL, f"nsosyal-demo-session:{behaviour.username}"))
    events: list[dict[str, Any]] = []

    for hour, deep_topics, skim_topics in behaviour.routine:
        # Olay zamanı istenen yerel saate denk gelmeli; `occurredAt` son
        # birkaç güne yayılıyor ki profil "yakın geçmiş" saysın.
        for offset, (topics, deep) in enumerate(((deep_topics, True), (skim_topics, False))):
            for topic in topics:
                for index, post in enumerate(by_topic.get(topic, [])[:4]):
                    author = post["author"]["username"]
                    if author == behaviour.username or is_blocked(behaviour.username, author):
                        continue
                    occurred = _occurred_at(days_ago=1 + offset + index % 3, hour=hour)
                    events.append(_event(behaviour, session, post, "content_impression", hour, occurred))
                    if deep:
                        events.append(
                            _event(
                                behaviour, session, post, "content_view", hour, occurred,
                                dwell_millis=38_000 + index * 4_000,
                                completion_ratio=0.82,
                            )
                        )
                        if post["media"] and post["media"][0]["mimeType"].startswith("video/"):
                            events.append(
                                _event(
                                    behaviour, session, post, "content_complete", hour, occurred,
                                    dwell_millis=6_000, completion_ratio=1.0,
                                )
                            )
                    else:
                        events.append(
                            _event(
                                behaviour, session, post, "content_view", hour, occurred,
                                dwell_millis=3_000 + index * 500,
                                completion_ratio=0.18,
                            )
                        )

    hidden = 0
    for topic in behaviour.disliked:
        for post in by_topic.get(topic, []):
            if hidden >= behaviour.hide_limit:
                break
            if post["author"]["username"] == behaviour.username or is_blocked(
                behaviour.username, post["author"]["username"]
            ):
                continue
            occurred = _occurred_at(days_ago=2, hour=behaviour.routine[0][0])
            events.append(
                _event(behaviour, session, post, "content_impression", behaviour.routine[0][0], occurred)
            )
            events.append(
                _event(behaviour, session, post, "content_hidden", behaviour.routine[0][0], occurred)
            )
            hidden += 1

    # Şikâyet sunucu üretimli; gerçek uçtan gönderiliyor, olay listesine
    # girmiyor. Sayısı raporda görünsün diye burada dönülüyor.
    reported = sum(1 for topic in behaviour.reports if by_topic.get(topic))
    return events, hidden, reported


def _event(
    behaviour: DemoBehaviour,
    session: str,
    post: dict[str, Any],
    event_type: str,
    hour: int,
    occurred: str,
    dwell_millis: int | None = None,
    completion_ratio: float | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "clientEventId": str(
            uuid.uuid5(
                uuid.NAMESPACE_URL,
                f"nsosyal-demo-v3:{behaviour.username}:{post['id']}:{event_type}:{hour}",
            )
        ),
        "sessionId": session,
        "postId": post["id"],
        "eventType": event_type,
        "surface": "seed_demo",
        "localHour": hour,
        "timezoneOffsetMinutes": 180,
        "occurredAt": occurred,
        "schemaVersion": 2,
        "platform": "android",
        "appVersion": "demo-1.0",
    }
    if dwell_millis is not None:
        payload["dwellMillis"] = dwell_millis
    if completion_ratio is not None:
        payload["completionRatio"] = completion_ratio
    return payload


def _occurred_at(days_ago: int, hour: int) -> str:
    moment = datetime.now(timezone.utc) - timedelta(days=days_ago)
    return moment.replace(hour=hour, minute=15, second=0, microsecond=0).isoformat().replace("+00:00", "Z")


def _chunks(items: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
    return [items[index:index + size] for index in range(0, len(items), size)]


def report_disliked_content(
    client: ApiClient,
    tokens: dict[str, str],
    posts: list[dict[str, Any]],
) -> int:
    """Şikâyetler gerçek uçtan gönderiliyor.

    `content_reported` sunucu üretimli bir olay; istemciden gönderilemez.
    Şikâyet ucunu kullanmak hem moderasyon kaydını hem AI sinyalini
    üretiyor, yani demo ikisini birden gösterebiliyor.
    """
    by_topic: dict[str, list[dict[str, Any]]] = {}
    for post in posts:
        for topic in post.get("topics", []):
            by_topic.setdefault(topic["slug"], []).append(post)

    created = 0
    for behaviour in BEHAVIOURS:
        token = tokens.get(behaviour.username)
        if token is None:
            continue
        for topic in behaviour.reports:
            candidates = [
                post for post in by_topic.get(topic, [])
                if post["author"]["username"] != behaviour.username
                and not is_blocked(behaviour.username, post["author"]["username"])
            ]
            if not candidates:
                continue
            response = client.request(
                "POST",
                "/api/v1/reports",
                {"targetType": "POST", "targetId": candidates[0]["id"], "reason": "SPAM"},
                token,
            )
            if not response.get("alreadyReported"):
                created += 1
    return created


def compact_feed(result: dict[str, Any], limit: int = 5) -> list[dict[str, str]]:
    return [{"author": item["author"]["username"], "preview": item["text"][:72] + ("…" if len(item["text"]) > 72 else ""), "media": item["media"][0]["mimeType"] if item["media"] else "text/plain", "reason": item.get("recommendationReason") or "-"} for item in result["items"][:limit]]


def verify_media_downloads(posts: list[dict[str, Any]], enabled: bool = True) -> dict[str, Any]:
    """Ön-imzalı medya URL'lerini gerçekten indirip imzalarını doğrular.

    `enabled=False` yalnızca betik depolama ile aynı ağ bağlamında
    çalışmadığında kullanılmalı. URL'nin host'u S3 imzasının içinde olduğu için
    yeniden yazılamıyor: backend `STORAGE_PUBLIC_ENDPOINT` neyse onu imzalıyor
    ve `localhost:9000` bir konteynerin içinden kendi localhost'u demek.
    Atlamak bir çözüm değil, kapsam dışı bırakmak; o yüzden rapora yazılıyor.
    """
    if not enabled:
        return {"skipped": True, "reason": "medya URL'si bu ağ bağlamından erişilemiyor"}

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
    parser.add_argument(
        "--skip-media-check",
        action="store_true",
        help="Medya URL indirme dogrulamasini atlar; betik depolama ile ayni ag baglaminda degilse gerekli.",
    )
    args = parser.parse_args()
    client = ApiClient(args.base_url)
    if client.request("GET", "/health").get("status") != "ok":
        raise RuntimeError("Backend sağlıklı yanıt vermedi.")

    print("[demo] Hesaplar doğrulanıyor...", file=sys.stderr)
    tokens = {user.username: authenticate(client, user) for user in USERS}
    catalog = client.request("GET", "/api/v1/topics")
    topic_ids = {item["slug"]: item["id"] for item in catalog["items"]}
    print("[demo] Kişiselleştirme rızası veriliyor...", file=sys.stderr)
    consented = grant_consent(client, tokens)
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
    print("[demo] Kullanıcı davranışları üretiliyor...", file=sys.stderr)
    behaviour = seed_behaviour(client, tokens, posts)
    reported = report_disliked_content(client, tokens, posts)
    profile = client.request("GET", "/api/v1/recommendations/profile?localHour=21", token=viewer_token)
    media_smoke_test = verify_media_downloads(posts, enabled=not args.skip_media_check)
    work_feed = feed(client, viewer_token, 11, True)
    evening_feed = feed(client, viewer_token, 21, True)
    notifications = client.request("GET", "/api/v1/notifications?limit=50", token=viewer_token)
    story_feed = client.request("GET", "/api/v1/stories/feed", token=viewer_token)

    report = {
        "backend": args.base_url,
        "demoAccounts": len(USERS),
        "personalizationConsented": consented,
        "posts": len(posts),
        "new": {"avatars": avatars_added, "posts": created_posts, "comments": created_comments, "stories": created_stories, "messages": created_messages},
        "stories": len(story_ids),
        "storyGroupsVisibleToTestUser": len(story_feed["items"]),
        "notificationsVisibleToTestUser": len(notifications["items"]),
        "imagePosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("image/")) for item in posts),
        "videoPosts": sum(bool(item["media"] and item["media"][0]["mimeType"].startswith("video/")) for item in posts),
        "modelVersion": work_feed.get("modelVersion"),
        # Kisisellestirme gercekten calisti mi: requestId yalnizca
        # kisisellestirilmis akista dolar.
        "personalizedFeed": work_feed.get("requestId") is not None,
        "mediaSmokeTest": media_smoke_test,
        "moderation": moderation,
        "behaviourEvents": behaviour,
        "reportsCreated": reported,
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
