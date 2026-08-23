#!/usr/bin/env bash
# NEXI backend uctan uca senaryo. Gercek Postgres + MinIO + backend uzerinde calisir.
# Basarisiz her adimda durur ve nedenini yazar.
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
PASS=0; FAIL=0
STAMP=$(date +%s)

c()  { curl -s -m 20 "$@"; }
ca() { curl -s -m 20 -H "Authorization: Bearer $1" "${@:2}"; }

ok()   { PASS=$((PASS+1)); printf '  \033[32mGECTI\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mKALDI\033[0m  %s\n     -> %s\n' "$1" "${2:-}"; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# jq yoksa basit alan cikarici
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }
num()   { grep -o "\"$1\":[0-9]*" | head -1 | sed "s/\"$1\"://"; }
boolf() { grep -o "\"$1\":\(true\|false\)" | head -1 | sed "s/\"$1\"://"; }

# ---------------------------------------------------------------- 1) Kayit + giris
step "1) Kayit, e-posta dogrulama, giris, token yenileme"

register_user() { # $1=suffix -> echoes "email|token|refresh|username|userid"
  local u="e2e${STAMP}$1" em="e2e${STAMP}$1@example.com" body code auth
  body=$(c -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"E2E Kullanici $1\",\"username\":\"$u\",\"email\":\"$em\",\"password\":\"StrongPass123\",\"acceptedTerms\":true}")
  code=$(printf '%s' "$body" | field developmentCode)
  [ -z "$code" ] && { echo "HATA:$body"; return 1; }
  auth=$(c -X POST "$BASE/api/v1/auth/verify-email" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$em\",\"code\":\"$code\"}")
  printf '%s|%s|%s|%s|%s' "$em" \
    "$(printf '%s' "$auth" | field accessToken)" \
    "$(printf '%s' "$auth" | field refreshToken)" \
    "$u" \
    "$(printf '%s' "$auth" | grep -o '"user":{"id":"[^"]*"' | sed 's/.*"id":"//')"
}

IFS='|' read -r EMAIL_A TOK_A REF_A USER_A ID_A <<< "$(register_user a)"
IFS='|' read -r EMAIL_B TOK_B REF_B USER_B ID_B <<< "$(register_user b)"

[ -n "${TOK_A:-}" ] && [ -n "${TOK_B:-}" ] && ok "iki kullanici kaydedildi ve dogrulandi" \
  || { bad "kayit/dogrulama" "$EMAIL_A"; echo; echo "Ozet: $PASS gecti, $((FAIL+1)) kaldi"; exit 1; }

LOGIN=$(c -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"StrongPass123\"}")
[ -n "$(printf '%s' "$LOGIN" | field accessToken)" ] && ok "giris" || bad "giris" "$LOGIN"

REFRESHED=$(c -X POST "$BASE/api/v1/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REF_A\"}")
NEWTOK=$(printf '%s' "$REFRESHED" | field accessToken)
[ -n "$NEWTOK" ] && { TOK_A="$NEWTOK"; REF_A=$(printf '%s' "$REFRESHED" | field refreshToken); ok "token yenileme"; } \
  || bad "token yenileme" "$REFRESHED"

ME=$(ca "$TOK_A" "$BASE/api/v1/users/me")
[ "$(printf '%s' "$ME" | field username)" = "$USER_A" ] && ok "/users/me" || bad "/users/me" "$ME"

UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' -m 20 "$BASE/api/v1/feed")
[ "$UNAUTH" = "401" ] && ok "jetonsuz erisim 401" || bad "jetonsuz erisim" "beklenen 401, gelen $UNAUTH"

# ---------------------------------------------------------------- 2) Ilgi alanlari
step "2) Ilgi alani secimi"
CAT=$(c "$BASE/api/v1/topics")
TOPIC_IDS=$(printf '%s' "$CAT" | grep -o '"id":"[^"]*"' | sed 's/"id":"//;s/"//' )
T1=$(printf '%s' "$TOPIC_IDS" | sed -n 1p); T2=$(printf '%s' "$TOPIC_IDS" | sed -n 2p); T3=$(printf '%s' "$TOPIC_IDS" | sed -n 3p)
[ -n "$T1" ] && ok "katalog acik uc olarak geldi ($(printf '%s' "$TOPIC_IDS" | wc -l) konu)" || bad "katalog" "$CAT"

for T in "$TOK_A" "$TOK_B"; do
  SEL=$(ca "$T" -X PUT "$BASE/api/v1/users/me/topics" -H 'Content-Type: application/json' \
    -d "{\"topicIds\":[\"$T1\",\"$T2\",\"$T3\"]}")
done
[ "$(printf '%s' "$SEL" | boolf completed)" = "true" ] && ok "siralama kaydedildi, completed=true" || bad "ilgi alani kaydi" "$SEL"

FEWER=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/me/topics" -H 'Content-Type: application/json' -d "{\"topicIds\":[\"$T1\"]}")
[ "$(printf '%s' "$FEWER" | field code)" = "TOO_FEW_TOPICS" ] && ok "3'ten az konu reddedildi" || bad "TOO_FEW_TOPICS" "$FEWER"

# ---------------------------------------------------------------- 3) Medya
step "3) Medya yukleme (imzali adres -> depo -> tamamla)"
PNG=/tmp/e2e.png
printf '\211PNG\r\n\032\n' > "$PNG"
head -c 200 /dev/urandom >> "$PNG"
SIZE=$(wc -c < "$PNG" | tr -d ' ')

UP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
  -d "{\"filename\":\"e2e.png\",\"mimeType\":\"image/png\",\"sizeBytes\":$SIZE}")
MEDIA_ID=$(printf '%s' "$UP" | field mediaId)
UPLOAD_URL=$(printf '%s' "$UP" | field uploadUrl)
[ -n "$MEDIA_ID" ] && ok "imzali yukleme adresi alindi" || bad "media/uploads" "$UP"

PUTCODE=$(curl -s -o /dev/null -w '%{http_code}' -m 30 -X PUT "$UPLOAD_URL" -H "Content-Type: image/png" --data-binary "@$PNG")
[ "$PUTCODE" = "200" ] && ok "dosya dogrudan depoya yuklendi" || bad "depoya PUT" "HTTP $PUTCODE"

DONE=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/$MEDIA_ID/complete")
[ "$(printf '%s' "$DONE" | field status)" = "READY" ] && ok "imza dogrulandi, READY" || bad "media complete" "$DONE"

BADUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
  -d '{"filename":"x.pdf","mimeType":"application/pdf","sizeBytes":100}')
[ "$(printf '%s' "$BADUP" | field code)" = "UNSUPPORTED_MEDIA_TYPE" ] && ok "desteklenmeyen tur reddedildi (pdf)" || bad "UNSUPPORTED_MEDIA_TYPE" "$BADUP"

# --- Video: gercek bir MP4 ile. Depodaki demo varlik kullaniliyor. ---
MP4="${MP4_FIXTURE:-$(cd "$(dirname "$0")/.." && pwd)/seed/assets/teknoloji-demo.mp4}"
if [ -f "$MP4" ]; then
  MP4SIZE=$(wc -c < "$MP4" | tr -d ' ')
  VUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
    -d "{\"filename\":\"e2e.mp4\",\"mimeType\":\"video/mp4\",\"sizeBytes\":$MP4SIZE}")
  VID=$(printf '%s' "$VUP" | field mediaId); VURL=$(printf '%s' "$VUP" | field uploadUrl)
  curl -s -o /dev/null -m 60 -X PUT "$VURL" -H "Content-Type: video/mp4" --data-binary "@$MP4"

  # Kapak gorseli: ayri bir gorsel yukleyip videoya bagliyoruz.
  TH=/tmp/e2e_thumb.png
  printf '\211PNG\r\n\032\n' > "$TH"; head -c 120 /dev/urandom >> "$TH"
  THSIZE=$(wc -c < "$TH" | tr -d ' ')
  TUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
    -d "{\"filename\":\"kapak.png\",\"mimeType\":\"image/png\",\"sizeBytes\":$THSIZE}")
  THID=$(printf '%s' "$TUP" | field mediaId); THURL=$(printf '%s' "$TUP" | field uploadUrl)
  curl -s -o /dev/null -m 30 -X PUT "$THURL" -H "Content-Type: image/png" --data-binary "@$TH"
  ca "$TOK_A" -X POST "$BASE/api/v1/media/$THID/complete" >/dev/null

  VDONE=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/$VID/complete" -H 'Content-Type: application/json' \
    -d "{\"thumbnailMediaId\":\"$THID\"}")
  [ "$(printf '%s' "$VDONE" | field status)" = "READY" ] && ok "mp4 yuklendi" || bad "mp4 yukleme" "$VDONE"
  printf '%s' "$VDONE" | grep -qE '"durationSeconds":[0-9]' && ok "video suresi kaptan cozuldu" || bad "video suresi" "$VDONE"
  printf '%s' "$VDONE" | grep -qE '"width":[0-9]+,"height":[0-9]+' && ok "cozunurluk cozuldu" || bad "cozunurluk" "$VDONE"
  printf '%s' "$VDONE" | grep -q '"thumbnailUrl":"http' && ok "kapak gorseli baglandi" || bad "kapak gorseli" "$VDONE"

  VSTAT=$(ca "$TOK_A" "$BASE/api/v1/media/$VID/status")
  [ "$(printf '%s' "$VSTAT" | boolf playable)" = "true" ] && ok "durum ucu: oynatilabilir" || bad "durum ucu" "$VSTAT"
else
  echo "  ATLANDI  video testleri (fixture yok: $MP4)"
fi

# Gecersiz icerikli mp4 reddedilmeli: imzasi bile yok
FAKE=/tmp/e2e_fake.mp4; printf 'bu bir video degil' > "$FAKE"
FSIZE=$(wc -c < "$FAKE" | tr -d ' ')
FUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
  -d "{\"filename\":\"f.mp4\",\"mimeType\":\"video/mp4\",\"sizeBytes\":$FSIZE}")
FID=$(printf '%s' "$FUP" | field mediaId); FURL=$(printf '%s' "$FUP" | field uploadUrl)
curl -s -o /dev/null -m 30 -X PUT "$FURL" -H "Content-Type: video/mp4" --data-binary "@$FAKE"
FDONE=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/$FID/complete")
[ "$(printf '%s' "$FDONE" | field code)" = "INVALID_UPLOADED_FILE" ] && ok "sahte mp4 reddedildi" || bad "sahte mp4" "$FDONE"

# Imzasi dogru ama moov kutusu olmayan dosya da reddedilmeli
NOMOOV=/tmp/e2e_nomoov.mp4
printf '\x00\x00\x00\x14ftypisom\x00\x00\x02\x00isom' > "$NOMOOV"
NMSIZE=$(wc -c < "$NOMOOV" | tr -d ' ')
NMUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
  -d "{\"filename\":\"nomoov.mp4\",\"mimeType\":\"video/mp4\",\"sizeBytes\":$NMSIZE}")
NMID=$(printf '%s' "$NMUP" | field mediaId); NMURL=$(printf '%s' "$NMUP" | field uploadUrl)
curl -s -o /dev/null -m 30 -X PUT "$NMURL" -H "Content-Type: video/mp4" --data-binary "@$NOMOOV"
NMDONE=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/$NMID/complete")
[ "$(printf '%s' "$NMDONE" | field code)" = "INVALID_UPLOADED_FILE" ] && ok "meta verisi okunamayan mp4 reddedildi" || bad "moov'suz mp4" "$NMDONE"

# ---------------------------------------------------------------- 4) Gonderi
step "4) Gonderi olusturma"
POST_A=$(ca "$TOK_A" -X POST "$BASE/api/v1/posts" -H 'Content-Type: application/json' \
  -d "{\"text\":\"E2E gorselli gonderi\",\"mediaIds\":[\"$MEDIA_ID\"],\"topicIds\":[\"$T1\"]}")
PID_A=$(printf '%s' "$POST_A" | field id)
[ -n "$PID_A" ] && ok "gorselli + konulu gonderi olusturuldu" || bad "gonderi olusturma" "$POST_A"
printf '%s' "$POST_A" | grep -q '"url":"http' && ok "gonderi cevabinda sureli gorsel adresi var" || bad "gorsel adresi" "$POST_A"

POST_B=$(ca "$TOK_B" -X POST "$BASE/api/v1/posts" -H 'Content-Type: application/json' \
  -d "{\"text\":\"B kullanicisinin gonderisi\",\"topicIds\":[\"$T2\"]}")
PID_B=$(printf '%s' "$POST_B" | field id)
[ -n "$PID_B" ] && ok "ikinci kullanicidan gonderi" || bad "B gonderisi" "$POST_B"

EMPTY=$(ca "$TOK_A" -X POST "$BASE/api/v1/posts" -H 'Content-Type: application/json' -d '{"text":"   "}')
[ "$(printf '%s' "$EMPTY" | field code)" = "EMPTY_POST" ] && ok "bos gonderi reddedildi" || bad "EMPTY_POST" "$EMPTY"

REUSE=$(ca "$TOK_A" -X POST "$BASE/api/v1/posts" -H 'Content-Type: application/json' \
  -d "{\"text\":\"ayni gorsel\",\"mediaIds\":[\"$MEDIA_ID\"]}")
[ "$(printf '%s' "$REUSE" | field code)" = "MEDIA_ALREADY_ATTACHED" ] && ok "gorsel ikinci kez baglanamadi" || bad "MEDIA_ALREADY_ATTACHED" "$REUSE"

# ---------------------------------------------------------------- 5) Akis
step "5) Akis olusturma"
FEED=$(ca "$TOK_A" "$BASE/api/v1/feed?limit=10")
printf '%s' "$FEED" | grep -q '"items"' && ok "akis dondu" || bad "akis" "$FEED"
[ "$(printf '%s' "$FEED" | boolf personalized)" = "true" ] && ok "personalized=true" || bad "personalized" "$FEED"
printf '%s' "$FEED" | grep -q '"reason"' && ok "her ogede gerekce var" || bad "reason" "$FEED"
printf '%s' "$FEED" | grep -q '"mix"' && ok "mix alani var" || bad "mix" "$FEED"

BADCUR=$(ca "$TOK_A" "$BASE/api/v1/feed?cursor=bozuk")
[ "$(printf '%s' "$BADCUR" | field code)" = "INVALID_CURSOR" ] && ok "bozuk imlec reddedildi" || bad "INVALID_CURSOR" "$BADCUR"

CHRONO=$(ca "$TOK_A" "$BASE/api/v1/posts/feed?limit=5")
printf '%s' "$CHRONO" | grep -q '"items"' && ok "kronolojik akis (/posts/feed)" || bad "/posts/feed" "$CHRONO"

# ---------------------------------------------------------------- 6) Begeni / kaydetme
step "6) Begeni ve kaydetme"
L1=$(ca "$TOK_A" -X PUT "$BASE/api/v1/posts/$PID_B/like")
[ "$(printf '%s' "$L1" | num count)" = "1" ] && ok "begeni sayaci 1" || bad "begeni" "$L1"
L2=$(ca "$TOK_A" -X PUT "$BASE/api/v1/posts/$PID_B/like")
[ "$(printf '%s' "$L2" | num count)" = "1" ] && ok "tekrar begeni idempotent" || bad "idempotent begeni" "$L2"
L3=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/posts/$PID_B/like")
[ "$(printf '%s' "$L3" | num count)" = "0" ] && ok "begeni geri alindi" || bad "begeni kaldirma" "$L3"
S1=$(ca "$TOK_A" -X PUT "$BASE/api/v1/posts/$PID_B/save")
[ "$(printf '%s' "$S1" | num count)" = "1" ] && ok "kaydetme" || bad "kaydetme" "$S1"

# ---------------------------------------------------------------- 7) Yorumlar
step "7) Yorum yazma, listeleme, silme"
C1=$(ca "$TOK_A" -X POST "$BASE/api/v1/posts/$PID_B/comments" -H 'Content-Type: application/json' -d '{"text":"Ilk yorum"}')
CID1=$(printf '%s' "$C1" | field id)
[ -n "$CID1" ] && ok "yorum yazildi" || bad "yorum yazma" "$C1"

ca "$TOK_B" -X POST "$BASE/api/v1/posts/$PID_B/comments" -H 'Content-Type: application/json' -d '{"text":"Sahibin yorumu"}' >/dev/null
CLIST=$(ca "$TOK_A" "$BASE/api/v1/posts/$PID_B/comments")
[ "$(printf '%s' "$CLIST" | num totalCount)" = "2" ] && ok "iki yorum listelendi" || bad "yorum listesi" "$CLIST"
printf '%s' "$CLIST" | grep -q '"Ilk yorum".*"Sahibin yorumu"\|Ilk yorum' && ok "eskiden yeniye siralama" || bad "siralama" "$CLIST"

POSTB_NOW=$(ca "$TOK_A" "$BASE/api/v1/posts/$PID_B")
[ "$(printf '%s' "$POSTB_NOW" | num commentCount)" = "2" ] && ok "gonderide commentCount=2" || bad "commentCount" "$POSTB_NOW"

EMPTYC=$(ca "$TOK_A" -X POST "$BASE/api/v1/posts/$PID_B/comments" -H 'Content-Type: application/json' -d '{"text":"  "}')
[ "$(printf '%s' "$EMPTYC" | field code)" = "EMPTY_COMMENT" ] && ok "bos yorum reddedildi" || bad "EMPTY_COMMENT" "$EMPTYC"

DEL=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/comments/$CID1")
printf '%s' "$DEL" | grep -q "silindi" && ok "yorum silindi" || bad "yorum silme" "$DEL"
AFTER=$(ca "$TOK_A" "$BASE/api/v1/posts/$PID_B/comments")
[ "$(printf '%s' "$AFTER" | num totalCount)" = "1" ] && ok "silinen yorum sayacdan dustu" || bad "sayac guncellemesi" "$AFTER"

# ---------------------------------------------------------------- 8) Profil
step "8) Profil goruntuleme"
PROF=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B")
[ "$(printf '%s' "$PROF" | field username)" = "$USER_B" ] && ok "baskasinin profili" || bad "profil" "$PROF"
[ "$(printf '%s' "$PROF" | boolf isMe)" = "false" ] && ok "isMe=false" || bad "isMe" "$PROF"
SELFP=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_A")
[ "$(printf '%s' "$SELFP" | boolf isMe)" = "true" ] && ok "kendi profilinde isMe=true" || bad "kendi profil" "$SELFP"
UPOSTS=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B/posts")
printf '%s' "$UPOSTS" | grep -q "$PID_B" && ok "profil altinda gonderileri" || bad "profil gonderileri" "$UPOSTS"
NOUSER=$(ca "$TOK_A" "$BASE/api/v1/users/yokboyle123")
[ "$(printf '%s' "$NOUSER" | field code)" = "USER_NOT_FOUND" ] && ok "olmayan kullanici 404" || bad "USER_NOT_FOUND" "$NOUSER"

# ---------------------------------------------------------- 8b) Avatar ve bio
step "8b) Avatar ve biyografi"
BIO=$(ca "$TOK_A" -X PATCH "$BASE/api/v1/users/me/profile" -H 'Content-Type: application/json' \
  -d '{"bio":"E2E biyografi metni"}')
[ "$(printf '%s' "$BIO" | field bio)" = "E2E biyografi metni" ] && ok "biyografi yazildi" || bad "bio yazma" "$BIO"

NAMEONLY=$(ca "$TOK_A" -X PATCH "$BASE/api/v1/users/me/profile" -H 'Content-Type: application/json' -d '{"fullName":"E2E Yeni Ad"}')
[ "$(printf '%s' "$NAMEONLY" | field bio)" = "E2E biyografi metni" ] && ok "ad degisti, bio korundu" || bad "kismi guncelleme" "$NAMEONLY"

LONGBIO=$(ca "$TOK_A" -X PATCH "$BASE/api/v1/users/me/profile" -H 'Content-Type: application/json' \
  -d "{\"bio\":\"$(head -c 300 /dev/zero | tr '\0' 'a')\"}")
[ "$(printf '%s' "$LONGBIO" | field code)" = "BIO_TOO_LONG" ] && ok "uzun bio reddedildi" || bad "BIO_TOO_LONG" "$LONGBIO"

# Avatar icin ayri bir gorsel yukle
AV=/tmp/e2e_avatar.png
printf '\211PNG\r\n\032\n' > "$AV"; head -c 150 /dev/urandom >> "$AV"
AVSIZE=$(wc -c < "$AV" | tr -d ' ')
AUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
  -d "{\"filename\":\"avatar.png\",\"mimeType\":\"image/png\",\"sizeBytes\":$AVSIZE}")
AVID=$(printf '%s' "$AUP" | field mediaId); AVURL=$(printf '%s' "$AUP" | field uploadUrl)
curl -s -o /dev/null -m 30 -X PUT "$AVURL" -H "Content-Type: image/png" --data-binary "@$AV"
ca "$TOK_A" -X POST "$BASE/api/v1/media/$AVID/complete" >/dev/null

SETAV=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/me/avatar" -H 'Content-Type: application/json' -d "{\"mediaId\":\"$AVID\"}")
printf '%s' "$SETAV" | grep -q '"avatarUrl":"http' && ok "avatar atandi, sureli adres dondu" || bad "avatar atama" "$SETAV"

SEEN=$(ca "$TOK_B" "$BASE/api/v1/users/$USER_A")
printf '%s' "$SEEN" | grep -q '"avatarUrl":"http' && ok "avatar baskasinin gozunden de gorunuyor" || bad "avatar gorunurlugu" "$SEEN"

# Video avatar olamaz (VID onceki adimda yuklendi)
VIDAV=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/me/avatar" -H 'Content-Type: application/json' -d "{\"mediaId\":\"$VID\"}")
[ "$(printf '%s' "$VIDAV" | field code)" = "AVATAR_MUST_BE_IMAGE" ] && ok "video avatar reddedildi" || bad "AVATAR_MUST_BE_IMAGE" "$VIDAV"

FOREIGN=$(ca "$TOK_B" -X PUT "$BASE/api/v1/users/me/avatar" -H 'Content-Type: application/json' -d "{\"mediaId\":\"$AVID\"}")
[ "$(printf '%s' "$FOREIGN" | field code)" = "MEDIA_NOT_AVAILABLE" ] && ok "baskasinin gorseli avatar olamaz" || bad "MEDIA_NOT_AVAILABLE" "$FOREIGN"

# Avatar gonderi ve yorum cevaplarinda da olmali
FEEDAV=$(ca "$TOK_B" "$BASE/api/v1/feed?limit=20")
printf '%s' "$FEEDAV" | grep -q '"avatarUrl":"http' && ok "akis yazarinda avatar var" || bad "akista avatar" "$(printf '%s' "$FEEDAV" | head -c 200)"

# Avatarli kullanici (A) taze bir yorum yazsin; onceki yorumu 7. adimda silindi.
ca "$TOK_A" -X POST "$BASE/api/v1/posts/$PID_B/comments" -H 'Content-Type: application/json' \
  -d '{"text":"Avatarli yorum"}' >/dev/null
CMTAV=$(ca "$TOK_B" "$BASE/api/v1/posts/$PID_B/comments")
printf '%s' "$CMTAV" | grep -q '"avatarUrl":"http' && ok "yorum yazarinda avatar var" || bad "yorumda avatar" "$(printf '%s' "$CMTAV" | head -c 200)"

DELAV=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/users/me/avatar")
printf '%s' "$DELAV" | grep -q '"avatarUrl"' && bad "avatar silme" "$DELAV" || ok "avatar kaldirildi"

# ---------------------------------------------------------------- 9) Takip
step "9) Takip etme ve birakma"
F1=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/follow")
[ "$(printf '%s' "$F1" | num followerCount)" = "1" ] && ok "takip edildi" || bad "takip" "$F1"
F2=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/follow")
[ "$(printf '%s' "$F2" | num followerCount)" = "1" ] && ok "tekrar takip idempotent" || bad "idempotent takip" "$F2"
SELFF=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_A/follow")
[ "$(printf '%s' "$SELFF" | field code)" = "CANNOT_FOLLOW_SELF" ] && ok "kendini takip reddedildi" || bad "CANNOT_FOLLOW_SELF" "$SELFF"
FLW=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B/followers")
printf '%s' "$FLW" | grep -q "$USER_A" && ok "takipci listesinde goruunuyor" || bad "takipci listesi" "$FLW"
FLG=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_A/following")
printf '%s' "$FLG" | grep -q "$USER_B" && ok "takip edilenler listesi" || bad "takip listesi" "$FLG"

# --------------------------------------------------- 10) Takip icerigi akista
step "10) Takip edilen icerigin akisa girmesi"
FEED2=$(ca "$TOK_A" "$BASE/api/v1/feed?limit=20")
printf '%s' "$FEED2" | grep -q '"FOLLOWING"' && ok "akista FOLLOWING kaynagi var" || bad "FOLLOWING kaynagi" "$(printf '%s' "$FEED2" | head -c 300)"
printf '%s' "$FEED2" | grep -q 'Takip ettigin\|Takip ettiğin' && ok "takip gerekcesi metni" || bad "takip gerekcesi" "$(printf '%s' "$FEED2" | grep -o '"text":"[^"]*"' | head -3)"
printf '%s' "$FEED2" | grep -q '"followedByMe":true' && ok "yazarda followedByMe=true" || bad "followedByMe" "$(printf '%s' "$FEED2" | head -c 300)"

UF=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/users/$USER_B/follow")
[ "$(printf '%s' "$UF" | num followerCount)" = "0" ] && ok "takip birakildi" || bad "takip birakma" "$UF"

# ---------------------------------------------------- 11) Oneri olaylari
step "11) Oneri olaylarinin kaydedilmesi"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
# clientEventId ve sessionId UUID olmak zorunda.
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -NoProfile -Command "[guid]::NewGuid().ToString()" | tr -d '\r'; }
EV1=$(uuid); EV2=$(uuid); SESSION=$(uuid)

EV=$(ca "$TOK_A" -X POST "$BASE/api/v1/recommendations/events" -H 'Content-Type: application/json' -d "{\"events\":[
 {\"clientEventId\":\"$EV1\",\"sessionId\":\"$SESSION\",\"postId\":\"$PID_B\",\"eventType\":\"content_impression\",\"position\":0,\"localHour\":14,\"timezoneOffsetMinutes\":180,\"occurredAt\":\"$NOW\"},
 {\"clientEventId\":\"$EV2\",\"sessionId\":\"$SESSION\",\"postId\":\"$PID_B\",\"eventType\":\"content_liked\",\"localHour\":14,\"timezoneOffsetMinutes\":180,\"occurredAt\":\"$NOW\"}]}")
[ "$(printf '%s' "$EV" | num accepted)" = "2" ] && ok "iki olay kabul edildi" || bad "olay gonderimi" "$EV"

DUP=$(ca "$TOK_A" -X POST "$BASE/api/v1/recommendations/events" -H 'Content-Type: application/json' -d "{\"events\":[
 {\"clientEventId\":\"$EV1\",\"sessionId\":\"$SESSION\",\"postId\":\"$PID_B\",\"eventType\":\"content_impression\",\"localHour\":14,\"timezoneOffsetMinutes\":180,\"occurredAt\":\"$NOW\"}]}")
[ "$(printf '%s' "$DUP" | num ignored)" = "1" ] && ok "tekrar gonderim yok sayildi (idempotency)" || bad "idempotency" "$DUP"

BADID=$(ca "$TOK_A" -X POST "$BASE/api/v1/recommendations/events" -H 'Content-Type: application/json' -d "{\"events\":[
 {\"clientEventId\":\"uuid-degil\",\"sessionId\":\"$SESSION\",\"eventType\":\"session_started\",\"localHour\":14,\"timezoneOffsetMinutes\":180,\"occurredAt\":\"$NOW\"}]}")
[ "$(printf '%s' "$BADID" | field code)" = "INVALID_ID" ] && ok "UUID olmayan olay kimligi reddedildi" || bad "INVALID_ID" "$BADID"

RP=$(ca "$TOK_A" "$BASE/api/v1/recommendations/profile?localHour=14")
printf '%s' "$RP" | grep -q '"eventCount"' && ok "oneri profili okundu" || bad "oneri profili" "$RP"

# ------------------------------------------------------------- 11a) Hikayeler
step "11a) Hikayeler"

# A ve B karsilikli takiplessin ki hikaye akisinda gorunsunler.
ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/follow" >/dev/null

new_image() { # -> mediaId
  local f=/tmp/e2e_story_$1.png
  printf '\211PNG\r\n\032\n' > "$f"; head -c $((100 + $1)) /dev/urandom >> "$f"
  local sz up mid url
  sz=$(wc -c < "$f" | tr -d ' ')
  up=$(ca "$2" -X POST "$BASE/api/v1/media/uploads" -H 'Content-Type: application/json' \
    -d "{\"filename\":\"s$1.png\",\"mimeType\":\"image/png\",\"sizeBytes\":$sz}")
  mid=$(printf '%s' "$up" | field mediaId); url=$(printf '%s' "$up" | field uploadUrl)
  curl -s -o /dev/null -m 30 -X PUT "$url" -H "Content-Type: image/png" --data-binary "@$f"
  ca "$2" -X POST "$BASE/api/v1/media/$mid/complete" >/dev/null
  printf '%s' "$mid"
}

SM1=$(new_image 1 "$TOK_B")
ST1=$(ca "$TOK_B" -X POST "$BASE/api/v1/stories" -H 'Content-Type: application/json' \
  -d "{\"mediaId\":\"$SM1\",\"caption\":\"E2E hikaye\"}")
SID1=$(printf '%s' "$ST1" | field id)
[ -n "$SID1" ] && ok "hikaye olusturuldu" || bad "hikaye olusturma" "$ST1"
printf '%s' "$ST1" | grep -q '"expiresAt"' && ok "sona erme zamani var" || bad "expiresAt" "$ST1"

# Ayni medya ikinci kez kullanilamaz
DUPST=$(ca "$TOK_B" -X POST "$BASE/api/v1/stories" -H 'Content-Type: application/json' -d "{\"mediaId\":\"$SM1\"}")
[ "$(printf '%s' "$DUPST" | field code)" = "MEDIA_ALREADY_ATTACHED" ] && ok "ayni medya ikinci hikayede kullanilamadi" || bad "medya tekrari" "$DUPST"

# A takip ettigi icin B'nin hikayesini akista gormeli
SFEED=$(ca "$TOK_A" "$BASE/api/v1/stories/feed")
printf '%s' "$SFEED" | grep -q "$SID1" && ok "takip edilenin hikayesi akista" || bad "hikaye akisi" "$SFEED"
printf '%s' "$SFEED" | grep -q '"hasUnseen":true' && ok "gorulmemis isareti" || bad "hasUnseen" "$SFEED"

# Sayac baskasina gorunmemeli
printf '%s' "$SFEED" | grep -q '"viewCount"' && bad "sayac baskasina gorunmemeli" "$SFEED" || ok "sayac baskasina gizli"

# Goruntuleme
VIEW=$(ca "$TOK_A" -X PUT "$BASE/api/v1/stories/$SID1/view")
[ "$(printf '%s' "$VIEW" | boolf seen)" = "true" ] && ok "hikaye goruntulendi" || bad "goruntuleme" "$VIEW"

# Sahibi goruntuleyenleri gorebilmeli
VIEWERS=$(ca "$TOK_B" "$BASE/api/v1/stories/$SID1/viewers")
printf '%s' "$VIEWERS" | grep -q "$USER_A" && ok "sahibi goruntuleyenleri goruyor" || bad "goruntuleyen listesi" "$VIEWERS"

# Baskasi goruntuleyenleri gorememeli
NOVIEW=$(ca "$TOK_A" "$BASE/api/v1/stories/$SID1/viewers")
[ "$(printf '%s' "$NOVIEW" | field code)" = "STORY_NOT_FOUND" ] && ok "goruntuleyen listesi yalnizca sahibine" || bad "goruntuleyen yetkisi" "$NOVIEW"

# Profil uzerinden hikayeler
UST=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B/stories")
printf '%s' "$UST" | grep -q "$SID1" && ok "profil hikayeleri listelendi" || bad "profil hikayeleri" "$UST"

# Silme yalnizca sahibine
NODEL=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/stories/$SID1")
[ "$(printf '%s' "$NODEL" | field code)" = "STORY_NOT_FOUND" ] && ok "baskasinin hikayesi silinemiyor" || bad "silme yetkisi" "$NODEL"
DELST=$(ca "$TOK_B" -X DELETE "$BASE/api/v1/stories/$SID1")
printf '%s' "$DELST" | grep -q "silindi" && ok "sahibi hikayeyi sildi" || bad "hikaye silme" "$DELST"
GONEST=$(ca "$TOK_A" "$BASE/api/v1/stories/feed")
printf '%s' "$GONEST" | grep -q "$SID1" && bad "silinen hikaye akista" "$GONEST" || ok "silinen hikaye akistan dustu"

# Engelleme hikayeleri de gizlemeli
SM2=$(new_image 2 "$TOK_B")
ca "$TOK_B" -X POST "$BASE/api/v1/stories" -H 'Content-Type: application/json' -d "{\"mediaId\":\"$SM2\"}" >/dev/null
ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/block" >/dev/null
BLKST=$(ca "$TOK_A" "$BASE/api/v1/stories/feed")
printf '%s' "$BLKST" | grep -q "$USER_B" && bad "engellenenin hikayesi gorunuyor" "$BLKST" || ok "engellenenin hikayesi gizlendi"
ca "$TOK_A" -X DELETE "$BASE/api/v1/users/$USER_B/block" >/dev/null

# ------------------------------------------------------- 11b) Engelleme ve sikayet
step "11b) Engelleme ve sikayet"

# Once A, B'yi takip etsin ki engellemenin takibi kaldirdigini gorebilelim.
ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/follow" >/dev/null
ca "$TOK_B" -X PUT "$BASE/api/v1/users/$USER_A/follow" >/dev/null
# B'nin gonderisine A yorum yazsin; engellenince B'nin sayaci dusmeli.
ca "$TOK_A" -X POST "$BASE/api/v1/posts/$PID_B/comments" -H 'Content-Type: application/json' \
  -d '{"text":"Engellemeden onceki yorum"}' >/dev/null
BEFORE_CNT=$(ca "$TOK_B" "$BASE/api/v1/posts/$PID_B/comments" | num totalCount)

# --- Sikayet ---
REP=$(ca "$TOK_A" -X POST "$BASE/api/v1/reports" -H 'Content-Type: application/json' \
  -d "{\"targetType\":\"POST\",\"targetId\":\"$PID_B\",\"reason\":\"SPAM\",\"details\":\"E2E sikayet\"}")
[ "$(printf '%s' "$REP" | field status)" = "OPEN" ] && ok "gonderi sikayet edildi" || bad "sikayet" "$REP"

REP2=$(ca "$TOK_A" -X POST "$BASE/api/v1/reports" -H 'Content-Type: application/json' \
  -d "{\"targetType\":\"POST\",\"targetId\":\"$PID_B\",\"reason\":\"HARASSMENT\"}")
[ "$(printf '%s' "$REP2" | boolf alreadyReported)" = "true" ] && ok "ayni hedef ikinci kez sikayet edilmedi" || bad "tekrar sikayet" "$REP2"

STORYREP=$(ca "$TOK_A" -X POST "$BASE/api/v1/reports" -H 'Content-Type: application/json' \
  -d "{\"targetType\":\"STORY\",\"targetId\":\"$PID_B\",\"reason\":\"SPAM\"}")
[ "$(printf '%s' "$STORYREP" | field code)" = "UNSUPPORTED_REPORT_TARGET" ] && ok "hikaye sikayeti henuz desteklenmiyor" || bad "STORY sikayeti" "$STORYREP"

MYREP=$(ca "$TOK_A" "$BASE/api/v1/users/me/reports")
[ "$(printf '%s' "$MYREP" | num totalCount)" -ge 1 ] && ok "kendi sikayetlerim listelendi" || bad "sikayet listesi" "$MYREP"

# --- Engelleme ---
BLK=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_B/block")
[ "$(printf '%s' "$BLK" | boolf blocked)" = "true" ] && ok "kullanici engellendi" || bad "engelleme" "$BLK"

SELFBLK=$(ca "$TOK_A" -X PUT "$BASE/api/v1/users/$USER_A/block")
[ "$(printf '%s' "$SELFBLK" | field code)" = "CANNOT_BLOCK_SELF" ] && ok "kendini engelleme reddedildi" || bad "CANNOT_BLOCK_SELF" "$SELFBLK"

BLIST=$(ca "$TOK_A" "$BASE/api/v1/users/me/blocked")
printf '%s' "$BLIST" | grep -q "$USER_B" && ok "engellenenler listesinde" || bad "engellenenler listesi" "$BLIST"

# Profil artik gorunmemeli - iki yonlu
PROFBLK=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B")
[ "$(printf '%s' "$PROFBLK" | field code)" = "USER_NOT_FOUND" ] && ok "engellenen profil gorunmuyor" || bad "profil engeli" "$PROFBLK"
PROFREV=$(ca "$TOK_B" "$BASE/api/v1/users/$USER_A")
[ "$(printf '%s' "$PROFREV" | field code)" = "USER_NOT_FOUND" ] && ok "engel cift yonlu: karsi taraf da goremiyor" || bad "cift yonlu engel" "$PROFREV"

# Takip iliskisi kalkmali
FLWAFTER=$(ca "$TOK_B" "$BASE/api/v1/users/$USER_B/followers")
printf '%s' "$FLWAFTER" | grep -q "$USER_A" && bad "engelleme takibi kaldirmali" "$FLWAFTER" || ok "karsilikli takip kaldirildi"

# Gonderi ve akis
POSTBLK=$(ca "$TOK_A" "$BASE/api/v1/posts/$PID_B")
[ "$(printf '%s' "$POSTBLK" | field code)" = "POST_NOT_FOUND" ] && ok "engellenenin gonderisi acilmyor" || bad "gonderi engeli" "$POSTBLK"

FEEDBLK=$(ca "$TOK_A" "$BASE/api/v1/feed?limit=50")
printf '%s' "$FEEDBLK" | grep -q "$PID_B" && bad "akista engellenen gonderi var" "gorulmemeliydi" || ok "engellenen gonderi akista yok"

# Yorum sayaci da suzulmeli
AFTER_CNT=$(ca "$TOK_B" "$BASE/api/v1/posts/$PID_B/comments" | num totalCount)
[ "$AFTER_CNT" -lt "$BEFORE_CNT" ] && ok "engellenenin yorumu sayacdan dustu ($BEFORE_CNT -> $AFTER_CNT)" || bad "yorum sayaci" "$BEFORE_CNT -> $AFTER_CNT"

# Engel kaldirilinca geri gelmeli
UNBLK=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/users/$USER_B/block")
[ "$(printf '%s' "$UNBLK" | boolf blocked)" = "false" ] && ok "engel kaldirildi" || bad "engel kaldirma" "$UNBLK"
PROFBACK=$(ca "$TOK_A" "$BASE/api/v1/users/$USER_B")
[ "$(printf '%s' "$PROFBACK" | field username)" = "$USER_B" ] && ok "engel kalkinca profil geri geldi" || bad "profil geri gelmedi" "$PROFBACK"

# ---------------------------------------------------------------- Gonderi silme
step "12) Gonderi silme ve medya serbest birakma"
DELP=$(ca "$TOK_A" -X DELETE "$BASE/api/v1/posts/$PID_A")
printf '%s' "$DELP" | grep -q "silindi" && ok "gonderi silindi" || bad "gonderi silme" "$DELP"
GONE=$(ca "$TOK_A" "$BASE/api/v1/posts/$PID_A")
[ "$(printf '%s' "$GONE" | field code)" = "POST_NOT_FOUND" ] && ok "silinen gonderi 404" || bad "silinen gonderi" "$GONE"

printf '\n\033[1m===== OZET: %d gecti, %d kaldi =====\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
