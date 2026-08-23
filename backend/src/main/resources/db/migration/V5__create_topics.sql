CREATE TABLE topics (
    id UUID PRIMARY KEY,
    slug VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(240),
    icon VARCHAR(50) NOT NULL,
    color_hex CHAR(7) NOT NULL,
    display_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX topics_active_order_idx ON topics(active, display_order, id);

CREATE TABLE topic_relations (
    topic_id UUID NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    related_topic_id UUID NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    weight NUMERIC(3, 2) NOT NULL DEFAULT 0.50,
    PRIMARY KEY (topic_id, related_topic_id),
    CONSTRAINT topic_relations_not_self CHECK (topic_id <> related_topic_id)
);

CREATE INDEX topic_relations_related_idx ON topic_relations(related_topic_id);

CREATE TABLE user_topics (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, topic_id),
    UNIQUE (user_id, position),
    CONSTRAINT user_topics_position_range CHECK (position >= 0)
);

CREATE INDEX user_topics_user_position_idx ON user_topics(user_id, position);

CREATE TABLE post_topics (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES topics(id) ON DELETE RESTRICT,
    PRIMARY KEY (post_id, topic_id)
);

CREATE INDEX post_topics_topic_idx ON post_topics(topic_id, post_id);

INSERT INTO topics (id, slug, name, description, icon, color_hex, display_order) VALUES
    ('00000000-0000-4000-8000-000000000001', 'teknoloji',    'Teknoloji',        'Yazılım, donanım ve ürün haberleri',          'cpu',            '#38BDF8',  1),
    ('00000000-0000-4000-8000-000000000002', 'yapay-zeka',   'Yapay Zekâ',       'Modeller, araçlar ve yapay zekâ tartışmaları', 'sparkles',       '#6366F1',  2),
    ('00000000-0000-4000-8000-000000000003', 'sanat',        'Sanat ve Tasarım', 'Dijital sanat, illüstrasyon ve tasarım',       'palette',        '#A855F7',  3),
    ('00000000-0000-4000-8000-000000000004', 'egitim',       'Eğitim',           'Kurslar, öğrenme yöntemleri ve kaynaklar',     'graduation-cap', '#22C55E',  4),
    ('00000000-0000-4000-8000-000000000005', 'spor',         'Spor',             'Müsabakalar, antrenman ve spor gündemi',       'soccer-ball',    '#F59E0B',  5),
    ('00000000-0000-4000-8000-000000000006', 'gundem',       'Gündem',           'Güncel olaylar ve haber akışı',                'newspaper',      '#EF4444',  6),
    ('00000000-0000-4000-8000-000000000007', 'bilim',        'Bilim',            'Araştırma, uzay ve doğa bilimleri',            'atom',           '#06B6D4',  7),
    ('00000000-0000-4000-8000-000000000008', 'oyun',         'Oyun',             'Oyun geliştirme, incelemeler ve espor',        'gamepad',        '#8B5CF6',  8),
    ('00000000-0000-4000-8000-000000000009', 'muzik',        'Müzik',            'Yeni çıkanlar, sahne ve üretim',               'music-note',     '#EC4899',  9),
    ('00000000-0000-4000-8000-00000000000a', 'saglik',       'Sağlık ve Yaşam',  'Beslenme, hareket ve iyi yaşam',               'heart-pulse',    '#14B8A6', 10),
    ('00000000-0000-4000-8000-00000000000b', 'girisimcilik', 'Girişimcilik',     'Startup, ürün ve kariyer',                     'rocket',         '#F97316', 11),
    ('00000000-0000-4000-8000-00000000000c', 'seyahat',      'Seyahat',          'Rotalar, şehirler ve keşif',                   'compass',        '#0EA5E9', 12);

INSERT INTO topic_relations (topic_id, related_topic_id, weight)
SELECT source.id, target.id, pairs.weight
FROM (
    VALUES
        ('teknoloji',    'yapay-zeka',   0.90),
        ('teknoloji',    'bilim',        0.70),
        ('teknoloji',    'oyun',         0.60),
        ('teknoloji',    'girisimcilik', 0.70),
        ('yapay-zeka',   'bilim',        0.80),
        ('yapay-zeka',   'egitim',       0.60),
        ('sanat',        'muzik',        0.70),
        ('sanat',        'oyun',         0.50),
        ('sanat',        'seyahat',      0.50),
        ('egitim',       'bilim',        0.70),
        ('egitim',       'girisimcilik', 0.50),
        ('spor',         'saglik',       0.80),
        ('spor',         'gundem',       0.40),
        ('gundem',       'bilim',        0.40),
        ('muzik',        'seyahat',      0.40)
) AS pairs(source_slug, target_slug, weight)
JOIN topics source ON source.slug = pairs.source_slug
JOIN topics target ON target.slug = pairs.target_slug;

-- İlişkiler simetriktir: yukarıdaki her çifti ters yönde de ekle.
INSERT INTO topic_relations (topic_id, related_topic_id, weight)
SELECT related_topic_id, topic_id, weight FROM topic_relations;
