package com.nexi.recommendations

import java.util.UUID

/**
 * "Bu kullanıcı kişiselleştirmeye rıza verdi mi" sorusunun dar hâli.
 *
 * Rıza kontrolü `RecommendationService.append` içinde vardı ve istemciden
 * gelen olayları doğru şekilde durduruyordu. Ama beğeni, kaydetme ve şikâyet
 * sinyalleri AI Faz 1'de sunucu tarafına taşınınca o kapıyı atlar oldular:
 * `PostService` ve `ModerationService` olayı doğrudan depoya yazıyor, rızayı
 * hiç sormuyordu. Sonuç, rıza vermemiş bir kullanıcının beğenisinin yine de
 * AI verisi üretmesiydi.
 *
 * Arayüz `ConsentRepository`'nin tamamını taşımıyor: gönderi ve şikâyet
 * servislerinin rıza *yazma* yetkisine ihtiyacı yok, yalnızca okumaya var.
 */
fun interface PersonalizationConsent {
    fun isGranted(userId: UUID): Boolean

    companion object {
        /**
         * Rıza deposu bağlanmamış bağlamlar için.
         *
         * Yalnızca testler ve öneri deposu olmayan yerel kurulum içindir;
         * orada zaten yazılan bir yere kaydedilmiyor. Üretimde her zaman
         * [JdbcConsentRepository] bağlı.
         */
        val ALWAYS_GRANTED = PersonalizationConsent { true }
    }
}

/** Rıza deposunu dar arayüze bağlar. */
fun ConsentRepository.asPersonalizationConsent() = PersonalizationConsent { userId -> find(userId).granted }
