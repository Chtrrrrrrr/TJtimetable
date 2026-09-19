package com.ranorac.tjtimetable.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows a one-shot message from a ViewModel and marks it as handled.
 *
 * The order here is the whole point, and it was wrong before:
 *
 * ```
 * // WRONG — what the three screens used to do
 * LaunchedEffect(message) {
 *     message?.let {
 *         snackbar.showSnackbar(it)   // suspends until the snackbar is gone
 *         onConsumeMessage()          // ...so this may never run
 *     }
 * }
 * ```
 *
 * `showSnackbar` suspends until the snackbar is dismissed, and leaving the page disposes the
 * composition — which cancels this effect. A student who switched tabs while a
 * "已更新校历与调休" snackbar was on screen therefore never consumed the message, so every
 * return to that page replayed it, for as long as the ViewModel survived.
 *
 * Consuming first fixes that. The show then has to run on the *composition's* scope rather
 * than the effect's, because consuming flips this effect's key to null, and a
 * `showSnackbar` parked in that effect would be cancelled with it — the message would flash
 * and vanish instead.
 */
@Composable
fun SnackbarMessageEffect(
    message: String?,
    host: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        // 先注销：否则切页会跳过后半段，消息留在 ViewModel 里反复弹出。
        onConsumed()
        scope.launch { host.showSnackbar(text) }
    }
}
