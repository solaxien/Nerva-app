package com.amg.nerva

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

// ---- Global Dark Theme Colors ----
private val DarkBg = Color(0xFF0A0D16)
private val DarkCard = Color(0xFF121726)
private val SoftText = Color(0xFF9FA8DA)
private val LightText = Color(0xFFE0E0E0)

// Solid premium header color (Nerva bar)
private val NervaHeaderColor = Color(0xFF151823)

// BLE UUIDs (must match ESP32)
private val SERVICE_UUID: UUID =
    UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
private val HRV_CHAR_UUID: UUID =
    UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
private val EDA_CHAR_UUID: UUID =
    UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
private val STATE_CHAR_UUID: UUID =
    UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")

private val CCC_DESCRIPTOR_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ---------- STRESS STATE & UI STATE ----------

enum class StressState { UNKNOWN, CALM, STRESS, AMUSEMENT }

data class BleUiState(
    val isConnected: Boolean = false,
    val statusText: String = "Device not connected",
    val hrv: Float = 0f,
    val eda: Float = 0f,
    val stressState: StressState = StressState.UNKNOWN,
    val hrvHistory: List<Float> = emptyList(),
    val edaHistory: List<Float> = emptyList(),
    val lastStateCode: Int = -1   // raw state byte (0/1/2/...)
)

// ---------- MAIN ACTIVITY ----------

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: NervaBleManager

    private val blePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // If user grants, they can press Scan & Connect again
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge, transparent nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
            isAppearanceLightStatusBars = false
        }

        bleManager = NervaBleManager(this)

        setContent {
            val uiState by bleManager.uiState.collectAsState()

            NervaApp(
                uiState = uiState,
                onScanAndConnect = {
                    if (ensureBlePermissions()) {
                        bleManager.startScanAndConnect()
                    }
                }
            )
        }
    }

    private fun ensureBlePermissions(): Boolean {
        val needed = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return if (needed.isNotEmpty()) {
            blePermissionLauncher.launch(needed.toTypedArray())
            false
        } else {
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
    }
}

// ---------- COMPOSE ROOT ----------

@Composable
fun NervaApp(
    uiState: BleUiState,
    onScanAndConnect: () -> Unit
) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg),
            color = DarkBg
        ) {
            NervaScreen(
                uiState = uiState,
                onScanAndConnect = onScanAndConnect
            )
        }
    }
}

@Composable
fun NervaScreen(
    uiState: BleUiState,
    onScanAndConnect: () -> Unit
) {
    val scrollState = rememberScrollState()
    val collapseFraction = min(scrollState.value / 220f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Spacer so first card appears below header
            Spacer(modifier = Modifier.height(140.dp))

            DeviceConnectionCard(
                isConnected = uiState.isConnected,
                statusText = uiState.statusText,
                onScanAndConnect = onScanAndConnect
            )

            Spacer(modifier = Modifier.height(16.dp))

            val stateLabel = when (uiState.stressState) {
                StressState.UNKNOWN   -> "Waiting..."
                StressState.CALM      -> "Calm"
                StressState.STRESS    -> "Stressed"
                StressState.AMUSEMENT -> "Amused"
            }

            val stateDescription = when (uiState.stressState) {
                StressState.UNKNOWN ->
                    "Waiting for first analysis window from the device..."
                StressState.CALM ->
                    "Your physiological signals are stable and within baseline."
                StressState.STRESS ->
                    "Elevated arousal detected. HRV and EDA patterns indicate stress."
                StressState.AMUSEMENT ->
                    "Increased activation with positive pattern – amusement detected."
            }

            RealTimeAnalysisCard(
                stateLabel = stateLabel,
                stateDescription = stateDescription,
                stateType = uiState.stressState,
                rawStateCode = uiState.lastStateCode
            )

            Spacer(modifier = Modifier.height(16.dp))

            HRVCard(
                currentHrv = uiState.hrv,
                history = uiState.hrvHistory.ifEmpty { dummyFlatList() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            EDACard(
                currentEda = uiState.eda,
                history = uiState.edaHistory.ifEmpty { dummyFlatList() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SystemInfoCard()

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Fixed header on top
        NervaHeader(
            modifier = Modifier.align(Alignment.TopCenter),
            collapseFraction = collapseFraction
        )
    }
}

// ---------- HEADER (SOLID, ALWAYS SPACED FROM STATUS BAR) ----------

@Composable
fun NervaHeader(
    modifier: Modifier = Modifier,
    collapseFraction: Float
) {
    val t = collapseFraction.coerceIn(0f, 1f)

    val titleSize = (30f - 4f * t).sp   // 30 -> 26
    val infoAlpha = 1f - t

    val topPadding = 40.dp              // fixed, so it never hugs status bar
    val bottomPadding = (18f - 6f * t).dp

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        color = NervaHeaderColor,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = topPadding,
                    bottom = bottomPadding
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Nerva",
                color = Color.White,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold
            )
            if (infoAlpha > 0.05f) {
                Text(
                    text = "Real-time physiological stress detection system",
                    color = SoftText.copy(alpha = infoAlpha),
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ---------- DEVICE CONNECTION CARD ----------

@Composable
fun DeviceConnectionCard(
    isConnected: Boolean,
    statusText: String,
    onScanAndConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Device Connection",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = if (isConnected) "Connected" else "Disconnected",
                        color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF7043),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = statusText,
                        color = SoftText,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onScanAndConnect
                ) {
                    Text(text = if (isConnected) "Reconnect" else "Scan & Connect")
                }
            }
        }
    }
}

// ---------- REAL-TIME ANALYSIS CARD ----------

@Composable
fun RealTimeAnalysisCard(
    stateLabel: String,
    stateDescription: String,
    stateType: StressState,
    rawStateCode: Int
) {
    val accent = when (stateType) {
        StressState.UNKNOWN   -> Color(0xFF90CAF9)   // neutral soft blue
        StressState.CALM      -> Color(0xFF81C784)
        StressState.STRESS    -> Color(0xFFFF8A65)
        StressState.AMUSEMENT -> Color(0xFF64B5F6)
    }
    val bgTint = accent.copy(alpha = 0.12f)

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Real-time Analysis",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = bgTint),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 14.dp, horizontal = 10.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stateLabel,
                        color = accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Current State Detection",
                        color = LightText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stateDescription,
                        color = SoftText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ---------- SYSTEM INFO CARD ----------

@Composable
fun SystemInfoCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "System Information",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            InfoRow("Protocol", "Bluetooth Low Energy (BLE)")
            InfoRow("Device", "ESP32 Dev Board")
            InfoRow("Sensor Array", "ECG + EDA")
            InfoRow("Sample Rate", "700 Hz")
            InfoRow("Analysis Window", "10 seconds (ECG + EDA)")
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = SoftText, fontSize = 13.sp)
        Text(value, color = LightText, fontSize = 13.sp)
    }
}

// ---------- HRV & EDA CARDS + CHART ----------

@Composable
fun HRVCard(
    currentHrv: Float,
    history: List<Float>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Heart Rate Variability (HRV)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "ms",
                    color = SoftText,
                    fontSize = 12.sp
                )
            }

            LineChart(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                points = history,
                lineColor = Color(0xFF4DD0E1)
            )

            Text(
                text = "Current value: ${"%.1f".format(currentHrv)} ms",
                color = LightText,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun EDACard(
    currentEda: Float,
    history: List<Float>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Electrodermal Activity (EDA)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "µS",
                    color = SoftText,
                    fontSize = 12.sp
                )
            }

            LineChart(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                points = history,
                lineColor = Color(0xFFFFD54F)
            )

            Text(
                text = "Current value: ${"%.2f".format(currentEda)}",
                color = LightText,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun LineChart(
    modifier: Modifier = Modifier,
    points: List<Float>,
    lineColor: Color
) {
    val safePoints = remember(points) {
        if (points.isEmpty()) listOf(0f, 0f, 0f) else points
    }

    Canvas(modifier = modifier) {
        if (safePoints.size < 2) return@Canvas

        val max = safePoints.maxOrNull() ?: 1f
        val min = safePoints.minOrNull() ?: 0f
        val range = (max - min).takeIf { it != 0f } ?: 1f

        val stepX = size.width / (safePoints.size - 1)

        val path = Path()

        safePoints.forEachIndexed { index, value ->
            val x = index * stepX
            val normY = (value - min) / range
            val y = size.height - (normY * size.height)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

// Dummy flat list for now; till live data arrives
@Composable
fun dummyFlatList(): List<Float> = remember {
    List(30) { 0f }
}

// ---------- BLE MANAGER ----------

@SuppressLint("MissingPermission")
class NervaBleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var isScanning = false

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    // 👉 Descriptor write queue (important!)
    private val descriptorQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()

    fun startScanAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update {
                it.copy(statusText = "Bluetooth is off. Please enable Bluetooth.")
            }
            return
        }

        if (isScanning) {
            _uiState.update { it.copy(statusText = "Already scanning...") }
            return
        }

        _uiState.update { it.copy(statusText = "Scanning for Nerva device...") }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        isScanning = true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device ?: return

            if (isScanning) {
                scanner?.stopScan(this)
                isScanning = false
            }

            _uiState.update {
                it.copy(statusText = "Connecting to ${device.address}...")
            }

            gatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            _uiState.update {
                it.copy(statusText = "Scan failed: $errorCode")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _uiState.update {
                    it.copy(
                        isConnected = true,
                        statusText = "Connected. Discovering services..."
                    )
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        statusText = "Disconnected from device"
                    )
                }
                cleanup()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (service == null) {
                _uiState.update {
                    it.copy(statusText = "Service not found on device")
                }
                return
            }

            val hrvChar = service.getCharacteristic(HRV_CHAR_UUID)
            val edaChar = service.getCharacteristic(EDA_CHAR_UUID)
            val stateChar = service.getCharacteristic(STATE_CHAR_UUID)

            enableNotifications(gatt, hrvChar)
            enableNotifications(gatt, edaChar)
            enableNotifications(gatt, stateChar)

            _uiState.update {
                it.copy(statusText = "Streaming HRV, EDA & state...")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // Pop the completed descriptor and write the next one, if any
            if (descriptorQueue.isNotEmpty() && descriptorQueue.first() == descriptor) {
                descriptorQueue.removeFirst()
                if (descriptorQueue.isNotEmpty()) {
                    gatt.writeDescriptor(descriptorQueue.first())
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val raw = characteristic.value ?: return

            when (characteristic.uuid) {
                HRV_CHAR_UUID -> {
                    if (raw.size < 4) return
                    val value = ByteBuffer
                        .wrap(raw)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float

                    _uiState.update { old ->
                        val newHistory = (old.hrvHistory + value).takeLast(60)
                        old.copy(
                            hrv = value,
                            hrvHistory = newHistory
                        )
                    }
                }

                EDA_CHAR_UUID -> {
                    if (raw.size < 4) return
                    val value = ByteBuffer
                        .wrap(raw)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float

                    _uiState.update { old ->
                        val newHistory = (old.edaHistory + value).takeLast(60)
                        old.copy(
                            eda = value,
                            edaHistory = newHistory
                        )
                    }
                }

                STATE_CHAR_UUID -> {
                    if (raw.isEmpty()) return

                    // First byte from ESP32
                    val stateCode = raw[0].toInt() and 0xFF

                    // Map:
                    // 0 = CALM / BASELINE
                    // 1 = STRESS
                    // 2 = AMUSEMENT
                    val mappedState = when (stateCode) {
                        0 -> StressState.CALM
                        1 -> StressState.STRESS
                        2 -> StressState.AMUSEMENT
                        else -> StressState.UNKNOWN
                    }

                    _uiState.update { old ->
                        old.copy(
                            stressState = mappedState,
                            lastStateCode = stateCode,
                            statusText = "Last state: $stateCode → $mappedState"
                        )
                    }
                }
            }
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?
    ) {
        if (characteristic == null) return

        gatt.setCharacteristicNotification(characteristic, true)
        val ccc = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return

        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        descriptorQueue.add(ccc)
        // If nothing else is being written, start with this one
        if (descriptorQueue.size == 1) {
            gatt.writeDescriptor(ccc)
        }
    }

    fun cleanup() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
        descriptorQueue.clear()
        gatt?.close()
        gatt = null
    }
}
