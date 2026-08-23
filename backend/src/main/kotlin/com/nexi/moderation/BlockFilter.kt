package com.nexi.moderation

/**
 * Engellenen kullanıcıları sorgulardan çıkaran ortak koşul.
 *
 * Engel kayıtta tek yönlü (kim kimi engelledi) ama etkisi çift yönlü: A, B'yi
 * engellediyse B de A'nın içeriğini görmemeli. Bu yüzden koşul iki yönü de
 * kontrol eder ve **iki kez** `viewerId` bağlaması ister.
 *
 * Akış, gönderi, yorum, profil ve takip listesi sorgularının hepsi buradan
 * geçiyor; koşul tek yerde durduğu için yeni bir sorgu eklendiğinde aynı
 * davranış kopyalanmak yerine yeniden kullanılıyor.
 */
internal object BlockFilter {

    /** `detailsSelect` gibi yerlerde kaç kez `viewerId` bağlanacağı. */
    const val BINDINGS = 2

    /**
     * @param otherColumn Karşı tarafın kimlik sütunu, örneğin `p.owner_id`.
     *   Sütun adı kod içinde sabittir, dışarıdan gelmez.
     */
    fun notBlocked(otherColumn: String): String =
        """NOT EXISTS (SELECT 1 FROM user_blocks ub
                       WHERE (ub.blocker_id = ? AND ub.blocked_id = $otherColumn)
                          OR (ub.blocked_id = ? AND ub.blocker_id = $otherColumn))"""
}
