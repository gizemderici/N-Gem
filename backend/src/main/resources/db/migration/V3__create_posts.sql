CREATE TABLE posts (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body VARCHAR(2000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX posts_feed_idx ON posts(status, created_at DESC, id DESC);
CREATE INDEX posts_owner_idx ON posts(owner_id, created_at DESC);

CREATE TABLE post_media (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    PRIMARY KEY (post_id, media_id),
    UNIQUE (media_id),
    UNIQUE (post_id, position)
);

CREATE TABLE post_likes (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX post_likes_user_idx ON post_likes(user_id, created_at DESC);

CREATE TABLE post_saves (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX post_saves_user_idx ON post_saves(user_id, created_at DESC);
