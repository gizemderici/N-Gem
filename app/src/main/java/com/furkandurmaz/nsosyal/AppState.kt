package com.furkandurmaz.nsosyal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.furkandurmaz.nsosyal.data.MockSocialData
import com.furkandurmaz.nsosyal.model.MainTab
import com.furkandurmaz.nsosyal.model.SocialPost
import java.time.LocalTime

class AppState {
    var selectedTab by mutableStateOf(MainTab.HOME)
    val selectedInterestIds = mutableStateListOf("technology", "design", "education")
    val likedPostIds = mutableStateListOf<String>()
    val savedPostIds = mutableStateListOf("long-learning")
    val followedCreatorIds = mutableStateListOf("mert", "teknoloji")
    val hiddenPostIds = mutableStateListOf<String>()
    var toastMessage by mutableStateOf<String?>(null)
    var selectedReasonPost by mutableStateOf<SocialPost?>(null)

    val visiblePosts: List<SocialPost>
        get() = MockSocialData.posts
            .filterNot { it.id in hiddenPostIds }
            .sortedByDescending(::score)

    fun completeOnboarding(interests: List<String>) {
        selectedInterestIds.clear()
        selectedInterestIds.addAll(interests)
    }

    fun toggleLike(post: SocialPost) {
        if (post.id in likedPostIds) likedPostIds.remove(post.id) else likedPostIds.add(post.id)
    }

    fun toggleSave(post: SocialPost) {
        if (post.id in savedPostIds) {
            savedPostIds.remove(post.id)
        } else {
            savedPostIds.add(post.id)
            showToast("Gönderi kaydedildi")
        }
    }

    fun toggleFollow(creatorId: String, creatorName: String) {
        if (creatorId in followedCreatorIds) {
            followedCreatorIds.remove(creatorId)
        } else {
            followedCreatorIds.add(creatorId)
            showToast("$creatorName takip edildi")
        }
    }

    fun hide(post: SocialPost) {
        if (post.id !in hiddenPostIds) hiddenPostIds.add(post.id)
        showToast("Bu gönderiyi daha az göstereceğiz")
    }

    fun showToast(message: String) {
        toastMessage = message
    }

    fun publish(text: String) {
        if (text.isBlank()) return
        showToast("Gönderin yayınlandı")
        selectedTab = MainTab.HOME
    }

    fun resetLearnedProfile() {
        showToast("Öğrenilmiş profil sıfırlandı")
    }

    private fun score(post: SocialPost): Int {
        var score = 0
        val selectedTitles = MockSocialData.interests
            .filter { it.id in selectedInterestIds }
            .map { it.title }

        if (post.topic in selectedTitles) score += 30
        if (post.creator.id in followedCreatorIds) score += 25
        if (post.id in savedPostIds) score += 12

        when (LocalTime.now().hour) {
            in 6..8 -> if (post.topic == "Gündem") score += 45
            in 9..17 -> if (post.topic in listOf("Teknoloji", "Eğitim", "Tasarım")) score += 35
            in 18..21 -> if (post.topic == "Yerel") score += 45
            else -> if (post.topic == "Mizah") score += 45
        }
        return score
    }
}
