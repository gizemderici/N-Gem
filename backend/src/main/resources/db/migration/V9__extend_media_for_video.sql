-- Video meta verisi. Görsellerde bu alanlar boş kalır.
ALTER TABLE media_assets ADD COLUMN duration_seconds NUMERIC(10, 3);
ALTER TABLE media_assets ADD COLUMN width INTEGER;
ALTER TABLE media_assets ADD COLUMN height INTEGER;

-- Kapak görseli, kendisi de bir medya kaydı. Sunucuda FFmpeg olmadığı için
-- kareyi istemci üretip ayrı bir görsel olarak yüklüyor.
ALTER TABLE media_assets ADD COLUMN thumbnail_media_id UUID REFERENCES media_assets(id) ON DELETE SET NULL;

-- `status` yüklemenin kabul edilip edilmediğini söyler (PENDING/READY/REJECTED/DELETED).
-- `processing_status` ise içeriğin oynatılabilir olup olmadığını söyler. Bugün
-- işleme kuyruğu yok, bu yüzden ikisi birlikte ilerliyor; kuyruk eklendiğinde
-- video "status=READY, processing_status=PROCESSING" durumunda bekleyebilecek.
ALTER TABLE media_assets ADD COLUMN processing_status VARCHAR(30) NOT NULL DEFAULT 'READY';
ALTER TABLE media_assets ADD COLUMN failure_reason VARCHAR(500);

-- Mevcut satırları upload durumlarıyla hizala.
UPDATE media_assets
SET processing_status = CASE status
    WHEN 'PENDING'  THEN 'PENDING_UPLOAD'
    WHEN 'READY'    THEN 'READY'
    ELSE 'FAILED'
END;

-- İşleme kuyruğu eklendiğinde bekleyen işleri bulmak için.
CREATE INDEX media_assets_processing_idx
    ON media_assets(processing_status, updated_at)
    WHERE processing_status IN ('UPLOADED', 'PROCESSING');
