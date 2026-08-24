-- Soy kütüğünden **eğitim** verisi çıkarır.
--
-- Etiket, sunumdan sonra gelen olumlu etkileşimden; özelliklerin hepsi istek
-- anında dondurulmuş satırlardan okunuyor. Bu ayrım eğitim verisinin
-- tamamıdır: özellikler geçmişten, etiket gelecekten.
--
-- Çıktısı `train_ranker.py` içindir. `offline_evaluate.py` başka bir şema
-- bekliyor; onun girdisi `export_events.sql`.
--
-- Kullanım:
--   psql "$DATABASE_URL" --csv -v ON_ERROR_STOP=1 -v window_hours=24 \
--     -f export_training_data.sql > training.csv

-- Sonuç penceresi: etkileşim, gösterimden bu kadar saat içinde gelmeliyse
-- o gösterime atfedilir.
\if :{?window_hours}
\else
\set window_hours 24
\endif

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
      -- Gölge koşuları kullanıcıya gösterilmedi.
      AND fr.shadow_of IS NULL
),
-- Her etkileşim **tek** bir gösterime atfedilir: kendisinden önceki en yakın
-- olana. Aksi hâlde bir gönderi sabah ve akşam iki kez gösterilip akşam
-- beğenilseydi, sabahki gösterim de olumlu etiketlenir ve model "bu içerik
-- sabah da iyi gitti" diye öğrenirdi.
attributed AS (
    SELECT DISTINCT ON (e.id)
        s.feed_request_id,
        s.post_id,
        CASE
            WHEN e.event_type IN ('CONTENT_COMPLETE', 'CONTENT_LIKED', 'CONTENT_SAVED', 'CONTENT_SHARED')
                THEN 1
            WHEN e.event_type = 'CONTENT_VIEW' AND COALESCE(e.dwell_millis, 0) >= 5000
                THEN 1
            ELSE 0
        END AS positive,
        CASE
            WHEN e.event_type IN ('CONTENT_HIDDEN', 'CONTENT_REPORTED') THEN 1 ELSE 0
        END AS negative
    FROM recommendation_events e
    JOIN served s
      ON s.user_id = e.user_id
     AND s.post_id = e.post_id
     -- Sunumdan sonraki tepkiler; öncesi zaten özelliklerin içinde.
     AND e.occurred_at >= s.requested_at
     -- Üst sınır olmadan bir aylık beğeni, o gönderinin bütün geçmiş
     -- gösterimlerini olumlu etiketliyordu.
     AND e.occurred_at < s.requested_at + (interval '1 hour' * :window_hours)
    WHERE e.event_type <> 'FEED_SERVED'
    ORDER BY e.id, s.requested_at DESC
),
outcomes AS (
    SELECT
        feed_request_id,
        post_id,
        MAX(positive) AS label,
        MAX(negative) AS negative
    FROM attributed
    GROUP BY feed_request_id, post_id
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
