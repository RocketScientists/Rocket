object Versions {
    const val min_sdk = 21
    const val target_sdk = 30
    const val compile_sdk = 31
    const val version_code = 1
    const val version_name = "2.9.2"
    const val android_gradle_plugin = "7.0.3"
    const val gms_oss_licenses_plugin = "0.10.4"
    const val support = "1.0.0"
    const val material = "1.5.0"
    const val cardview = "1.0.0"
    const val recyclerview = "1.2.1"
    const val viewpager2 = "1.0.0"
    const val palette = "1.0.0"
    const val arch_core_testing = "2.1.0"
    const val arch_work = "2.7.0"
    const val guava_android = "28.2-android"
    const val lifecycle = "2.4.0"
    const val room = "2.4.3"
    const val glide = "4.0.0"
    const val kotlin = "1.6.0"
    const val kotlinXCoroutine = "1.6.0"
    const val ktlint = "0.41.0"
    const val gms = "11.8.0"
    const val paging = "3.0.1"
    const val lottie = "3.4.0"
    const val leakcanary = "2.7"
    const val android_components = "0.52.0"
    const val android_components_awesomebar = "0.56.0"
    const val android_x_core_ktx = "1.7.0"
    const val android_x_appcompat = "1.3.1"
    const val android_x_constraint = "2.1.4"
    const val android_x_navigation = "2.5.3"
    const val android_x_preference = "1.1.0"
    const val annotation = "1.3.0"
    const val junit4 = "4.13.2"
    const val mockito = "4.4.0"
    const val mockitoKotlin = "4.0.0"
    const val json = "20190722"
    const val robolectric = "4.9"
    const val espresso = "3.4.0"
    const val test_core = "1.4.0"
    const val test_ext = "1.1.3"
    const val test_runner = "1.4.0"
    const val uiautomator = "2.2.0"
    const val mockwebserver = "4.9.3"
    const val firebase_bom = "29.0.0"
    const val google_services_plugin = "3.1.1"
    const val fabric_plugin = "1.25.1"
    const val fastlane_screengrab = "2.1.0"
    const val jraska_falcon = "2.2.0"
    const val dagger = "2.38.1"
    const val play = "1.10.2"
}

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
