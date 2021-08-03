/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.browser

import android.os.Bundle
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.SessionManager.Factor
import org.mozilla.rocket.tabs.utils.TabUtil

class SessionManagerObserver(
    private val sessionCtrl: SessionController,
    private val isStartedFromExternalApp: Boolean
) : SessionManager.Observer {

    override fun onFocusChanged(session: Session?, factor: Factor) {
        if (session == null) {
            if (factor === Factor.FACTOR_NO_FOCUS && !isStartedFromExternalApp) {
                sessionCtrl.chromePopToHomeScreen()
            } else {
                sessionCtrl.chromeFinishActivity()
            }
        } else {
            sessionCtrl.chromeTransitToTab(session)
        }
    }

    override fun onSessionAdded(session: Session, arguments: Bundle?) {
        val extra = arguments?.getInt(BrowserFragment.EXTRA_NEW_TAB_SRC, -1)
        if (extra == BrowserFragment.SRC_CONTEXT_MENU) {
            promoteAddedSession(session, arguments)
        }
    }

    override fun onSessionCountChanged(count: Int) {
        sessionCtrl.chromeUpdateTabCount(count)
    }

    private fun promoteAddedSession(session: Session, arguments: Bundle) {
        val isFocusingTab = TabUtil.toFocus(arguments)
        if (isFocusingTab) {
            return
        }
        sessionCtrl.chromePromoteAddedSession(session)
    }
}
