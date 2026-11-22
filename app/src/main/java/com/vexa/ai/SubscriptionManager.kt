package com.vexa.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سیستم مدیریت اشتراک و سهمیه Vexa AI:
 * - FREE (رایگان): محدودیت روزانه در پیام، ساخت عکس، ساخت فایل، ساخت ویدیو و مدل پایه
 * - PRO (پرو — ۸۰۰,۰۰۰ تومان): سهمیه بسیار بالا، مدل‌های هوشمند پیشرفته، ساخت ویدیو و آموزش سفارشی
 * - ALPHA (آلفا — ۱,۵۰۰,۰۰۰ تومان): کاملاً نامحدود در تمام قابلیت‌ها بدون هیچ محدودیتی + بالاترین مدل‌های استدلالی
 */
class SubscriptionManager(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vexa_subscription", Context.MODE_PRIVATE)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    enum class Tier(
        val titleFa: String,
        val titleEn: String,
        val priceLabelFa: String,
        val dailyChatLimit: Int,
        val dailyImageLimit: Int,
        val dailyVideoLimit: Int,
        val dailyFileLimit: Int,
        val maxTrainingRules: Int
    ) {
        FREE(
            "نسخه رایگان (Free)",
            "Free Plan",
            "رایگان / ۰ تومان",
            dailyChatLimit = 12,
            dailyImageLimit = 2,
            dailyVideoLimit = 0, // ویدیو در نسخه رایگان قفل است
            dailyFileLimit = 1,
            maxTrainingRules = 2
        ),
        PRO(
            "نسخه پرو (Pro)",
            "Pro Plan",
            "۸۰۰,۰۰۰ تومان / ماهانه",
            dailyChatLimit = 120,
            dailyImageLimit = 25,
            dailyVideoLimit = 5,
            dailyFileLimit = 20,
            maxTrainingRules = 15
        ),
        ALPHA(
            "نسخه آلفا (Alpha نامحدود)",
            "Alpha Unlimited",
            "۱,۵۰۰,۰۰۰ تومان / ماهانه",
            dailyChatLimit = Int.MAX_VALUE,
            dailyImageLimit = Int.MAX_VALUE,
            dailyVideoLimit = Int.MAX_VALUE,
            dailyFileLimit = Int.MAX_VALUE,
            maxTrainingRules = Int.MAX_VALUE
        )
    }

    enum class Feature { CHAT, IMAGE, VIDEO, FILE }

    fun getCurrentTier(): Tier {
        val raw = prefs.getString("active_tier", Tier.FREE.name) ?: Tier.FREE.name
        return try { Tier.valueOf(raw) } catch (_: Exception) { Tier.FREE }
    }

    fun setTier(tier: Tier) {
        prefs.edit().putString("active_tier", tier.name).apply()
    }

    /**
     * فعال‌سازی کد لایسنس (شامل کدهای تست و تولید کد برای خریداران شما)
     * فرمت کدهای معتبر:
     * - PRO: شروع با VEXA-PRO- (مثلاً VEXA-PRO-800K)
     * - ALPHA: شروع با VEXA-ALPHA- (مثلاً VEXA-ALPHA-1500K)
     */
    fun activateWithCode(rawCode: String): Pair<Boolean, String> {
        val code = rawCode.trim().uppercase(Locale.ROOT)
        return when {
            code.startsWith("VEXA-ALPHA-") && code.length >= 13 -> {
                setTier(Tier.ALPHA)
                true to "تبریک! نسخه «آلفا (Alpha)» با دسترسی ۱۰۰٪ نامحدود در تمامی قابلیت‌ها فعال شد."
            }
            code.startsWith("VEXA-PRO-") && code.length >= 11 -> {
                setTier(Tier.PRO)
                true to "تبریک! نسخه «پرو (Pro)» با موفقیت برای حساب شما فعال شد."
            }
            code == "VEXA-FREE" -> {
                setTier(Tier.FREE)
                true to "حساب به نسخه رایگان تغییر یافت."
            }
            else -> {
                false to "کد فعال‌سازی معتبر نیست. لطفاً کد خریداری‌شده را به صورت دقیق وارد کنید."
            }
        }
    }

    private fun resetIfNewDay() {
        val today = dayFmt.format(Date())
        val savedDay = prefs.getString("usage_date", "")
        if (savedDay != today) {
            prefs.edit()
                .putString("usage_date", today)
                .putInt("used_chat", 0)
                .putInt("used_image", 0)
                .putInt("used_video", 0)
                .putInt("used_file", 0)
                .apply()
        }
    }

    fun getUsedCount(feature: Feature): Int {
        resetIfNewDay()
        val key = when (feature) {
            Feature.CHAT -> "used_chat"
            Feature.IMAGE -> "used_image"
            Feature.VIDEO -> "used_video"
            Feature.FILE -> "used_file"
        }
        return prefs.getInt(key, 0)
    }

    fun getLimit(feature: Feature): Int {
        val t = getCurrentTier()
        return when (feature) {
            Feature.CHAT -> t.dailyChatLimit
            Feature.IMAGE -> t.dailyImageLimit
            Feature.VIDEO -> t.dailyVideoLimit
            Feature.FILE -> t.dailyFileLimit
        }
    }

    fun canUse(feature: Feature): Pair<Boolean, String> {
        val tier = getCurrentTier()
        if (tier == Tier.ALPHA) return true to "" // Alpha has zero restrictions anywhere!

        val used = getUsedCount(feature)
        val limit = getLimit(feature)
        if (used >= limit) {
            val msg = when {
                feature == Feature.VIDEO && tier == Tier.FREE ->
                    "🔒 ساخت ویدیوی هوشمند در «نسخه رایگان» غیرفعال است.\nبرای ساخت ویدیو به «نسخه پرو (۸۰۰ هزار تومان)» یا برای استفاده کاملاً نامحدود به «نسخه آلفا (۱,۵۰۰ هزار تومان)» ارتقا دهید."
                tier == Tier.FREE ->
                    "⚠️ سهمیه روزانه نسخه رایگان شما ($limit مورد) به پایان رسید.\nبا ارتقا به «نسخه پرو (۸۰۰ هزار تومان)» یا «نسخه آلفا نامحدود (۱,۵۰۰ هزار تومان)» بدون محدودیت ادامه دهید."
                else ->
                    "⚠️ سهمیه روزانه نسخه پرو شما ($limit مورد) تکمیل شد.\nبرای دسترسی کاملاً نامحدود در تمام بخش‌ها، به «نسخه آلفا (۱,۵۰۰ هزار تومان)» ارتقا دهید."
            }
            return false to msg
        }
        return true to ""
    }

    fun recordUsage(feature: Feature) {
        if (getCurrentTier() == Tier.ALPHA) return
        resetIfNewDay()
        val key = when (feature) {
            Feature.CHAT -> "used_chat"
            Feature.IMAGE -> "used_image"
            Feature.VIDEO -> "used_video"
            Feature.FILE -> "used_file"
        }
        prefs.edit().putInt(key, getUsedCount(feature) + 1).apply()
    }

    fun getUsageSummaryFa(): String {
        val t = getCurrentTier()
        if (t == Tier.ALPHA) {
            return "طرح فعال: 👑 آلفا (Alpha) — دسترسی کاملاً نامحدود در همه بخش‌ها"
        }
        return "طرح فعال: ${t.titleFa}\n" +
            "• پیام امروز: ${getUsedCount(Feature.CHAT)} از ${t.dailyChatLimit}\n" +
            "• عکس امروز: ${getUsedCount(Feature.IMAGE)} از ${t.dailyImageLimit}\n" +
            "• ویدیو امروز: ${getUsedCount(Feature.VIDEO)} از ${t.dailyVideoLimit}\n" +
            "• فایل امروز: ${getUsedCount(Feature.FILE)} از ${t.dailyFileLimit}"
    }
}
