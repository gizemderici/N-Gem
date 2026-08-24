"""Kotlin sıralayıcısı ile Python karşılığının aynı sonucu ürettiğini doğrular.

Referans dosyası backend tarafında üretiliyor:

    gradle test --tests '*RankingParityTest*' -Dnexi.parity.write=true

Bu test kırılıyorsa iki uygulama ayrışmış demektir; çevrimdışı değerlendirme
o andan itibaren üretimdeki modeli ölçmüyor.
"""

import json
import sys
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from nexi_ranker import Candidate, Signal, build_affinities, rank  # noqa: E402

FIXTURE = LAB / "fixtures" / "ranking_parity.json"

# Kayan nokta toplama sırası iki dilde birebir aynı olduğu için fark yalnızca
# son basamaklarda olabilir; buna bile yer bırakmak istemiyoruz.
TOLERANCE = 1e-12


class RankingParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        cls.signals = [
            Signal(
                event_type=item["eventType"],
                features=tuple(item["features"]),
                local_hour=item["localHour"],
                occurred_at=item["occurredAtEpochSecond"],
                dwell_millis=item["dwellMillis"],
                completion_ratio=item["completionRatio"],
            )
            for item in cls.fixture["signals"]
        ]
        cls.candidates = [
            Candidate(
                post_id=item["postId"],
                owner_id=item["ownerId"],
                created_at=item["createdAtEpochSecond"],
                like_count=item["likeCount"],
                save_count=item["saveCount"],
                features=tuple(item["features"]),
            )
            for item in cls.fixture["candidates"]
        ]

    def test_model_version_matches(self):
        self.assertEqual("nexi-contextual-v1", self.fixture["modelVersion"])
        self.assertEqual("nexi-features-v2", self.fixture["featureVersion"])

    def test_affinities_match(self):
        affinities = build_affinities(
            self.signals,
            self.fixture["localHour"],
            self.fixture["rankedAtEpochSecond"],
        )
        rounded = {key: round(value, 3) for key, value in affinities.items()}
        self.assertEqual(self.fixture["affinities"], rounded)

    def test_ordering_matches_exactly(self):
        ranked = rank(
            self.fixture["viewerId"],
            self.candidates,
            self.signals,
            self.fixture["localHour"],
            self.fixture["rankedAtEpochSecond"],
        )

        self.assertEqual(
            [item["postId"] for item in self.fixture["expected"]],
            [item.candidate.post_id for item in ranked],
            "Python ve Kotlin ayni aday listesinde ayni sirayi uretmeli",
        )

    def test_scores_match(self):
        ranked = rank(
            self.fixture["viewerId"],
            self.candidates,
            self.signals,
            self.fixture["localHour"],
            self.fixture["rankedAtEpochSecond"],
        )
        by_post = {item.candidate.post_id: item for item in ranked}

        for expected in self.fixture["expected"]:
            actual = by_post[expected["postId"]]
            self.assertAlmostEqual(
                expected["rawScore"], actual.raw_score, delta=TOLERANCE,
                msg=f"ham puan ayristi: {expected['postId']}",
            )
            self.assertAlmostEqual(
                expected["finalScore"], actual.final_score, delta=TOLERANCE,
                msg=f"nihai puan ayristi: {expected['postId']}",
            )

    def test_the_fixture_is_not_degenerate(self):
        # Tek adayli ya da hepsi ayni puanli bir dosya, ayrismayi gizlerdi.
        self.assertGreaterEqual(len(self.candidates), 4)
        scores = {item["finalScore"] for item in self.fixture["expected"]}
        self.assertEqual(len(scores), len(self.fixture["expected"]))
        self.assertGreaterEqual(len({c.owner_id for c in self.candidates}), 2)


if __name__ == "__main__":
    unittest.main()
