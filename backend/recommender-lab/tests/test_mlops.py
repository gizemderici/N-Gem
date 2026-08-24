"""Model kayıt defteri, terfi kapıları ve kayma tespiti."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from drift import CRITICAL_THRESHOLD, compare, psi  # noqa: E402
from features import FEATURE_VERSION, TrainingRow  # noqa: E402
from registry import (  # noqa: E402
    PromotionError,
    check_production_gates,
    promote,
    register,
    verify,
)
from train_ranker import TrainingSettings, build_model, train  # noqa: E402


def training_rows(count: int = 20, topic_affinity: float = 0.6, likes: int = 5) -> list[TrainingRow]:
    return [
        TrainingRow(
            feed_request_id="req",
            post_id=f"p{index}",
            candidate_source="DISCOVERY",
            like_count=likes + index % 3,
            comment_count=0,
            age_hours=float(index % 12),
            media_type="text",
            topic_slugs=("teknoloji",),
            creator_id="c1",
            local_hour=21,
            signal_count=4,
            affinities={"topic:teknoloji": topic_affinity},
            label=index % 2,
        )
        for index in range(count)
    ]


def write_model(directory: Path, name: str = "model.json") -> Path:
    settings = TrainingSettings(epochs=20)
    weights, metrics = train(training_rows(), settings)
    model = build_model(weights, metrics, settings, 20, "c" * 64, "training.csv")
    path = directory / name
    path.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def offline_metrics(learned_hit: float, negative: float = 0.01, new_creator: float = 0.3) -> dict:
    return {
        "k": 10,
        "policies": {
            "chronological": {
                "hit_rate@10": 0.10,
                "negative_feedback_rate@10": 0.02,
                "new_creator_share@10": 0.40,
            },
            "learned": {
                "hit_rate@10": learned_hit,
                "negative_feedback_rate@10": negative,
                "new_creator_share@10": new_creator,
            },
        },
    }


class RegistryTest(unittest.TestCase):
    def test_a_registered_model_starts_as_a_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_model(Path(directory))
            registry = {"models": {}}

            entry = register(path, None, registry)

            self.assertEqual("candidate", entry["stage"])
            self.assertEqual(FEATURE_VERSION, entry["feature_version"])
            self.assertEqual(64, len(entry["sha256"]))
            self.assertFalse(entry["shadow_verified"])

    def test_a_model_from_another_feature_version_cannot_be_registered(self):
        # Farklı özellik sürümüyle eğitilmiş model servis tarafında başka bir
        # vektör görür; sessizce kaydetmek onu üretime taşırdı.
        with tempfile.TemporaryDirectory() as directory:
            path = write_model(Path(directory))
            model = json.loads(path.read_text(encoding="utf-8"))
            model["feature_version"] = "nexi-learned-features-v0"
            path.write_text(json.dumps(model), encoding="utf-8")

            with self.assertRaises(PromotionError):
                register(path, None, {"models": {}})

    def test_verify_catches_a_tampered_model_file(self):
        # Elle degistirilmis bir model dosyasi uretimde sessizce baska bir
        # siralama uretir; verify CI'da alarm kaynagi.
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            path = write_model(folder)
            registry = {"models": {}}
            register(path, None, registry)

            self.assertEqual([], verify(registry, folder))

            model = json.loads(path.read_text(encoding="utf-8"))
            model["weights"]["freshness"] = 99.0
            path.write_text(json.dumps(model), encoding="utf-8")

            problems = verify(registry, folder)
            self.assertEqual(1, len(problems))
            self.assertIn("özet tutmuyor", problems[0])

    def test_verify_catches_a_missing_model_file(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            path = write_model(folder)
            registry = {"models": {}}
            register(path, None, registry)
            path.unlink()

            self.assertIn("dosya yok", verify(registry, folder)[0])


class PromotionGateTest(unittest.TestCase):
    def registered(self, metrics: dict | None) -> tuple[dict, str]:
        directory = tempfile.mkdtemp()
        path = write_model(Path(directory))
        registry = {"models": {}}
        metrics_path = None
        if metrics is not None:
            metrics_path = Path(directory) / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
        entry = register(path, metrics_path, registry)
        return registry, entry["version"]

    def test_production_needs_a_shadow_run(self):
        # Kabul kriteri: yeni model gölge testinden geçmeden üretime çıkamaz.
        registry, version = self.registered(offline_metrics(learned_hit=0.20))

        with self.assertRaises(PromotionError) as error:
            promote(version, "production", registry)

        self.assertIn("gölge modda doğrulanmadı", str(error.exception))

    def test_production_needs_offline_metrics(self):
        registry, version = self.registered(None)
        promote(version, "shadow", registry)

        with self.assertRaises(PromotionError) as error:
            promote(version, "production", registry)

        self.assertIn("çevrimdışı değerlendirme sonucu yok", str(error.exception))

    def test_a_model_that_loses_to_chronological_is_refused(self):
        registry, version = self.registered(offline_metrics(learned_hit=0.05))
        promote(version, "shadow", registry)

        with self.assertRaises(PromotionError) as error:
            promote(version, "production", registry)

        self.assertIn("kronolojik tabanı geçmiyor", str(error.exception))

    def test_a_model_that_raises_negative_feedback_is_refused(self):
        # Isabet artarken guvenlik bozuluyorsa kapi kapali.
        registry, version = self.registered(offline_metrics(learned_hit=0.30, negative=0.09))
        promote(version, "shadow", registry)

        with self.assertRaises(PromotionError) as error:
            promote(version, "production", registry)

        self.assertIn("olumsuz geri bildirim", str(error.exception))

    def test_a_model_that_buries_new_creators_is_refused(self):
        registry, version = self.registered(offline_metrics(learned_hit=0.30, new_creator=0.10))
        promote(version, "shadow", registry)

        with self.assertRaises(PromotionError) as error:
            promote(version, "production", registry)

        self.assertIn("yeni üretici payı", str(error.exception))

    def test_a_model_that_passes_every_gate_reaches_production(self):
        registry, version = self.registered(offline_metrics(learned_hit=0.30))
        promote(version, "shadow", registry)

        entry = promote(version, "production", registry)

        self.assertEqual("production", entry["stage"])
        self.assertEqual([], check_production_gates(entry))
        self.assertEqual(["shadow", "production"], [step["stage"] for step in entry["history"]])

    def test_promoting_a_new_model_archives_the_previous_one(self):
        # Geri alma icin eski model dosyasi silinmiyor, arsive aliniyor.
        registry, first = self.registered(offline_metrics(learned_hit=0.30))
        promote(first, "shadow", registry)
        promote(first, "production", registry)

        directory = tempfile.mkdtemp()
        second_path = write_model(Path(directory), "model2.json")
        model = json.loads(second_path.read_text(encoding="utf-8"))
        model["model_version"] = "nexi-lr-v2"
        second_path.write_text(json.dumps(model), encoding="utf-8")
        metrics_path = Path(directory) / "metrics.json"
        metrics_path.write_text(json.dumps(offline_metrics(learned_hit=0.35)), encoding="utf-8")
        register(second_path, metrics_path, registry)
        promote("nexi-lr-v2", "shadow", registry)
        promote("nexi-lr-v2", "production", registry)

        self.assertEqual("archived", registry["models"][first]["stage"])
        self.assertEqual("production", registry["models"]["nexi-lr-v2"]["stage"])

    def test_an_unknown_model_cannot_be_promoted(self):
        with self.assertRaises(PromotionError):
            promote("yok-boyle", "shadow", {"models": {}})


class DriftTest(unittest.TestCase):
    def test_identical_distributions_show_no_drift(self):
        rows = training_rows(count=60)

        report = compare(rows, rows)

        self.assertFalse(report["retrain_recommended"])
        self.assertEqual([], report["critical"])

    def test_a_shifted_feature_is_flagged(self):
        # Kod degismeden veri kayarsa model egitildigi dunyayi degil baska bir
        # dunyayi puanlamaya baslar; hicbir birim testi bunu yakalamaz.
        reference = training_rows(count=60, topic_affinity=0.1, likes=1)
        current = training_rows(count=60, topic_affinity=0.9, likes=200)

        report = compare(reference, current)

        self.assertTrue(report["retrain_recommended"])
        self.assertIn("topic_affinity", report["critical"])
        self.assertGreater(report["psi"]["topic_affinity"], CRITICAL_THRESHOLD)

    def test_psi_is_symmetric_enough_to_be_readable(self):
        left = [float(value) for value in range(100)]
        right = [float(value) + 500 for value in range(100)]

        self.assertGreater(psi(left, right), CRITICAL_THRESHOLD)
        self.assertLess(psi(left, left), 1e-9)

    def test_empty_input_is_refused(self):
        with self.assertRaises(ValueError):
            compare([], training_rows())


if __name__ == "__main__":
    unittest.main()
