-- Kronolojik kontrol kolu da soy kütüğüne yazılıyor.
--
-- Kontrol kolu önceden yalnızca `feed_fallbacks`'e düşüyordu, yani kolları
-- karşılaştıran her rapor temel çizgiden yoksundu. Kronolojik akışta puan
-- diye bir şey yok; 0.0 yazmak puan dağılımına bakan her sorguyu yanıltırdı,
-- bu yüzden sütunlar NULL kabul ediyor. Sıra bilgisi `rank_position` ve
-- `position` alanlarında duruyor, raporlar zaten onları kullanıyor.
ALTER TABLE feed_candidates
    ALTER COLUMN raw_score DROP NOT NULL,
    ALTER COLUMN final_score DROP NOT NULL;
