-- Kullanıcının "bunu gösterme" dediği gönderiler.
--
-- Gizleme bugüne kadar yalnızca bir öneri olayıydı: sıralamada olumsuz puan
-- veriyor ama gönderi ertesi gün yine akışa girebiliyordu. Gizleme bir analiz
-- kaydı değil, kullanıcının açık tercihidir; aday havuzundan kalıcı olarak
-- çıkarılabilmesi için kendi tablosunda durmalı.
CREATE TABLE hidden_posts (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, post_id)
);

-- Aday üretiminde "bu kullanıcının gizledikleri" tek seferde okunuyor.
CREATE INDEX hidden_posts_user_idx ON hidden_posts(user_id, created_at DESC);

-- Sunum kaydının hangi aday kaynağından geldiği. Akışın neden o sırayla
-- olustugunu sonradan yeniden uretebilmek icin gerekli; Faz 3'teki tam
-- soy kutugu tablolari bunun uzerine gelecek.
ALTER TABLE recommendation_events
    ADD COLUMN candidate_source VARCHAR(30);
