-- Sıralamanın kendisi.
--
-- `feed_candidates` iki sayı tutuyordu ve ikisi de sıralamayı vermiyordu:
-- `final_score` çeşitlendirme **öncesi** puan, `position` ise yalnızca
-- kullanıcıya gösterilen adaylarda dolu. Çok hedefli yeniden sıralama
-- (çeşitlilik, güvenlik, üretici sınırı) puanı değiştirmeden sırayı
-- değiştirdiği için, iki koşunun sırasını karşılaştırmanın yolu yoktu.
--
-- Gölge koşusunda hiçbir adayın `position`'ı olmadığından bu özellikle
-- kırılıyordu: gölge sıralaması hesaplanıyor, kaydediliyor ama sırası
-- kayboluyordu. `experiment_report.sql` içindeki örtüşme sorgusu da bu
-- yüzden `final_score` sırasına bakıyor, yani yanlış şeyi ölçüyordu.
ALTER TABLE feed_candidates
    ADD COLUMN rank_position INTEGER;

ALTER TABLE feed_candidates
    ADD CONSTRAINT feed_candidates_rank_check
        CHECK (rank_position IS NULL OR rank_position >= 0);

-- Gösterilen adaylar için `position` ile `rank_position` aynı olmalı;
-- gösterilmeyenlerde yalnızca ikincisi dolu.
CREATE INDEX feed_candidates_rank_idx ON feed_candidates(feed_request_id, rank_position);
