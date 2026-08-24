-- Model sağlığı panosu.
--
-- Bir modelin bozulduğu üç yerden görünür: gecikme uzar, yedeğe düşme oranı
-- yükselir, ya da sunum hacmi beklenmedik şekilde değişir. Üçü aynı tabloda
-- olmadan "model iyi mi" sorusunun cevabı yok.
--
-- Kullanım:
--   psql "$DATABASE_URL" -v hours=24 -f model_health.sql

\set hours :hours

-- Sürüm başına gecikme ve hacim.
SELECT
    model_version,
    policy_version,
    feature_version,
    COALESCE(experiment_variant, 'bilinmiyor')                          AS kol,
    COUNT(*)                                                            AS istek,
    ROUND(AVG(duration_millis)::numeric, 1)                             AS ortalama_ms,
    PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_millis)        AS p50_ms,
    PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_millis)       AS p95_ms,
    MAX(duration_millis)                                                AS en_yuksek_ms,
    ROUND(AVG(candidate_count)::numeric, 1)                             AS ortalama_aday,
    ROUND(AVG(returned_count)::numeric, 1)                              AS ortalama_donen
FROM feed_requests
WHERE requested_at >= now() - (:'hours' || ' hours')::interval
  AND shadow_of IS NULL
GROUP BY model_version, policy_version, feature_version, experiment_variant
ORDER BY istek DESC;

-- Yedeğe düşme oranı. RANKING_ERROR sıfırdan farklıysa alarm; diğerleri
-- beklenen durumlar (rıza yok, kontrol kolu, içerik yok).
WITH totals AS (
    SELECT COUNT(*) AS served
    FROM feed_requests
    WHERE requested_at >= now() - (:'hours' || ' hours')::interval
      AND shadow_of IS NULL
)
SELECT
    f.reason,
    f.model_version,
    COUNT(*)                                                            AS adet,
    ROUND((COUNT(*)::numeric / NULLIF(COUNT(*) + (SELECT served FROM totals), 0)) * 100, 2)
                                                                        AS yuzde,
    MAX(f.occurred_at)                                                  AS son,
    -- Aynı hatanın tekrarı; ilk örneği görmek sebebi bulmaya yetiyor.
    MIN(f.detail)                                                       AS ornek
FROM feed_fallbacks f
WHERE f.occurred_at >= now() - (:'hours' || ' hours')::interval
GROUP BY f.reason, f.model_version
ORDER BY adet DESC;

-- Saatlik hata eğrisi: ani sıçrama bir dağıtımla mı çakıştı sorusuna cevap.
SELECT
    date_trunc('hour', occurred_at)                                     AS saat,
    COUNT(*) FILTER (WHERE reason = 'RANKING_ERROR')                    AS siralama_hatasi,
    COUNT(*) FILTER (WHERE reason = 'NO_CANDIDATES')                    AS aday_yok,
    COUNT(*) FILTER (WHERE reason = 'CONSENT_MISSING')                  AS riza_yok
FROM feed_fallbacks
WHERE occurred_at >= now() - (:'hours' || ' hours')::interval
GROUP BY saat
ORDER BY saat DESC;
