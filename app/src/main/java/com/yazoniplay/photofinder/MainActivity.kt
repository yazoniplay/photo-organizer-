package com.yazoniplay.photofinder

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF171A18)
private val Panel = Color(0xFF222723)
private val Mint = Color(0xFFB9F3D0)
private val Muted = Color(0xFFADB8B0)

private data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val recognizedText: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoFinderApp() }
    }
}

@Composable
private fun PhotoFinderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var loading by remember { mutableStateOf(false) }
    var indexing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your photos stay on this device.") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) status = "Photo access is needed to show your gallery."
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loading = true
            photos = withContext(Dispatchers.IO) { loadPhotos(context) }
            loading = false
            status = if (photos.isEmpty()) "No photos found in the accessible gallery."
            else "${photos.size} photos ready. Search names or index screenshot text."
        }
    }

    val visiblePhotos = remember(photos, query) {
        val term = query.trim()
        if (term.isEmpty()) photos
        else photos.filter {
            it.name.contains(term, ignoreCase = true) ||
                it.recognizedText.contains(term, ignoreCase = true)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink,
        surface = Panel,
        primary = Mint,
        onPrimary = Ink,
        onBackground = Color(0xFFF3F6F3),
        onSurface = Color(0xFFF3F6F3)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                            .background(Mint),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.ImageSearch,
                            contentDescription = null,
                            tint = Ink,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("PHOTO FINDER", style = MaterialTheme.typography.labelMedium, color = Mint)
                        Text(
                            "Find the one.",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "On-device privacy",
                        tint = Mint
                    )
                }

                Text(
                    "Search your gallery by filename or by words inside screenshots.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Try “server IP” or “receipt”…") },
                    leadingIcon = {
                        androidx.compose.material3.Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(18.dp)
                )

                if (!hasPermission) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                            .background(Panel).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Your gallery, on your terms", fontWeight = FontWeight.SemiBold)
                        Text("Photo Finder needs photo access to show images and search screenshot text. Images are not uploaded.", color = Muted)
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                                    else Manifest.permission.READ_EXTERNAL_STORAGE
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint)
                        ) { Text("Allow photo access") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("YOUR LIBRARY", color = Mint, style = MaterialTheme.typography.labelMedium)
                            Text("${visiblePhotos.size} results", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            enabled = !indexing && !loading && photos.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    indexing = true
                                    status = "Reading text from screenshots on your phone…"
                                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    val candidates = photos.filter { item ->
                                        val filename = item.name.lowercase()
                                        filename.contains("screenshot") ||
                                            filename.contains("screen_shot") ||
                                            filename.contains("screen shot")
                                    }.take(250)
                                    val indexed = withContext(Dispatchers.IO) {
                                        candidates.map { item ->
                                            try {
                                                val image = InputImage.fromFilePath(context, item.uri)
                                                val result = recognizer.process(image).await()
                                                item.copy(recognizedText = result.text)
                                            } catch (_: Exception) {
                                                item
                                            }
                                        }
                                    }
                                    recognizer.close()
                                    val byId = indexed.associateBy { it.id }
                                    photos = photos.map { byId[it.id] ?: it }
                                    indexing = false
                                    status = if (candidates.isEmpty()) {
                                        "No screenshots were recognized by filename. Try searching by filename instead."
                                    } else {
                                        "Indexed text in ${indexed.count { it.recognizedText.isNotBlank() }} of ${candidates.size} screenshots. Search above."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            if (indexing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(17.dp),
                                    strokeWidth = 2.dp,
                                    color = Ink
                                )
                            } else {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.TextSnippet,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Text(
                                if (indexing) " Indexing" else " Index screenshots",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }

                    Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)

                    when {
                        loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Mint)
                        }
                        visiblePhotos.isEmpty() -> Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.ImageSearch,
                                    contentDescription = null,
                                    tint = Muted,
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("Nothing found", fontWeight = FontWeight.SemiBold)
                                Text("Try another search or index screenshot text.", color = Muted)
                            }
                        }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 104.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(items = visiblePhotos, key = { it.id }) { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = item.name,
                                        modifier = Modifier.fillMaxWidth().height(124.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Panel),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        item.name,
                                        color = Color(0xFFE5EBE6),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        "  Private by design · No cloud uploads",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun loadPhotos(context: android.content.Context): List<PhotoItem> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED
    )
    val results = mutableListOf<PhotoItem>()
    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn) ?: "Image"
            val dateAdded = cursor.getLong(dateColumn)
            results += PhotoItem(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                name = name,
                dateAdded = dateAdded
            )
        }
    }
    return results
}
