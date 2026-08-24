"""Eğitilmiş sıralayıcının eğitim hattı.

Burada model kalitesi ölçülmüyor — o, birinci taraf veri geldiğinde
`offline_evaluate.py` ile yapılacak. Ölçülen şey hattın kendisi: öğreniyor mu,
tekrar üretilebilir mi, model dosyası bir tahmini kaynağına bağlamaya yetiyor
mu, ve özellik vektörü geleceğe bakıyor mu.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from features import FEATURE_NAMES, TrainingRow, feature_vector, period_of  # noqa: E402
from train_ranker import (  # noqa: E402
    MODEL_VERSION,
    TrainingSettings,
    build_model,
    load_rows,
    predict,
    train,
)


def row(
    *,
    label: int,
    topic: str = "teknoloji",
    topic_affinity: float = 0.0,
    likes: int = 0,
    age_hours: float = 1.0,
    source: str = "DISCOVERY",
    media: str = "text",
    signal_count: int = 5,
    hour: int = 21,
) -> TrainingRow:
    return TrainingRow(
        feed_request_id="00000000-0000-4000-8000-000000000001",
        post_id=f"post-{label}-{topic}-{likes}-{age_hours}-{source}",
        candidate_source=source,
        like_count=likes,
        comment_count=0,
        age_hours=age_hours,
        media_type=media,
        topic_slugs=(topic,),
        creator_id="creator-1",
        local_hour=hour,
        signal_count=signal_count,
        affinities={f"topic:{topic}": topic_affinity},
        label=label,
    )


class FeatureVectorTest(unittest.TestCase):
    def test_the_vector_matches_the_declared_names(self):
        self.assertEqual(len(FEATURE_NAMES), len(feature_vector(row(label=1))))

    def test_topic_affinity_is_read_from_the_snapshot(self):
        # Güncel profilden okunsaydı geçmiş bir isteğe geleceğin davranışı
        # sızardı; vektör yalnızca satırdaki anlık görüntüyü görmeli.
        vector = feature_vector(row(label=1, topic_affinity=0.7))
        self.assertEqual(0.7, vector[FEATURE_NAMES.index("topic_affinity")])

        unrelated = row(label=1, topic_affinity=0.7)
        unrelated.affinities["topic:baska"] = 0.9
        self.assertEqual(0.7, feature_vector(unrelated)[FEATURE_NAMES.index("topic_affinity")])

    def test_cold_start_is_marked_separately_from_indifference(self):
        cold = feature_vector(row(label=1, signal_count=0))
        warm = feature_vector(row(label=1, signal_count=12))
        index = FEATURE_NAMES.index("cold_start")

        self.assertEqual(1.0, cold[index])
        self.assertEqual(0.0, warm[index])

    def test_freshness_decays_with_age(self):
        index = FEATURE_NAMES.index("freshness")
        fresh = feature_vector(row(label=1, age_hours=1))[index]
        stale = feature_vector(row(label=1, age_hours=500))[index]

        self.assertGreater(fresh, stale)
        self.assertLessEqual(fresh, 1.0)
        self.assertGreater(stale, 0.0)

    def test_periods_cover_the_whole_day(self):
        self.assertEqual({"MORNING", "WORK", "EVENING", "NIGHT"}, {period_of(h) for h in range(24)})


class TrainingTest(unittest.TestCase):
    def separable_rows(self) -> list[TrainingRow]:
        # İlgi yakınlığı yüksek olanlar olumlu, düşük olanlar olumsuz.
        positives = [row(label=1, topic_affinity=0.8, likes=10) for _ in range(20)]
        negatives = [row(label=0, topic_affinity=-0.6, likes=0) for _ in range(20)]
        return positives + negatives

    def test_training_reduces_loss_and_separates_the_classes(self):
        rows = self.separable_rows()

        weights, metrics = train(rows)

        self.assertLess(metrics["final_loss"], metrics["initial_loss"])
        positive = predict(weights, row(label=1, topic_affinity=0.8, likes=10))
        negative = predict(weights, row(label=0, topic_affinity=-0.6, likes=0))
        self.assertGreater(positive, negative)
        self.assertGreater(positive, 0.5)
        self.assertLess(negative, 0.5)

    def test_the_same_data_and_settings_produce_the_same_weights(self):
        # Tekrar üretilebilirlik bu asamada hizdan onemli: bir tahminin hangi
        # modelden geldigi ancak boyle soylenebilir.
        rows = self.separable_rows()

        first, _ = train(rows, TrainingSettings(epochs=50))
        second, _ = train(rows, TrainingSettings(epochs=50))

        self.assertEqual(first, second)

    def test_different_settings_produce_different_weights(self):
        rows = self.separable_rows()

        few, _ = train(rows, TrainingSettings(epochs=5))
        many, _ = train(rows, TrainingSettings(epochs=200))

        self.assertNotEqual(few, many)

    def test_empty_training_data_is_refused(self):
        with self.assertRaises(ValueError):
            train([])

    def test_regularisation_leaves_the_bias_alone(self):
        # Taban orani cezalandirmak modeli sistematik olarak kaydirirdi.
        rows = [row(label=1) for _ in range(30)]

        weights, _ = train(rows, TrainingSettings(epochs=200, l2=1.0))

        self.assertGreater(weights[FEATURE_NAMES.index("bias")], 0.0)


class ModelFileTest(unittest.TestCase):
    def test_the_model_file_ties_a_prediction_to_its_source(self):
        rows = [row(label=1, topic_affinity=0.8), row(label=0, topic_affinity=-0.5)]
        weights, metrics = train(rows, TrainingSettings(epochs=20))

        model = build_model(
            weights, metrics, TrainingSettings(epochs=20), len(rows), "a" * 64, "training.csv"
        )

        self.assertEqual(MODEL_VERSION, model["model_version"])
        self.assertEqual("a" * 64, model["data"]["sha256"])
        self.assertEqual(2, model["data"]["rows"])
        self.assertEqual(20, model["settings"]["epochs"])
        self.assertEqual(set(FEATURE_NAMES), set(model["weights"]))
        # JSON'a yazılıp geri okunabilmeli.
        self.assertEqual(model, json.loads(json.dumps(model)))

    def test_rows_round_trip_through_the_export_format(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "training.csv"
            path.write_text(
                "feed_request_id,post_id,candidate_source,like_count,comment_count,age_hours,"
                "media_type,topic_slugs,creator_id,local_hour,signal_count,affinities,label,negative\n"
                "req-1,post-1,FOLLOWING,3,1,2.5,video,teknoloji;oyun,creator-1,21,7,"
                '"{""topic:teknoloji"": 0.4}",1,0\n',
                encoding="utf-8",
            )

            loaded = load_rows(path)

            self.assertEqual(1, len(loaded))
            self.assertEqual(("teknoloji", "oyun"), loaded[0].topic_slugs)
            self.assertEqual(0.4, loaded[0].affinities["topic:teknoloji"])
            self.assertEqual(1, loaded[0].label)
            self.assertEqual("FOLLOWING", loaded[0].candidate_source)


if __name__ == "__main__":
    unittest.main()
