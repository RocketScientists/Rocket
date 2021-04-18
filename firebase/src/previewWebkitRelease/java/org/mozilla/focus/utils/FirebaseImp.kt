/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.utils

import android.content.Context
import android.os.Bundle
import android.util.ArrayMap
import android.util.Log
import androidx.annotation.Size
import androidx.annotation.WorkerThread
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import java.io.IOException
import java.util.HashMap

/**
 * It's a wrapper to communicate with Firebase
 */
open class FirebaseImp(fromResourceString: HashMap<String, Any>) : FirebaseContract(fromResourceString) {

    private lateinit var remoteConfig: FirebaseRemoteConfig
    private lateinit var firebaseAuth: FirebaseAuth
    private val traceMap = ArrayMap<String, FirebaseTrace>()

    override fun init(context: Context) {
        FirebaseApp.initializeApp(context)
        firebaseAuth = FirebaseAuth.getInstance()
    }

    // get Remote Config string
    override fun getRcString(key: String): String {
        return remoteConfig.getString(key)
    }

    override fun getRcLong(key: String): Long {
        return remoteConfig.getLong(key)
    }

    override fun getRcBoolean(key: String): Boolean {
        return remoteConfig.getBoolean(key)
    }

    override fun getInstanceId(): String? = try {
        // This method is synchronized and runs in background thread
        FirebaseInstanceId.getInstance().id

        // below catch is important, if the app starts with Firebase disabled, calling getInstance()
        // will throw IllegalStateException
    } catch (e: IOException) {
        null
    }

    override fun getRegisterToekn(callback: (String?) -> Unit) {
        try {
            FirebaseInstanceId.getInstance().instanceId.addOnCompleteListener { task ->
                val result = if (task.isSuccessful) {
                    task.result?.token
                } else {
                    null
                }
                callback(result)
            }
            // below catch is important, if the app starts with Firebase disabled, calling getInstance()
            // will throw IllegalStateException
        } catch (e: IOException) {
        }
    }

    @WorkerThread
    override fun deleteInstanceId() {
        try {
            // This method is synchronized and runs in background thread
            FirebaseInstanceId.getInstance().deleteInstanceId()

            // below catch is important, if the app starts with Firebase disabled, calling getInstance()
            // will throw IllegalStateException
        } catch (e: IOException) {
            Log.e(TAG, "FirebaseInstanceId update failed ", e)
        }
    }

    override fun enableAnalytics(context: Context, enable: Boolean) {
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enable)
    }

    // This need to be run in worker thread since FirebaseRemoteConfigSettings has IO access
    override fun enableRemoteConfig(context: Context, callback: Callback) {
        remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder().also {
            if (developerMode) {
                it.minimumFetchIntervalInSeconds = 0
            }
        }.build()

        remoteConfig.setConfigSettingsAsync(configSettings)
        if (remoteConfigDefault.size > 0) {
            remoteConfig.setDefaultsAsync(remoteConfigDefault)
        }

        // If app is using developer mode, cacheExpiration is set to 0, so each fetch will
        // retrieve values from the service.
        remoteConfigCacheExpirationInSeconds = remoteConfig.info.configSettings.minimumFetchIntervalInSeconds

        remoteConfig.fetch(remoteConfigCacheExpirationInSeconds).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Firebase RemoteConfig Fetch Successfully ")
                remoteConfig.activate()
                callback.onRemoteConfigFetched()
            } else {
                Log.d(TAG, "Firebase RemoteConfig Fetch Failed: ${task.exception}")
            }
        }
    }

    override fun setDeveloperModeEnabled(enable: Boolean) {
        developerMode = enable
    }

    override fun getFcmToken(): String? {
        return try {
            FirebaseInstanceId.getInstance().token
        } catch (e: Exception) {
            Log.e(TAG, "getGcmToken: ", e)
            ""
        }
    }

    override fun event(
        context: Context?,
        @Size(min = 1L, max = 40L) key: String,
        param: Bundle?
    ) {
        if (context == null) {
            return
        }
        FirebaseAnalytics.getInstance(context).logEvent(key, param)
    }

    override fun setFirebaseUserProperty(context: Context, tag: String, value: String) {
        FirebaseAnalytics.getInstance(context).setUserProperty(tag, value)
    }

    override fun refreshRemoteConfig(callback: (Boolean, e: Exception?) -> Unit) {
        remoteConfig.fetch(0).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Firebase RemoteConfig Fetch Successfully ")
                callback(true, null)
                remoteConfig.activate()
            } else {
                Log.d(TAG, "Firebase RemoteConfig Fetch Failed: ${task.exception}")
                callback(false, task.exception)
            }
        }
    }

    override fun enableCrashlytics(applicationContext: Context, enabled: Boolean) {
        if (enabled) {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        } else {
            Log.d(TAG, "Disabling Crashlytics will need to restart the app")
            // see https://firebase.google.com/docs/crashlytics/customize-crash-reports?platform=android
        }
    }

    override fun enablePerformanceCollection(enabled: Boolean) {
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
    }

    override fun newTrace(key: String): FirebaseTrace? {
        if (!FirebasePerformance.getInstance().isPerformanceCollectionEnabled) return null

        return FirebaseTraceImp(key).also {
            traceMap[key] = it
        }
    }

    override fun retrieveTrace(key: String): FirebaseTrace? {
        if (!FirebasePerformance.getInstance().isPerformanceCollectionEnabled) return null

        return traceMap[key]
    }

    override fun cancelTrace(key: String): FirebaseTrace? {
        if (!FirebasePerformance.getInstance().isPerformanceCollectionEnabled) return null

        return traceMap.remove(key)
    }

    override fun closeTrace(trace: FirebaseTrace): FirebaseTrace? {
        if (!FirebasePerformance.getInstance().isPerformanceCollectionEnabled) return null

        trace.stop()
        return traceMap.remove(trace.getKey())
    }
}
