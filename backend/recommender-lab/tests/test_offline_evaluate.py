import sys
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from offline_evaluate import dataset_checksum, evaluate, load_events  # noqa: E402
from policies import POLICIES  # noqa: E402

EVENTS = LAB / "tests" / "fixtures" / "events.csv"


class OfflineEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.events = load_events(EVENTS)

    def test_every_policy_is_reported(self):
        # Tek bir modeli tek başına ölçmek anlamsız; taban modeller olmadan
        # "HitRate 0,14" iyi mi kötü mü belli değil.
        metrics = evaluate(self.events, k=3, max_candidates=10)

        self.assertEqual(
            {"chronological", "popularity", "interest_only", "contextual", "random"},
            set(metrics["policies"]),
        )
        self.assertEqual(set(POLICIES), set(metrics["policies"]))
        self.assertEqual(3, metrics["evaluated_examples"])
        self.assertEqual(6, metrics["catalog_size"])

    def test_metrics_stay_within_bounds(self):
        metrics = evaluate(self.events, k=3, max_candidates=10)

        for name, summary in metrics["policies"].items():
            for metric, value in summary.items():
                self.assertGreaterEqual(value, 0.0, f"{name}.{metric}")
                self.assertLessEqual(value, 1.0, f"{name}.{metric}")

    def test_safety_and_diversity_are_reported_next_to_accuracy(self):
        # İsabet metrikleri tek başına yanıltıcı: yalnızca popüleri döndüren
        # bir politika iyi görünüp katalogun küçük bir bölümünü gösterir.
        summary = evaluate(self.events, k=3, max_candidates=10)["policies"]["contextual"]

        for metric in (
            "hit_rate@3",
            "mrr@3",
            "ndcg@3",
            "coverage@3",
            "creator_diversity@3",
            "topic_diversity@3",
            "new_creator_share@3",
            "negative_feedback_rate@3",
        ):
            self.assertIn(metric, summary)

    def test_the_same_data_and_version_produce_the_same_result(self):
        # Kabul kriteri: aynı veri ve aynı model sürümü aynı sonucu üretmeli.
        first = evaluate(self.events, k=3, max_candidates=10)
        second = evaluate(load_events(EVENTS), k=3, max_candidates=10)

        self.assertEqual(first, second)
        self.assertEqual("nexi-contextual-v1", first["model_version"])
        self.assertEqual("nexi-features-v2", first["feature_version"])

    def test_the_dataset_checksum_is_stable(self):
        self.assertEqual(dataset_checksum(EVENTS), dataset_checksum(EVENTS))
        self.assertEqual(64, len(dataset_checksum(EVENTS)))


if __name__ == "__main__":
    unittest.main()
