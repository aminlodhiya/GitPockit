package dev.gitpockit.ui.common

import android.content.Context
import dev.gitpockit.R
import dev.gitpockit.core.git.PullResult
import dev.gitpockit.core.git.PushResult

/** Turns a failure into a short message for a snackbar. */
fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

fun Context.describe(result: PullResult): String = when (result) {
    PullResult.UpToDate -> getString(R.string.sync_up_to_date)
    PullResult.FastForward, PullResult.Merged -> getString(R.string.pull_done)
    is PullResult.Conflicts -> getString(R.string.sync_conflicts, result.paths.size)
}

fun Context.describe(result: PushResult): String = when {
    result.isSuccess -> getString(R.string.push_done)
    result.rejectedRefs.values.any { it.contains("REJECTED_NONFASTFORWARD") || it.contains("non-fast-forward") } ->
        getString(R.string.push_rejected_hint)
    else -> getString(R.string.push_rejected, result.rejectedRefs.values.joinToString())
}
