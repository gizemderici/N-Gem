-- Soy kütüğünden eğitim verisi çıkarır.
--
-- Etiket, sunumdan **sonra** gelen olumlu etkileşimden gelir; özelliklerin
-- hepsi istek anında dondurulmuş satırlardan okunur. Bu ayrım eğitim verisinin
-- tamamıdır: özellikler geçmişten, etiket gelecekten.
--
-- Kullanım:
--   psql "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 \
--     -f export_training_data.sql > training.csv
--
-- Sonra:
--   python train_ranker.py training.csv --output models/nexi-lr-v1.json

WITH served AS (
    SELECT
        fc.feed_request_id,
        fc.post_id,
        fc.candidate_source,
        fc.like_count,
        fc.comment_count,
        fc.age_hours,
        fc.media_type,
        fc.topic_slugs,
        fr.user_id,
        fr.local_hour,
        fr.requested_at,
        p.owner_id AS creator_id,
        s.signal_count,
        s.affinities
    FROM feed_candidates fc
    JOIN feed_requests fr ON fr.id = fc.feed_request_id
    JOIN posts p ON p.id = fc.post_id
    LEFT JOIN user_feature_snapshots s ON s.feed_request_id = fc.feed_request_id
    -- Yalnızca gösterilen adaylar etiketlenebilir: gösterilmeyene kullanıcının
    -- tepki verme şansı hiç olmadı, onu "olumsuz" saymak modeli yanlış eğitir.
    WHERE fc.position IS NOT NULL
),
outcomes AS (
    SELECT
        s.feed_request_id,
        s.post_id,
        -- Olumlu: tamamlama, beğeni, kaydetme, paylaşma ya da uzun görüntüleme.
        MAX(
            CASE
                WHEN re.event_type IN ('CONTENT_COMPLETE', 'CONTENT_LIKED', 'CONTENT_SAVED', 'CONTENT_SHARED')
                    THEN 1
                WHEN re.event_type = 'CONTENT_VIEW' AND COALESCE(re.dwell_millis, 0) >= 5000
                    THEN 1
                ELSE 0
            END
        ) AS label,
        MAX(
            CASE WHEN re.event_type IN ('CONTENT_HIDDEN', 'CONTENT_REPORTED') THEN 1 ELSE 0 END
        ) AS negative
    FROM served s
    LEFT JOIN recommendation_events re
        ON re.user_id = s.user_id
       AND re.post_id = s.post_id
       -- Sunumdan sonraki tepkiler; öncesi zaten özelliklerin içinde.
       AND re.occurred_at >= s.requested_at
    GROUP BY s.feed_request_id, s.post_id
)
SELECT
    s.feed_request_id,
    s.post_id,
    s.candidate_source,
    s.like_count,
    s.comment_count,
    s.age_hours,
    s.media_type,
    array_to_string(s.topic_slugs, ';') AS topic_slugs,
    s.creator_id,
    s.local_hour,
    COALESCE(s.signal_count, 0) AS signal_count,
    COALESCE(s.affinities::text, '{}') AS affinities,
    COALESCE(o.label, 0) AS label,
    COALESCE(o.negative, 0) AS negative
FROM served s
LEFT JOIN outcomes o
    ON o.feed_request_id = s.feed_request_id AND o.post_id = s.post_id
-- Zaman bazlı ayrım dışarıda yapılacak; sıralı çıktı bunu kolaylaştırıyor.
ORDER BY s.requested_at, s.feed_request_id, s.post_id;
