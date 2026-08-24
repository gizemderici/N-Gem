#!/usr/bin/env bash
# Yerel kol karşılaştırması: aynı kullanıcıları her koldan geçirir.
#
# Planın önerdiği "her kola bir kullanıcı" kurulumunda kol başına n=1 olur ve
# kol etkisi kullanıcı farkından ayrılamaz. Burada aynı kullanıcılar bütün
# kollardan geçiyor; karşılaştırma kişi içi olduğu için o karışıklık yok.
#
# Kullanım:
#   ./scripts/compare-arms.sh
#   ARMS="control heuristic" HOURS="14" ./scripts/compare-arms.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${BASE_URL:-http://127.0.0.1:8080}"
ARMS="${ARMS:-control heuristic safe fair}"
HOURS="${HOURS:-9 14 21}"
PASSWORD="${DEMO_PASSWORD:-Demo1234!}"
LIMIT="${LIMIT:-10}"

cd "$ROOT"

compose() { docker compose "$@"; }
psql_() { compose exec -T postgres psql -U nexi -d nexi "$@"; }

# Saf bash: Git Bash'te /proc/sys/kernel/random/uuid yok ve `python` PATH'te
# olmayabiliyor. Ikisine de guvenince sessizce bos sessionId gonderiliyordu.
uuid() {
  printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x'     $((RANDOM * 2)) $((RANDOM * 2)) $((RANDOM * 2)) $((RANDOM % 4096))     $(( (RANDOM % 4096) + 32768 )) $((RANDOM * 2)) $((RANDOM * 2)) $((RANDOM * 2))
}

wait_healthy() {
  for _ in $(seq 1 30); do
    if curl -sf "$BASE/health" | grep -q '"database":"up"'; then return 0; fi
    sleep 2
  done
  echo "Backend saglikli duruma gelmedi." >&2
  exit 1
}

echo "Demo kullanicilari okunuyor..."
mapfile -t USERS < <(psql_ -tAc "SELECT username FROM users WHERE username LIKE 'demo\\_%' ORDER BY username")
if [ "${#USERS[@]}" -eq 0 ]; then
  echo "Demo kullanicisi yok. Once seed betigini calistirin." >&2
  exit 1
fi
echo "  ${#USERS[@]} kullanici"

# Jetonlar bir kez alınıyor ve bütün kollarda kullanılıyor: her kol için
# yeniden giriş yapmak giriş hız sınırına takılıyordu, ve JWT sunucu yeniden
# başlatmadan etkilenmiyor.
#
# Sınır adres başına 60 saniyede 10 deneme (RequestRateLimiter varsayılanı),
# bu yüzden girişler aralıklı; pencere yine de doluysa bir kez bekleyip
# yeniden deniyoruz.
echo "Oturumlar aciliyor..."
declare -A TOKENS=()
sayi=0

giris() {
  # `grep` eslesme bulamazsa 1 doner; `pipefail` ile bu betigi oldururdu ve
  # asagidaki hata kontrolune hic sira gelmezdi.
  curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json'     -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}"     | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//' || true
}

for user in "${USERS[@]}"; do
  email="demo.${user#demo_}@nsosyal.local"
  token=$(giris "$email")
  if [ -z "$token" ]; then
    echo "  $user: hiz siniri, pencere bekleniyor..." >&2
    sleep 62
    token=$(giris "$email")
  fi
  if [ -z "$token" ]; then
    echo "  $user: giris basarisiz, atlaniyor" >&2
    continue
  fi
  TOKENS["$user"]="$token"
  sayi=$((sayi + 1))
  sleep 7
done
echo "  $sayi oturum"
if [ "$sayi" -eq 0 ]; then
  echo "Hicbir oturum acilamadi." >&2
  exit 1
fi

# Rapor yalnizca bu kosuyu gormeli: ayni veritabaninda onceki kosularin
# heuristic istekleri duruyor ve pencereye girip sayilari kirletiyordu.
BASLANGIC=$(psql_ -tAc "SELECT now()" | head -1 | sed 's/^ *//;s/ *$//')
echo "Kosu baslangici: $BASLANGIC"

for arm in $ARMS; do
  echo
  echo "== kol: $arm =="
  FEED_EXPERIMENT="$arm:1" compose up -d >/dev/null 2>&1
  wait_healthy

  requests=0
  for user in "${!TOKENS[@]}"; do
    for hour in $HOURS; do
      curl -s -o /dev/null \
        "$BASE/api/v1/posts/feed?limit=$LIMIT&sessionId=$(uuid)&localHour=$hour&timezoneOffsetMinutes=180" \
        -H "Authorization: Bearer ${TOKENS[$user]}"
      requests=$((requests + 1))
    done
  done
  echo "  $requests istek"
done

echo
echo "== rapor =="
psql_ -v since="$BASLANGIC" -f - < recommender-lab/arm_comparison.sql
