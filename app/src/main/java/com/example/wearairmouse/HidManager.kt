package com.example.wearairmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер Bluetooth HID устройства для эмуляции мыши.
 * Реализует полный цикл: регистрация, подключение, отправка отчётов.
 */
class HidManager(private val context: Context) {

    companion object {
        private const val TAG = "WearAirMouse"
        
        // HID Report Descriptor для мыши (3 кнопки, X, Y, колесо)
        // Соответствует USB HID стандарту для мыши
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),        // Usage Page (Desktop)
            0x09.toByte(), 0x02.toByte(),        // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
            0x85.toByte(), 0x01.toByte(),        //   Report ID (1)
            0x09.toByte(), 0x01.toByte(),        //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(),        //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),        //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(),        //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(),        //     Report Size (1)
            0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
            0x81.toByte(), 0x02.toByte(),        //     Input (Data, Var, Abs)
            0x75.toByte(), 0x05.toByte(),        //     Report Size (5)
            0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
            0x81.toByte(), 0x01.toByte(),        //     Input (Const)
            0x05.toByte(), 0x01.toByte(),        //     Usage Page (Desktop)
            0x09.toByte(), 0x30.toByte(),        //     Usage (X)
            0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
            0x09.toByte(), 0x38.toByte(),        //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
            0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
            0x81.toByte(), 0x06.toByte(),        //     Input (Data, Var, Rel)
            0xC0.toByte(),                       //   End Collection
            0xC0.toByte()                        // End Collection
        )

        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_SIZE = 5 // 1 байт кнопки + 4 бита padding + 3 байта (X, Y, wheel)
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: android.bluetooth.BluetoothDevice? = null
    
    private val isConnected = AtomicBoolean(false)
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Callbacks
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: android.bluetooth.BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "HID Device registered: $registered")
            if (!registered) {
                isConnected.set(false)
                connectedDevice = null
                handler.post { onConnectionStateChanged?.invoke(false) }
            }
        }

        override fun onConnectionStateChanged(device: android.bluetooth.BluetoothDevice?, state: Int) {
            Log.d(TAG, "Connection state changed: ${device?.address} -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected.set(true)
                    connectedDevice = device
                    handler.post { 
                        onConnectionStateChanged?.invoke(true)
                        Log.i(TAG, "HID Mouse connected!")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected.set(false)
                    connectedDevice = null
                    handler.post { 
                        onConnectionStateChanged?.invoke(false)
                        Log.i(TAG, "HID Mouse disconnected")
                    }
                }
            }
        }

        override fun onTouchReport(device: android.bluetooth.BluetoothDevice?, report: ByteArray?) {
            Log.d(TAG, "Touch report received: ${report?.contentToString()}")
        }
    }

    /**
     * Инициализация HID устройства
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return false
        }

        val qosParams = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            65000,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val sdpRecord = BluetoothHidDeviceAppSdpSettings(
            "AirMouse Wear OS",
            "Wear OS Air Mouse HID Device",
            "Example Inc.",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_REPORT_DESCRIPTOR
        )

        return try {
            val registered = bluetoothAdapter?.profileProxy(
                context,
                hidDeviceCallback,
                BluetoothProfile.HID_DEVICE
            ) ?: false
            
            if (registered) {
                Log.i(TAG, "HID Device registration initiated")
            } else {
                Log.e(TAG, "Failed to initiate HID Device registration")
            }
            registered
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HID device", e)
            handler.post { onError?.invoke(e.message ?: "Unknown error") }
            false
        }
    }

    /**
     * Получение экземпляра BluetoothHidDevice через callback
     */
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                Log.d(TAG, "HID Device service connected")
                
                val qosParams = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800,
                    9,
                    0,
                    65000,
                    BluetoothHidDeviceAppQosSettings.MAX
                )

                val sdpRecord = BluetoothHidDeviceAppSdpSettings(
                    "AirMouse Wear OS",
                    "Wear OS Air Mouse HID Device",
                    "Example Inc.",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HID_REPORT_DESCRIPTOR
                )

                val registered = hidDevice?.registerApp(sdpRecord, qosParams, null, hidDeviceCallback)
                Log.d(TAG, "registerApp result: $registered")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                Log.d(TAG, "HID Device service disconnected")
            }
        }
    }

    /**
     * Альтернативная инициализация через profileProxy
     */
    @SuppressLint("MissingPermission")
    fun initializeWithProxy(): Boolean {
        if (bluetoothAdapter == null) {
            return false
        }

        try {
            bluetoothAdapter?.getProfileProxy(
                context,
                serviceListener,
                BluetoothProfile.HID_DEVICE
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in initializeWithProxy", e)
            return false
        }
    }

    /**
     * Отправка отчёта мыши
     * @param buttons битовая маска кнопок (bit 0: ЛКМ, bit 1: ПКМ, bit 2: Средняя)
     * @param x смещение по X (-127..127)
     * @param y смещение по Y (-127..127)
     * @param wheel смещение колеса (-127..127)
     */
    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttons: Int = 0, x: Int = 0, y: Int = 0, wheel: Int = 0): Boolean {
        if (!isConnected.get()) {
            return false
        }

        val device = connectedDevice ?: return false
        val hidDev = hidDevice ?: return false

        try {
            // Формат отчёта: [Report ID, Buttons, X, Y, Wheel]
            val report = ByteArray(REPORT_SIZE + 1)
            report[0] = REPORT_ID_MOUSE.toByte()
            report[1] = (buttons and 0x07).toByte() // 3 бита для кнопок
            report[2] = x.coerceIn(-127, 127).toByte()
            report[3] = y.coerceIn(-127, 127).toByte()
            report[4] = wheel.coerceIn(-127, 127).toByte()

            val sent = hidDev.sendReport(device, REPORT_ID_MOUSE, report)
            
            if (!sent) {
                Log.w(TAG, "Failed to send report")
            }
            
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending mouse report", e)
            return false
        }
    }

    /**
     * Проверка состояния подключения
     */
    fun isDeviceConnected(): Boolean = isConnected.get()

    /**
     * Очистка ресурсов
     */
    @SuppressLint("MissingPermission")
    fun cleanup() {
        try {
            hidDevice?.let { device ->
                connectedDevice?.let { btDevice ->
                    if (isConnected.get()) {
                        device.disconnect(btDevice)
                    }
                }
                // unregisterApp removed in API 33+, use close instead
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        
        hidDevice = null
        connectedDevice = null
        isConnected.set(false)
    }

    /**
     * Запрос на подключение (для совместимости)
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        return try {
            hidDevice?.connect(device) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            false
        }
    }
}
