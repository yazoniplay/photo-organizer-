package com.yazoniplay.photofinder

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DarkBackground = Color(0xFF101210)
private val DarkSurface = Color(0xFF1B1E1B)
private val GreenAccent = Color(0xFFC5E8B7)
private val LightBackground = Color(0xFFF6F7F3)
private val LightSurface = Color(0xFFFFFFFF)
private val LightAccent = Color(0xFF426B35)

private data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val dateTaken: Long,
    val album: String,
    val isVideo: Boolean,
    val recognizedText: String = "",
    val aiLabel: String = ""
)

private enum class Page { PHOTOS, ALBUMS, FAVORITES, BIN, SETTINGS, AI }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoFinderApp() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoFinderApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("photo_finder_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var indexing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(Page.PHOTOS) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showVideos by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var aiWorking by remember { mutableStateOf(false) }
    var geminiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var geminiPrompt by remember { mutableStateOf("") }
    var geminiAnswer by remember { mutableStateOf("") }
    var geminiBusy by remember { mutableStateOf(false) }
    var geminiPhotoAnswer by remember { mutableStateOf("") }
    var showGeminiPhotoAnswer by remember { mutableStateOf(false) }
    var aiProgress by remember { mutableStateOf(0) }
    var aiTotal by remember { mutableStateOf(0) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var bin by remember { mutableStateOf(prefs.getStringSet("bin", emptySet())?.toSet() ?: emptySet()) }
    var customNames by remember { mutableStateOf(prefs.getString("custom_names", "") ?: "") }
    var aiLabels by remember { mutableStateOf(prefs.getString("ai_labels", "") ?: "") }

    val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else imagePermission
    var hasImagePermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, imagePermission) == PackageManager.PERMISSION_GRANTED) }
    var hasVideoPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, videoPermission) == PackageManager.PERMISSION_GRANTED) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasImagePermission = result[imagePermission] == true || ContextCompat.checkSelfPermission(context, imagePermission) == PackageManager.PERMISSION_GRANTED
        hasVideoPermission = result[videoPermission] == true || ContextCompat.checkSelfPermission(context, videoPermission) == PackageManager.PERMISSION_GRANTED
        if (!hasImagePermission) status = "Allow photo access to show your gallery."
    }

    fun persistSet(key: String, values: Set<String>) {
        prefs.edit().putStringSet(key, values.toSet()).apply()
    }
    fun persistNames(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    LaunchedEffect(hasImagePermission, hasVideoPermission) {
        if (hasImagePermission) {
            loading = true
            media = withContext(Dispatchers.IO) {
                val ocr = context.getSharedPreferences("photo_finder_ocr", Context.MODE_PRIVATE)
                loadMedia(context, hasVideoPermission).map { item ->
                    item.copy(
                        recognizedText = ocr.getString(item.id.toString(), "") ?: "",
                        aiLabel = parseMap(aiLabels)[item.id.toString()] ?: ""
                    )
                }
            }
            loading = false
        }
    }

    val activeItems = remember(media, page, showVideos, selectedAlbum, query, favorites, bin, customNames, aiLabels) {
        val nameMap = parseMap(customNames)
        val labels = parseMap(aiLabels)
        var list = when (page) {
            Page.FAVORITES -> media.filter { it.id.toString() in favorites }
            Page.BIN -> media.filter { it.id.toString() in bin }
            else -> media.filter { it.id.toString() !in bin }
        }
        if (page == Page.PHOTOS) list = list.filter { it.isVideo == showVideos }
        if (selectedAlbum != null && page == Page.ALBUMS) list = list.filter { it.album == selectedAlbum }
        if (query.isNotBlank()) list = list.filter {
            (nameMap[it.id.toString()] ?: it.name).contains(query, true) ||
                it.album.contains(query, true) || it.recognizedText.contains(query, true) ||
                labels[it.id.toString()].orEmpty().contains(query, true)
        }
        list
    }

    val darkColors = if (darkMode) darkColorScheme(
        background = DarkBackground, surface = DarkSurface, primary = GreenAccent,
        onPrimary = DarkBackground, onBackground = Color(0xFFF2F3EF), onSurface = Color(0xFFF2F3EF)
    ) else lightColorScheme(
        background = LightBackground, surface = LightSurface, primary = LightAccent,
        onPrimary = Color.White, onBackground = Color(0xFF1C211A), onSurface = Color(0xFF1C211A)
    )
    val bg = if (darkMode) DarkBackground else LightBackground
    val surface = if (darkMode) DarkSurface else LightSurface
    val accent = if (darkMode) GreenAccent else LightAccent
    val muted = if (darkMode) Color(0xFFA4AAA1) else Color(0xFF6B7167)

    MaterialTheme(colorScheme = darkColors) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (page) {
                                Page.PHOTOS -> if (showVideos) "Videos" else "Photos"
                                Page.ALBUMS -> selectedAlbum ?: "Albums"
                                Page.FAVORITES -> "Favorites"
                                Page.BIN -> "Bin"
                                Page.SETTINGS -> "Settings"
                                Page.AI -> "AI Studio"
                            },
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (page) {
                                Page.SETTINGS -> "Make Photo Finder yours"
                                Page.AI -> "Private, on-device photo intelligence"
                                Page.ALBUMS -> if (selectedAlbum == null) "${media.map { it.album }.distinct().size} folders" else "${activeItems.size} items"
                                else -> "${activeItems.size} items"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = muted
                        )
                    }
                    if (page != Page.SETTINGS && page != Page.BIN && page != Page.ALBUMS) {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowMenuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Bin") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    page = Page.BIN
                                    selectedAlbum = null
                                    searchOpen = false
                                    query = ""
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("AI Studio") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    page = Page.AI
                                    selectedAlbum = null
                                    searchOpen = false
                                    query = ""
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    page = Page.SETTINGS
                                    selectedAlbum = null
                                    searchOpen = false
                                    query = ""
                                }
                            )
                        }
                    }
                }

                if (searchOpen && page != Page.SETTINGS) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true, placeholder = { Text("Search names, folders or text in screenshots") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }

                if (page == Page.PHOTOS) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showVideos, onClick = { showVideos = false }, label = { Text("Photos") }, leadingIcon = { Icon(Icons.Rounded.Photo, null) })
                        FilterChip(selected = showVideos, onClick = { showVideos = true }, label = { Text("Videos") }, leadingIcon = { Icon(Icons.Rounded.Videocam, null) })
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            enabled = !indexing && activeItems.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    indexing = true
                                    status = "Reading text from screenshots…"
                                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    val candidates = media.filter { !it.isVideo && it.name.contains("screenshot", true) }.take(250)
                                    val indexed = withContext(Dispatchers.IO) {
                                        candidates.map { item ->
                                            try { item.copy(recognizedText = recognizer.process(InputImage.fromFilePath(context, item.uri)).await().text) }
                                            catch (_: Exception) { item }
                                        }
                                    }
                                    recognizer.close()
                                    val ocr = context.getSharedPreferences("photo_finder_ocr", Context.MODE_PRIVATE)
                                    ocr.edit().also { e -> indexed.forEach { e.putString(it.id.toString(), it.recognizedText) } }.apply()
                                    val map = indexed.associateBy { it.id }
                                    media = media.map { map[it.id] ?: it }
                                    indexing = false
                                    status = "Text search updated for ${indexed.size} screenshots."
                                }
                            }
                        ) { if (indexing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.TextSnippet, null); Spacer(Modifier.width(6.dp)); Text("Find text") }
                    }
                    if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = muted, style = MaterialTheme.typography.bodySmall)
                }

                when (page) {
                    Page.AI -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsRow("Analyze photo library", "Recognize objects and scenes across your photos", Icons.Rounded.AutoAwesome) {
                            Button(
                                enabled = !aiWorking && hasImagePermission && media.any { !it.isVideo && it.id.toString() !in bin },
                                onClick = {
                                    scope.launch {
                                        aiWorking = true
                                        aiProgress = 0
                                        val candidates = media.filter { !it.isVideo && it.id.toString() !in bin }
                                        aiTotal = candidates.size
                                        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                                        val updated = parseMap(aiLabels).toMutableMap()
                                        var completed = 0
                                        try {
                                            for (item in candidates) {
                                                if (updated[item.id.toString()].isNullOrBlank()) {
                                                    try {
                                                        val labels = withContext(Dispatchers.IO) {
                                                            labeler.process(InputImage.fromFilePath(context, item.uri)).await()
                                                        }.sortedByDescending { it.confidence }.take(3).map { it.text }
                                                        if (labels.isNotEmpty()) updated[item.id.toString()] = labels.joinToString(", ")
                                                    } catch (_: Exception) { }
                                                }
                                                completed++
                                                aiProgress = completed
                                            }
                                            aiLabels = mapToString(updated)
                                            persistNames("ai_labels", aiLabels)
                                            media = media.map { item -> item.copy(aiLabel = updated[item.id.toString()].orEmpty()) }
                                            status = "AI scan complete. Your labels are saved on this device."
                                        } finally {
                                            labeler.close()
                                            aiWorking = false
                                        }
                                    }
                                }
                            ) { Text(if (aiWorking) "Analyzing…" else "Analyze library") }
                        }
                        if (aiWorking) {
                            LinearProgressIndicator(
                                progress = { if (aiTotal > 0) aiProgress.toFloat() / aiTotal else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Analyzed $aiProgress of $aiTotal photos", color = muted)
                        }
                        val labelMap = parseMap(aiLabels)
                        val topLabels = labelMap.values.flatMap { it.split(", ") }
                            .filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                            .entries.sortedByDescending { it.value }.take(12)
                        SettingsRow("Recognized content", "Search your photos by objects and scenes", Icons.Rounded.Search) {
                            Text("${labelMap.size} analyzed", color = accent, style = MaterialTheme.typography.labelLarge)
                        }
                        if (topLabels.isEmpty()) {
                            Text("Run Analyze library to build searchable labels like dog, beach, food, or sunset.", color = muted)
                        } else {
                            Text("What your library contains", fontWeight = FontWeight.SemiBold)
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                topLabels.forEach { entry ->
                                    AssistChip(
                                        onClick = { page = Page.PHOTOS; showVideos = false; query = entry.key; searchOpen = true },
                                        label = { Text("${entry.key} · ${entry.value}") },
                                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) }
                                    )
                                }
                            }
                        }
                        Divider(color = muted.copy(alpha = 0.25f))
                        Text("Ask Gemini", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Ask for help organizing your library. Your prompt and limited filename/label context are sent to Google Gemini.", color = muted, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = geminiPrompt,
                            onValueChange = { geminiPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("What do you want help with?") },
                            placeholder = { Text("e.g. Help me organize travel photos…") },
                            minLines = 2,
                            maxLines = 4
                        )
                        Button(
                            enabled = !geminiBusy && geminiPrompt.isNotBlank() && geminiKey.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    geminiBusy = true
                                    geminiAnswer = ""
                                    try {
                                        val libraryContext = media.filter { it.id.toString() !in bin }.take(100)
                                            .joinToString("\n") { item ->
                                                "${parseMap(customNames)[item.id.toString()] ?: item.name}: ${parseMap(aiLabels)[item.id.toString()].orEmpty()}"
                                            }
                                        geminiAnswer = askGemini(
                                            context, geminiKey,
                                            "You are Photo Finder's helpful photo-library assistant. Be accurate and concise. Do not claim to see images unless image data is included. Library filename and on-device label context (may be incomplete):\n$libraryContext\n\nUser request: $geminiPrompt"
                                        )
                                    } catch (e: Exception) {
                                        geminiAnswer = e.message ?: "Gemini request failed. Check the API key and internet connection."
                                    } finally {
                                        geminiBusy = false
                                    }
                                }
                            }
                        ) {
                            if (geminiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (geminiBusy) "Thinking…" else "Ask Gemini")
                        }
                        if (geminiAnswer.isNotBlank()) {
                            Surface(color = surface, shape = RoundedCornerShape(16.dp)) {
                                Text(geminiAnswer, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Text("The library scan above is on-device. Gemini prompts and any photo you explicitly ask it to analyze are sent to Google for processing.", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Page.SETTINGS -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsRow("Appearance", "Choose your preferred theme", Icons.Rounded.DarkMode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (darkMode) "Dark" else "Light", color = muted)
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = darkMode, onCheckedChange = {
                                    darkMode = it
                                    persistSet("favorites", favorites)
                                    prefs.edit().putBoolean("dark_mode", it).apply()
                                })
                            }
                        }
                        SettingsRow("AI features", "On-device labels plus optional Gemini AI", Icons.Rounded.AutoAwesome) {
                            Text(if (geminiKey.isBlank()) "Set up" else "Ready", color = accent, style = MaterialTheme.typography.labelLarge)
                        }
                        Text("Gemini API key", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = { geminiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paste your API key") },
                            placeholder = { Text("Gemini API key") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                prefs.edit().putString("gemini_api_key", geminiKey.trim()).apply()
                                geminiKey = geminiKey.trim()
                                status = "Gemini key saved on this device."
                            }) { Text("Save key") }
                            TextButton(onClick = {
                                geminiKey = ""
                                prefs.edit().remove("gemini_api_key").apply()
                                status = "Gemini key removed."
                            }) { Text("Remove key") }
                        }
                        Text("Gemini requires internet and may have usage limits or charges. Never share your key. This prototype stores it locally; a public production app should use a secure backend instead of embedding API credentials.", color = muted, style = MaterialTheme.typography.bodySmall)
                        SettingsRow("Privacy", "Photos are only sent to Gemini when you explicitly ask it to analyze one.", Icons.Rounded.Lock) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = accent)
                        }
                        SettingsRow("Library", "${media.size} items · ${media.map { it.album }.distinct().size} folders", Icons.Rounded.Collections) {
                            Text("Synced", color = muted)
                        }
                        Text("Smart names are saved inside Photo Finder. Original filenames are not changed.", color = muted, style = MaterialTheme.typography.bodySmall)
                        Text("The Bin hides items inside this app; it does not delete the original files from your device.", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Page.ALBUMS -> {
                        if (selectedAlbum != null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                TextButton(onClick = { selectedAlbum = null }) { Icon(Icons.Rounded.ArrowBack, null); Text("All albums") }
                            }
                            MediaGrid(activeItems, favorites, customNames, onOpen = { item -> viewerIndex = activeItems.indexOf(item) }, onFavorite = { item ->
                                favorites = if (item.id.toString() in favorites) favorites - item.id.toString() else favorites + item.id.toString()
                                persistSet("favorites", favorites)
                            }, onRename = { renameTarget = it; renameText = parseMap(customNames)[it.id.toString()] ?: it.name }, accent = accent, surface = surface, modifier = Modifier.weight(1f))
                        } else {
                            val albumNames = media.filter { it.id.toString() !in bin }.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(albumNames.size) { index ->
                                    val (albumName, itemsInAlbum) = albumNames[index]
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surface).clickable { selectedAlbum = albumName }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(itemsInAlbum.first().uri, null, Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                            Text(albumName, fontWeight = FontWeight.SemiBold)
                                            Text("${itemsInAlbum.size} items", color = muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Icon(Icons.Rounded.ChevronRight, null, tint = muted)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        when {
                            !hasImagePermission -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Rounded.PhotoLibrary, null, tint = accent, modifier = Modifier.size(52.dp))
                                    Text("Your photos, all in one place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Your media stays on this device.", color = muted)
                                    Button(onClick = { permissionLauncher.launch(arrayOf(imagePermission, videoPermission)) }) { Text("Allow access") }
                                }
                            }
                            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            page == Page.BIN && activeItems.isEmpty() -> EmptyState("Your bin is empty", "Items you hide from the app will appear here.", accent, muted, Modifier.weight(1f))
                            activeItems.isEmpty() -> EmptyState(if (query.isBlank()) "Nothing here yet" else "No matching items", "Try another search or switch between Photos and Videos.", accent, muted, Modifier.weight(1f))
                            else -> MediaGrid(
                                activeItems, favorites, customNames,
                                onOpen = { item ->
                                    if (item.isVideo) {
                                        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        catch (_: Exception) { status = "No video player found." }
                                    } else viewerIndex = activeItems.indexOf(item)
                                },
                                onFavorite = { item ->
                                    favorites = if (item.id.toString() in favorites) favorites - item.id.toString() else favorites + item.id.toString()
                                    persistSet("favorites", favorites)
                                },
                                onRename = { renameTarget = it; renameText = parseMap(customNames)[it.id.toString()] ?: suggestedBaseName(it) },
                                onBin = { item ->
                                    bin = bin + item.id.toString()
                                    persistSet("bin", bin)
                                },
                                accent = accent, surface = surface, modifier = Modifier.weight(1f), isBin = page == Page.BIN,
                                onRestore = { item -> bin = bin - item.id.toString(); persistSet("bin", bin) },
                                onDeleteFromBin = { item ->
                                    // The app removes its own bin marker; the source media file is never permanently deleted.
                                    bin = bin - item.id.toString()
                                    persistSet("bin", bin)
                                }
                            )
                        }
                    }
                }

                NavigationBar(containerColor = bg, tonalElevation = 0.dp, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    NavigationBarItem(selected = page == Page.PHOTOS, onClick = { page = Page.PHOTOS; selectedAlbum = null }, icon = { Icon(Icons.Rounded.PhotoLibrary, null) }, label = { Text("Photos") })
                    NavigationBarItem(selected = page == Page.ALBUMS, onClick = { page = Page.ALBUMS; selectedAlbum = null }, icon = { Icon(Icons.Rounded.Collections, null) }, label = { Text("Albums") })
                    NavigationBarItem(selected = page == Page.FAVORITES, onClick = { page = Page.FAVORITES }, icon = { Icon(Icons.Rounded.Favorite, null) }, label = { Text("Favorites") })
                }
            }
        }
    }

    val viewerItems = activeItems.filter { !it.isVideo }
    viewerIndex?.let { rawIndex ->
        val index = rawIndex.coerceIn(0, (viewerItems.size - 1).coerceAtLeast(0))
        if (viewerItems.isNotEmpty()) {
            PhotoViewer(
                item = viewerItems[index], index = index, total = viewerItems.size,
                title = parseMap(customNames)[viewerItems[index].id.toString()] ?: viewerItems[index].name,
                label = parseMap(aiLabels)[viewerItems[index].id.toString()].orEmpty(),
                onClose = { viewerIndex = null },
                onPrevious = { viewerIndex = (index - 1).coerceAtLeast(0) },
                onNext = { viewerIndex = (index + 1).coerceAtMost(viewerItems.lastIndex) },
                onFavorite = {
                    val id = viewerItems[index].id.toString()
                    favorites = if (id in favorites) favorites - id else favorites + id
                    persistSet("favorites", favorites)
                },
                isFavorite = viewerItems[index].id.toString() in favorites,
                onSuggestName = {
                    scope.launch {
                        aiWorking = true
                        try {
                            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                            val labels = labeler.process(InputImage.fromFilePath(context, viewerItems[index].uri)).await()
                                .sortedByDescending { it.confidence }.take(3).map { it.text }
                            labeler.close()
                            val year = inferYear(viewerItems[index])
                            val description = labels.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Photo"
                            val name = (listOf(year?.toString(), labels.take(2).joinToString("-") { it.replace(Regex("[^A-Za-z0-9]+"), "-") }.take(48))
                                .filterNotNull().filter { it.isNotBlank() }.joinToString("-") + ".jpg")
                            val map = parseMap(aiLabels).toMutableMap()
                            map[viewerItems[index].id.toString()] = labels.joinToString(", ")
                            aiLabels = mapToString(map)
                            persistNames("ai_labels", aiLabels)
                            renameTarget = viewerItems[index]
                            renameText = name.ifBlank { "$description.jpg" }
                            status = "AI suggestion ready. Review before saving."
                        } catch (_: Exception) {
                            status = "Couldn't analyze this image. Try again."
                        }
                        aiWorking = false
                    }
                },
                aiWorking = aiWorking,
                darkMode = darkMode,
                onAskGemini = {
                    scope.launch {
                        geminiBusy = true
                        geminiPhotoAnswer = ""
                        try {
                            geminiPhotoAnswer = askGemini(
                                context, geminiKey,
                                "Analyze this photo for a personal photo organizer. Describe visible content, likely objects and scene, useful searchable keywords, and suggest a short descriptive filename. Do not identify real people. Clearly say when something is uncertain.",
                                viewerItems[index].uri
                            )
                        } catch (e: Exception) {
                            geminiPhotoAnswer = e.message ?: "Gemini could not analyze this photo."
                        } finally {
                            geminiBusy = false
                            showGeminiPhotoAnswer = true
                        }
                    }
                },
                geminiBusy = geminiBusy
            )
        }
    }

    if (showGeminiPhotoAnswer) {
        AlertDialog(
            onDismissRequest = { showGeminiPhotoAnswer = false },
            title = { Text("Gemini photo analysis") },
            text = { Text(geminiPhotoAnswer) },
            confirmButton = { TextButton(onClick = { showGeminiPhotoAnswer = false }) { Text("Done") } }
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Smart name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Suggested from image content and available date clues. This changes the name shown in Photo Finder, not the original file.", color = muted)
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Photo name") }, singleLine = true)
                    val year = inferYear(item)
                    if (year != null) Text("Detected year clue: $year", color = accent, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                val map = parseMap(customNames).toMutableMap()
                if (renameText.isNotBlank()) map[item.id.toString()] = renameText.trim()
                customNames = mapToString(map)
                persistNames("custom_names", customNames)
                renameTarget = null
            }) { Text("Save name") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, trailing: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        trailing()
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, accent: Color, muted: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = accent, modifier = Modifier.size(48.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = muted)
        }
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>, favorites: Set<String>, customNames: String,
    onOpen: (MediaItem) -> Unit, onFavorite: (MediaItem) -> Unit, onRename: (MediaItem) -> Unit,
    accent: Color, surface: Color, modifier: Modifier = Modifier, isBin: Boolean = false,
    onBin: (MediaItem) -> Unit = {}, onRestore: (MediaItem) -> Unit = {}, onDeleteFromBin: (MediaItem) -> Unit = {}
) {
    val names = remember(customNames) { parseMap(customNames) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = modifier,
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(items = items, key = { "${it.isVideo}-${it.id}" }) { item ->
            Box(Modifier.fillMaxWidth().height(132.dp).background(surface)) {
                AsyncImage(model = item.uri, contentDescription = names[item.id.toString()] ?: item.name,
                    modifier = Modifier.fillMaxSize().clickable { onOpen(item) }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                if (item.isVideo) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
                IconButton(onClick = { if (isBin) onRestore(item) else onFavorite(item) }, modifier = Modifier.align(Alignment.TopEnd).size(34.dp)) {
                    Icon(if (isBin) Icons.Rounded.Restore else if (item.id.toString() in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isBin) "Restore" else "Favorite", tint = if (isBin || item.id.toString() in favorites) Color(0xFFFFD5D5) else Color.White)
                }
                if (isBin) {
                    IconButton(onClick = { onDeleteFromBin(item) }, modifier = Modifier.align(Alignment.BottomEnd).size(34.dp)) { Icon(Icons.Rounded.Close, "Remove from bin", tint = Color.White) }
                } else if (!item.isVideo) {
                    IconButton(onClick = { onRename(item) }, modifier = Modifier.align(Alignment.BottomEnd).size(34.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, "AI name", tint = Color.White)
                    }
                    IconButton(onClick = { onBin(item) }, modifier = Modifier.align(Alignment.BottomStart).size(34.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "Move to bin", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(
    item: MediaItem, index: Int, total: Int, title: String, label: String,
    onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onFavorite: () -> Unit, isFavorite: Boolean, onSuggestName: () -> Unit,
    aiWorking: Boolean, darkMode: Boolean,
    onAskGemini: () -> Unit,
    geminiBusy: Boolean
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }
    val bg = if (darkMode) Color.Black else Color(0xFFF8F8F4)
    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.statusBars)) {
        AsyncImage(
            model = item.uri, contentDescription = title,
            modifier = Modifier.fillMaxSize().pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(bg.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Close photo", tint = if (darkMode) Color.White else Color.Black) }
            Column(Modifier.weight(1f)) {
                Text(title, color = if (darkMode) Color.White else Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${index + 1} of $total", color = if (darkMode) Color.LightGray else Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onFavorite) { Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (isFavorite) Color(0xFFFF8C9B) else if (darkMode) Color.White else Color.Black) }
            IconButton(onClick = onSuggestName, enabled = !aiWorking) { if (aiWorking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, "On-device labels", tint = if (darkMode) Color.White else Color.Black) }
            IconButton(onClick = onAskGemini, enabled = !geminiBusy) { if (geminiBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Chat, "Ask Gemini about photo", tint = if (darkMode) Color.White else Color.Black) }
        }
        if (label.isNotBlank()) Text("AI: $label", Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg.copy(alpha = 0.72f)).padding(12.dp), color = if (darkMode) Color.White else Color.Black)
        Row(Modifier.align(Alignment.CenterStart).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous photo", tint = if (darkMode) Color.White else Color.Black) }
        }
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNext, enabled = index < total - 1) { Icon(Icons.Rounded.ChevronRight, "Next photo", tint = if (darkMode) Color.White else Color.Black) }
        }
    }
}

private fun loadMedia(context: Context, includeVideos: Boolean): List<MediaItem> {
    val output = mutableListOf<MediaItem>()
    fun readCollection(collection: Uri, isVideo: Boolean) {
        val idCol = MediaStore.MediaColumns._ID
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        val addedCol = MediaStore.MediaColumns.DATE_ADDED
        val takenCol = MediaStore.MediaColumns.DATE_TAKEN
        val bucketName = if (isVideo) MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME else MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME
        val projection = arrayOf(idCol, nameCol, addedCol, takenCol, bucketName)
        context.contentResolver.query(collection, projection, null, null, "$addedCol DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(idCol)
            val nameIndex = cursor.getColumnIndexOrThrow(nameCol)
            val addedIndex = cursor.getColumnIndexOrThrow(addedCol)
            val takenIndex = cursor.getColumnIndex(takenCol)
            val bucketIndex = cursor.getColumnIndex(bucketName)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                output += MediaItem(
                    id = id, uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameIndex) ?: if (isVideo) "Video" else "Photo",
                    dateAdded = cursor.getLong(addedIndex),
                    dateTaken = if (takenIndex >= 0) cursor.getLong(takenIndex) else 0L,
                    album = if (bucketIndex >= 0) cursor.getString(bucketIndex)?.takeIf { it.isNotBlank() } ?: "Other" else "Other",
                    isVideo = isVideo
                )
            }
        }
    }
    readCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    if (includeVideos) readCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return output.sortedByDescending { it.dateAdded }
}

private fun inferYear(item: MediaItem): Int? {
    if (item.dateTaken > 0L) {
        val year = java.util.Calendar.getInstance().apply { timeInMillis = item.dateTaken }.get(java.util.Calendar.YEAR)
        if (year in 1990..2100) return year
    }
    val match = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)").find(item.name)
    return match?.value?.toIntOrNull()
}

private fun suggestedBaseName(item: MediaItem): String {
    val year = inferYear(item)?.toString()
    val base = item.name.substringBeforeLast('.', item.name).replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40)
    return listOf(year, base.ifBlank { "Photo" }).filterNotNull().joinToString("-") + ".jpg"
}

private fun parseMap(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.split("\\n").mapNotNull { line ->
        val at = line.indexOf('|')
        if (at <= 0) null else line.substring(0, at) to line.substring(at + 1).replace("%7C", "|").replace("%0A", "\n")
    }.toMap()
}

private fun mapToString(map: Map<String, String>): String = map.entries.joinToString("\\n") {
    it.key + "|" + it.value.replace("|", "%7C").replace("\n", "%0A")
}
