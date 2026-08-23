CREATE TABLE recommendation_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id UUID REFERENCES posts(id) ON DELETE CASCADE,
    client_event_id UUID NOT NULL,
    session_id UUID NOT NULL,
    feed_request_id UUID,
    event_type VARCHAR(40) NOT NULL,
    surface VARCHAR(40) NOT NULL,
    position INTEGER,
    dwell_millis BIGINT,
    completion_ratio DOUBLE PRECISION,
    local_hour SMALLINT NOT NULL,
    timezone_offset_minutes SMALLINT NOT NULL,
    target_feature VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, client_event_id),
    CONSTRAINT recommendation_events_local_hour_check CHECK (local_hour BETWEEN 0 AND 23),
    CONSTRAINT recommendation_events_timezone_check CHECK (timezone_offset_minutes BETWEEN -840 AND 840),
    CONSTRAINT recommendation_events_position_check CHECK (position IS NULL OR position BETWEEN 0 AND 999),
    CONSTRAINT recommendation_events_dwell_check CHECK (dwell_millis IS NULL OR dwell_millis BETWEEN 0 AND 86400000),
    CONSTRAINT recommendation_events_completion_check CHECK (completion_ratio IS NULL OR completion_ratio BETWEEN 0 AND 1)
);

CREATE INDEX recommendation_events_user_time_idx
    ON recommendation_events(user_id, occurred_at DESC);

CREATE INDEX recommendation_events_post_type_idx
    ON recommendation_events(post_id, event_type, occurred_at DESC)
    WHERE post_id IS NOT NULL;

CREATE INDEX recommendation_events_received_idx
    ON recommendation_events(received_at DESC);
