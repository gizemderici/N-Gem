"""Karşılaştırılan sıralama politikaları.

Tek bir modeli tek başına ölçmek anlamsız: "HitRate@10 = 0,14" iyi mi kötü mü
belli değil. Taban modeller bu soruyu cevaplıyor, ve `nexi-contextual-v1`
[`nexi_ranker`][nexi_ranker] üzerinden — yani backend ile eşitliği test edilen
uygulamayla — çalışıyor. Değerlendirici kendi basitleştirilmiş kopyasını
kullandığı sürece ölçtüğü şey üretimdeki model değildi.
"""

from __future__ import annotations

import uuid
from typing import Callable

from nexi_ranker import (
    Candidate,
    Signal,
    build_affinities,
    deterministic_exploration,
    rank,
    score_candidate,
)

# Veri setlerindeki kimlikler UUID değil; keşif gürültüsü UUID bitlerine
# dayandığı için kararlı bir eşleme gerekiyor. uuid5 aynı girdi için hep aynı
# değeri verir, yani koşular arası tekrar üretilebilirlik korunur.
NAMESPACE = uuid.UUID("6f2a9d5c-0000-4000-8000-000000000000")


def as_uuid(value: str) -> str:
    return str(uuid.uuid5(NAMESPACE, value))


Policy = Callable[[str, list[Candidate], list[Signal], int, int], list[Candidate]]


def chronological(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Candidate]:
    """En yeni içerik önce. Kişiselleştirmenin aşması gereken alt sınır."""
    return sorted(candidates, key=lambda item: (item.created_at, item.post_id), reverse=True)


def popularity(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Candidate]:
    """En çok etkileşim alan önce.

    Gösterim logu önceki önericinin seçtiklerine bağlı olduğu için bu taban
    isabet metriğinde beklenenden güçlü çıkar; katalogun küçük bir bölümünü
    döndürdüğü kapsama metriğinden görülür.
    """
    return sorted(
        candidates,
        key=lambda item: (item.like_count + item.save_count, item.post_id),
        reverse=True,
    )


def interest_only(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Candidate]:
    """Yalnızca kişiselleştirme bileşeni; güncellik, kalite ve keşif yok.

    Bağlamsal modelin kazandığı farkın gerçekten ilgi eşleşmesinden mi yoksa
    tazelik ve popülerlikten mi geldiğini ayırıyor.
    """
    affinities = build_affinities(signals, local_hour, now)
    scored = [score_candidate(viewer_id, item, affinities, now) for item in candidates]
    scored.sort(key=lambda item: (item.raw_score, item.candidate.post_id), reverse=True)
    return [item.candidate for item in scored]


def contextual(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Candidate]:
    """Üretimdeki `nexi-contextual-v1`."""
    return [item.candidate for item in rank(viewer_id, candidates, signals, local_hour, now)]


def random_order(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Candidate]:
    """Kararlı rastgele sıra; metriklerin taban gürültüsü."""
    return sorted(
        candidates,
        key=lambda item: deterministic_exploration(viewer_id, item.post_id, now),
        reverse=True,
    )


POLICIES: dict[str, Policy] = {
    "chronological": chronological,
    "popularity": popularity,
    "interest_only": interest_only,
    "contextual": contextual,
    "random": random_order,
}
