-- Her ikisi de PostgreSQL 13+'ta "trusted" uzantı; veritabanı sahibi kurabilir.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- PostgreSQL'in hazır Türkçe sözlüğü yok. Kök bulma (stemming) olmadan,
-- aksan duyarsız bir yapılandırma kuruyoruz: "İstanbul" ~ "istanbul",
-- "çiçek" ~ "cicek", "yazılım" ~ "yazilim" eşleşiyor.
--
-- Sınırı açık olsun: kök bulma yok, yani "kitaplar" araması "kitap" içeren
-- gönderiyi bulmaz. Bunun için hunspell sözlüğü kurulmalı; bu, imaja dosya
-- eklemeyi gerektirdiği için sonraki adıma bırakıldı.
CREATE TEXT SEARCH CONFIGURATION turkish_simple (COPY = simple);

ALTER TEXT SEARCH CONFIGURATION turkish_simple
    ALTER MAPPING FOR hword, hword_part, word, asciiword, asciihword, numword, numhword
    WITH unaccent, simple;

-- Gönderi metni. Üretilmiş sütun her zaman gövdeyle senkron kalır; ayrı bir
-- tetikleyici ya da uygulama kodu gerekmiyor.
-- Etiketler (#kotlin) ayrıştırıcı tarafından '#' atılarak "kotlin" olarak
-- indeksleniyor, bu yüzden ayrı bir hashtag tablosuna gerek yok.
ALTER TABLE posts ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('turkish_simple', coalesce(body, ''))) STORED;

CREATE INDEX posts_search_idx ON posts USING GIN (search_vector);

-- Görünen ad ve kullanıcı adı tek vektörde.
ALTER TABLE users ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('turkish_simple', coalesce(full_name, '') || ' ' || coalesce(username, ''))
    ) STORED;

CREATE INDEX users_search_idx ON users USING GIN (search_vector);

-- Kullanıcı adında parçalı arama ("giz" -> "gizem"): tam kelime eşleşmesi
-- yetmediği için trigram indeksi de var.
CREATE INDEX users_username_trgm_idx ON users USING GIN (username_normalized gin_trgm_ops);

-- Konu adı ve açıklamasında arama.
CREATE INDEX topics_search_idx ON topics
    USING GIN (to_tsvector('turkish_simple', name || ' ' || coalesce(description, '')));

-- Keşfet sıralaması: yayında olan gönderileri tarihe göre gezerken kullanılıyor.
CREATE INDEX posts_explore_idx ON posts(status, created_at DESC) WHERE status = 'PUBLISHED';
