object SystemEnv {
    val google_app_id: String? = System.getenv("google_app_id")
    val default_web_client_id: String? = System.getenv("default_web_client_id")
    val firebase_database_url: String? = System.getenv("firebase_database_url")
    val gcm_defaultSenderId: String? = System.getenv("gcm_defaultSenderId")
    val google_api_key: String? = System.getenv("google_api_key")
    val google_crash_reporting_api_key: String? = System.getenv("google_crash_reporting_api_key")
    val project_id: String? = System.getenv("project_id")
    val auto_screenshot: String? = System.getenv("auto_screenshot")
}

object Localization {
    val KEPT_LOCALE = arrayOf("in", "hi-rIN", "th", "tl", "su", "jv", "vi", "zh-rTW", "ta", "kn", "ml")
}
