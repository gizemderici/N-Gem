CREATE TABLE comments (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Yorumlar eskiden yeniye okunuyor; sayfalama imleci de bu sırayı kullanıyor.
CREATE INDEX comments_post_idx ON comments(post_id, status, created_at, id);

-- Gönderi cevaplarındaki yorum sayacı yalnızca yayında olanları sayar.
CREATE INDEX comments_author_idx ON comments(author_id, created_at DESC);
