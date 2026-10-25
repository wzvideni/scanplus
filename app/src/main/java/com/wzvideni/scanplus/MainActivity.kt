package com.wzvideni.scanplus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.scankit.camera2.Camera2ScanView
import com.wzvideni.scanplus.ui.theme.ScanplusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanplusTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScanScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ScanScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission is required to scan", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var lastResult by remember { mutableStateOf<HmsScan?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            var scanView: Camera2ScanView? by remember { mutableStateOf(null) }
            val lifecycleOwner = LocalLifecycleOwner.current

            // Synchronize flashlight state
            LaunchedEffect(isTorchOn, scanView) {
                scanView?.setTorch(isTorchOn)
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> scanView?.startScan()
                        Lifecycle.Event.ON_PAUSE -> scanView?.stopScan()
                        Lifecycle.Event.ON_DESTROY -> scanView?.release()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    scanView?.release()
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Camera2ScanView(ctx).apply {
                        this.onScanResult = { results ->
                            val valid = results.firstOrNull { !it.originalValue.isNullOrEmpty() }
                            if (valid != null && lastResult == null) {
                                lastResult = valid
                            }
                        }
                        this.setTorch(isTorchOn)
                        scanView = this
                        startScan()
                    }
                },
                update = { view ->
                    view.onScanResult = { results ->
                        val valid = results.firstOrNull { !it.originalValue.isNullOrEmpty() }
                        if (valid != null && lastResult == null) {
                            lastResult = valid
                        }
                    }
                    view.setTorch(isTorchOn)
                }
            )

            // Flashlight Toggle Button
            FloatingActionButton(
                onClick = {
                    val nextState = !isTorchOn
                    isTorchOn = nextState
                    scanView?.setTorch(nextState)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
                containerColor = if (isTorchOn) MaterialTheme.colorScheme.primary else Color.DarkGray
            ) {
                Text(
                    text = if (isTorchOn) "🔦 ON" else "🔦 OFF",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // Scanned Result Overlay Card
            lastResult?.let { scan ->
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Barcode Scanned!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = scan.getOriginalValue() ?: "Unknown value",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { lastResult = null }) {
                            Text("Scan Again")
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required to use Camera2 ScanPlus.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}
