-- Akış soy kütüğü: bir sıralamanın neden o şekilde oluştuğunu sonradan
-- açıklayabilmek ve eğitim verisini yalnızca olay anında var olan bilgiden
-- üretebilmek için.
--
-- Bugüne kadar elimizde tek şey `recommendation_events` içindeki sunum
-- kaydıydı: hangi gönderinin kaçıncı sırada gösterildiğini biliyorduk ama
-- hangi adayların değerlendirilip elendiğini, puanların ne olduğunu ve
-- kullanıcı profilinin o anda neye benzediğini bilmiyorduk. Bu bilgi olmadan
-- ne model karşılaştırması ne de hata ayıklaması yapılabilir.

CREATE TABLE feed_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,

    -- Sıralamayı üreten kod ve veri sürümleri. Üçü birden olmadan iki koşu
    -- karşılaştırılamaz: aynı model farklı özellik sürümüyle başka sonuç verir.
    model_version VARCHAR(40) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    feature_version VARCHAR(40) NOT NULL,

    -- Deney varyantı; A/B testi Faz 6'da gelecek, kolon şimdiden burada ki
    -- o güne kadar toplanan veri de hangi kolda üretildiğini söyleyebilsin.
    experiment_variant VARCHAR(40),

    local_hour SMALLINT NOT NULL,
    timezone_offset_minutes SMALLINT NOT NULL,
    personalized BOOLEAN NOT NULL,

    -- Havuzdaki aday sayısı ve bunlardan kaçının döndüğü. İkisinin oranı
    -- eleme oranını verir.
    candidate_count INTEGER NOT NULL,
    returned_count INTEGER NOT NULL,

    CONSTRAINT feed_requests_local_hour_check CHECK (local_hour BETWEEN 0 AND 23),
    CONSTRAINT feed_requests_timezone_check CHECK (timezone_offset_minutes BETWEEN -840 AND 840),
    CONSTRAINT feed_requests_counts_check CHECK (candidate_count >= 0 AND returned_count >= 0)
);

CREATE INDEX feed_requests_user_time_idx ON feed_requests(user_id, requested_at DESC);
CREATE INDEX feed_requests_model_idx ON feed_requests(model_version, requested_at DESC);

-- Değerlendirilen her aday, gösterilmeyenler dâhil.
--
-- Etkileşim sayaçları burada satır içinde tutuluyor; sonradan `posts`
-- tablosundan okunsaydı eğitim verisine geleceğin beğenileri sızardı.
-- Sızıntının önlendiğini `FeedLineageIntegrationTest` doğruluyor.
CREATE TABLE feed_candidates (
    feed_request_id UUID NOT NULL REFERENCES feed_requests(id) ON DELETE CASCADE,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    candidate_source VARCHAR(30) NOT NULL,

    -- Ham puan yalnızca kişiselleştirme bileşeni; nihai puan güncellik,
    -- kalite ve keşif eklendikten sonraki değer. İkisini ayrı tutmak
    -- "model mi yoksa tazelik mi sıraladı" sorusunu cevaplıyor.
    raw_score DOUBLE PRECISION NOT NULL,
    final_score DOUBLE PRECISION NOT NULL,

    -- Gösterilmeyen aday için NULL.
    position INTEGER,
    reason VARCHAR(200),

    like_count BIGINT NOT NULL,
    comment_count BIGINT NOT NULL,
    age_hours DOUBLE PRECISION NOT NULL,
    media_type VARCHAR(10) NOT NULL,
    topic_slugs TEXT[] NOT NULL DEFAULT '{}',

    PRIMARY KEY (feed_request_id, post_id),
    CONSTRAINT feed_candidates_position_check CHECK (position IS NULL OR position BETWEEN 0 AND 999)
);

CREATE INDEX feed_candidates_post_idx ON feed_candidates(post_id);
CREATE INDEX feed_candidates_served_idx ON feed_candidates(feed_request_id, position)
    WHERE position IS NOT NULL;

-- Kullanıcı profilinin istek anındaki hâli.
--
-- Profil sürekli değişiyor; eğitim sırasında "bugünkü" profili geçmiş bir
-- isteğe bağlamak doğrudan gelecekten bilgi sızdırmak olurdu.
CREATE TABLE user_feature_snapshots (
    feed_request_id UUID PRIMARY KEY REFERENCES feed_requests(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_version VARCHAR(40) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    -- {"topic:teknoloji": 0.42, "creator:<uuid>": 0.11, ...}
    affinities JSONB NOT NULL,
    signal_count INTEGER NOT NULL
);

CREATE INDEX user_feature_snapshots_user_idx ON user_feature_snapshots(user_id, captured_at DESC);

-- Kişiselleştirme rızası ve kabul edilen sözleşme sürümü.
--
-- Rıza yoksa davranış olayı toplanmaz ve akış kişiselleştirilmez; kayıt
-- olmadan "kullanıcı ne zaman neye onay verdi" sorusunun cevabı yok.
CREATE TABLE recommendation_consents (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    granted BOOLEAN NOT NULL,
    contract_version INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
