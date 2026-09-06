package com.wrick.DacVolumeFix.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.wrick.DacVolumeFix.util.AppLogger

data class UnlockResult(
    val success: Boolean,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val appliedVolumeDb: Int,
    val featureUnitId: Int?,
    val message: String
)

object DacVolumeEngine {
    private const val TAG = "DacVolumeEngine"

    // USB Audio Class (UAC) Request Types:
    // 0x21: Host-to-Device (0x00) | Class-Specific (0x20) | Interface Recipient (0x01)
    private const val REQUEST_TYPE_SET_INTERFACE = 0x21
    // 0x20: Host-to-Device (0x00) | Class-Specific (0x20) | Device Recipient (0x00)
    // Device-targeted control transfers bypass interface-claim checks in the Linux kernel usbfs!
    private const val REQUEST_TYPE_SET_DEVICE = 0x20

    // 0xA1: Device-to-Host (0x80) | Class-Specific (0x20) | Interface Recipient (0x01)
    private const val REQUEST_TYPE_GET_INTERFACE = 0xA1
    // 0xA0: Device-to-Host (0x80) | Class-Specific (0x20) | Device Recipient (0x00)
    private const val REQUEST_TYPE_GET_DEVICE = 0xA0

    private const val REQUEST_CUR = 0x01
    private const val FU_VOLUME_CONTROL = 0x02

    private const val TIMEOUT_MS = 1500

    /**
     * Converts a volume in decibels (e.g. 0 dB, -10 dB, -80 dB)
     * to UAC 16-bit 8.8 fixed-point representation (little-endian byte array).
     * 0 dB -> 0x0000 -> [0x00, 0x00] (unity gain / full unattenuated volume)
     * -10 dB -> -2560 -> [0x00, 0xF6]
     * -80 dB -> -20480 -> [0x00, 0xB0]
     */
    fun dbToUacVolumeBytes(volumeDb: Int): ByteArray {
        val clampedDb = volumeDb.coerceIn(-80, 0)
        val fixedPointValue = (clampedDb * 256).toShort()
        val lowByte = (fixedPointValue.toInt() and 0xFF).toByte()
        val highByte = ((fixedPointValue.toInt() shr 8) and 0xFF).toByte()
        return byteArrayOf(lowByte, highByte)
    }

    /**
     * Unlocks the given USB DAC using pure Android framework APIs.
     * ZERO native C++, ZERO libusb dependencies.
     * Uses a multi-strategy approach to ensure the kernel audio driver (snd-usb-audio)
     * is NEVER permanently detached, completely preventing the mute/silence bug.
     */
    fun unlockDac(
        usbManager: UsbManager,
        device: UsbDevice,
        targetVolumeDb: Int = 0
    ): UnlockResult {
        val deviceName = device.productName ?: device.deviceName
        val vid = device.vendorId
        val pid = device.productId

        AppLogger.i(TAG, "Starting pure framework DAC unlock for $deviceName [VID: 0x${vid.toString(16)}, PID: 0x${pid.toString(16)}] to $targetVolumeDb dB")

        if (!usbManager.hasPermission(device)) {
            AppLogger.w(TAG, "USB permission not granted for $deviceName")
            return UnlockResult(
                success = false,
                deviceName = deviceName,
                vendorId = vid,
                productId = pid,
                appliedVolumeDb = targetVolumeDb,
                featureUnitId = null,
                message = "USB permission not granted. Please approve USB access permission."
            )
        }

        val connection: UsbDeviceConnection? = try {
            usbManager.openDevice(device)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open USB device: ${e.message}", e)
            null
        }

        if (connection == null) {
            return UnlockResult(
                success = false,
                deviceName = deviceName,
                vendorId = vid,
                productId = pid,
                appliedVolumeDb = targetVolumeDb,
                featureUnitId = null,
                message = "Could not open USB connection."
            )
        }

        val volumeBytes = dbToUacVolumeBytes(targetVolumeDb)

        try {
            // Locate strictly the AudioControl interface (Class 1, Subclass 1)
            var controlIntf: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    intf.interfaceSubclass == 0x01) { // AUDIO_CONTROL
                    controlIntf = intf
                    break
                }
            }

            if (controlIntf == null && device.interfaceCount > 0) {
                controlIntf = device.getInterface(0)
            }

            val controlIntfId = controlIntf?.id ?: 0
            AppLogger.i(TAG, "Targeting AudioControl Interface #$controlIntfId")

            // Parse Feature Units dynamically from descriptors or fallback to 0x02 for Apple DAC
            val rawDescriptors = connection.rawDescriptors
            val parsedFeatureUnits = UsbDescriptorParser.findAudioFeatureUnits(rawDescriptors)
            AppLogger.i(TAG, "Parsed ${parsedFeatureUnits.size} Audio Feature Unit(s) from descriptors")

            val candidateUnitIds = mutableListOf<Int>()
            candidateUnitIds.addAll(parsedFeatureUnits.map { it.unitId })
            if (!candidateUnitIds.contains(0x02)) candidateUnitIds.add(0x02) // Apple DAC Feature Unit 2
            candidateUnitIds.addAll(listOf(0x05, 0x07, 0x06, 0x09, 0x0A, 0x01))
            val distinctUnits = candidateUnitIds.distinct()

            // -------------------------------------------------------------
            // STRATEGY 1: Direct SET_CUR (0x21) WITHOUT claiming interface.
            // When interface is not claimed, snd-usb-audio kernel driver is
            // untouched and audio output CANNOT MUTE.
            // -------------------------------------------------------------
            AppLogger.i(TAG, "--- Strategy 1: Attempting 0x21 without claiming interface ---")
            for (unitId in distinctUnits) {
                readCurrentVolume(connection, unitId, controlIntfId, REQUEST_TYPE_GET_INTERFACE)
                val success = sendVolumeControlTransfer(connection, unitId, controlIntfId, volumeBytes, REQUEST_TYPE_SET_INTERFACE)
                if (success) {
                    AppLogger.i(TAG, "[STRATEGY 1 SUCCESS] Volume unlocked cleanly on Unit $unitId via 0x21 without claim! Audio driver intact.")
                    return UnlockResult(
                        success = true,
                        deviceName = deviceName,
                        vendorId = vid,
                        productId = pid,
                        appliedVolumeDb = targetVolumeDb,
                        featureUnitId = unitId,
                        message = "Unlocked to $targetVolumeDb dB (Unit $unitId, Strategy 1)"
                    )
                }
            }

            // -------------------------------------------------------------
            // STRATEGY 2: SET_CUR with Device Recipient (0x20).
            // In Linux devio.c check_ctrlrecip(), requests with recipient=DEVICE
            // are unconditionally permitted without any interface claim check!
            // -------------------------------------------------------------
            AppLogger.i(TAG, "--- Strategy 2: Attempting 0x20 (Device recipient) without claim ---")
            for (unitId in distinctUnits) {
                readCurrentVolume(connection, unitId, controlIntfId, REQUEST_TYPE_GET_DEVICE)
                val success = sendVolumeControlTransfer(connection, unitId, controlIntfId, volumeBytes, REQUEST_TYPE_SET_DEVICE)
                if (success) {
                    AppLogger.i(TAG, "[STRATEGY 2 SUCCESS] Volume unlocked on Unit $unitId via 0x20 without claim! Audio driver intact.")
                    return UnlockResult(
                        success = true,
                        deviceName = deviceName,
                        vendorId = vid,
                        productId = pid,
                        appliedVolumeDb = targetVolumeDb,
                        featureUnitId = unitId,
                        message = "Unlocked to $targetVolumeDb dB (Unit $unitId, Strategy 2)"
                    )
                }
            }

            // -------------------------------------------------------------
            // STRATEGY 3: Non-destructive Claim (force = false).
            // Tries claiming without detaching the kernel driver.
            // -------------------------------------------------------------
            if (controlIntf != null) {
                AppLogger.i(TAG, "--- Strategy 3: Attempting non-force claimInterface(force=false) ---")
                var nonForceClaimed = false
                try {
                    nonForceClaimed = connection.claimInterface(controlIntf, false)
                    AppLogger.d(TAG, "claimInterface(force=false) result: $nonForceClaimed")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "claimInterface(force=false) exception: ${e.message}")
                }

                if (nonForceClaimed) {
                    try {
                        for (unitId in distinctUnits) {
                            val success = sendVolumeControlTransfer(connection, unitId, controlIntfId, volumeBytes, REQUEST_TYPE_SET_INTERFACE)
                            if (success) {
                                AppLogger.i(TAG, "[STRATEGY 3 SUCCESS] Volume unlocked on Unit $unitId with non-force claim!")
                                return UnlockResult(
                                    success = true,
                                    deviceName = deviceName,
                                    vendorId = vid,
                                    productId = pid,
                                    appliedVolumeDb = targetVolumeDb,
                                    featureUnitId = unitId,
                                    message = "Unlocked to $targetVolumeDb dB (Unit $unitId, Strategy 3)"
                                )
                            }
                        }
                    } finally {
                        try {
                            connection.releaseInterface(controlIntf)
                            AppLogger.d(TAG, "releaseInterface(force=false) executed")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "releaseInterface error: ${e.message}")
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // STRATEGY 4: Claim (force = true) + SET_CUR + USBDEVFS_RESET.
            // On Linux/Android, force=true detaches the kernel driver snd-usb-audio.
            // After transferring the volume bytes, we MUST issue USBDEVFS_RESET
            // (ioctl 21780) to force the Linux kernel to re-probe and re-bind
            // snd-usb-audio to Interface 0, 1, 2. This completely restores the ALSA
            // sound card and prevents audio muting.
            // -------------------------------------------------------------
            if (controlIntf != null) {
                AppLogger.i(TAG, "--- Strategy 4: Claim + SET_CUR + Driver Re-bind ---")
                var forceClaimed = false
                try {
                    forceClaimed = connection.claimInterface(controlIntf, true)
                    AppLogger.d(TAG, "claimInterface(force=true) result: $forceClaimed")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "claimInterface(force=true) exception: ${e.message}")
                }

                if (forceClaimed) {
                    var anySuccess = false
                    var successfulUnitId = 2
                    try {
                        for (unitId in distinctUnits) {
                            readCurrentVolume(connection, unitId, controlIntfId, REQUEST_TYPE_GET_INTERFACE)
                            val success = sendVolumeControlTransfer(connection, unitId, controlIntfId, volumeBytes, REQUEST_TYPE_SET_INTERFACE)
                            if (success) {
                                anySuccess = true
                                successfulUnitId = unitId
                                AppLogger.i(TAG, "[STRATEGY 4] Volume written on Unit $unitId.")
                                readCurrentVolume(connection, unitId, controlIntfId, REQUEST_TYPE_GET_INTERFACE)
                                break
                            }
                        }
                    } finally {
                        try {
                            connection.releaseInterface(controlIntf)
                            AppLogger.d(TAG, "releaseInterface(force=true) executed")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "releaseInterface error: ${e.message}")
                        }

                        // CRUCIAL: Re-bind snd-usb-audio kernel driver via USBDEVFS_RESET (21780)
                        // BEFORE closing the file descriptor!
                        val resetOk = resetUsbDevice(connection.fileDescriptor)
                        AppLogger.i(TAG, "Kernel driver re-bind USBDEVFS_RESET: resetOk=$resetOk")
                    }

                    if (anySuccess) {
                        return UnlockResult(
                            success = true,
                            deviceName = deviceName,
                            vendorId = vid,
                            productId = pid,
                            appliedVolumeDb = targetVolumeDb,
                            featureUnitId = successfulUnitId,
                            message = "Unlocked to $targetVolumeDb dB (Driver re-bound)"
                        )
                    }
                }
            }

            return UnlockResult(
                success = false,
                deviceName = deviceName,
                vendorId = vid,
                productId = pid,
                appliedVolumeDb = targetVolumeDb,
                featureUnitId = null,
                message = "All unlock strategies exhausted. DAC did not accept volume commands."
            )
        } finally {
            try {
                connection.close()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error closing UsbDeviceConnection: ${e.message}")
            }
        }
    }

    /**
     * Re-binds kernel snd-usb-audio driver by issuing USBDEVFS_RESET ioctl (21780)
     * using Android's internal android.system.Os.ioctlInt(FileDescriptor, int).
     * This restores the ALSA sound card pipeline without needing any native C++ libraries.
     */
    private fun resetUsbDevice(fdInt: Int): Boolean {
        return try {
            val fdObj = java.io.FileDescriptor()
            val descField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            descField.isAccessible = true
            descField.setInt(fdObj, fdInt)

            val osClass = Class.forName("android.system.Os")
            val ioctlMethod = osClass.getMethod(
                "ioctlInt",
                java.io.FileDescriptor::class.java,
                Int::class.javaPrimitiveType
            )
            val res = ioctlMethod.invoke(null, fdObj, 21780) as Int
            AppLogger.i(TAG, "USBDEVFS_RESET (ioctlInt 21780) returned: $res")
            res == 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "USBDEVFS_RESET failed: ${e.message}", e)
            false
        }
    }

    /**
     * Reads current hardware volume via UAC GET_CUR for diagnostic verification.
     */
    private fun readCurrentVolume(
        connection: UsbDeviceConnection,
        featureUnitId: Int,
        interfaceNumber: Int,
        requestType: Int = REQUEST_TYPE_GET_INTERFACE
    ) {
        try {
            val curBuffer = ByteArray(2)
            val index = (featureUnitId shl 8) or (interfaceNumber and 0xFF)
            val leftValue = (FU_VOLUME_CONTROL shl 8) or 0x01
            val res = connection.controlTransfer(
                requestType,
                REQUEST_CUR,
                leftValue,
                index,
                curBuffer,
                curBuffer.size,
                500
            )
            if (res >= 0) {
                val hex = String.format("%02X%02X", curBuffer[1], curBuffer[0])
                AppLogger.i(TAG, "Read GET_CUR hardware volume: 0x$hex on Feature Unit $featureUnitId (reqType=0x${requestType.toString(16)})")
            }
        } catch (e: Exception) {
            // Non-critical diagnostic read
        }
    }

    /**
     * Sends UAC SET_CUR volume control transfer to discrete Left and Right channels.
     * Apple USB-C Dongle requires discrete Channel 1 (Left: 0x0201) and Channel 2 (Right: 0x0202).
     */
    private fun sendVolumeControlTransfer(
        connection: UsbDeviceConnection,
        featureUnitId: Int,
        interfaceNumber: Int,
        volumeBytes: ByteArray,
        requestType: Int
    ): Boolean {
        var anySuccess = false
        val index = (featureUnitId shl 8) or (interfaceNumber and 0xFF)

        // Channel 1 (Left): 0x0201
        val leftValue = (FU_VOLUME_CONTROL shl 8) or 0x01
        val leftResult = connection.controlTransfer(
            requestType,
            REQUEST_CUR,
            leftValue,
            index,
            volumeBytes,
            volumeBytes.size,
            TIMEOUT_MS
        )
        AppLogger.d(TAG, "controlTransfer Left (0x0201, reqType=0x${requestType.toString(16)}) result: $leftResult")
        if (leftResult >= 0) {
            anySuccess = true
        }

        // Channel 2 (Right): 0x0202
        val rightValue = (FU_VOLUME_CONTROL shl 8) or 0x02
        val rightResult = connection.controlTransfer(
            requestType,
            REQUEST_CUR,
            rightValue,
            index,
            volumeBytes,
            volumeBytes.size,
            TIMEOUT_MS
        )
        AppLogger.d(TAG, "controlTransfer Right (0x0202, reqType=0x${requestType.toString(16)}) result: $rightResult")
        if (rightResult >= 0) {
            anySuccess = true
        }

        return anySuccess
    }
}
