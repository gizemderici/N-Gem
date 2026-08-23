CREATE TABLE stories (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Bir medya yalnızca tek bir hikâyede kullanılabilir; gönderilerdeki
    -- post_media.UNIQUE(media_id) kısıtıyla aynı mantık.
    media_id UUID NOT NULL UNIQUE REFERENCES media_assets(id) ON DELETE RESTRICT,
    caption VARCHAR(280),
    status VARCHAR(20) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT stories_expiry_after_publish CHECK (expires_at > published_at)
);

-- Hikâye akışı: yayında, süresi dolmamış, yazara göre gruplu.
CREATE INDEX stories_active_idx ON stories(status, expires_at, owner_id, published_at);

-- Süresi dolanları toplayan temizlik işi bu indeksi kullanıyor.
CREATE INDEX stories_expiry_idx ON stories(expires_at) WHERE status = 'PUBLISHED';

CREATE TABLE story_views (
    story_id UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- İlk görüntüleme anı; tekrar bakmak zaman damgasını değiştirmez.
    viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (story_id, viewer_id)
);

-- Görüntüleyen listesi en yeniden eskiye sayfalanıyor.
CREATE INDEX story_views_story_idx ON story_views(story_id, viewed_at DESC, viewer_id DESC);
