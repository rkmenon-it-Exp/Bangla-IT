package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.location.LocationPermissionHelper
import com.example.model.ShopBindingUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCodeScannerDialog(
    bindingState: ShopBindingUiState,
    onQrCodeScanned: (String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var lastScannedText by remember { mutableStateOf<String?>(null) }
    val scanLock = remember { AtomicBoolean(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequestedOnce) {
            permissionRequestedOnce = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!bindingState.isLoading) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !bindingState.isLoading,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("qr_scanner_dialog")
        ) {
            if (hasCameraPermission) {
                CameraQrPreview(
                    lifecycleOwner = lifecycleOwner,
                    scanLock = scanLock,
                    onCameraReady = { cam ->
                        boundCamera = cam
                        cam.cameraControl.enableTorch(torchEnabled)
                    },
                    onQrDetected = { raw ->
                        lastScannedText = raw
                        onQrCodeScanned(raw)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Translucent scanner viewfinder mask
                QrViewfinderOverlay(
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Camera permission required card
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ElevatedCard(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.CameraAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Text(
                                text = "ক্যামেরা অনুমতি প্রয়োজন",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "অ্যাডমিন প্যানেলের শপ বাইন্ডিং QR কোড স্ক্যান করতে ক্যামেরার অনুমতি দিন।",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_request_camera_permission_button")
                            ) {
                                Text("ক্যামেরা অনুমতি দিন")
                            }
                            OutlinedButton(
                                onClick = {
                                    LocationPermissionHelper.openAppDetailsSettings(context)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("অ্যাপ সেটিংস খুলুন")
                            }
                        }
                    }
                }
            }

            // Top bar overlay (title, torch toggle, close)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "শপ QR কোড স্ক্যান করুন",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCameraPermission && boundCamera?.cameraInfo?.hasFlashUnit() == true) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            IconButton(
                                onClick = {
                                    torchEnabled = !torchEnabled
                                    boundCamera?.cameraControl?.enableTorch(torchEnabled)
                                },
                                modifier = Modifier.testTag("qr_toggle_flash_button")
                            ) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                    contentDescription = stringResource(R.string.cd_toggle_flash),
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        IconButton(
                            onClick = {
                                if (!bindingState.isLoading) onDismiss()
                            },
                            modifier = Modifier.testTag("qr_close_scanner_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_close_scanner),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Bottom instruction / loading / error panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (bindingState.isLoading) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bindingState.statusMessage ?: "শপের সাথে সংযোগ ও সিঙ্ক হচ্ছে...",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!lastScannedText.isNullOrBlank()) {
                                    Text(
                                        text = lastScannedText.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                } else if (!bindingState.errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp)
                            .testTag("qr_scanner_error_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = bindingState.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Button(
                                onClick = {
                                    onClearError()
                                    lastScannedText = null
                                    scanLock.set(false)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_rescan_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("আবার স্ক্যান করুন (Scan Again)")
                            }
                        }
                    }
                } else if (hasCameraPermission) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier.widthIn(max = 420.dp)
                    ) {
                        Text(
                            text = "অ্যাডমিন প্যানেলের স্টোর QR কোডটি ফ্রেমের ভেতর ধরুন — স্বয়ংক্রিয়ভাবে শপ বাইন্ড ও রিবুট হবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    scanLock: AtomicBoolean,
    onCameraReady: (Camera) -> Unit,
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(
                            cameraExecutor,
                            ZxingQrImageAnalyzer { decoded ->
                                if (scanLock.compareAndSet(false, true)) {
                                    mainExecutor.execute {
                                        onQrDetected(decoded)
                                    }
                                }
                            }
                        )
                    }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                onCameraReady(camera)
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                cameraProviderFuture.get().unbindAll()
            }
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun QrViewfinderOverlay(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val boxSide = (minOf(size.width, size.height) * 0.66f).coerceIn(220.dp.toPx(), 320.dp.toPx())
        val left = (size.width - boxSide) / 2f
        val top = (size.height - boxSide) / 2f
        val cornerPx = 22.dp.toPx()

        drawRect(color = Color.Black.copy(alpha = 0.56f))

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSide, boxSide),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            blendMode = BlendMode.Clear
        )

        drawRoundRect(
            color = accentColor,
            topLeft = Offset(left, top),
            size = Size(boxSide, boxSide),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

private class ZxingQrImageAnalyzer(
    private val onQrDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            val normalizedY = if (rowStride == width) {
                data
            } else {
                ByteArray(width * height).also { out ->
                    for (row in 0 until height) {
                        val srcOffset = row * rowStride
                        val copyLen = minOf(width, data.size - srcOffset)
                        if (copyLen > 0) {
                            System.arraycopy(data, srcOffset, out, row * width, copyLen)
                        }
                    }
                }
            }

            val decoded = tryDecode(normalizedY, width, height)
                ?: tryDecode(rotate90(normalizedY, width, height), height, width)

            if (!decoded.isNullOrBlank()) {
                onQrDecoded(decoded)
            }
        } catch (_: Exception) {
            // Frame did not contain a readable QR code
        } finally {
            image.close()
        }
    }

    private fun tryDecode(luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        return runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull() ?: runCatching {
            reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
        }.getOrNull().also {
            reader.reset()
        }
    }

    private fun rotate90(src: ByteArray, width: Int, height: Int): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until height) {
            val rowOffset = y * width
            val dstCol = height - 1 - y
            for (x in 0 until width) {
                dst[x * height + dstCol] = src[rowOffset + x]
            }
        }
        return dst
    }
}
