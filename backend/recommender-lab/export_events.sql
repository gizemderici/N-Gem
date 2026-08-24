-- Çevrimdışı **değerlendirme** için olay akışı.
--
-- Bu dosya `export_training_data.sql` ile karıştırılmamalı; ikisi farklı
-- soruları cevaplıyor ve şemaları bilerek ayrı:
--
--   * Bu dosya  → `offline_evaluate.py`: politikaları karşılaştırır. Aday
--     havuzunu ve zaman ayrımını kendisi kurduğu için ham etkileşim akışına
--     ihtiyacı var (kim, neye, ne zaman).
--   * Diğeri    → `train_ranker.py`: model eğitir. Her satır sunulmuş bir
--     aday ve etiketi; özellikler istek anında dondurulmuş.
--
-- Aynı CSV'yi ikisine de vermek `run_pipeline.sh`'ı üçüncü adımda
-- "Eksik kolonlar: event_type, item_id, timestamp, user_id" ile öldürüyordu.
--
-- Kullanım:
--   psql "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 -f export_events.sql > events.csv

SELECT
    re.user_id::text                                            AS user_id,
    COALESCE(re.post_id::text, '')                              AS item_id,
    to_char(re.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                                                                AS timestamp,
    re.local_hour                                               AS local_hour,
    lower(re.event_type)                                        AS event_type,
    COALESCE((
        SELECT string_agg(t.slug, ';' ORDER BY t.display_order)
        FROM post_topics pt JOIN topics t ON t.id = pt.topic_id
        WHERE pt.post_id = re.post_id
    ), '')                                                      AS topics,
    COALESCE(re.target_feature, '')                             AS target_feature,
    COALESCE(p.owner_id::text, '')                              AS creator_id,
    CASE
        WHEN re.post_id IS NULL THEN ''
        WHEN EXISTS (
            SELECT 1 FROM post_media pm JOIN media_assets m ON m.id = pm.media_id
            WHERE pm.post_id = re.post_id AND m.mime_type LIKE 'video/%'
        ) THEN 'video'
        WHEN EXISTS (SELECT 1 FROM post_media pm WHERE pm.post_id = re.post_id) THEN 'image'
        ELSE 'text'
    END                                                         AS media_type,
    COALESCE(re.dwell_millis, 0)                                AS dwell_ms,
    COALESCE(re.completion_ratio, 0)                            AS completion_ratio
FROM recommendation_events re
LEFT JOIN posts p ON p.id = re.post_id
-- Sunum kaydı kullanıcının bir şey yaptığını söylemiyor; değerlendirmede
-- aday havuzunu şişirmekten başka işe yaramaz.
WHERE re.event_type <> 'FEED_SERVED'
ORDER BY re.occurred_at, re.id;
