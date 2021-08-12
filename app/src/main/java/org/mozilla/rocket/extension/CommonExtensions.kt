package org.mozilla.rocket.extension

/**
 * Run a function if condition is true
 *
 * SomeCheckIfTrue().thenRun { block() }
 */
inline fun Boolean.thenRun(block: () -> Any) {
    if (this) {
        block()
    }
}
