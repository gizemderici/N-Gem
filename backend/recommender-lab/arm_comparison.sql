-- Yerel kol karşılaştırması.
--
-- Planın önerdiği kurulum "her kola bir kullanıcı" idi. Kol başına n=1 ile
-- kol etkisi kullanıcı farkından ayrılamaz: A ile B arasındaki fark kolun
-- mu, yoksa iki kişinin farklı ilgi alanlarının mı sonucu olduğu
-- söylenemez. Bunun yerine **aynı** kullanıcılar her koldan geçiriliyor;
-- karşılaştırma kişi içi olduğu için kullanıcı farkı ortadan kalkıyor.
--
-- Kullanım:
--   psql "$DATABASE_URL" -v since='2026-08-24 10:00:00+00' -f arm_comparison.sql
--
-- `since` yalnizca ilgilenilen kosuyu kapsamali: ayni veritabaninda onceki
-- kosularin istekleri duruyor ve genis bir pencere onlari da sayiyordu.
\if :{?since}
\else
\set since '-infinity'
\endif

-- Gösterilen slotlar. Gölge koşuları kullanıcıya gösterilmediği için dışarıda.
CREATE TEMP VIEW gosterilen AS
SELECT
    fr.id            AS request_id,
    fr.user_id,
    fr.experiment_variant AS kol,
    fr.duration_millis,
    fc.post_id,
    fc.position,
    p.owner_id       AS creator_id,
    fc.topic_slugs
FROM feed_requests fr
JOIN feed_candidates fc ON fc.feed_request_id = fr.id
JOIN posts p ON p.id = fc.post_id
WHERE fr.shadow_of IS NULL
  AND fc.position IS NOT NULL
  AND fr.requested_at >= :'since'::timestamptz;

-- İstek başına çeşitlilik, sonra kol ortalaması. Doğrudan kol düzeyinde
-- saymak yanıltıcı olurdu: çok istek atan bir kullanıcı ortalamayı kendi
-- ilgi alanına çeker.
CREATE TEMP VIEW istek_basi AS
SELECT
    request_id,
    kol,
    user_id,
    MAX(duration_millis) AS duration_millis,
    COUNT(*)             AS slot,
    COUNT(DISTINCT creator_id)::float / NULLIF(COUNT(*), 0) AS uretici_cesitliligi,
    (SELECT COUNT(DISTINCT slug)::float / NULLIF(COUNT(*), 0)
     FROM gosterilen g2, unnest(g2.topic_slugs) AS slug
     WHERE g2.request_id = g.request_id)                    AS konu_cesitliligi,
    -- Az takipçili üreticiye ayrılan pay.
    AVG(CASE WHEN (SELECT COUNT(*) FROM follows f WHERE f.followee_id = g.creator_id) <= 5
             THEN 1.0 ELSE 0.0 END)                         AS yeni_uretici_payi,
    -- Kullanıcının daha önce gizlediği konudan içerik gösterme oranı.
    -- Güvenlik hedefinin ölçülebilen tek etkisi bu; gizlenen gönderinin
    -- kendisi zaten aday havuzuna hiç girmiyor.
    AVG(CASE WHEN EXISTS (
            SELECT 1
            FROM recommendation_events e
            JOIN post_topics pt ON pt.post_id = e.post_id
            JOIN topics t ON t.id = pt.topic_id
            WHERE e.user_id = g.user_id
              AND e.event_type IN ('CONTENT_HIDDEN', 'CONTENT_REPORTED')
              AND t.slug = ANY(g.topic_slugs)
        ) THEN 1.0 ELSE 0.0 END)                            AS olumsuz_konu_orani
FROM gosterilen g
GROUP BY request_id, kol, user_id;

SELECT
    COALESCE(kol, 'bilinmiyor')                          AS kol,
    COUNT(*)                                             AS istek,
    COUNT(DISTINCT user_id)                              AS kullanici,
    ROUND(AVG(slot)::numeric, 1)                         AS ort_slot,
    ROUND(AVG(uretici_cesitliligi)::numeric, 4)          AS uretici_cesitliligi,
    ROUND(AVG(konu_cesitliligi)::numeric, 4)             AS konu_cesitliligi,
    ROUND(AVG(yeni_uretici_payi)::numeric, 4)            AS yeni_uretici_payi,
    ROUND(AVG(olumsuz_konu_orani)::numeric, 4)           AS olumsuz_konu_orani,
    PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_millis)  AS p50_ms,
    PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_millis) AS p95_ms
FROM istek_basi
GROUP BY kol
ORDER BY kol;

-- Kollar arasında sıralamanın ne kadar değiştiği. Aynı kullanıcı ve aynı
-- saat için iki kolun ilk on içeriğinin örtüşmesi.
SELECT
    a.experiment_variant || ' - ' || b.experiment_variant AS karsilastirma,
    COUNT(*)                                             AS cift,
    ROUND(AVG(o.ratio)::numeric, 4)                      AS ortusme_top10
FROM feed_requests a
JOIN feed_requests b
  ON b.user_id = a.user_id
 AND b.local_hour = a.local_hour
 AND b.shadow_of IS NULL
 AND a.shadow_of IS NULL
 AND a.experiment_variant < b.experiment_variant
CROSS JOIN LATERAL (
    SELECT COUNT(*)::float / 10 AS ratio
    FROM (SELECT post_id FROM feed_candidates
          WHERE feed_request_id = a.id ORDER BY rank_position LIMIT 10) x
    JOIN (SELECT post_id FROM feed_candidates
          WHERE feed_request_id = b.id ORDER BY rank_position LIMIT 10) y USING (post_id)
) o
-- Iki taraf da pencerede olmali: yalnizca `a` suzuldugunde onceki
-- kosularin istekleri `b` olarak eslesip cift sayisini sisiriyordu.
WHERE a.requested_at >= :'since'::timestamptz
  AND b.requested_at >= :'since'::timestamptz
GROUP BY 1
ORDER BY 1;
