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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF101210)
private val SurfaceDark = Color(0xFF1B1E1B)
private val Accent = Color(0xFFC5E8B7)
private val TextMain = Color(0xFFF2F3EF)
private val TextMuted = Color(0xFFA4AAA1)

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
        // Keep app content below Android's status bar and above gesture navigation.
        window.statusBarColor = android.graphics.Color.rgb(16, 18, 16)
        window.navigationBarColor = android.graphics.Color.rgb(16, 18, 16)
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
    var status by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) status = "Allow photo access to show your gallery."
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loading = true
            photos = withContext(Dispatchers.IO) {
                val saved = context.getSharedPreferences("photo_finder_ocr", android.content.Context.MODE_PRIVATE)
                loadPhotos(context).map { photo ->
                    photo.copy(recognizedText = saved.getString(photo.id.toString(), "") ?: "")
                }
            }
            loading = false
        }
    }

    val visiblePhotos = remember(photos, query, selectedTab) {
        val term = query.trim()
        val filtered = if (term.isEmpty()) photos else photos.filter {
            it.name.contains(term, ignoreCase = true) ||
                it.recognizedText.contains(term, ignoreCase = true)
        }
        if (selectedTab == 1) filtered.distinctBy { it.dateAdded / 86400L } else filtered
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Background,
        surface = SurfaceDark,
        primary = Accent,
        onPrimary = Background,
        onBackground = TextMain,
        onSurface = TextMain
    )) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (selectedTab == 1) "Albums" else if (searchOpen) "Search" else "Photos",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )
                        Text(
                            if (hasPermission) "${photos.size} items on this device" else "Your personal gallery",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(SurfaceDark)
                            .clickable { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search photos", tint = TextMain)
                    }
                }

                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text("Search photos or screenshot text") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }

                if (hasPermission) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (query.isBlank()) "Recently added" else "${visiblePhotos.size} results",
                            modifier = Modifier.weight(1f),
                            color = TextMain,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(SurfaceDark)
                                .clickable(enabled = !indexing && !loading && photos.isNotEmpty()) {
                                    scope.launch {
                                        indexing = true
                                        status = "Reading screenshot text…"
                                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                        val candidates = photos.filter { item ->
                                            val n = item.name.lowercase()
                                            n.contains("screenshot") || n.contains("screen_shot") || n.contains("screen shot")
                                        }.take(250)
                                        val indexed = withContext(Dispatchers.IO) {
                                            candidates.map { item ->
                                                try {
                                                    val image = InputImage.fromFilePath(context, item.uri)
                                                    item.copy(recognizedText = recognizer.process(image).await().text)
                                                } catch (_: Exception) { item }
                                            }
                                        }
                                        recognizer.close()
                                        val byId = indexed.associateBy { it.id }
                                        val saved = context.getSharedPreferences("photo_finder_ocr", android.content.Context.MODE_PRIVATE)
                                        with(saved.edit()) {
                                            indexed.forEach { putString(it.id.toString(), it.recognizedText) }
                                            apply()
                                        }
                                        photos = photos.map { byId[it.id] ?: it }
                                        indexing = false
                                        status = "Indexed text in ${indexed.count { it.recognizedText.isNotBlank() }} of ${candidates.size} screenshots."
                                    }
                                }.padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (indexing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.TextSnippet, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                Text(if (indexing) "Scanning" else "Find text", color = Accent, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(status, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }

                when {
                    !hasPermission -> Box(Modifier.fillMaxSize().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(52.dp))
                            Text("Your photos, all in one place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Photo Finder searches locally. Your images are never uploaded.", color = TextMuted)
                            Button(onClick = {
                                permissionLauncher.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                            }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                Text("Allow access", color = Background)
                            }
                        }
                    }
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                    visiblePhotos.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Text(if (query.isBlank()) "No photos found" else "No matching photos", fontWeight = FontWeight.SemiBold)
                            Text(if (query.isBlank()) "Photos on your device will appear here." else "Try a different search.", color = TextMuted)
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(items = visiblePhotos, key = { it.id }) { item ->
                            AsyncImage(
                                model = item.uri,
                                contentDescription = "Photo from ${friendlyDate(item.dateAdded)}",
                                modifier = Modifier.fillMaxWidth().height(128.dp).background(SurfaceDark),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Photos") },
                        label = { Text("Photos") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Rounded.Collections, contentDescription = "Albums") },
                        label = { Text("Albums") }
                    )
                    NavigationBarItem(
                        selected = searchOpen,
                        onClick = { searchOpen = true; selectedTab = 0 },
                        icon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                        label = { Text("Search") }
                    )
                }
            }
        }
    }
}

private fun friendlyDate(seconds: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(seconds * 1000L))

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
            results += PhotoItem(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                name = cursor.getString(nameColumn) ?: "Image",
                dateAdded = cursor.getLong(dateColumn)
            )
        }
    }
    return results
}
