package com.wrick.DacVolumeFix.usb

import com.wrick.DacVolumeFix.util.AppLogger

data class ParsedFeatureUnit(
    val unitId: Int,
    val interfaceNumber: Int,
    val isUac2: Boolean
)

object UsbDescriptorParser {
    private const val TAG = "UsbDescriptorParser"

    private const val DESC_TYPE_INTERFACE = 0x04
    private const val DESC_TYPE_CS_INTERFACE = 0x24

    private const val USB_CLASS_AUDIO = 0x01
    private const val USB_SUBCLASS_AUDIOCONTROL = 0x01

    private const val UAC_SUBTYPE_FEATURE_UNIT = 0x06

    /**
     * Parses the raw USB configuration descriptors to find Audio Control Feature Units.
     * Returns a list of all detected Feature Units with their corresponding interface numbers.
     */
    fun findAudioFeatureUnits(rawDescriptors: ByteArray?): List<ParsedFeatureUnit> {
        val results = mutableListOf<ParsedFeatureUnit>()
        if (rawDescriptors == null || rawDescriptors.isEmpty()) {
            AppLogger.w(TAG, "rawDescriptors is null or empty")
            return results
        }

        var offset = 0
        var currentInterfaceNumber = 0
        var isAudioControlInterface = false
        var isUac2 = false

        while (offset < rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xFF
            if (length < 2 || offset + length > rawDescriptors.size) {
                // Invalid or truncated descriptor
                break
            }

            val descType = rawDescriptors[offset + 1].toInt() and 0xFF

            if (descType == DESC_TYPE_INTERFACE && length >= 9) {
                currentInterfaceNumber = rawDescriptors[offset + 2].toInt() and 0xFF
                val intfClass = rawDescriptors[offset + 5].toInt() and 0xFF
                val intfSubClass = rawDescriptors[offset + 6].toInt() and 0xFF
                val intfProtocol = rawDescriptors[offset + 7].toInt() and 0xFF

                isAudioControlInterface = (intfClass == USB_CLASS_AUDIO && intfSubClass == USB_SUBCLASS_AUDIOCONTROL)
                isUac2 = (intfProtocol == 0x20)

                AppLogger.d(TAG, "Found Interface #$currentInterfaceNumber: class=$intfClass, subClass=$intfSubClass, protocol=$intfProtocol (AudioControl=$isAudioControlInterface, UAC2=$isUac2)")
            } else if (descType == DESC_TYPE_CS_INTERFACE && isAudioControlInterface && length >= 4) {
                val subtype = rawDescriptors[offset + 2].toInt() and 0xFF
                if (subtype == UAC_SUBTYPE_FEATURE_UNIT) {
                    val unitId = rawDescriptors[offset + 3].toInt() and 0xFF
                    AppLogger.i(TAG, "Found Feature Unit ID: $unitId on Interface #$currentInterfaceNumber (UAC2=$isUac2)")
                    results.add(
                        ParsedFeatureUnit(
                            unitId = unitId,
                            interfaceNumber = currentInterfaceNumber,
                            isUac2 = isUac2
                        )
                    )
                }
            }

            offset += length
        }

        return results
    }
}
