package org.mozilla.focus.utils

import android.content.Context
import android.os.Bundle
import java.util.HashMap

/**
 * Fake implementation fot test.
 *
 * TODO: use mockito to mock the FirebaseContract instead of creating a fake implementation.
 */
open class FirebaseFakeImpl (remoteConfigDefault: java.util.HashMap<String, Any> = HashMap()) : FirebaseContract(remoteConfigDefault) {

    override fun init(context: Context) {
    }

    // get Remote Config string
    override fun getRcString(key: String): String {
        val value = remoteConfigDefault[key]
        if (value is String) {
            return value
        }
        return FIREBASE_STRING_DEFAULT
    }

    override fun getRcLong(key: String): Long {
        val value = remoteConfigDefault[key]
        if (value is Int) {
            return value.toLong()
        } else if (value is Long) {
            return value
        }
        return FIREBASE_LONG_DEFAULT
    }

    override fun getRcBoolean(key: String): Boolean {
        val value = remoteConfigDefault[key]
        if (value is Boolean) {
            return value
        }
        return FIREBASE_BOOLEAN_DEFAULT
    }

    override fun getInstanceId(): String? = null

    override fun getRegisterToekn(callback: (String?) -> Unit) {
    }

    override fun deleteInstanceId() {
    }

    override fun enableAnalytics(context: Context, enable: Boolean) {
    }

    // This need to be run in worker thread since FirebaseRemoteConfigSettings has IO access
    override fun enableRemoteConfig(context: Context, callback: Callback) {
    }

    override fun setDeveloperModeEnabled(enable: Boolean) {
    }

    override fun getFcmToken() = ""

    override fun event(context: Context?, key: String, param: Bundle?) {
    }

    override fun setFirebaseUserProperty(context: Context, tag: String, value: String) {
    }

    override fun refreshRemoteConfig(callback: (Boolean, e: Exception?) -> Unit) {
    }

    override fun enableCrashlytics(applicationContext: Context, enabled: Boolean) {
    }

    override fun enablePerformanceCollection(enabled: Boolean) {
    }

    override fun newTrace(key: String): FirebaseTrace? = null

    override fun retrieveTrace(key: String): FirebaseTrace? = null

    override fun cancelTrace(key: String): FirebaseTrace? = null

    override fun closeTrace(trace: FirebaseTrace): FirebaseTrace? = null
}

