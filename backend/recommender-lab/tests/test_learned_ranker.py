"""Eğitilmiş modelin karşılaştırma hattına bağlanması."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from features import FEATURE_NAMES, FEATURE_VERSION, TrainingRow  # noqa: E402
from learned_ranker import LearnedRanker, policy_from  # noqa: E402
from nexi_ranker import Candidate, Signal  # noqa: E402
from train_ranker import MODEL_VERSION, TrainingSettings, build_model, train  # noqa: E402


def trained_model() -> dict:
    rows = [
        TrainingRow(
            feed_request_id="req",
            post_id=f"p{index}",
            candidate_source="DISCOVERY",
            like_count=10 if index % 2 == 0 else 0,
            comment_count=0,
            age_hours=1.0,
            media_type="text",
            topic_slugs=("teknoloji",),
            creator_id="c1",
            local_hour=21,
            signal_count=4,
            affinities={"topic:teknoloji": 0.8 if index % 2 == 0 else -0.6},
            label=1 if index % 2 == 0 else 0,
        )
        for index in range(40)
    ]
    settings = TrainingSettings(epochs=200)
    weights, metrics = train(rows, settings)
    return build_model(weights, metrics, settings, len(rows), "b" * 64, "t.csv")


class LearnedRankerTest(unittest.TestCase):
    def setUp(self):
        self.model = trained_model()
        self.ranker = LearnedRanker(self.model)

    def test_a_model_with_the_wrong_feature_set_is_refused(self):
        # Eksik agirliga sessizce sifir atamak, ozellik surumu degistiginde
        # modeli gizlice bozar ve fark etmenin yolu kalmaz.
        broken = json.loads(json.dumps(self.model))
        broken["weights"].pop("freshness")

        with self.assertRaises(ValueError):
            LearnedRanker(broken)

    def test_a_model_from_another_feature_version_is_refused(self):
        stale = json.loads(json.dumps(self.model))
        stale["feature_version"] = "nexi-learned-features-v0"

        with self.assertRaises(ValueError):
            LearnedRanker(stale)

    def test_the_model_declares_its_versions(self):
        self.assertEqual(MODEL_VERSION, self.ranker.model_version)
        self.assertEqual(FEATURE_VERSION, self.model["feature_version"])
        self.assertEqual(len(FEATURE_NAMES), len(self.ranker.weights))

    def test_ranking_prefers_the_candidate_the_profile_matches(self):
        signals = [
            Signal(
                event_type="CONTENT_LIKED",
                features=("topic:teknoloji",),
                local_hour=21,
                occurred_at=1_787_000_000,
                dwell_millis=None,
                completion_ratio=None,
            )
        ]
        matching = Candidate(
            post_id="00000000-0000-4000-8000-000000000001",
            owner_id="00000000-0000-4000-8000-0000000000aa",
            created_at=1_787_500_000,
            like_count=9,
            save_count=0,
            features=("topic:teknoloji", "media:text"),
        )
        unrelated = Candidate(
            post_id="00000000-0000-4000-8000-000000000002",
            owner_id="00000000-0000-4000-8000-0000000000bb",
            created_at=1_787_500_000,
            like_count=0,
            save_count=0,
            features=("topic:seyahat", "media:text"),
        )

        ranking = self.ranker.rank(
            "00000000-0000-4000-8000-0000000000cc",
            [unrelated, matching],
            signals,
            21,
            1_787_518_800,
        )

        self.assertEqual(matching.post_id, ranking[0].post_id)

    def test_the_policy_loads_from_a_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.json"
            path.write_text(json.dumps(self.model), encoding="utf-8")

            policy = policy_from(path)

            self.assertEqual([], policy("00000000-0000-4000-8000-0000000000cc", [], [], 21, 1_787_518_800))


if __name__ == "__main__":
    unittest.main()
