/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.rocket.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import com.google.android.material.snackbar.Snackbar
import org.mozilla.focus.R
import org.mozilla.focus.navigation.ScreenNavigator
import org.mozilla.focus.utils.IntentUtils
import org.mozilla.rocket.tabs.Session
import org.mozilla.rocket.tabs.SessionManager
import org.mozilla.rocket.tabs.SessionManager.Factor
import org.mozilla.rocket.tabs.TabView
import org.mozilla.rocket.tabs.TabViewClient
import org.mozilla.rocket.tabs.TabViewEngineSession
import org.mozilla.rocket.tabs.utils.TabUtil

class SessionManagerObserver(
    private val browserFragment: BrowserFragment,
    private val sessionObserver: SessionObserver
) : SessionManager.Observer {

    private var tabTransitionAnimator: ValueAnimator? = null

    override fun onFocusChanged(session: Session?, factor: Factor) {
        browserFragment.chromeViewModel.onFocusedUrlChanged(session?.url)
        browserFragment.chromeViewModel.onFocusedTitleChanged(session?.title)
        if (session == null) {
            if (factor === Factor.FACTOR_NO_FOCUS && !isStartedFromExternalApp()) {
                ScreenNavigator.get(browserFragment.context).popToHomeScreen(true)
            } else {
                browserFragment.requireActivity().finish()
            }
        } else {
            transitToTab(session)
            refreshChrome(session)
        }
    }

    override fun onSessionAdded(session: Session, arguments: Bundle?) {
        if (arguments == null) {
            return
        }
        when (arguments.getInt(BrowserFragment.EXTRA_NEW_TAB_SRC, -1)) {
            BrowserFragment.SRC_CONTEXT_MENU -> onTabAddedByContextMenu(session, arguments)
            else -> Unit
        }
    }

    override fun onSessionCountChanged(count: Int) {
        browserFragment.chromeViewModel.onTabCountChanged(count)
    }

    private fun transitToTab(targetTab: Session) {
        val tabView = targetTab.engineSession?.tabView
            ?: throw RuntimeException("Tabview should be created at this moment and never be null")
        val webViewSlot = browserFragment.getWebViewSlot()
            ?: throw RuntimeException("WebViewSlot not found")
        // ensure it does not have attach to parent earlier.
        targetTab.engineSession?.detach()
        val outView = findExistingTabView(browserFragment.getWebViewSlot())
        webViewSlot.removeView(outView)
        val inView = tabView.getView()
        webViewSlot.addView(inView)
        this.sessionObserver.changeSession(targetTab)
        if (inView != null) {
            startTransitionAnimation(null, inView, null)
        }
    }

    private fun refreshChrome(tab: Session) {
        browserFragment.geolocationController.reset()
        browserFragment.updateURL(tab.url)
        browserFragment.shoppingSearchPromptMessageViewModel.checkShoppingSearchPromptVisibility(
            tab.url
        )

        if (tab.progress == 0 || tab.progress == 100) {
            browserFragment.binding?.progressBar?.visibility = View.GONE
        } else {
            browserFragment.binding?.progressBar?.progress = tab.progress
        }

        val identity = if (tab.securityInfo.secure) BrowserFragment.SITE_LOCK
        else BrowserFragment.SITE_GLOBE

        browserFragment.binding?.toolbar?.siteIdentity?.setImageLevel(identity)
        browserFragment.hideFindInPage()
        val current = browserFragment.sessionManager.focusSession
        if (current != null) {
            browserFragment.chromeViewModel.onNavigationStateChanged(
                browserFragment.canGoBack(),
                browserFragment.canGoForward()
            )
        }
        // check if newer config exists whenever navigating to Browser screen
        browserFragment.bottomBarViewModel.refresh()
    }

    private fun startTransitionAnimation(
        outView: View?,
        inView: View,
        finishCallback: Runnable?
    ) {
        stopTabTransition()
        inView.alpha = 0f
        outView?.alpha = 1f

        val duration = inView.resources.getInteger(R.integer.tab_transition_time).toLong()
        tabTransitionAnimator =
            ValueAnimator.ofFloat(0f, 1f).setDuration(duration).apply {
                addUpdateListener { animation ->
                    val alpha = animation.animatedValue as Float
                    inView.alpha = alpha
                    outView?.alpha = 1 - alpha
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishCallback?.run()
                        inView.alpha = 1f
                        outView?.alpha = 1f
                    }
                })
            }.also {
                it.start()
            }
    }

    private fun findExistingTabView(nullableParent: ViewGroup?): View? {
        val parent = nullableParent ?: return null
        val viewCount = parent.childCount
        for (childIdx in 0 until viewCount) {
            val childView = parent.getChildAt(childIdx)
            if (childView is TabView) {
                return (childView as TabView).getView()
            }
        }
        return null
    }

    private fun stopTabTransition() {
        val animator = tabTransitionAnimator ?: return
        if (animator.isRunning) {
            animator.end()
        }
    }

    private fun onTabAddedByContextMenu(tab: Session, arguments: Bundle) {
        if (!TabUtil.toFocus(arguments)) {
            val binding = browserFragment.binding ?: return
            Snackbar.make(
                binding.root,
                R.string.new_background_tab_hint,
                Snackbar.LENGTH_LONG
            ).apply {
                setAction(R.string.new_background_tab_switch) {
                    browserFragment.sessionManager.switchToTab(tab.id)
                }
                anchorView = browserFragment.binding?.browserBottomBar
            }.show()
        }
    }

    override fun updateFailingUrl(url: String?, updateFromError: Boolean) {
        sessionObserver.updateFailingUrl(url, updateFromError)
    }

    override fun handleExternalUrl(url: String?): Boolean {
        return sessionObserver.handleExternalUrl(url)
    }

    override fun onShowFileChooser(
        es: TabViewEngineSession,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        return sessionObserver.onShowFileChooser(es, filePathCallback, fileChooserParams)
    }

    override fun onHttpAuthRequest(
        callback: TabViewClient.HttpAuthCallback,
        host: String?,
        realm: String?
    ) {
        sessionObserver.onHttpAuthRequest(callback, host, realm)
    }

    private fun isStartedFromExternalApp(): Boolean {
        // No SafeIntent needed here because intent.getAction() is safe (SafeIntent simply calls
        // intent.getAction() without any wrapping):
        val intent = browserFragment?.activity?.intent ?: return false
        val isInternal = intent.getBooleanExtra(IntentUtils.EXTRA_IS_INTERNAL_REQUEST, false)
        return !isInternal && Intent.ACTION_VIEW == intent.action
    }
}
