package com.ljyh.mei.ui.screen.account

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun readPcQrImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val image = InputImage.fromFilePath(context, uri)
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    try {
        suspendCancellableCoroutine { continuation ->
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    if (continuation.isActive) {
                        val value = selectNeteaseLoginQr(codes.mapNotNull { it.rawValue })
                        continuation.resume(value)
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    } finally {
        scanner.close()
    }
}
