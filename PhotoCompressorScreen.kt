package com.scancode.myapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import androidx.exifinterface.media.ExifInterface


@Composable
fun PhotoCompressorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var compressedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var totalSizeKb by remember { mutableStateOf(0) }
    var isCompressing by remember { mutableStateOf(false) }
    var selectedLevel by remember { mutableStateOf("自動壓縮") }

    val compressionLevels = listOf("高品質", "中等壓縮", "極致壓縮", "自動壓縮")

    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            selectedUris = uris
        }

    val gmailLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            selectedUris = emptyList()
            compressedUris = emptyList()
            totalSizeKb = 0
        }
    fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    Column(Modifier.padding(16.dp)) {
        Text("圖片壓縮器", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) {
                Text("選擇圖片")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = {
                    isCompressing = true
                    scope.launch {
                        try {
                            val files = compressImagesToTargetSize(
                                context,
                                selectedUris,
                                context.cacheDir,
                                selectedLevel
                            )

                            compressedUris = files.map {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    it
                                )
                            }

                            compressedUris.forEach {
                                context.grantUriPermission(
                                    "com.google.android.gm",
                                    it,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }

                            totalSizeKb = files.sumOf { it.length().toInt() } / 1024
                            isCompressing = false

                            if (compressedUris.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "image/jpeg"
                                    putParcelableArrayListExtra(
                                        Intent.EXTRA_STREAM,
                                        ArrayList(compressedUris)
                                    )
                                    putExtra(Intent.EXTRA_SUBJECT, "壓縮後照片")
                                    putExtra(Intent.EXTRA_TEXT, "請見附件")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                gmailLauncher.launch(Intent.createChooser(intent, "寄送照片"))
                            }
                        } catch (e: Exception) {
                            isCompressing = false
                            Log.e("Compress", "壓縮流程錯誤：${e.message}", e)
                            Toast.makeText(context, "壓縮錯誤：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = selectedUris.isNotEmpty() && !isCompressing
            ) {
                Text("確認壓縮")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("壓縮等級：", modifier = Modifier.padding(end = 8.dp))
            DropdownMenuBox(
                options = compressionLevels,
                selected = selectedLevel,
                onSelectedChange = { selectedLevel = it }
            )
        }

        if (compressedUris.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("共 ${compressedUris.size} 張，總大小：$totalSizeKb KB")
        }

        if (isCompressing) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 預覽區
        if (selectedUris.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("預覽 (${selectedUris.size} 張)", style = MaterialTheme.typography.bodyLarge)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(selectedUris) { uri ->
                    val bitmap = remember(uri, selectedUris) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val bytes = inputStream?.readBytes()
                            inputStream?.close()
                            if (bytes != null) {
                                val exif = ExifInterface(bytes.inputStream())
                                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                val orientation = exif.getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL
                                )
                                when (orientation) {
                                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(rawBitmap, 90f)
                                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(rawBitmap, 180f)
                                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(rawBitmap, 270f)
                                    else -> rawBitmap
                                }
                            } else null
                        } catch (e: Exception) {
                            Log.e("Preview", "圖片預覽錯誤: ${e.message}")
                            null
                        }
                    }

                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(4.dp)
                                .aspectRatio(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DropdownMenuBox(options: List<String>, selected: String, onSelectedChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach {
                DropdownMenuItem(
                    text = { Text(it) },
                    onClick = {
                        onSelectedChange(it)
                        expanded = false
                    }
                )
            }
        }
    }
}
fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
suspend fun compressImagesToTargetSize(
    context: Context,
    uris: List<Uri>,
    cacheDir: File,
    level: String
): List<File> = withContext(Dispatchers.IO) {
    val baseQuality = when (level) {
        "高品質" -> 90
        "中等壓縮" -> 70
        "極致壓縮" -> 50
        else -> null
    }

    uris.mapNotNull { uri ->
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                val exif = ExifInterface(bytes.inputStream())

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

                val originalBitmap = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                    else -> bitmap
                }

                val maxWidth = 720
                val maxHeight = 720
                val ratio = minOf(
                    maxWidth.toFloat() / originalBitmap.width,
                    maxHeight.toFloat() / originalBitmap.height
                )
                val resized = Bitmap.createScaledBitmap(
                    originalBitmap,
                    (originalBitmap.width * ratio).toInt(),
                    (originalBitmap.height * ratio).toInt(),
                    true
                )

                val file = File(cacheDir, "IMG_${System.currentTimeMillis()}.jpg")

                if (baseQuality != null) {
                    FileOutputStream(file).use { out ->
                        resized.compress(Bitmap.CompressFormat.JPEG, baseQuality, out)
                    }
                } else {
                    // 自動壓縮：嘗試壓縮到接近 100KB
                    var q = 100
                    while (q >= 10) {
                        FileOutputStream(file).use { tempOut ->
                            resized.compress(Bitmap.CompressFormat.JPEG, q, tempOut)
                        }
                        if (file.length() <= 100 * 1024) break
                        q -= 5
                    }
                }

                file
            }
        } catch (e: Exception) {
            Log.e("AutoCompress", "錯誤: ${e.message}", e)
            null
        }
    }
}

