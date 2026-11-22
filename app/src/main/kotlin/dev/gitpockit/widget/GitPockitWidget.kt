package dev.gitpockit.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.gitpockit.MainActivity
import dev.gitpockit.R
import dev.gitpockit.background.SyncScheduler
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.ui.repo.relativeTime
import java.time.Instant

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repos(): RepoRepository
    fun scheduler(): SyncScheduler
}

private fun Context.entry() = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

private val Ink = Color(0xFF12151C)
private val Text1 = Color(0xFFE6E9F0)
private val Text2 = Color(0xFF9AA3B5)
private val Accent = Color(0xFF34D399)
private val Problem = Color(0xFFF85149)
private val Waiting = Color(0xFFD29922)

/** Home-screen widget: repo list with last sync status, per-repo sync and "sync all". */
class GitPockitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repos = context.entry().repos().all().sortedBy { it.name.lowercase() }
        provideContent { GlanceTheme { Content(context, repos) } }
    }

    @Composable
    private fun Content(context: Context, repos: List<Repo>) {
        Column(GlanceModifier.fillMaxSize().background(Ink).cornerRadius(20.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.app_name),
                    style = TextStyle(color = ColorProvider(Text1), fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_stat_sync),
                    contentDescription = context.getString(R.string.widget_sync_all),
                    colorFilter = ColorFilter.tint(ColorProvider(Accent)),
                    modifier = GlanceModifier.size(28.dp).padding(2.dp).clickable(actionRunCallback<SyncAction>()),
                )
            }
            Spacer(GlanceModifier.size(6.dp))
            if (repos.isEmpty()) {
                Text(context.getString(R.string.home_empty_title), style = TextStyle(color = ColorProvider(Text2), fontSize = 13.sp))
            } else {
                LazyColumn {
                    items(repos, itemId = { it.id }) { repo -> RepoRow(context, repo) }
                }
            }
        }
    }

    @Composable
    private fun RepoRow(context: Context, repo: Repo) {
        val (status, color) = when {
            repo.lastError != null -> context.getString(R.string.widget_problem) to Problem
            repo.pendingSync -> context.getString(R.string.status_pending) to Waiting
            repo.lastSyncAt != null -> relativeTime(Instant.ofEpochMilli(repo.lastSyncAt!!)) to Text2
            else -> context.getString(R.string.widget_never) to Text2
        }
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(repo.name, style = TextStyle(color = ColorProvider(Text1), fontSize = 14.sp), maxLines = 1)
                Text(status, style = TextStyle(color = ColorProvider(color), fontSize = 12.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_stat_sync),
                contentDescription = context.getString(R.string.action_sync),
                colorFilter = ColorFilter.tint(ColorProvider(Accent)),
                modifier = GlanceModifier.size(32.dp).padding(6.dp)
                    .clickable(actionRunCallback<SyncAction>(actionParametersOf(SyncAction.RepoKey to repo.id))),
            )
        }
    }
}

class SyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.entry().scheduler().request(parameters[RepoKey], SyncTrigger.WIDGET)
    }

    companion object {
        val RepoKey = ActionParameters.Key<Long>("repoId")
    }
}

class GitPockitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GitPockitWidget()
}
