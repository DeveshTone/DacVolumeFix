package com.wrick.DacVolumeFix.ui

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import com.wrick.DacVolumeFix.data.DacRepository
import com.wrick.DacVolumeFix.service.UsbUnlockService
import com.wrick.DacVolumeFix.util.AppLogger

/**
 * Completely invisible trampoline activity that receives Android OS auto-launch
 * for USB_DEVICE_ATTACHED via device_filter.xml pre-grant, passes the USB device
 * to UsbUnlockService, and immediately terminates without disturbing the user's foreground app.
 */
class UsbTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "UsbTrampoline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "UsbTrampolineActivity triggered: $intent")

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent?.action) {
            val repository = DacRepository(this)
            if (repository.isAutoApplyEnabled) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                AppLogger.i(TAG, "Auto-apply enabled. Dispatching to UsbUnlockService for: ${device?.productName}")
                UsbUnlockService.startForDevice(this, device)
            } else {
                AppLogger.i(TAG, "Auto-apply disabled in preferences. Trampoline exiting without service trigger.")
            }
        }

        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
