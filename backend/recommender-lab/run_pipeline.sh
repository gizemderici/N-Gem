#!/usr/bin/env bash
# Otomatik eğitim hattı: dışa aktar → eğit → değerlendir → kayma → kaydet.
#
# Her adım bir öncekinin çıktısını kullanır ve herhangi biri patlarsa hat
# durur (`set -e`). Terfi bilerek burada yok: üretime çıkış kapı kontrolü
# gerektiriyor ve otomatik yapılmamalı.
#
# Kullanım:
#   DATABASE_URL=postgres://... ./run_pipeline.sh
#
# Zamanlanmış iş olarak koşacaksa çıkış kodu izlenmeli; kayma tespiti
# sıfırdan farklı dönerse model yeniden eğitilmeli demektir.

set -euo pipefail

LAB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="${OUTPUT_DIR:-$LAB/output}"
MODELS="$LAB/models"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

: "${DATABASE_URL:?DATABASE_URL gerekli}"
PYTHON="${PYTHON:-python3}"
# psql yerel kurulumda olmayabilir; demo icin compose konteynerine
# yonlendirilebilsin diye disaridan verilebiliyor.
PSQL="${PSQL:-psql}"
# Surum adi. Verilmezse her kosu damgayla ayri bir kayit olusturur;
# verilirse (ornegin nexi-lr-demo-v1) sabit kalir ve kayit guncellenir.
VERSION="${MODEL_VERSION:-${MODEL_NAME:-nexi-lr}-$STAMP}"
# Kurgu veriyle egitiliyorsa isaretlensin; uretim kapisi bunu reddediyor.
DEMO_FLAG=""
if [ "${DEMO:-0}" = "1" ]; then DEMO_FLAG="--demo"; fi

mkdir -p "$OUTPUT" "$MODELS"

# İki ayrı şema, iki ayrı tüketici. Aynı dosyayı ikisine de vermek hattı
# üçüncü adımda "Eksik kolonlar" hatasıyla öldürüyordu.
echo "1/5 Soy kütüğünden eğitim seti ve olay akışı çıkarılıyor"
$PSQL "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 -v window_hours="${LABEL_WINDOW_HOURS:-24}" \
  -f "$LAB/export_training_data.sql" > "$OUTPUT/training-$STAMP.csv"
$PSQL "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 \
  -f "$LAB/export_events.sql" > "$OUTPUT/events-$STAMP.csv"

rows="$(($(wc -l < "$OUTPUT/training-$STAMP.csv") - 1))"
events="$(($(wc -l < "$OUTPUT/events-$STAMP.csv") - 1))"
if [ "$rows" -lt "${MIN_TRAINING_ROWS:-1000}" ]; then
  # Az veriyle eğitilmiş bir model gürültü öğrenir; hattı burada durdurmak
  # onu üretime taşımaktan iyi.
  echo "Eğitim verisi yetersiz: $rows satır (en az ${MIN_TRAINING_ROWS:-1000})" >&2
  exit 2
fi
echo "    $rows eğitim satırı, $events olay"

echo "2/5 Model eğitiliyor"
"$PYTHON" "$LAB/train_ranker.py" "$OUTPUT/training-$STAMP.csv" \
  --output "$MODELS/$VERSION.json" \
  --model-version "$VERSION"

echo "3/5 Çevrimdışı değerlendirme"
# Eğitilmiş model de karşılaştırmaya giriyor. Taban modeller olmadan "şu
# skoru aldı" demek kıyas noktası bulunmadığı için bir şey söylemez.
"$PYTHON" "$LAB/offline_evaluate.py" "$OUTPUT/events-$STAMP.csv" \
  --k 10 --model "$MODELS/$VERSION.json" --output "$OUTPUT/metrics-$STAMP.json"

echo "4/5 Kayma kontrolü"
previous="$(ls -1 "$OUTPUT"/training-*.csv 2>/dev/null | sort | tail -2 | head -1)"
if [ -n "$previous" ] && [ "$previous" != "$OUTPUT/training-$STAMP.csv" ]; then
  # Kayma çıkışı sıfırdan farklı döner; hattı durdurmak yerine rapor edip
  # devam ediyoruz, karar insanın.
  "$PYTHON" "$LAB/drift.py" "$previous" "$OUTPUT/training-$STAMP.csv" \
    --output "$OUTPUT/drift-$STAMP.json" || echo "    Kayma tespit edildi, rapora bakın"
else
  echo "    Karşılaştırılacak önceki veri yok, atlanıyor"
fi

echo "5/5 Kayıt defterine ekleniyor"
"$PYTHON" "$LAB/registry.py" register "$MODELS/$VERSION.json" \
  --metrics "$OUTPUT/metrics-$STAMP.json" $DEMO_FLAG

cat <<EOF

Hat tamamlandı. Model 'candidate' aşamasında.

Sonraki adımlar elle:
  python registry.py promote $VERSION --to shadow
  # gölgede karşılaştır: psql -f experiment_report.sql
  python registry.py promote $VERSION --to production
EOF
