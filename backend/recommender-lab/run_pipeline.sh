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

mkdir -p "$OUTPUT" "$MODELS"

echo "1/5 Soy kütüğünden eğitim seti çıkarılıyor"
psql "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 \
  -f "$LAB/export_training_data.sql" > "$OUTPUT/training-$STAMP.csv"

rows="$(($(wc -l < "$OUTPUT/training-$STAMP.csv") - 1))"
if [ "$rows" -lt "${MIN_TRAINING_ROWS:-1000}" ]; then
  # Az veriyle eğitilmiş bir model gürültü öğrenir; hattı burada durdurmak
  # onu üretime taşımaktan iyi.
  echo "Eğitim verisi yetersiz: $rows satır" >&2
  exit 2
fi
echo "    $rows satır"

echo "2/5 Model eğitiliyor"
"$PYTHON" "$LAB/train_ranker.py" "$OUTPUT/training-$STAMP.csv" \
  --output "$MODELS/nexi-lr-$STAMP.json"

echo "3/5 Çevrimdışı değerlendirme"
"$PYTHON" "$LAB/offline_evaluate.py" "$OUTPUT/training-$STAMP.csv" \
  --k 10 --output "$OUTPUT/metrics-$STAMP.json"

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
"$PYTHON" "$LAB/registry.py" register "$MODELS/nexi-lr-$STAMP.json" \
  --metrics "$OUTPUT/metrics-$STAMP.json"

cat <<EOF

Hat tamamlandı. Model 'candidate' aşamasında.

Sonraki adımlar elle:
  python registry.py promote nexi-lr-$STAMP --to shadow
  # gölgede karşılaştır: psql -f experiment_report.sql
  python registry.py promote nexi-lr-$STAMP --to production
EOF
