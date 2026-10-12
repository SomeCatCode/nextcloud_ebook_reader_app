package com.somecatcode.ebookreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Text of a text field that is edited locally and reported to a ViewModel whose state comes back
 * asynchronously (`combine`/`stateIn`, DataStore). Showing that state directly makes the field jump:
 * a value that lags behind the typing replaces the text and moves the cursor.
 *
 * [text] is what the field shows; [onChange] updates it at once and reports it. A value from outside
 * replaces [text] only when it is not the echo of something typed here (e.g. a reset or a dialog that
 * opened with other data).
 */
@Stable
class LocalText internal constructor(initial: String, private val report: (String) -> Unit) {
    var text by mutableStateOf(initial)
        private set

    /** Values sent but not yet seen coming back, oldest first. */
    private val pending = ArrayDeque<String>()

    val onChange: (String) -> Unit = { v ->
        text = v
        pending.addLast(v)
        if (pending.size > MAX_PENDING) pending.removeFirst()
        report(v)
    }

    internal fun onExternal(value: String) {
        val echo = pending.indexOf(value)
        if (echo >= 0) {
            // our own input (state flows may skip intermediate values): drop it and everything older
            repeat(echo + 1) { pending.removeFirst() }
        } else if (value != text) {
            text = value
            pending.clear()
        }
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

/** See [LocalText]. [value] is the (possibly lagging) state, [onValueChange] reports typed text. */
@Composable
fun rememberLocalText(value: String, onValueChange: (String) -> Unit): LocalText {
    val latest by rememberUpdatedState(onValueChange)
    val state = remember { LocalText(value) { latest(it) } }
    LaunchedEffect(value) { state.onExternal(value) }
    return state
}
