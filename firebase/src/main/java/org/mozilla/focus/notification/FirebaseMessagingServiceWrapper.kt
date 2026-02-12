/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.notification

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Provide the actual impl (FirebaseMessagingService) or the dummy one (Service) so we can use the
 * module accordingly with our build types (currently only release and  firebase will provide actual
 * impl)
 */
abstract class FirebaseMessagingServiceWrapper : Service() {
    abstract fun onNotificationMessage(
        data: Map<String, String>,
        title: String?,
        body: String?,
        imageUrl: String?
    )

    abstract fun onDataMessage(data: MutableMap<String, String>)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
