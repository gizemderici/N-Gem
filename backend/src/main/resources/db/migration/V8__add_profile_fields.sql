ALTER TABLE users ADD COLUMN bio VARCHAR(280);

-- Avatar bir medya kaydına işaret eder. Kayıt satırı silinirse referans boşalır;
-- uygulama zaten silinmiş medyayı avatar olarak göstermiyor (durum READY olmalı).
ALTER TABLE users ADD COLUMN avatar_media_id UUID REFERENCES media_assets(id) ON DELETE SET NULL;

-- Avatarı olan kullanıcılar azınlıkta kalacağı için kısmi indeks yeterli.
CREATE INDEX users_avatar_idx ON users(avatar_media_id) WHERE avatar_media_id IS NOT NULL;
