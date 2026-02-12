package org.mozilla.rocket.home.logoman.domain

import org.mozilla.rocket.home.logoman.data.LogoManNotificationRepo
import org.mozilla.rocket.home.logoman.ui.LogoManNotification
import org.mozilla.rocket.home.logoman.ui.LogoManNotification.Notification.RemoteNotification

class DismissLogoManNotificationUseCase(
    private val logoManNotificationRepo: LogoManNotificationRepo
) {

    operator fun invoke(notification: LogoManNotification.Notification) {
        when (notification) {
            is RemoteNotification -> logoManNotificationRepo.saveLastReadNotificationId(notification.id)
            // FIXME: find a proper way to handle this
            is LogoManNotification.Notification.MissionNotification -> Unit
        }
    }
}
