package com.vexa.ai

import android.content.Context

/**
 * مدیریت اشتراک Vexa AI:
 * در حال حاضر تمام امکانات برنامه (چت، عکس، ویدیو MP4، فایل و آموزش) به‌صورت ۱۰۰٪ رایگان و نامحدود باز است!
 */
class SubscriptionManager(@Suppress("UNUSED_PARAMETER") ctx: Context) {

    enum class Tier(val titleFa: String, val maxTrainingRules: Int) {
        FREE_UNLIMITED("نسخه رایگان نامحدود (VIP Launch)", Int.MAX_VALUE)
    }

    enum class Feature { CHAT, IMAGE, VIDEO, FILE }

    fun getCurrentTier(): Tier = Tier.FREE_UNLIMITED

    fun canUse(@Suppress("UNUSED_PARAMETER") feature: Feature): Pair<Boolean, String> {
        return true to ""
    }

    fun recordUsage(@Suppress("UNUSED_PARAMETER") feature: Feature) {
        // Free & unlimited launch mode
    }

    fun getUsageSummaryFa(): String {
        return "طرح فعال: 🎁 کاملاً رایگان و نامحدود (همه امکانات باز است)"
    }
}
