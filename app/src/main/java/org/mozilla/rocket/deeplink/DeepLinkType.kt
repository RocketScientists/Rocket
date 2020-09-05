package org.mozilla.rocket.deeplink

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.mozilla.rocket.deeplink.task.OpenPrivateModeTask
import org.mozilla.rocket.deeplink.task.StartSettingsActivityTask
import org.mozilla.rocket.deeplink.task.StartShoppingSearchActivityTask
import org.mozilla.rocket.deeplink.task.Task
import org.mozilla.rocket.extension.getParam
import java.net.URI
import java.net.URISyntaxException

enum class DeepLinkType {

    SHOPPING_SEARCH_HOME {
        override fun match(uri: URI) = isContentLink(uri, DeepLinkConstants.PATH_SHOPPING_SEARCH, hasQuery = false)

        override fun addTasks(uri: URI) {
            addTask(StartShoppingSearchActivityTask())
        }
    },

    PRIVATE_MODE {
        override fun match(uri: URI) = isDeepLink(uri) && DeepLinkConstants.HOST_PRIVATE_MODE == uri.host && uri.path.isNullOrEmpty()

        override fun addTasks(uri: URI) {
            addTask(OpenPrivateModeTask())
        }
    },

    COMMAND_SET_DEFAULT_BROWSER {
        override fun match(uri: URI) = isCommandUri(uri, DeepLinkConstants.COMMAND_SET_DEFAULT_BROWSER)

        override fun addTasks(uri: URI) {
            addTask(StartSettingsActivityTask(DeepLinkConstants.COMMAND_SET_DEFAULT_BROWSER))
        }
    },

    NOT_SUPPORT {
        override fun match(uri: URI) = false

        override fun addTasks(uri: URI) = Unit
    };

    private val taskList = ArrayList<Task>()

    protected abstract fun match(uri: URI): Boolean

    protected abstract fun addTasks(uri: URI)

    protected fun addTask(task: Task) {
        taskList.add(task)
    }

    @VisibleForTesting
    fun getTaskList(): List<Task> {
        return taskList
    }

    fun execute(context: Context) {
        for (task in taskList) {
            task.execute(context)
        }
        taskList.clear()
    }

    companion object {
        fun parse(url: String): DeepLinkType {
            try {
                val uri = URI(url)

                for (type in values()) {
                    if (type === NOT_SUPPORT) {
                        continue
                    }

                    if (type.match(uri)) {
                        type.addTasks(uri)

                        return type
                    }
                }
            } catch (e: URISyntaxException) {
                e.printStackTrace()
            }

            return NOT_SUPPORT
        }

        private fun isContentLink(uri: URI, path: String, hasQuery: Boolean): Boolean {
            return isContentLink(uri) && path == uri.path && if (hasQuery) {
                !uri.query.isNullOrEmpty()
            } else {
                uri.query.isNullOrEmpty()
            }
        }

        private fun isContentLink(uri: URI) =
                isDeepLink(uri) && DeepLinkConstants.HOST_CONTENT == uri.host

        private fun isCommandUri(uri: URI, command: String) = isCommandUri(uri) &&
                uri.getParam(DeepLinkConstants.COMMAND_PARAM_KEY) == command

        private fun isCommandUri(uri: URI) =
                isDeepLink(uri) && DeepLinkConstants.HOST_COMMAND == uri.host

        fun isDeepLink(uri: URI) = DeepLinkConstants.SCHEMA_ROCKET == uri.scheme
    }
}