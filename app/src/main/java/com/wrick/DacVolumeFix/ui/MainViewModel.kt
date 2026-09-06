package com.wrick.DacVolumeFix.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import com.wrick.DacVolumeFix.data.DacRepository
import com.wrick.DacVolumeFix.service.UsbUnlockService
import com.wrick.DacVolumeFix.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = DacRepository(application)
    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _uiState = MutableStateFlow<DacUiState>(DacUiState.Idle)
    val uiState: StateFlow<DacUiState> = _uiState.asStateFlow()

    private val _isAutoApplyEnabled = MutableStateFlow(repository.isAutoApplyEnabled)
    val isAutoApplyEnabled: StateFlow<Boolean> = _isAutoApplyEnabled.asStateFlow()

    private val _liveVolumeDb = MutableStateFlow(0)
    val liveVolumeDb: StateFlow<Int> = _liveVolumeDb.asStateFlow()

    var currentDevice: UsbDevice? = null
        private set

    init {
        refreshDeviceState()
    }

    fun setAutoApply(enabled: Boolean) {
        repository.isAutoApplyEnabled = enabled
        _isAutoApplyEnabled.value = enabled
    }

    fun refreshDeviceState() {
        val devices = usbManager.deviceList.values
        var foundDevice: UsbDevice? = null

        // Check for USB audio class device
        for (dev in devices) {
            for (i in 0 until dev.interfaceCount) {
                if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    foundDevice = dev
                    break
                }
            }
            if (foundDevice != null) break
        }

        currentDevice = foundDevice

        if (foundDevice != null) {
            val name = foundDevice.productName?.takeIf { it.isNotBlank() } ?: "USB Audio DAC"
            val vidHex = String.format("0x%04X", foundDevice.vendorId)
            val pidHex = String.format("0x%04X", foundDevice.productId)
            val targetDb = repository.getTargetVolume(foundDevice.vendorId, foundDevice.productId)
            _liveVolumeDb.value = targetDb

            val volFormatted = "$targetDb dB"
            val idsFormatted = "VID: $vidHex  •  PID: $pidHex"

            val isUnlocked = repository.isDeviceUnlocked(foundDevice.vendorId, foundDevice.productId)
            if (isUnlocked) {
                _uiState.value = DacUiState.UnlockSuccess(
                    deviceName = name,
                    volumeDb = targetDb,
                    volumeFormatted = volFormatted,
                    statusMessage = "Hardware volume unlocked at $volFormatted."
                )
            } else {
                _uiState.value = DacUiState.DacConnected(
                    deviceName = name,
                    vendorIdHex = vidHex,
                    productIdHex = pidHex,
                    targetVolumeDb = targetDb,
                    targetVolumeFormatted = volFormatted,
                    idsFormatted = idsFormatted
                )
            }
        } else {
            currentDevice = null
            _liveVolumeDb.value = 0
            _uiState.value = DacUiState.Idle
        }
    }

    fun setLiveVolume(volumeDb: Int) {
        val clamped = volumeDb.coerceIn(-80, 0)
        _liveVolumeDb.value = clamped
        currentDevice?.let { dev ->
            repository.setTargetVolume(dev.vendorId, dev.productId, clamped)
            val volFormatted = "$clamped dB"
            val state = _uiState.value
            if (state is DacUiState.DacConnected) {
                _uiState.value = state.copy(
                    targetVolumeDb = clamped,
                    targetVolumeFormatted = volFormatted
                )
            }
        }
    }

    fun triggerManualApply(onNeedPermission: (UsbDevice) -> Unit) {
        applyLiveVolume(_liveVolumeDb.value, onNeedPermission)
    }

    fun applyLiveVolume(volumeDb: Int = _liveVolumeDb.value, onNeedPermission: (UsbDevice) -> Unit) {
        val dev = currentDevice ?: return
        setLiveVolume(volumeDb)

        if (usbManager.hasPermission(dev)) {
            repository.setDeviceUnlocked(dev.vendorId, dev.productId, true)
            val volFormatted = "$volumeDb dB"
            AppLogger.i(TAG, "Starting UsbUnlockService manually for ${dev.productName} with target ${volumeDb}dB")
            UsbUnlockService.startForDevice(getApplication(), dev, volumeDb)
            _uiState.value = DacUiState.UnlockSuccess(
                deviceName = dev.productName ?: "USB Audio DAC",
                volumeDb = volumeDb,
                volumeFormatted = volFormatted,
                statusMessage = "Hardware volume register set to $volFormatted."
            )
        } else {
            AppLogger.i(TAG, "Requesting USB permission for ${dev.productName}")
            onNeedPermission(dev)
        }
    }

    fun onDeviceDetached() {
        currentDevice?.let { dev ->
            repository.setDeviceUnlocked(dev.vendorId, dev.productId, false)
        }
        refreshDeviceState()
    }
}
