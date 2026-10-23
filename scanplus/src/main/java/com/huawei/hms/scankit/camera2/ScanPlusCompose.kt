package com.huawei.hms.scankit.camera2

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.huawei.hms.ml.scan.HmsScan

/**
 * Modern Jetpack Compose wrapper for ScanPlus Camera2 barcode scanning.
 *
 * @param modifier Compose Modifier.
 * @param isTorchOn Flashlight toggle state.
 * @param onScanResult Callback invoked when one or more barcodes are recognized.
 */
@Composable
fun ScanPlusCamera2Preview(
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false,
    onScanResult: (Array<HmsScan>) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanView: Camera2ScanView? by remember { mutableStateOf(null) }

    // Synchronize torch status
    LaunchedEffect(isTorchOn) {
        if (isTorchOn) {
            scanView?.startScan()
        }
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
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            Camera2ScanView(context).apply {
                this.onScanResult = onScanResult
                scanView = this
                startScan()
            }
        },
        update = { view ->
            view.onScanResult = onScanResult
        }
    )
}
