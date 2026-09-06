package com.wrick.DacVolumeFix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wrick.DacVolumeFix.R
import com.wrick.DacVolumeFix.data.DacRepository
import com.wrick.DacVolumeFix.usb.DacVolumeEngine
import com.wrick.DacVolumeFix.usb.UnlockResult
import com.wrick.DacVolumeFix.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class UsbUnlockService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: DacRepository
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "UsbUnlockService"
        const val CHANNEL_ID = "dac_volume_unlock_channel"
        const val ONGOING_NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002

        const val ACTION_UNLOCK = "com.wrick.DacVolumeFix.ACTION_UNLOCK"
        const val ACTION_UNLOCK_COMPLETED = "com.wrick.DacVolumeFix.ACTION_UNLOCK_COMPLETED"
        const val EXTRA_TARGET_DB = "extra_target_db"

        // Debounce tracking: minimum 1000ms gap between events for the same VID/PID
        @Volatile
        private var lastVid = -1
        @Volatile
        private var lastPid = -1
        @Volatile
        private var lastUnlockTimeMs = 0L

        fun startForDevice(context: Context, device: UsbDevice?, targetDb: Int? = null) {
            val intent = Intent(context, UsbUnlockService::class.java).apply {
                action = ACTION_UNLOCK
                device?.let { putExtra(UsbManager.EXTRA_DEVICE, it) }
                targetDb?.let { putExtra(EXTRA_TARGET_DB, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DacRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        AppLogger.i(TAG, "UsbUnlockService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "UsbUnlockService onStartCommand received")

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        } ?: findConnectedAudioDevice(usbManager)

        val deviceName = device?.productName?.takeIf { it.isNotBlank() } ?: "Apple USB-C Adapter"

        // Single notification: Starts as "Unlocking [DAC Name]..."
        val initialNotification = buildOngoingNotification("Unlocking $deviceName...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ONGOING_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(ONGOING_NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, initialNotification)
        }

        // Strict 3-second maximum lifetime: try/finally ALWAYS calls stopForeground + stopSelf
        serviceScope.launch {
            try {
                withTimeout(3000L) {
                    handleUnlock(usbManager, device, intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Unlock operation timed out or failed: ${e.message}", e)
            } finally {
                AppLogger.i(TAG, "Terminating foreground service: stopForeground + stopSelf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun handleUnlock(usbManager: UsbManager, device: UsbDevice?, intent: Intent?) {
        if (device == null) {
            AppLogger.w(TAG, "No target USB audio device available for unlock.")
            return
        }

        // Debounce: 1000ms minimum gap between same VID/PID events
        val now = System.currentTimeMillis()
        if (device.vendorId == lastVid && device.productId == lastPid && (now - lastUnlockTimeMs) < 1000L) {
            AppLogger.i(TAG, "Debounce: Skipping duplicate unlock for VID: ${device.vendorId}, PID: ${device.productId}")
            return
        }
        lastVid = device.vendorId
        lastPid = device.productId
        lastUnlockTimeMs = now

        val specifiedDb = intent?.getIntExtra(EXTRA_TARGET_DB, Integer.MIN_VALUE)
        val targetVolumeDb = if (specifiedDb != null && specifiedDb != Integer.MIN_VALUE) {
            specifiedDb
        } else {
            repository.getTargetVolume(device.vendorId, device.productId)
        }

        // Settling guard: 200ms delay to allow the kernel snd-usb-audio driver handshake
        // to settle before claiming the USB interface for controlTransfer
        delay(200L)

        // Run USB unlock sequence with a strict 3000ms timeout
        val result = try {
            withTimeout(3000L) {
                DacVolumeEngine.unlockDac(usbManager, device, targetVolumeDb)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unlock operation timed out or failed: ${e.message}", e)
            UnlockResult(
                success = false,
                deviceName = device.productName ?: "USB Audio Device",
                vendorId = device.vendorId,
                productId = device.productId,
                appliedVolumeDb = targetVolumeDb,
                featureUnitId = null,
                message = "Timed out after 3000ms: ${e.message}"
            )
        }

        if (result.success) {
            repository.setDeviceUnlocked(device.vendorId, device.productId, true)
            val completedIntent = Intent(ACTION_UNLOCK_COMPLETED).apply {
                setPackage(packageName)
                putExtra(UsbManager.EXTRA_DEVICE, device)
                putExtra(EXTRA_TARGET_DB, targetVolumeDb)
            }
            sendBroadcast(completedIntent)
        } else {
            repository.setDeviceUnlocked(device.vendorId, device.productId, false)
        }

        // Update the SAME notification in place (ID 1001)
        val title = if (result.success) {
            if (result.appliedVolumeDb == 0) "Volume Unlocked — 0 dB Full Output" else "Volume Unlocked — ${result.appliedVolumeDb} dB"
        } else {
            "DAC Volume Unlock Failed"
        }

        val text = if (result.success) {
            result.deviceName
        } else {
            "${result.deviceName}: ${result.message}"
        }

        // Post the updated notification: stays in shade until swiped or tapped by user
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.wrick.DacVolumeFix.ui.MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val finalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_dac)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(COMPLETED_NOTIFICATION_ID, finalNotification)
        AppLogger.i(TAG, "Unlock outcome: ${result.message}")
    }

    private fun findConnectedAudioDevice(usbManager: UsbManager): UsbDevice? {
        val devices = usbManager.deviceList.values
        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    return device
                }
            }
        }
        return devices.firstOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DAC Volume Status",
                NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't beep or buzz during music playback
            ).apply {
                description = "Shows confirmation when USB DAC volume register is unlocked"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DacVolumeFix")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_dac)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        AppLogger.i(TAG, "UsbUnlockService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
