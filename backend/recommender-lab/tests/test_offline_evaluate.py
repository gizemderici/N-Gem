import sys
import unittest
from pathlib import Path


LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from offline_evaluate import evaluate, load_events  # noqa: E402


class OfflineEvaluationTest(unittest.TestCase):
    def test_fixture_produces_bounded_metrics(self):
        events = load_events(LAB / "tests" / "fixtures" / "events.csv")
        metrics = evaluate(events, k=3, max_candidates=10)
        self.assertEqual(metrics["evaluated_users"], 3)
        self.assertEqual(metrics["catalog_size"], 6)
        for name in ("hit_rate@3", "mrr@3", "ndcg@3", "coverage@3"):
            self.assertGreaterEqual(metrics[name], 0.0)
            self.assertLessEqual(metrics[name], 1.0)


if __name__ == "__main__":
    unittest.main()
