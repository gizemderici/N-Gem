-- Deney sonuç paneli.
--
-- Kol başına isabet, güvenlik ve çeşitlilik sayıları. Bir kolun isabeti
-- yüksek görünüp gizleme oranı da yükselmişse o kol iyi değil; bu yüzden
-- ikisi aynı tabloda.
--
-- Kullanım:
--   psql "$DATABASE_URL" -v days=7 -f experiment_report.sql
--
-- Otomatik geri alma **henüz yok**: eşikler aşağıda tanımlı ama bir kolu
-- kapatmak bugün elle yapılıyor (`FEED_PERSONALIZATION_ENABLED=false` ya da
-- `FEED_EXPERIMENT` ağırlığını sıfırlamak). Otomatik tetikleme, ölçüm
-- toplayan bir izleme sistemi gerektiriyor; o AI Faz 8'in işi.

\set days :days

WITH window_requests AS (
    SELECT *
    FROM feed_requests
    WHERE requested_at >= now() - (:'days' || ' days')::interval
      -- Gölge koşuları kullanıcıya gösterilmedi; davranış metriklerine
      -- karışmamalılar. Gölge–gerçek karşılaştırması aşağıda ayrı.
      AND shadow_of IS NULL
),
served AS (
    SELECT
        r.experiment_variant,
        r.user_id,
        r.id AS request_id,
        c.post_id,
        c.position
    FROM window_requests r
    JOIN feed_candidates c ON c.feed_request_id = r.id
    WHERE c.position IS NOT NULL
),
outcomes AS (
    SELECT
        s.experiment_variant,
        s.request_id,
        s.post_id,
        MAX(CASE WHEN e.event_type IN
            ('CONTENT_LIKED', 'CONTENT_SAVED', 'CONTENT_SHARED', 'CONTENT_COMPLETE')
            THEN 1 ELSE 0 END) AS positive,
        MAX(CASE WHEN e.event_type IN ('CONTENT_HIDDEN', 'CONTENT_REPORTED')
            THEN 1 ELSE 0 END) AS negative
    FROM served s
    LEFT JOIN recommendation_events e
        ON e.user_id = s.user_id AND e.post_id = s.post_id AND e.feed_request_id = s.request_id
    GROUP BY s.experiment_variant, s.request_id, s.post_id
)
SELECT
    COALESCE(o.experiment_variant, 'bilinmiyor')       AS kol,
    COUNT(DISTINCT o.request_id)                        AS istek,
    COUNT(*)                                            AS gosterim,
    ROUND(AVG(o.positive)::numeric, 4)                  AS olumlu_oran,
    -- Güvenlik sınırı: bu oran kontrol kolunun iki katını geçerse kol
    -- kapatılmalı.
    ROUND(AVG(o.negative)::numeric, 4)                  AS olumsuz_oran,
    (
        SELECT ROUND(AVG(distinct_creators)::numeric, 4)
        FROM (
            SELECT COUNT(DISTINCT p.owner_id)::float / NULLIF(COUNT(*), 0) AS distinct_creators
            FROM served s2
            JOIN posts p ON p.id = s2.post_id
            WHERE s2.experiment_variant IS NOT DISTINCT FROM o.experiment_variant
            GROUP BY s2.request_id
        ) per_request
    )                                                   AS uretici_cesitliligi
FROM outcomes o
GROUP BY o.experiment_variant
ORDER BY kol;

-- Gölge ile gerçek sıralamanın ilk 10'daki örtüşmesi.
--
-- Örtüşme 1'e yakınsa yeni kol pratikte aynı akışı üretiyor demektir ve A/B
-- testine çıkmanın anlamı yok; 0'a yakınsa fark büyük, önce sebebine bakmalı.
SELECT
    shadow.experiment_variant                           AS golge_kol,
    COUNT(*)                                            AS karsilastirma,
    ROUND(AVG(overlap.ratio)::numeric, 4)               AS ortusme_top10
FROM feed_requests shadow
JOIN feed_requests real ON real.id = shadow.shadow_of
CROSS JOIN LATERAL (
    SELECT COUNT(*)::float / 10 AS ratio
    FROM (
        SELECT post_id FROM feed_candidates
        WHERE feed_request_id = shadow.id ORDER BY final_score DESC LIMIT 10
    ) s
    JOIN (
        SELECT post_id FROM feed_candidates
        WHERE feed_request_id = real.id AND position IS NOT NULL ORDER BY position LIMIT 10
    ) r USING (post_id)
) overlap
WHERE shadow.shadow_of IS NOT NULL
  AND shadow.requested_at >= now() - (:'days' || ' days')::interval
GROUP BY shadow.experiment_variant
ORDER BY golge_kol;
