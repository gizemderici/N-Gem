CREATE TABLE follows (
    follower_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT follows_not_self CHECK (follower_id <> followee_id)
);

-- Birincil anahtar "kimleri takip ediyorum" sorgusunu zaten karşılıyor;
-- bu indeks ters yönü, yani "bu kullanıcıyı kimler takip ediyor"u karşılar.
CREATE INDEX follows_followee_idx ON follows(followee_id, created_at DESC, follower_id);

-- Takip edilenler listesi de kronolojik sayfalanıyor.
CREATE INDEX follows_follower_idx ON follows(follower_id, created_at DESC, followee_id);
