-- Model sağlığı: gecikme ve yedeğe düşme kaydı.
--
-- "Her üretim tahmini bir model sürümüne bağlanabilmeli" kriteri sürüm
-- kolonuyla zaten karşılanıyordu; eksik olan, o sürümün **nasıl** çalıştığıydı.
-- Gecikme ve hata görünmeden bir modelin bozulduğu ancak kullanıcı şikâyet
-- ettiğinde anlaşılır.

ALTER TABLE feed_requests
    ADD COLUMN duration_millis INTEGER;

ALTER TABLE feed_requests
    ADD CONSTRAINT feed_requests_duration_check
        CHECK (duration_millis IS NULL OR duration_millis >= 0);

-- Kişiselleştirmenin çalışmadığı her istek.
--
-- Ayrı tabloda çünkü bunlar `feed_requests` satırı üretmiyor: sıralama
-- tamamlanamadığı için kaydedilecek aday da yok. Alarm kaynağı bu tablo;
-- oran ani yükseldiğinde model ya da veri bozulmuş demektir.
CREATE TABLE feed_fallbacks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- RANKING_ERROR, CONSENT_MISSING, EXPERIMENT_DISABLED, NO_CANDIDATES
    reason VARCHAR(40) NOT NULL,
    model_version VARCHAR(40),
    experiment_variant VARCHAR(40),
    -- İstisna sınıfı ve kısa mesaj; yığın izi değil.
    detail VARCHAR(300)
);

CREATE INDEX feed_fallbacks_time_idx ON feed_fallbacks(occurred_at DESC);
CREATE INDEX feed_fallbacks_reason_idx ON feed_fallbacks(reason, occurred_at DESC);
