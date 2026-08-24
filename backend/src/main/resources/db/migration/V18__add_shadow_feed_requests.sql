-- Gölge sıralama kaydı.
--
-- Yeni bir model kullanıcıya gösterilmeden önce aynı istek için ayrıca
-- hesaplanır ve buraya yazılır. Karşılaştırma ancak iki sıralama **aynı aday
-- kümesinden** üretildiğinde anlamlı; ayrı bir istek olarak koşsalardı havuz
-- da, profil de farklı olur ve fark modelden mi girdiden mi geldiği
-- söylenemezdi.
--
-- Ayrı tablo açmak yerine `feed_requests` yeniden kullanılıyor: gölge koşusu
-- da bir sıralama isteği, tek farkı gösterilmemiş olması. Böylece aday
-- satırları, puanlar ve profil anlık görüntüsü aynı şemadan okunuyor.
ALTER TABLE feed_requests
    ADD COLUMN shadow_of UUID REFERENCES feed_requests(id) ON DELETE CASCADE;

CREATE INDEX feed_requests_shadow_idx ON feed_requests(shadow_of)
    WHERE shadow_of IS NOT NULL;

-- Gölge kaydın kendisi gösterilmediği için dönen sayısı sıfır olmalı.
ALTER TABLE feed_requests
    ADD CONSTRAINT feed_requests_shadow_not_served_check
        CHECK (shadow_of IS NULL OR returned_count = 0);
