// The Record widget (ADR 0017 § 4): the capture path that skips the app,
// and the only legal one.
//
// RemoteViews rather than Glance, deliberately. Glance is a Compose
// runtime for widgets and a new dependency; four static cells and a
// number need none of it, and the lighter path is also the faster one
// from tap to open microphone — which is the budget this widget exists
// to meet.
//
// The Record cell is the exemption. Everything else on the widget is an
// ordinary activity intent.

package org.gilune.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.File

/// Asks every placed widget to redraw. Explicit component, never an
/// implicit broadcast: this is the app talking to its own receiver.
const val ACTION_REFRESH_WIDGET = "org.gilune.app.action.REFRESH_WIDGET"

class GiluneWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = widgetViews(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) refreshWidgets(context)
    }
}

/// Redraw now, from whichever component noticed the vault had changed.
fun refreshWidgets(context: Context) {
    runCatching {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, GiluneWidget::class.java))
        if (ids.isEmpty()) return
        val views = widgetViews(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}

/// The broadcast form, for a caller that would rather not know what an
/// `AppWidgetManager` is. Same effect, one hop later.
fun requestWidgetRefresh(context: Context) {
    context.sendBroadcast(Intent(context, GiluneWidget::class.java).setAction(ACTION_REFRESH_WIDGET))
}

/// How many captures are sitting in the inbox.
///
/// `Captures/` **is** the inbox — ADR 0017 § 1 says so, and the kernel
/// projects every capture there — so this counts the notes in that one
/// folder and nothing else. It is deliberately a cheap directory read
/// rather than a kernel query: the widget is redrawn by the launcher, off
/// any thread it likes, and must never open a vault to draw a number.
///
/// It is therefore approximate in exactly one way, honestly: a capture
/// committed but not yet projected is not counted until the next
/// projection, which in practice is the same moment.
fun inboxCount(vaultDir: String): Int {
    val captures = File(vaultDir, "Captures")
    if (!captures.isDirectory) return 0
    return captures.listFiles()
        ?.count { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }
        ?: 0
}

/// The widget as it will be drawn. Public because a `RemoteViews` only
/// fails when the launcher inflates it — an unsupported view or a wrong
/// id is a silent "problem loading widget" on someone's home screen and
/// nothing at build time — so the test net inflates it here instead.
fun widgetViews(context: Context): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.gilune_widget)

    val count = runCatching { inboxCount(vaultDir(context)) }.getOrDefault(0)
    views.setTextViewText(R.id.widget_inbox_count, count.toString())
    views.setTextViewText(R.id.widget_inbox_label, if (count == 1) "capture in inbox" else "captures in inbox")

    // While a recording runs the cell offers the way out of it, so the
    // widget never claims a state the notification contradicts.
    val recording = RecordService.running
    views.setImageViewResource(
        R.id.widget_record_icon,
        if (recording) R.drawable.ic_widget_stop else R.drawable.ic_widget_mic,
    )
    views.setTextViewText(R.id.widget_record_label, if (recording) "Stop" else "Record")

    views.setOnClickPendingIntent(R.id.widget_record, recordPendingIntent(context, recording))
    views.setOnClickPendingIntent(R.id.widget_new_note, shellPendingIntent(context, REQ_NEW_NOTE, Routes.CAPTURE))
    views.setOnClickPendingIntent(R.id.widget_search, shellPendingIntent(context, REQ_SEARCH, Routes.SEARCH))
    views.setOnClickPendingIntent(R.id.widget_inbox, shellPendingIntent(context, REQ_INBOX, Routes.NOTES))
    return views
}

/// The one cell that is not an activity.
///
/// A widget cannot ask for a permission, so when the microphone has not
/// been granted the cell hands the ask to the app instead of firing a
/// service that would only refuse itself. Once it is granted the tap goes
/// straight into the foreground service and the app is never composed.
private fun recordPendingIntent(context: Context, recording: Boolean): PendingIntent = when {
    recording -> PendingIntent.getForegroundService(
        context,
        REQ_STOP,
        RecordService.stopIntent(context, SOURCE_WIDGET),
        PENDING_FLAGS,
    )
    hasMicPermission(context) -> PendingIntent.getForegroundService(
        context,
        REQ_RECORD,
        RecordService.startIntent(context, SOURCE_WIDGET),
        PENDING_FLAGS,
    )
    else -> shellPendingIntent(context, REQ_ASK_FOR_MIC, Routes.RECORD)
}

private fun shellPendingIntent(context: Context, request: Int, route: String): PendingIntent =
    PendingIntent.getActivity(context, request, shellIntent(context, route), PENDING_FLAGS)

private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
private const val REQ_RECORD = 20
private const val REQ_STOP = 21
private const val REQ_ASK_FOR_MIC = 22
private const val REQ_NEW_NOTE = 23
private const val REQ_SEARCH = 24
private const val REQ_INBOX = 25
