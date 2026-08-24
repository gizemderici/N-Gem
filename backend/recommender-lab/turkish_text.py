#!/usr/bin/env python3
"""Türkçe metin için belirteçleme ve TF-IDF. Yalnızca standart kütüphane.

Türkçeye özgü iki tuzak var ve ikisi de sessizce yanlış sonuç üretiyor:

1. `"I".lower()` Python'da `i` verir; Türkçede `ı` olmalı. `"İ".lower()` ise
   `i` + birleşik nokta (U+0307) üretir, yani görünüşte `i` ama eşleşmeyen
   iki karakterlik bir dizi. Büyük harfle yazılmış bir gönderi bu yüzden
   küçük harfli örneklerle hiç aynı belirtece düşmezdi.
2. Türkçe eklemeli: "başarılı" ile "başarısız" ilk altı harfi paylaşır.
   Kök bulmaya benzeyen bir önek kısaltması bu ikisini aynı belirtece
   indirger ve duygu sınıflandırmasında tam ters iki kelimeyi birleştirirdi.
   Bu yüzden kök bulma yok; kelime ikilileri ekle olumsuzlamayı yakalıyor.
"""

from __future__ import annotations

import math
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field

# Büyük harften küçüğe Türkçe eşlemesi. `str.lower()` çağrılmadan önce
# uygulanır; sonrasında uygulamak `İ`nin ürettiği birleşik noktayı temizlemez.
_UPPER_MAP = str.maketrans({"I": "ı", "İ": "i"})

_TOKEN = re.compile(r"[a-zçğıöşü][a-zçğıöşü0-9]*")

# Sınıfa dair bilgi taşımayan, her sınıfta eşit sıklıkta geçen kelimeler.
# Liste bilerek kısa: agresif bir durak kelime listesi Türkçede olumsuzlama
# taşıyan "değil", "yok" gibi kelimeleri de atardı.
STOPWORDS = frozenset(
    """
    bir bu şu o ve ile de da için gibi çok daha en ki mi mı mu mü
    ben sen biz siz onlar bunu şunu ama fakat ancak ise ya veya
    """.split()
)


def turkish_lower(text: str) -> str:
    """Türkçe kurallarına göre küçük harfe indirger."""
    lowered = text.translate(_UPPER_MAP).lower()
    # `İ` dışında bir kaynaktan gelmiş olabilecek birleşik noktaları da at.
    return unicodedata.normalize("NFC", lowered.replace("̇", ""))


def _char_ngrams(word: str, low: int, high: int) -> list[str]:
    """Kelime içi karakter n-gramları, sınır işaretiyle.

    Türkçe eklemeli olduğu için "kamera" ile "kamerası" kelime düzeyinde iki
    ayrı belirteç. Kök bulma bu ikisini birleştirirdi ama aynı işlem
    "başarılı" ile "başarısız"ı da birleştirir; karakter n-gramları paylaşılan
    gövdeyi yakalarken ayrışan sonu ayrı tutuyor.
    """
    padded = f"<{word}>"
    grams: list[str] = []
    for n in range(low, high + 1):
        if len(padded) < n:
            continue
        grams.extend(f"c:{padded[i:i + n]}" for i in range(len(padded) - n + 1))
    return grams


def tokenize(text: str, char_ngrams: tuple[int, int] | None = (4, 5)) -> list[str]:
    """Kelime birlileri, ikilileri ve isteğe bağlı karakter n-gramları.

    İkililer olumsuzlama için: "iyi değil" tek tek bakıldığında olumlu bir
    kelime ile nötr bir kelimedir, birlikte olumsuzdur.
    """
    words = [w for w in _TOKEN.findall(turkish_lower(text)) if len(w) > 1]
    tokens = [w for w in words if w not in STOPWORDS]
    tokens += [f"{a}_{b}" for a, b in zip(words, words[1:])]
    if char_ngrams is not None:
        low, high = char_ngrams
        for word in words:
            tokens.extend(_char_ngrams(word, low, high))
    return tokens


@dataclass
class TfidfVectorizer:
    """Belge sıklığı eşiğiyle sınırlanmış TF-IDF.

    `min_df` yalnızca hız için değil: tek bir belgede geçen bir kelime o
    belgenin etiketini ezberlemekten başka bir şey yapmaz ve eğitim
    doğruluğunu gerçek başarıymış gibi şişirir.
    """

    min_df: int = 2
    char_ngrams: tuple[int, int] | None = (4, 5)
    vocabulary: dict[str, int] = field(default_factory=dict)
    idf: list[float] = field(default_factory=list)

    def _analyze(self, document: str) -> list[str]:
        return tokenize(document, self.char_ngrams)

    def fit(self, documents: list[str]) -> "TfidfVectorizer":
        document_frequency: Counter[str] = Counter()
        for document in documents:
            document_frequency.update(set(self._analyze(document)))

        terms = sorted(t for t, df in document_frequency.items() if df >= self.min_df)
        self.vocabulary = {term: index for index, term in enumerate(terms)}
        total = len(documents)
        self.idf = [
            math.log((1.0 + total) / (1.0 + document_frequency[term])) + 1.0
            for term in terms
        ]
        return self

    def transform(self, document: str) -> dict[int, float]:
        """Seyrek vektör: indeks -> ağırlık, L2 normalli.

        Normalleştirme uzunluk etkisini siliyor; olmasa uzun gönderiler
        yalnızca daha çok kelime içerdikleri için daha büyük puan alırdı.
        """
        counts = Counter(self._analyze(document))
        raw: dict[int, float] = {}
        for term, count in counts.items():
            index = self.vocabulary.get(term)
            if index is None:
                continue
            raw[index] = (1.0 + math.log(count)) * self.idf[index]

        norm = math.sqrt(sum(value * value for value in raw.values()))
        if norm == 0.0:
            return {}
        return {index: value / norm for index, value in raw.items()}

    def to_dict(self) -> dict:
        return {
            "min_df": self.min_df,
            # JSON'da demet yok; listeye dönüşen değeri geri yüklerken demete
            # çevirmezsek `tokenize` imzası sessizce farklı davranırdı.
            "char_ngrams": list(self.char_ngrams) if self.char_ngrams else None,
            "vocabulary": self.vocabulary,
            "idf": self.idf,
        }

    @classmethod
    def from_dict(cls, payload: dict) -> "TfidfVectorizer":
        grams = payload.get("char_ngrams")
        return cls(
            min_df=payload["min_df"],
            char_ngrams=tuple(grams) if grams else None,
            vocabulary=payload["vocabulary"],
            idf=payload["idf"],
        )
