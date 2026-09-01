// The capture path that skips the app (ADR 0017 § 4).
//
// A home-screen widget tap is a verbatim exemption permitting a
// microphone foreground service to start; nothing Gilune can do from the
// background may open the mic. So this service exists only as the far end
// of a `PendingIntent` the user pressed, it is `exported=false`, it has no
// intent filter, no boot receiver and no scheduler, it returns
// `START_NOT_STICKY` so the system never restarts it on its own, and it
// refuses outright any start that does not name the widget or the app as
// its source. The permission check is not the guard here — § 4's point is
// that the check *lies* in the background, returning GRANTED where
// `startForeground` will throw — so the guard is that no background path
// into this class exists at all.
//
// While it runs it survives lock and screen-off, and its mandatory
// notification carries Stop; when the capture lands, the notification
// becomes the way into the note.
//
// The bytes: PCM is streamed into the kernel chunk-by-chunk through the
// existing streamed-capture FFI, so a process kill loses only the tail.

package org.gilune.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import uniffi.gilune_ffi.Gilune
import java.util.concurrent.LinkedBlockingQueue

/// The mandatory notification's line while a recording runs. Pure, so the
/// wording is testable without a microphone.
fun recordingNotificationText(elapsedMs: Long): String = "Recording ${formatDuration(elapsedMs)}"

/// And once it has landed. Tapping it opens the note (§ 4); when the
/// duration could not be read the line simply omits it rather than
/// inventing one, the same rule the capture feed follows.
fun recordedNotificationText(durationMs: Long?): String =
    if (durationMs == null) "Recorded · tap to open" else "Recorded ${formatDuration(durationMs)} · tap to open"

class RecordService : Service() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var recorder: Thread? = null
    private var committer: Thread? = null

    @Volatile
    private var capturing = false
    private var startedAt = 0L

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!capturing) return
            notifications().notify(NOTIFICATION_ID, recordingNotification())
            ticker.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            // Low: the notification is mandatory and permanent while a
            // recording runs, so it must not also make a noise.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while Gilune is recording." }
        notifications().createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        val action = intent?.action
        // Refused, deliberately (§ 4). A null intent is a system restart;
        // an unknown source is anything that is neither the widget nor the
        // app.
        if (source !in ALLOWED_SOURCES || (action != ACTION_START && action != ACTION_STOP)) {
            refuse("action=$action source=$source")
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            if (capturing) stopCapturing() else refuse("stop with nothing running")
            return START_NOT_STICKY
        }
        // A second Record tap while one is running is not a second
        // recording — but it is a second start, and every start owes the
        // system a `startForeground`, so the notification is re-posted
        // rather than the call being skipped.
        if (capturing) {
            runCatching { goForeground(recordingNotification()) }
            return START_NOT_STICKY
        }
        if (!hasMicPermission(this)) {
            refuse("the microphone is not granted; the widget hands this to the app instead")
            return START_NOT_STICKY
        }
        beginCapturing()
        return START_NOT_STICKY
    }

    /// Decline a start without recording anything.
    ///
    /// Android gives a service started with `startForegroundService` no
    /// way to decline quietly: failing to call `startForeground` crashes
    /// the process with `ForegroundServiceDidNotStartInTimeException`
    /// **even when the service has already stopped itself** — measured on
    /// API 36, 2026-09-01, which is the whole reason this method exists.
    /// So a refusal goes foreground for the instant it takes to withdraw
    /// again. No microphone is opened by any of it: `startForeground`
    /// declares a type, it does not open a device.
    private fun refuse(why: String) {
        Log.w(TAG, "refused a start: $why")
        runCatching {
            goForeground(baseNotification().setContentTitle("Gilune").build())
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun goForeground(notification: Notification) =
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

    private fun beginCapturing() {
        startedAt = SystemClock.elapsedRealtime()
        val started = runCatching { goForeground(recordingNotification()) }
        if (started.isFailure) {
            // The documented failure this design exists to avoid: the
            // permission check said yes where service creation says no.
            Log.e(TAG, "the microphone foreground service was refused", started.exceptionOrNull())
            stopSelf()
            return
        }
        capturing = true
        running = true
        queue.clear()
        recorder = Thread(::recorderLoop, "gilune-record").apply { isDaemon = true; start() }
        committer = Thread(::commitLoop, "gilune-commit").apply { isDaemon = true; start() }
        ticker.postDelayed(tick, 1_000)
        refreshWidgets(this)
    }

    private fun stopCapturing() {
        capturing = false
        ticker.removeCallbacks(tick)
        // The recorder thread notices `capturing` and closes the stream
        // with the sentinel; the committer finishes the capture and stops
        // the service. Nothing here waits on a thread on the main looper.
    }

    /// Reads the microphone and hands blocks on as fast as they arrive.
    /// It never touches the kernel: opening a vault takes longer than the
    /// hardware buffer can wait, so the two are separate threads and the
    /// queue between them is the whole reason the mic opens promptly.
    @SuppressLint("MissingPermission")
    private fun recorderLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            WAV_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        var record: AudioRecord? = null
        try {
            val opened = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WAV_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, READ_BYTES * 4),
            )
            check(opened.state == AudioRecord.STATE_INITIALIZED) { "microphone init failed" }
            record = opened
            opened.startRecording()
            val buffer = ByteArray(READ_BYTES)
            while (capturing) {
                val read = opened.read(buffer, 0, buffer.size)
                if (read > 0) queue.put(buffer.copyOf(read)) else if (read < 0) break
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recording failed", e)
        } finally {
            record?.runCatching { stop() }
            record?.release()
            queue.put(END)
        }
    }

    /// Opens the vault and streams what the recorder produces into it.
    ///
    /// This is a **second** `Gilune.open` on the same vault, in the same
    /// process as the activity's. Multi-instance on one vault is supported
    /// use (owner decision 2026-08-31) and torture-tested across twenty
    /// concurrent processes, so one more handle in one process is the easy
    /// case — and it is the right one here, because a service that must
    /// outlive the activity cannot borrow the activity's state.
    private fun commitLoop() {
        var landed: String? = null
        var duration: Long? = null
        try {
            Gilune.open(vaultDir(this)).use { gilune ->
                val writer = gilune.beginCapture("wav")
                // The header goes across on its own, before any PCM: an
                // abandoned stage file is then still a readable WAV
                // prefix rather than a headless heap of samples.
                writer.writeChunk(wavStreamHeader())
                val sink = ChunkedSink(CAPTURE_CHUNK) { writer.writeChunk(it) }
                while (true) {
                    val block = queue.take()
                    if (block === END) break
                    sink.append(block)
                }
                sink.flush()
                val receipt = writer.finish()
                duration = sink.emitted * 1000L / WAV_BYTE_RATE
                gilune.project()
                landed = gilune.recentSources(20u)
                    .firstOrNull { it.objectId == receipt.sourceId }
                    ?.projectionRelPath
            }
        } catch (e: Throwable) {
            Log.e(TAG, "the recording did not commit", e)
        }
        finished(landed, duration)
    }

    private fun finished(notePath: String?, durationMs: Long?) {
        capturing = false
        running = false
        ticker.post {
            ticker.removeCallbacks(tick)
            // Detach rather than cancel: the notification is how the user
            // reaches the note they just spoke (§ 4).
            stopForeground(STOP_FOREGROUND_DETACH)
            notifications().notify(NOTIFICATION_ID, recordedNotification(notePath, durationMs))
            refreshWidgets(this)
            stopSelf()
        }
    }

    private fun notifications(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun recordingNotification(): Notification {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        return baseNotification()
            .setContentTitle(recordingNotificationText(elapsed))
            .setContentText("Gilune is listening. Nothing leaves the phone.")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, REQ_OPEN_APP, shellIntent(this), PENDING_FLAGS),
            )
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    "Stop",
                    PendingIntent.getForegroundService(
                        this,
                        REQ_STOP,
                        stopIntent(this, SOURCE_APP),
                        PENDING_FLAGS,
                    ),
                ).build(),
            )
            .build()
    }

    private fun recordedNotification(notePath: String?, durationMs: Long?): Notification =
        baseNotification()
            .setContentTitle(recordedNotificationText(durationMs))
            .setContentText(notePath?.let(::noteTitle) ?: "Saved to your vault.")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQ_OPEN_NOTE,
                    shellIntent(this, openNote = notePath),
                    PENDING_FLAGS,
                ),
            )
            .build()

    private fun baseNotification(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setShowWhen(false)

    override fun onDestroy() {
        capturing = false
        running = false
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.gilune.app.action.START_RECORDING"
        const val ACTION_STOP = "org.gilune.app.action.STOP_RECORDING"
        const val CHANNEL_ID = "gilune.recording"

        private const val TAG = "GiluneRecord"
        private const val NOTIFICATION_ID = 0x6701
        private const val REQ_OPEN_APP = 10
        private const val REQ_STOP = 11
        private const val REQ_OPEN_NOTE = 12
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /// One read off the driver. Small enough that the hardware buffer
        /// never overruns while the kernel is opening on the other thread.
        private const val READ_BYTES = 4096

        private val END = ByteArray(0)
        private val ALLOWED_SOURCES = setOf(SOURCE_WIDGET, SOURCE_APP)

        /// Bytes per second of the canonical shape, for turning what was
        /// committed back into a duration.
        private const val WAV_BYTE_RATE = WAV_SAMPLE_RATE * WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8

        /// Whether a recording is running, read by the widget so its
        /// Record cell can offer Stop. A process-wide fact about a
        /// process-wide service.
        @Volatile
        var running: Boolean = false
            private set

        fun startIntent(context: Context, source: String): Intent =
            Intent(context, RecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source)

        fun stopIntent(context: Context, source: String): Intent =
            Intent(context, RecordService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SOURCE, source)
    }
}
