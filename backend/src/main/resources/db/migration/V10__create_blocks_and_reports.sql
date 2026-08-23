-- Engelleme kayıtta tek yönlü tutulur (kim kimi engelledi) ama etkisi çift
-- yönlüdür: A, B'yi engellediyse ikisi de birbirinin içeriğini görmez.
CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT user_blocks_not_self CHECK (blocker_id <> blocked_id)
);

-- Birincil anahtar "kimleri engelledim"i karşılıyor; bu indeks ters yönü,
-- yani sorgulardaki çift yönlü kontrolün ikinci yarısını hızlandırır.
CREATE INDEX user_blocks_blocked_idx ON user_blocks(blocked_id, blocker_id);

CREATE TABLE reports (
    id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- USER | POST | COMMENT | STORY | MESSAGE. Beş ayrı tabloya yabancı anahtar
    -- verilemediği için hedef kimliği düz UUID; varlığı serviste doğrulanır.
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(1000),
    -- OPEN | REVIEWING | ACTIONED | DISMISSED
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Aynı kişi aynı hedefi iki kez şikâyet edemez; ikinci istek mevcut kaydı döner.
    CONSTRAINT reports_unique_target UNIQUE (reporter_id, target_type, target_id)
);

CREATE INDEX reports_reporter_idx ON reports(reporter_id, created_at DESC);
-- Moderasyon kuyruğu: önce en eski açık şikâyet.
CREATE INDEX reports_queue_idx ON reports(status, created_at) WHERE status IN ('OPEN', 'REVIEWING');

-- Şikâyetin durum geçmişi. Kim ne zaman ne yaptı sorusunun cevabı burada;
-- `reports.status` yalnızca en son durumu tutar.
CREATE TABLE report_events (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    note VARCHAR(1000),
    -- Moderatör; sistem tarafından üretilen olaylarda boş kalır.
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX report_events_report_idx ON report_events(report_id, created_at);
