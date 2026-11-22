package com.vexa.ai

/**
 * موتور تغییر زبان کامل رابط کاربری (Localization) و تم‌های حرفه‌ای Vexa AI:
 * با تغییر زبان، تمام متن‌ها، دکمه‌ها، منوها، چیپ‌ها و تنظیمات برنامه فوراً به زبان جدید تغییر می‌کنند.
 */
object AppStringsAndThemes {

    enum class AppTheme(
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val bgRoot: String,
        val bgHeader: String,
        val bgCard: String,
        val bgInput: String,
        val border: String,
        val accent: String,
        val textPrimary: String,
        val textSecondary: String
    ) {
        OBSIDIAN_CYAN(
            "obsidian", "مشکی نئونی (پیش‌فرض)", "Obsidian Cyan",
            "#050811", "#0A101E", "#0F172A", "#111827", "#1E293B", "#22D3EE", "#F8FAFC", "#94A3B8"
        ),
        MIDNIGHT_GOLD(
            "gold", "مشکی و طلایی لوکس", "Midnight Gold",
            "#090806", "#14110C", "#1C1812", "#241F17", "#383024", "#F59E0B", "#FEFCE8", "#D6C7B2"
        ),
        EMERALD_MATRIX(
            "emerald", "سبز زمردی تیره", "Emerald Dark",
            "#040D0A", "#081A14", "#0D261E", "#123328", "#1B4738", "#10B981", "#ECFDF5", "#A7F3D0"
        ),
        ROYAL_VIOLET(
            "violet", "بنفش کهکشانی", "Royal Violet",
            "#090512", "#120A24", "#1A1033", "#221642", "#352463", "#C084FC", "#FAF5FF", "#D8B4FE"
        ),
        PURE_LIGHT(
            "light", "روشن و روزانه (Light)", "Pure Light",
            "#F1F5F9", "#FFFFFF", "#FFFFFF", "#E2E8F0", "#CBD5E1", "#0284C7", "#0F172A", "#475569"
        )
    }

    data class WorldLang(
        val code: String,      // e.g. "fa-IR", "en-US", "ar-SA", "tr-TR", "fr-FR", "de-DE", "es-ES", "ru-RU", "zh-CN"
        val shortLabel: String,
        val displayName: String,
        val isRtl: Boolean
    )

    val SUPPORTED_UI_LANGUAGES = listOf(
        WorldLang("fa-IR", "FA", "فارسی (Persian)", true),
        WorldLang("en-US", "EN", "English (انگلیسی)", false),
        WorldLang("ar-SA", "AR", "العربية (عربی)", true),
        WorldLang("tr-TR", "TR", "Türkçe (ترکی)", false),
        WorldLang("fr-FR", "FR", "Français (فرانسوی)", false),
        WorldLang("de-DE", "DE", "Deutsch (آلمانی)", false),
        WorldLang("es-ES", "ES", "Español (اسپانیایی)", false),
        WorldLang("ru-RU", "RU", "Русский (روسی)", false),
        WorldLang("zh-CN", "ZH", "中文 (چینی)", false)
    )

    fun tr(langCode: String, key: String): String {
        val lang = langCode.substringBefore("-").lowercase()
        return when (lang) {
            "fa" -> faMap[key] ?: enMap[key] ?: key
            "ar" -> arMap[key] ?: enMap[key] ?: key
            "tr" -> trMap[key] ?: enMap[key] ?: key
            "fr" -> frMap[key] ?: enMap[key] ?: key
            "de" -> deMap[key] ?: enMap[key] ?: key
            "es" -> esMap[key] ?: enMap[key] ?: key
            "ru" -> ruMap[key] ?: enMap[key] ?: key
            "zh" -> zhMap[key] ?: enMap[key] ?: key
            else -> enMap[key] ?: key
        }
    }

    private val faMap = mapOf(
        "tap_to_speak" to "برای صحبت کردن به هر زبانی، گوی را لمس کنید",
        "listening" to "در حال گوش دادن… (برای توقف لمس کنید)",
        "thinking" to "وِکسا در حال پردازش است…",
        "speaking" to "وِکسا در حال صحبت است…",
        "input_hint" to "پیام به هر زبانی… (ویدیو، عکس، کد، ترید)",
        "wake_label" to "بیدارباش صوتی: فقط با گفتن «وِکسا» / \"Vexa\" پاسخ دهد",
        "new_chat" to "+ گفتگوی جدید",
        "menu_custom_train" to "شخصی‌سازی و آموزش نامحدود",
        "menu_theme_lang" to "ظاهر، تم و زبان برنامه",
        "menu_server" to "تنظیمات اتصال و سرور",
        "history_title" to "تاریخچه گفتگوها",
        "verified_user" to "تایید شده ✓",
        "logout" to "خروج",
        "chip_video" to "🎬 ساخت ویدیو MP4",
        "chip_video_cmd" to "ویدیو بساز از یک فضانورد در سطح سیاره مریخ در غروب آفتاب",
        "chip_photo" to "📸 عکس واقع‌گرایانه",
        "chip_photo_cmd" to "عکس واقع‌گرایانه بساز از یک خانه مدرن چوبی کنار دریاچه در مه صبحگاهی",
        "chip_train" to "🧠 آموزش نامحدود",
        "chip_theme" to "🎨 تغییر تم و زبان",
        "chip_btc" to "📈 تحلیل بیت‌کوین",
        "chip_btc_cmd" to "تحلیل تکنیکال و روند لحظه‌ای بیت کوین را بگو",
        "chip_pdf" to "📄 ساخت فایل PDF",
        "chip_pdf_cmd" to "یک فایل pdf بساز درباره اصول مدیریت سرمایه در ترید",
        "chip_chart" to "📊 تحلیل چارت",
        "settings_title" to "تنظیمات جامع Vexa AI",
        "save" to "ذخیره تغییرات",
        "close" to "بستن",
        "welcome_body" to "سلام %s عزیز! خوش آمدی به Vexa AI:\n" +
            "• 🌍 پشتیبانی صوتی و متنی از تمام زبان‌های دنیا\n" +
            "• 🎬 ساخت ویدیوی MP4 و 📸 عکس واقع‌گرایانه\n" +
            "• 🧠 آموزش نامحدود و تغییر تم در تنظیمات پیشرفته (⚙️)\n" +
            "• 📱 کنترل کامل ابزارهای گوشی (تماس، پیامک، چراغ قوه و...)"
    )

    private val enMap = mapOf(
        "tap_to_speak" to "Tap the orb to speak in any language",
        "listening" to "Listening… (Tap to stop)",
        "thinking" to "Vexa is thinking…",
        "speaking" to "Vexa is speaking…",
        "input_hint" to "Message in any language… (Video, Image, Code, Trade)",
        "wake_label" to "Wake Word Mode: Respond only when saying \"Vexa\"",
        "new_chat" to "+ New Chat",
        "menu_custom_train" to "Unlimited Personalization & Training",
        "menu_theme_lang" to "Theme, Appearance & Language",
        "menu_server" to "Cloud Gateway & Server",
        "history_title" to "Chat History",
        "verified_user" to "Verified ✓",
        "logout" to "Logout",
        "chip_video" to "🎬 Create MP4 Video",
        "chip_video_cmd" to "Generate a video of an astronaut walking on Mars at sunset",
        "chip_photo" to "📸 Photorealistic Image",
        "chip_photo_cmd" to "Generate a photorealistic modern wooden cabin by a misty lake at sunrise",
        "chip_train" to "🧠 Unlimited Training",
        "chip_theme" to "🎨 Theme & Language",
        "chip_btc" to "📈 Bitcoin Analysis",
        "chip_btc_cmd" to "Analyze Bitcoin live price, RSI, and market trend",
        "chip_pdf" to "📄 Generate PDF",
        "chip_pdf_cmd" to "Create a PDF file about trading risk management principles",
        "chip_chart" to "📊 Chart Vision",
        "settings_title" to "Vexa AI Advanced Settings",
        "save" to "Save Changes",
        "close" to "Close",
        "welcome_body" to "Welcome %s to Vexa AI!\n" +
            "• 🌍 Speak & chat in ALL world languages with native voice synthesis\n" +
            "• 🎬 Create MP4 Videos & 📸 Photorealistic Images\n" +
            "• 🧠 Unlimited Custom Training & Themes in Advanced Settings (⚙️)\n" +
            "• 📱 Full Android device control & live market intelligence"
    )

    private val arMap = mapOf(
        "tap_to_speak" to "المس الكرة للتحدث بأي لغة في العالم",
        "listening" to "جارٍ الاستماع… (المس للإيقاف)",
        "thinking" to "فيكسا يفكر…",
        "speaking" to "فيكسا يتحدث…",
        "input_hint" to "اكتب رسالتك بأي لغة…",
        "wake_label" to "وضع التنبيه الصوتي: الرد فقط عند قول \"Vexa\"",
        "new_chat" to "+ محادثة جديدة",
        "menu_custom_train" to "التخصيص والتدريب غير المحدود",
        "menu_theme_lang" to "المظهر والسمات واللغة",
        "menu_server" to "إعدادات الخادم السحابي",
        "history_title" to "سجل المحادثات",
        "verified_user" to "موثق ✓",
        "logout" to "خروج",
        "chip_video" to "🎬 إنشاء فيديو MP4",
        "chip_photo" to "📸 صورة واقعية",
        "chip_train" to "🧠 تدريب غير محدود",
        "chip_theme" to "🎨 السمة واللغة",
        "chip_btc" to "📈 تحليل البيتكوين",
        "chip_pdf" to "📄 إنشاء ملف PDF",
        "chip_chart" to "📊 تحليل الرسم البياني",
        "settings_title" to "إعدادات Vexa AI المتقدمة",
        "save" to "حفظ",
        "close" to "إغلاق"
    )

    private val trMap = mapOf(
        "tap_to_speak" to "Herhangi bir dilde konuşmak için küreye dokunun",
        "listening" to "Dinleniyor… (Durdurmak için dokunun)",
        "thinking" to "Vexa düşünüyor…",
        "speaking" to "Vexa konuşuyor…",
        "input_hint" to "Herhangi bir dilde mesaj yazın…",
        "wake_label" to "Uyandırma Kelimesi: Sadece \"Vexa\" dendiğinde yanıt ver",
        "new_chat" to "+ Yeni Sohbet",
        "menu_custom_train" to "Sınırsız Kişiselleştirme ve Eğitim",
        "menu_theme_lang" to "Tema, Görünüm ve Dil",
        "menu_server" to "Sunucu Bağlantı Ayarları",
        "history_title" to "Sohbet Geçmişi",
        "verified_user" to "Doğrulandı ✓",
        "logout" to "Çıkış",
        "chip_video" to "🎬 MP4 Video Oluştur",
        "chip_photo" to "📸 Gerçekçi Fotoğraf",
        "chip_train" to "🧠 Sınırsız Eğitim",
        "chip_theme" to "🎨 Tema ve Dil",
        "chip_btc" to "📈 Bitcoin Analizi",
        "chip_pdf" to "📄 PDF Oluştur",
        "chip_chart" to "📊 Grafik Analizi",
        "settings_title" to "Vexa AI Gelişmiş Ayarlar",
        "save" to "Kaydet",
        "close" to "Kapat"
    )

    private val frMap = mapOf(
        "tap_to_speak" to "Appuyez sur l'orbe pour parler dans n'importe quelle langue",
        "listening" to "Écoute en cours… (Appuyez pour arrêter)",
        "thinking" to "Vexa réfléchit…",
        "speaking" to "Vexa parle…",
        "input_hint" to "Écrivez dans n'importe quelle langue…",
        "wake_label" to "Mot d'activation : Répondre uniquement à \"Vexa\"",
        "new_chat" to "+ Nouvelle discussion",
        "menu_custom_train" to "Personnalisation et entraînement illimités",
        "menu_theme_lang" to "Thème, apparence et langue",
        "menu_server" to "Paramètres du serveur",
        "history_title" to "Historique des discussions",
        "verified_user" to "Vérifié ✓",
        "logout" to "Déconnexion",
        "chip_video" to "🎬 Créer vidéo MP4",
        "chip_photo" to "📸 Image réaliste",
        "chip_train" to "🧠 Entraînement illimité",
        "chip_theme" to "🎨 Thème et langue",
        "chip_btc" to "📈 Analyse Bitcoin",
        "chip_pdf" to "📄 Créer PDF",
        "chip_chart" to "📊 Analyse graphique",
        "settings_title" to "Paramètres avancés Vexa AI",
        "save" to "Enregistrer",
        "close" to "Fermer"
    )

    private val deMap = mapOf(
        "tap_to_speak" to "Tippen Sie auf die Kugel, um in jeder Sprache zu sprechen",
        "listening" to "Hört zu… (Tippen zum Stoppen)",
        "thinking" to "Vexa denkt nach…",
        "speaking" to "Vexa spricht…",
        "input_hint" to "Nachricht in beliebiger Sprache…",
        "wake_label" to "Aktivierungswort: Nur auf \"Vexa\" reagieren",
        "new_chat" to "+ Neuer Chat",
        "menu_custom_train" to "Unbegrenztes Training & Anpassung",
        "menu_theme_lang" to "Design, Theme & Sprache",
        "menu_server" to "Server-Einstellungen",
        "history_title" to "Chat-Verlauf",
        "verified_user" to "Verifiziert ✓",
        "logout" to "Abmelden",
        "chip_video" to "🎬 MP4-Video erstellen",
        "chip_photo" to "📸 Fotorealistisches Bild",
        "chip_train" to "🧠 Unbegrenztes Training",
        "chip_theme" to "🎨 Theme & Sprache",
        "chip_btc" to "📈 Bitcoin-Analyse",
        "chip_pdf" to "📄 PDF erstellen",
        "chip_chart" to "📊 Chart-Analyse",
        "settings_title" to "Vexa AI Erweiterte Einstellungen",
        "save" to "Speichern",
        "close" to "Schließen"
    )

    private val esMap = mapOf(
        "tap_to_speak" to "Toca el orbe para hablar en cualquier idioma",
        "listening" to "Escuchando… (Toca para detener)",
        "thinking" to "Vexa está pensando…",
        "speaking" to "Vexa está hablando…",
        "input_hint" to "Escribe en cualquier idioma…",
        "wake_label" to "Palabra de activación: Responder solo a \"Vexa\"",
        "new_chat" to "+ Nuevo chat",
        "menu_custom_train" to "Entrenamiento y personalización ilimitados",
        "menu_theme_lang" to "Tema, apariencia e idioma",
        "menu_server" to "Configuración del servidor",
        "history_title" to "Historial de chats",
        "verified_user" to "Verificado ✓",
        "logout" to "Salir",
        "chip_video" to "🎬 Crear video MP4",
        "chip_photo" to "📸 Imagen realista",
        "chip_train" to "🧠 Entrenamiento ilimitado",
        "chip_theme" to "🎨 Tema e idioma",
        "chip_btc" to "📈 Análisis Bitcoin",
        "chip_pdf" to "📄 Crear PDF",
        "chip_chart" to "📊 Análisis de gráfico",
        "settings_title" to "Configuración avanzada de Vexa AI",
        "save" to "Guardar",
        "close" to "Cerrar"
    )

    private val ruMap = mapOf(
        "tap_to_speak" to "Нажмите на сферу, чтобы говорить на любом языке",
        "listening" to "Слушаю… (Нажмите, чтобы остановить)",
        "thinking" to "Vexa думает…",
        "speaking" to "Vexa говорит…",
        "input_hint" to "Сообщение на любом языке…",
        "wake_label" to "Голосовая активация: отвечать только на «Vexa»",
        "new_chat" to "+ Новый чат",
        "menu_custom_train" to "Безлимитное обучение и настройка",
        "menu_theme_lang" to "Тема, оформление и язык",
        "menu_server" to "Настройки сервера",
        "history_title" to "История чатов",
        "verified_user" to "Подтверждено ✓",
        "logout" to "Выйти",
        "chip_video" to "🎬 Создать MP4 видео",
        "chip_photo" to "📸 Реалистичное фото",
        "chip_train" to "🧠 Безлимитное обучение",
        "chip_theme" to "🎨 Тема и язык",
        "chip_btc" to "📈 Анализ Биткоина",
        "chip_pdf" to "📄 Создать PDF",
        "chip_chart" to "📊 Анализ графика",
        "settings_title" to "Расширенные настройки Vexa AI",
        "save" to "Сохранить",
        "close" to "Закрыть"
    )

    private val zhMap = mapOf(
        "tap_to_speak" to "点击光球，用任何语言交谈",
        "listening" to "正在聆听…（点击停止）",
        "thinking" to "Vexa 正在思考…",
        "speaking" to "Vexa 正在说话…",
        "input_hint" to "用任何语言输入消息…",
        "wake_label" to "唤醒词模式：仅在说出 \"Vexa\" 时响应",
        "new_chat" to "+ 新对话",
        "menu_custom_train" to "无限自定义训练与个性化",
        "menu_theme_lang" to "主题外观与语言",
        "menu_server" to "服务器连接设置",
        "history_title" to "历史对话",
        "verified_user" to "已验证 ✓",
        "logout" to "退出",
        "chip_video" to "🎬 生成 MP4 视频",
        "chip_photo" to "📸 逼真图像生成",
        "chip_train" to "🧠 无限训练",
        "chip_theme" to "🎨 主题与语言",
        "chip_btc" to "📈 比特币分析",
        "chip_pdf" to "📄 生成 PDF",
        "chip_chart" to "📊 图表分析",
        "settings_title" to "Vexa AI 高级设置",
        "save" to "保存更改",
        "close" to "关闭"
    )
}
