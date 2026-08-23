-- Olay sözleşmesinin sürümü ve olayı üreten istemci.
--
-- Bunlar olmadan bozuk veriyi hangi sürümün ürettiği anlaşılamıyordu: bir
-- istemci sürümü yanlış tamamlama oranı gönderse tabloda o satırları ayırmanın
-- yolu yoktu. Eğitim verisi bu tablodan üretileceği için kaynağın kayıtlı
-- olması şart.
ALTER TABLE recommendation_events
    ADD COLUMN schema_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN app_version VARCHAR(20),
    ADD COLUMN platform VARCHAR(10);

ALTER TABLE recommendation_events
    ADD CONSTRAINT recommendation_events_schema_version_check
        CHECK (schema_version BETWEEN 1 AND 999),
    ADD CONSTRAINT recommendation_events_platform_check
        CHECK (platform IS NULL OR platform IN ('ios', 'android', 'web', 'backend'));

-- "Hangi istemci sürümü ne kadar olay üretti / ne kadarı bozuk" sorgusu için.
CREATE INDEX recommendation_events_client_idx
    ON recommendation_events(platform, app_version, received_at DESC)
    WHERE platform IS NOT NULL;
