/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.tabs.tabtray

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import org.mozilla.focus.BuildConfig
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.TabViewClient
import org.mozilla.rocket.tabs.TabViewEngineSession
import java.util.ArrayList

internal class TabsSessionModel(
    private val sessionManager: SessionManager
) : TabTrayContract.Model {

    private val adapter: SessionModelObserverAdapter = SessionModelObserverAdapter(sessionManager)
    private val tabs = ArrayList<Session>()

    override fun loadTabs(listener: TabTrayContract.Model.OnLoadCompleteListener?) {
        tabs.clear()
        tabs.addAll(sessionManager.getTabs())

        listener?.onLoadComplete()
    }

    override fun getTabs(): List<Session> {
        return tabs
    }

    override fun getFocusedTab(): Session? {
        return sessionManager.focusSession
    }

    override fun switchTab(tabPosition: Int) {
        if (tabPosition >= 0 && tabPosition < tabs.size) {
            val target = tabs[tabPosition]
            val latestTabs = sessionManager.getTabs()
            val exist = latestTabs.indexOf(target) != -1
            if (exist) {
                sessionManager.switchToTab(target.id)
            }
        } else {
            if (BuildConfig.DEBUG) {
                throw ArrayIndexOutOfBoundsException("index: " + tabPosition + ", size: " + tabs.size)
            }
        }
    }

    override fun removeTab(tabPosition: Int) {
        if (tabPosition >= 0 && tabPosition < tabs.size) {
            sessionManager.dropTab(tabs[tabPosition].id)
        } else {
            if (BuildConfig.DEBUG) {
                throw ArrayIndexOutOfBoundsException("index: " + tabPosition + ", size: " + tabs.size)
            }
        }
    }

    override fun clearTabs() {
        val tabs = sessionManager.getTabs()
        for (tab in tabs) {
            sessionManager.dropTab(tab.id)
        }
    }

    override fun subscribe(observer: TabTrayContract.Model.Observer) {
        this.adapter.startMonitorSessionChange(observer)
    }

    override fun unsubscribe() {
        adapter.stopMonitorSessionChange()
    }

    /**
     * A class to monitor changes of tab/session content, and deliver the change to TabTray's
     * observer for subsequent actions
     *
     *                 events
     * SessionManager ------->                              TabModelChange
     *        Session -------> SessionModelObserverAdapter  ------------->  TabTrayObserver
     *        Session ------->                              ------------->
     *        Session                                      SessionCountChange
     */
    private class SessionModelObserverAdapter(
        val sessionManager: SessionManager
    ) : SessionManager.Observer, Session.Observer {

        var tabTrayObserver: TabTrayContract.Model.Observer? = null
        var monitoringSession: Session? = null

        override fun onUrlChanged(session: Session, url: String?) {
            onTabModelChanged(session)
        }

        override fun onTitleChanged(session: Session, title: String?) {
            onTabModelChanged(session)
        }

        override fun onReceivedIcon(icon: Bitmap?) {
            val session = monitoringSession ?: return
            onTabModelChanged(session)
        }

        override fun onFocusChanged(session: Session?, factor: SessionManager.Factor) {
            stopMonitorSession()
            // monitor next session, if any
            session?.let { startMonitorSession(it) }
        }

        fun startMonitorSessionChange(observer: TabTrayContract.Model.Observer) {
            this.tabTrayObserver = observer
            startMonitorSessionManager()
        }

        fun stopMonitorSessionChange() {
            this.tabTrayObserver = null
            stopMonitorSessionManager()
        }

        override fun onSessionCountChanged(count: Int) {
            tabTrayObserver?.onUpdate(sessionManager.getTabs())
        }

        /**
         * Be called, if the content of a tab/session is changed
         */
        fun onTabModelChanged(session: Session) {
            tabTrayObserver?.onTabUpdate(session)
        }

        private fun startMonitorSessionManager() {
            sessionManager.register(this as SessionManager.Observer)
        }

        private fun stopMonitorSessionManager() {
            sessionManager.unregister(this as SessionManager.Observer)
        }

        private fun startMonitorSession(session: Session) {
            this.monitoringSession = session
                .also { session.register(this) }
        }

        private fun stopMonitorSession() {
            this.monitoringSession?.unregister(this)
            this.monitoringSession = null
        }

        // empty
        override fun onShowFileChooser(
            es: TabViewEngineSession,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean = false

        // empty
        override fun updateFailingUrl(url: String?, updateFromError: Boolean) = Unit

        // empty
        override fun handleExternalUrl(url: String?): Boolean = false

        // empty
        override fun onHttpAuthRequest(
            callback: TabViewClient.HttpAuthCallback,
            host: String?,
            realm: String?
        ) = Unit
    }
}
