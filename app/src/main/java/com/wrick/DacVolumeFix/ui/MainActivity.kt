package com.wrick.DacVolumeFix.ui

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wrick.DacVolumeFix.service.UsbUnlockService
import com.wrick.DacVolumeFix.util.AppLogger

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.wrick.DacVolumeFix.USB_PERMISSION"
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private var hasNotificationPermission by mutableStateOf(true)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        AppLogger.i(TAG, "Notification permission result: $isGranted")
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLogger.i(TAG, "usbReceiver onReceive action: ${intent?.action}")
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbUnlockService.ACTION_UNLOCK_COMPLETED -> {
                    viewModel.refreshDeviceState()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.onDeviceDetached()
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(UsbUnlockService.ONGOING_NOTIFICATION_ID)
                    nm.cancel(UsbUnlockService.COMPLETED_NOTIFICATION_ID)
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AppLogger.i(TAG, "USB permission callback for ${device?.productName}: granted=$granted")
                    if (granted && device != null) {
                        UsbUnlockService.startForDevice(this@MainActivity, device)
                        viewModel.refreshDeviceState()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // System UI Integration: Edge to edge behind status and navigation bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false // Light status bar icons
        insetsController.isAppearanceLightNavigationBars = false // Transparent nav bar

        checkNotificationPermission()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbUnlockService.ACTION_UNLOCK_COMPLETED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            DacVolumeFixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val isAutoApplyEnabled by viewModel.isAutoApplyEnabled.collectAsStateWithLifecycle()
                    val liveVolumeDb by viewModel.liveVolumeDb.collectAsStateWithLifecycle()

                    NavHost(
                        navController = navController,
                        startDestination = "main"
                    ) {
                        composable(
                            route = "main",
                            enterTransition = {
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            },
                            exitTransition = {
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        ) {
                            val onRequestNotificationPermission = remember { { requestNotificationPermission() } }
                            val onAutoApplyChanged = remember { { enabled: Boolean -> viewModel.setAutoApply(enabled) } }
                            val onLiveVolumeChanged = remember { { volDb: Int -> viewModel.setLiveVolume(volDb) } }
                            val onApplyLiveVolume = remember {
                                {
                                    viewModel.applyLiveVolume { dev -> requestUsbPermission(dev) }
                                }
                            }
                            val onManualApply = remember {
                                {
                                    viewModel.triggerManualApply { dev -> requestUsbPermission(dev) }
                                }
                            }
                            val onOpenInfo = remember { { navController.navigate("info") } }

                            MainScreen(
                                uiState = uiState,
                                isAutoApplyEnabled = isAutoApplyEnabled,
                                liveVolumeDb = liveVolumeDb,
                                hasNotificationPermission = hasNotificationPermission,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onAutoApplyChanged = onAutoApplyChanged,
                                onLiveVolumeChanged = onLiveVolumeChanged,
                                onApplyLiveVolume = onApplyLiveVolume,
                                onManualApply = onManualApply,
                                onOpenInfo = onOpenInfo
                            )
                        }

                        composable(
                            route = "info",
                            enterTransition = {
                                slideIntoContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            },
                            exitTransition = {
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        ) {
                            val onNavigateBack = remember { { navController.popBackStack(); Unit } }
                            InfoScreen(
                                onNavigateBack = onNavigateBack
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun checkNotificationPermission() {
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        viewModel.refreshDeviceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }
}
