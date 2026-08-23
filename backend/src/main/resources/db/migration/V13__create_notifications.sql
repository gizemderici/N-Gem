CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    -- Bildirimi alan kişi.
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Eylemi yapan; sistem bildirimlerinde boş.
    actor_id UUID REFERENCES users(id) ON DELETE CASCADE,
    -- FOLLOW | POST_LIKE | POST_COMMENT | MESSAGE | SYSTEM
    type VARCHAR(30) NOT NULL,
    -- POST | COMMENT | STORY | MESSAGE | USER | CONVERSATION
    target_type VARCHAR(20),
    target_id UUID,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    -- Aynı kişinin aynı hedefte aynı eylemi tekrar tekrar bildirim üretmesin.
    -- Beğen-kaldır-beğen ya da arka arkaya mesaj tek satırı tazeler.
    -- NULLS NOT DISTINCT (PostgreSQL 15+) olmadan hedefsiz bildirimlerde
    -- (örneğin takip) NULL'lar birbirinden farklı sayılır ve kısıt işlemezdi.
    CONSTRAINT notifications_unique_event
        UNIQUE NULLS NOT DISTINCT (user_id, actor_id, type, target_type, target_id)
);

-- Bildirim listesi: kullanıcının en yenisi başta.
CREATE INDEX notifications_user_idx ON notifications(user_id, created_at DESC, id DESC);

-- Okunmamış sayacı yalnızca okunmamışları tarasın.
CREATE INDEX notifications_unread_idx ON notifications(user_id) WHERE read_at IS NULL;

-- Saklama süresi dolanları toplayan temizlik işi.
CREATE INDEX notifications_retention_idx ON notifications(created_at);
