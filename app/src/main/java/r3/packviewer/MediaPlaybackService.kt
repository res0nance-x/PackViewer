package r3.packviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import r3.content.BinaryContent
import r3.http.ContentHandler
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.pack.Pack
import java.io.File

object PackHolder {
    var currentPack: Pack? = null
    var listeningPort by mutableStateOf(0)
}

class MediaPlaybackService : Service() {
    private var webServer: WebServer? = null
    private val CHANNEL_ID = "media_playback_channel"
    private val NOTIFICATION_ID = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val pack = PackHolder.currentPack
        if (pack != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            startWebServer(pack)
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun startWebServer(pack: Pack) {
        webServer?.stop()
        val tmpDir = File(cacheDir, "server_tmp")
        tmpDir.mkdirs()
        val ws = WebServer(null, 0, tmpDir)
        ws.handlers.add(HandlerFactory.createLogRouter())
        ws.handlers.add(HandlerFactory.createWelcomeHandler())
        ws.handlers.add(HandlerFactory.createPackHandler(pack))
        ws.handlers.add(ContentHandler { header, _ ->
            if (header.optString("path") == "/index.html") {
                try {
                    assets.open("index.html").use { inputStream ->
                        val bytes = inputStream.readBytes()
                        BinaryContent(bytes, "index.html", "html")
                    }
                } catch (_: Exception) {
                    null
                }
            } else null
        })

        ws.start(0, false)
        webServer = ws
        PackHolder.listeningPort = ws.listeningPort
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Media Playback Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pack Viewer")
            .setContentText("Serving media content...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
        webServer = null
        PackHolder.currentPack = null
        PackHolder.listeningPort = 0
    }
}
