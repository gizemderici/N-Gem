"""Türkçe duygu + konu taban modelinin deterministik sözleşme testleri."""

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

LAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAB))

from classify_text import YuklenmisModel, siniflandirma_metrikleri  # noqa: E402
from train_text_classifier import Ornek, Settings, egit  # noqa: E402
from turkish_text import TfidfVectorizer, tokenize, turkish_lower  # noqa: E402


class TurkishTextTest(unittest.TestCase):
    def test_turkish_lower_handles_dotted_and_dotless_i(self):
        self.assertEqual("ı i ığdır izmir", turkish_lower("I İ IĞDIR İZMİR"))

    def test_tokenizer_keeps_negation_and_word_bigrams(self):
        tokens = tokenize("Hiç iyi değil", char_ngrams=None)

        self.assertIn("değil", tokens)
        self.assertIn("iyi_değil", tokens)

    def test_vectorizer_round_trip_preserves_sparse_vector(self):
        vectorizer = TfidfVectorizer(min_df=1).fit(
            ["Yeni telefon çok hızlı", "Telefon bugün satışa çıktı"]
        )
        restored = TfidfVectorizer.from_dict(
            json.loads(json.dumps(vectorizer.to_dict(), ensure_ascii=False))
        )

        self.assertEqual(
            vectorizer.transform("Telefon hızlı"),
            restored.transform("Telefon hızlı"),
        )


class ClassificationMetricsTest(unittest.TestCase):
    def test_cli_reconfigures_cp1252_stdout_for_turkish_text(self):
        script = (
            "from classify_text import stdout_utf8; "
            "stdout_utf8(); print('başarılı, yazılım')"
        )
        environment = dict(os.environ)
        environment["PYTHONIOENCODING"] = "cp1252"

        result = subprocess.run(
            [sys.executable, "-c", script],
            cwd=LAB,
            env=environment,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual("başarılı, yazılım", result.stdout.decode("utf-8").strip())

    def test_metrics_report_accuracy_and_macro_f1(self):
        report = siniflandirma_metrikleri(
            ["a", "a", "b", "b"],
            ["a", "b", "b", "b"],
            ["a", "b"],
        )

        self.assertEqual(3, report["dogru"])
        self.assertEqual(4, report["toplam"])
        self.assertAlmostEqual(0.75, report["accuracy"])
        self.assertAlmostEqual((2 / 3 + 0.8) / 2, report["macro_f1"])
        self.assertEqual(2, report["siniflar"]["a"]["support"])

    def test_metrics_reject_empty_input(self):
        with self.assertRaisesRegex(ValueError, "sıfırdan büyük"):
            siniflandirma_metrikleri([], [], ["a"])

    def test_serialized_model_matches_in_memory_models(self):
        examples = [
            Ornek("telefon çok hızlı", "olumlu", "teknoloji"),
            Ornek("telefon sürekli donuyor", "olumsuz", "teknoloji"),
            Ornek("telefon yarın çıkacak", "notr", "teknoloji"),
            Ornek("şarkı çok güzel", "olumlu", "muzik"),
            Ornek("şarkının sesi bozuk", "olumsuz", "muzik"),
            Ornek("albüm yarın çıkacak", "notr", "muzik"),
        ]
        vectorizer = TfidfVectorizer(min_df=1).fit([o.metin for o in examples])
        settings = Settings(epochs=20)
        sentiment = egit(examples, vectorizer, "duygu", settings)
        topic = egit(examples, vectorizer, "konu", settings)
        payload = json.loads(
            json.dumps(
                {
                    "model_version": "test-v1",
                    "vectorizer": vectorizer.to_dict(),
                    "duygu": sentiment.to_dict(),
                    "konu": topic.to_dict(),
                },
                ensure_ascii=False,
            )
        )
        loaded = YuklenmisModel(payload)
        text = "telefon çok hızlı"

        (loaded_sentiment, _), (loaded_topic, _) = loaded.siniflandir(text)

        self.assertEqual(sentiment.predict(vectorizer.transform(text))[0], loaded_sentiment)
        self.assertEqual(topic.predict(vectorizer.transform(text))[0], loaded_topic)


if __name__ == "__main__":
    unittest.main()
