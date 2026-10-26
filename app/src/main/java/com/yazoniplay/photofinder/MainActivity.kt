package com.yazoniplay.photofinder

import android.Manifest
import android.app.RecoverableSecurityException
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.widget.MediaController
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
    var visualSearchIds by remember { mutableStateOf<Set<String>?>(null) }
    var videoViewer by remember { mutableStateOf<MediaItem?>(null) }
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
    var confirmDeleteTarget by remember { mutableStateOf<MediaItem?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var aiWorking by remember { mutableStateOf(false) }
    var geminiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var geminiPrompt by remember { mutableStateOf("") }
    var geminiAnswer by remember { mutableStateOf("") }
    var aiChat by remember { mutableStateOf(listOf<Pair<Boolean, String>>()) }
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

    fun finishPermanentDelete(item: MediaItem) {
        val key = item.id.toString()
        media = media.filterNot { it.id == item.id }
        bin = bin - key
        favorites = favorites - key
        customNames = parseMap(customNames).toMutableMap().also { it.remove(key) }.let { mapToString(it) }
        aiLabels = parseMap(aiLabels).toMutableMap().also { it.remove(key) }.let { mapToString(it) }
        prefs.edit()
            .putStringSet("bin", bin.toSet())
            .putStringSet("favorites", favorites.toSet())
            .putString("custom_names", customNames)
            .putString("ai_labels", aiLabels)
            .apply()
        context.getSharedPreferences("photo_finder_ocr", Context.MODE_PRIVATE).edit().remove(key).apply()
        confirmDeleteTarget = null
        deleteBusy = false
        status = "Item permanently deleted from your device."
    }

    val deletePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val item = confirmDeleteTarget
        if (result.resultCode == android.app.Activity.RESULT_OK && item != null) {
            finishPermanentDelete(item)
            scope.launch { media = withContext(Dispatchers.IO) { loadMedia(context, hasVideoPermission) } }
        } else {
            deleteBusy = false
            status = "Nothing was deleted. Your item is still in the Bin."
        }
    }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = confirmDeleteTarget
        if (granted && item != null) {
            scope.launch {
                try {
                    val rows = withContext(Dispatchers.IO) { context.contentResolver.delete(item.uri, null, null) }
                    if (rows > 0) finishPermanentDelete(item)
                    else { deleteBusy = false; status = "Android did not delete this item. It is still in the Bin." }
                } catch (e: Exception) {
                    deleteBusy = false
                    status = e.message ?: "Could not delete this item."
                }
            }
        } else {
            deleteBusy = false
            status = "Storage permission was not granted. The item is still in the Bin."
        }
    }

    fun requestPermanentDelete(item: MediaItem) {
        if (deleteBusy) return
        deleteBusy = true
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
                    deletePermissionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    try {
                        val rows = withContext(Dispatchers.IO) { context.contentResolver.delete(item.uri, null, null) }
                        if (rows > 0) finishPermanentDelete(item)
                        else {
                            deleteBusy = false
                            status = "Android did not delete this item. It is still in the Bin."
                        }
                    } catch (e: RecoverableSecurityException) {
                        deletePermissionLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    }
                }
            } catch (e: Exception) {
                deleteBusy = false
                status = e.message ?: "Could not delete this item. It remains in the Bin."
            }
        }
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

    val activeItems = remember(media, page, showVideos, selectedAlbum, query, favorites, bin, customNames, aiLabels, visualSearchIds) {
        val nameMap = parseMap(customNames)
        val labels = parseMap(aiLabels)
        var list = when (page) {
            Page.FAVORITES -> media.filter { it.id.toString() in favorites }
            Page.BIN -> media.filter { it.id.toString() in bin }
            else -> media.filter { it.id.toString() !in bin }
        }
        if (page == Page.PHOTOS) list = list.filter { it.isVideo == showVideos }
        if (selectedAlbum != null && page == Page.ALBUMS) list = list.filter { it.album == selectedAlbum }
        if (visualSearchIds != null && page == Page.PHOTOS && !showVideos) list = list.filter { it.id.toString() in visualSearchIds.orEmpty() }
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
                                Page.AI -> "Ask AI"
                            },
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (page) {
                                Page.SETTINGS -> "Make Photo Finder yours"
                                Page.AI -> "Your AI assistant for the photo library"
                                Page.ALBUMS -> if (selectedAlbum == null) "${media.map { it.album }.distinct().size} folders" else "${activeItems.size} items"
                                else -> "${activeItems.size} items"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = muted
                        )
                    }
                    if (page != Page.SETTINGS && page != Page.BIN && page != Page.ALBUMS && page != Page.AI) {
                        IconButton(onClick = { searchOpen = !searchOpen; query = ""; visualSearchIds = null }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                    }
                    if (page != Page.AI) Box {
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
                                text = { Text("Ask AI") },
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

                if (searchOpen && page != Page.SETTINGS && page != Page.AI) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; visualSearchIds = null },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ask AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (geminiBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                                if (aiChat.isEmpty()) {
                                    Spacer(Modifier.height(20.dp))
                                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(34.dp))
                                    Text("Search by what is in the picture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text("Your photos can have numbered filenames. Describe the scene or object and I’ll scan the image contents instead of relying on filenames.", color = muted)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Find photos at a beach", "Find dogs", "Find cars", "Find sunsets").forEach { suggestion ->
                                            SuggestionChip(onClick = { geminiPrompt = suggestion }, label = { Text(suggestion) })
                                        }
                                    }
                                } else {
                                    aiChat.forEach { (fromUser, message) ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
                                        ) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(if (fromUser) 0.88f else 1f),
                                                shape = if (fromUser)
                                                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp)
                                                else
                                                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 5.dp),
                                                color = if (fromUser) accent.copy(alpha = 0.18f) else muted.copy(alpha = 0.09f),
                                                border = if (fromUser) null else androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.12f))
                                            ) {
                                                Text(message, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                                if (geminiBusy) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text("Thinking…", color = muted)
                                    }
                                }
                            }
                        if (aiChat.isEmpty()) {
                            Text("Try asking", color = muted, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = geminiPrompt,
                                onValueChange = { geminiPrompt = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message") },
                                minLines = 1,
                                maxLines = 4,
                                shape = RoundedCornerShape(24.dp)
                            )
                            FilledIconButton(
                                enabled = !geminiBusy && !aiWorking && geminiPrompt.isNotBlank(),
                                onClick = {
                                    val request = geminiPrompt.trim()
                                    geminiPrompt = ""
                                    val lower = request.lowercase(Locale.ROOT)
                                    geminiAnswer = ""
                                    aiChat = aiChat + (true to request)
                                    val navigationHandled = when {
                                        lower in listOf("photos", "open photos", "show photos", "go to photos") -> {
                                            page = Page.PHOTOS; selectedAlbum = null; showVideos = false; searchOpen = false; query = ""; true
                                        }
                                        lower.contains("video") && (lower.contains("show") || lower.contains("open") || lower == "videos") -> {
                                            page = Page.PHOTOS; selectedAlbum = null; showVideos = true; searchOpen = false; query = ""; true
                                        }
                                        lower.contains("favorite") && (lower.contains("show") || lower.contains("open") || lower.contains("go to")) -> {
                                            page = Page.FAVORITES; selectedAlbum = null; searchOpen = false; query = ""; true
                                        }
                                        lower.contains("album") && (lower.contains("show") || lower.contains("open") || lower.contains("go to")) -> {
                                            page = Page.ALBUMS; selectedAlbum = null; searchOpen = false; query = ""; true
                                        }
                                        lower.contains("setting") && (lower.contains("open") || lower.contains("go to")) -> {
                                            page = Page.SETTINGS; selectedAlbum = null; searchOpen = false; query = ""; true
                                        }
                                        (lower.contains("favorite") || lower.contains("star")) && !lower.contains("show") && !lower.contains("open") -> {
                                            val term = lower.substringAfter("favorite", lower.substringAfter("star", "all photos"))
                                                .replace("all photos", "").replace("all", "").replace("the", "").trim()
                                            val targets = media.filter { item ->
                                                item.id.toString() !in bin && (
                                                    term.isBlank() || term == "photos" ||
                                                    item.name.contains(term, true) || item.album.contains(term, true) ||
                                                    item.recognizedText.contains(term, true) ||
                                                    parseMap(aiLabels)[item.id.toString()].orEmpty().contains(term, true)
                                                )
                                            }
                                            favorites = favorites + targets.map { it.id.toString() }
                                            persistSet("favorites", favorites)
                                            geminiAnswer = "Done — added ${targets.size} matching photo(s) to Favorites. You can undo this from the Favorites view."
                                            true
                                        }
                                        (lower.contains("move") && lower.contains("bin") || lower.contains("hide")) -> {
                                            val term = lower.substringAfter("hide", lower.substringAfter("move", "all photos"))
                                                .substringBefore(" to bin")
                                                .replace("all", "").replace("photos", "").trim()
                                            val targets = media.filter { item ->
                                                item.id.toString() !in bin && (
                                                    term.isBlank() || item.name.contains(term, true) || item.album.contains(term, true) ||
                                                    item.recognizedText.contains(term, true) || parseMap(aiLabels)[item.id.toString()].orEmpty().contains(term, true)
                                                )
                                            }
                                            bin = bin + targets.map { it.id.toString() }
                                            persistSet("bin", bin)
                                            geminiAnswer = "Done — hid ${targets.size} matching item(s) in Photo Finder's Bin. Original files on your device were not deleted."
                                            true
                                        }
                                        lower.contains("analyze library") || lower.contains("scan my photos") -> {
                                            scope.launch {
                                                aiWorking = true
                                                val candidates = media.filter { !it.isVideo && it.id.toString() !in bin }
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
                                                        aiTotal = candidates.size
                                                    }
                                                    aiLabels = mapToString(updated)
                                                    persistNames("ai_labels", aiLabels)
                                                    media = media.map { item -> item.copy(aiLabel = updated[item.id.toString()].orEmpty()) }
                                                    val result = "Library scan complete. Analyzed $completed photo(s); labels are saved on this device and available in search."
                                                    geminiAnswer = result
                                                    aiChat = aiChat + (false to result)
                                                } catch (e: Exception) {
                                                    val result = e.message ?: "The library scan failed."
                                                    geminiAnswer = result
                                                    aiChat = aiChat + (false to result)
                                                } finally {
                                                    labeler.close()
                                                    aiWorking = false
                                                }
                                            }
                                            geminiAnswer = "Starting an on-device scan of your photo library…"
                                            true
                                        }
                                        lower.contains("restore all") -> {
                                            val restored = bin.size
                                            bin = emptySet()
                                            persistSet("bin", bin)
                                            geminiAnswer = "Done — restored $restored item(s) from Photo Finder's Bin. Original files were never deleted."
                                            true
                                        }
                                        lower.contains("clear search") -> {
                                            query = ""; visualSearchIds = null; searchOpen = false; page = Page.PHOTOS; selectedAlbum = null
                                            geminiAnswer = "Done — cleared the gallery search."
                                            true
                                        }
                                        lower.contains("find ") || lower.startsWith("search ") || lower.startsWith("show me ") || lower.startsWith("look for ") || lower.startsWith("locate ") -> {
                                            val term = extractGallerySearchTerm(request)
                                            if (term.isNotBlank()) {
                                                visualSearchIds = emptySet()
                                                scope.launch {
                                                    aiWorking = true
                                                    status = "Scanning image contents for “$term”…"
                                                    val candidates = media.filter { !it.isVideo && it.id.toString() !in bin }
                                                    val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                                                    val updated = parseMap(aiLabels).toMutableMap()
                                                    val matchedIds = mutableSetOf<String>()
                                                    var scanned = 0
                                                    try {
                                                        for (item in candidates) {
                                                            try {
                                                                val labels = withContext(Dispatchers.IO) {
                                                                    labeler.process(InputImage.fromFilePath(context, item.uri)).await()
                                                                }.filter { it.confidence >= 0.35f }
                                                                    .sortedByDescending { it.confidence }
                                                                    .take(10)
                                                                    .map { it.text }
                                                                if (labels.isNotEmpty()) updated[item.id.toString()] = labels.joinToString(", ")
                                                                if (visualLabelsMatch(term, labels)) matchedIds.add(item.id.toString())
                                                            } catch (_: Exception) { }
                                                            scanned++
                                                            aiProgress = scanned
                                                            aiTotal = candidates.size
                                                        }
                                                        aiLabels = mapToString(updated)
                                                        persistNames("ai_labels", aiLabels)
                                                        media = media.map { item -> item.copy(aiLabel = updated[item.id.toString()].orEmpty()) }
                                                        if (matchedIds.isNotEmpty()) {
                                                            visualSearchIds = matchedIds.toSet()
                                                            page = Page.PHOTOS
                                                            selectedAlbum = null
                                                            showVideos = false
                                                            query = ""
                                                            searchOpen = false
                                                        } else {
                                                            visualSearchIds = null
                                                        }
                                                        status = "Visual search finished: scanned $scanned photos; found ${matchedIds.size} possible matches for “$term”."
                                                        val response = if (matchedIds.isNotEmpty())
                                                            "I scanned $scanned photos for visual content related to “$term” and found ${matchedIds.size} possible match(es). They’re now shown in Photos."
                                                        else
                                                            "I scanned $scanned photos, but the on-device image model found no confident matches for “$term”. Try a broader description such as “water”, “outdoor”, or “landscape”."
                                                        aiChat = aiChat + (false to response)
                                                    } catch (_: Exception) {
                                                        val response = "I couldn't finish scanning the gallery. Check Photo Finder's photo permissions and try again."
                                                        status = response
                                                        aiChat = aiChat + (false to response)
                                                    } finally {
                                                        labeler.close()
                                                        aiWorking = false
                                                    }
                                                }
                                                geminiAnswer = "Scanning the actual image contents for “$term”. The first scan may take a little while."
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                    if (navigationHandled) {
                                        if (geminiAnswer.isBlank()) {
                                            geminiAnswer = "Done — I changed Photo Finder to match your request."
                                        }
                                        aiChat = aiChat + (false to geminiAnswer)
                                    } else {
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
                                                    "You are Ask AI, the assistant built into Photo Finder. Be clear, useful, concise and honest about capabilities. The app can execute navigation, gallery search, batch favorites, moving matching items into the app-only Bin, restoring items, clearing search, and on-device library labeling when a command is recognized. Do not claim an action was performed unless the app confirms it. Do not claim to see photos unless image data is included. Do not identify real people. Library filename and on-device label context (incomplete):\n$libraryContext\n\nUser request: $request"
                                                )
                                                aiChat = aiChat + (false to geminiAnswer)
                                            } catch (e: Exception) {
                                                geminiAnswer = e.message ?: "AI request failed. Check the API key and internet connection in Settings."
                                                aiChat = aiChat + (false to geminiAnswer)
                                            } finally {
                                                geminiBusy = false
                                            }
                                        }
                                    }
                                },
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send to Ask AI")
                            }
                        }
                        Text("Your prompt and limited filename/label context are sent to the configured AI service. Photos are sent only when you explicitly ask to analyze one.", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Page.SETTINGS -> Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
                        SettingsRow("AI features", "On-device labels plus Ask AI", Icons.Rounded.AutoAwesome) {
                            Text(if (geminiKey.isBlank()) "Set up" else "Ready", color = accent, style = MaterialTheme.typography.labelLarge)
                        }
                        Text("AI service API key", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = { geminiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paste your API key") },
                            placeholder = { Text("Paste your AI service key") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                prefs.edit().putString("gemini_api_key", geminiKey.trim()).apply()
                                geminiKey = geminiKey.trim()
                                status = "AI service key saved on this device."
                            }) { Text("Save key") }
                            TextButton(onClick = {
                                geminiKey = ""
                                prefs.edit().remove("gemini_api_key").apply()
                                status = "AI service key removed."
                            }) { Text("Remove key") }
                        }
                        Text("The AI service needs internet and may have usage limits or charges. Never share your key. This prototype stores it locally; a public production app should use a secure backend instead of embedding API credentials.", color = muted, style = MaterialTheme.typography.bodySmall)
                        SettingsRow("Privacy", "Photos are only sent to the AI service when you explicitly ask it to analyze one.", Icons.Rounded.Lock) {
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
                            page == Page.BIN && activeItems.isEmpty() -> EmptyState("Your bin is empty", "Items you move here stay on your phone until you choose to permanently delete them.", accent, muted, Modifier.weight(1f))
                            activeItems.isEmpty() -> EmptyState(if (query.isBlank()) "Nothing here yet" else "No matching items", "Try another search or switch between Photos and Videos.", accent, muted, Modifier.weight(1f))
                            else -> MediaGrid(
                                activeItems, favorites, customNames,
                                onOpen = { item ->
                                    if (item.isVideo) videoViewer = item
                                    else viewerIndex = activeItems.indexOf(item)
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
                                onDeleteFromBin = { item -> confirmDeleteTarget = item }
                            )
                        }
                    }
                }

                if (viewerIndex == null && page != Page.AI) Box(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    val slot = (position.x / size.width.coerceAtLeast(1).toFloat() * 3).toInt().coerceIn(0, 2)
                                    page = when (slot) { 0 -> Page.PHOTOS; 1 -> Page.ALBUMS; else -> Page.FAVORITES }
                                    selectedAlbum = null
                                },
                                onDrag = { change, _ ->
                                    val slot = (change.position.x / size.width.coerceAtLeast(1).toFloat() * 3).toInt().coerceIn(0, 2)
                                    page = when (slot) { 0 -> Page.PHOTOS; 1 -> Page.ALBUMS; else -> Page.FAVORITES }
                                    selectedAlbum = null
                                    change.consume()
                                }
                            )
                        },
                        shape = RoundedCornerShape(36.dp),
                        color = (if (darkMode) Color(0xFF343634) else Color(0xFFFDFDFD)).copy(alpha = if (darkMode) 0.22f else 0.30f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, (if (darkMode) Color.White else Color.Black).copy(alpha = 0.22f)),
                        shadowElevation = 20.dp
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            modifier = Modifier.height(72.dp)
                        ) {
                            NavigationBarItem(
                                selected = page == Page.PHOTOS,
                                onClick = { page = Page.PHOTOS; selectedAlbum = null },
                                icon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                                label = { Text("Photos") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.14f),
                                    selectedIconColor = if (darkMode) Color.White else Color.Black,
                                    selectedTextColor = if (darkMode) Color.White else Color.Black,
                                    unselectedIconColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f),
                                    unselectedTextColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f)
                                )
                            )
                            NavigationBarItem(
                                selected = page == Page.ALBUMS,
                                onClick = { page = Page.ALBUMS; selectedAlbum = null },
                                icon = { Icon(Icons.Rounded.Collections, null) },
                                label = { Text("Albums") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.14f),
                                    selectedIconColor = if (darkMode) Color.White else Color.Black,
                                    selectedTextColor = if (darkMode) Color.White else Color.Black,
                                    unselectedIconColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f),
                                    unselectedTextColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f)
                                )
                            )
                            NavigationBarItem(
                                selected = page == Page.FAVORITES,
                                onClick = { page = Page.FAVORITES },
                                icon = { Icon(Icons.Rounded.Favorite, null) },
                                label = { Text("Favorites") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.14f),
                                    selectedIconColor = if (darkMode) Color.White else Color.Black,
                                    selectedTextColor = if (darkMode) Color.White else Color.Black,
                                    unselectedIconColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f),
                                    unselectedTextColor = (if (darkMode) Color.White else Color.Black).copy(alpha = 0.66f)
                                )
                            )
                        }
                    }
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
                currentPage = page,
                onNavigate = { destination -> page = destination; selectedAlbum = null; searchOpen = false; query = "" },
                onAskAI = {
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
                            geminiPhotoAnswer = e.message ?: "Could not analyze this photo. Check your AI service settings and internet connection."
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

    videoViewer?.let { item ->
        VideoViewerDialog(item = item, onClose = { videoViewer = null })
    }

    AnimatedVisibility(
        visible = confirmDeleteTarget != null,
        enter = fadeIn() + scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)),
        exit = fadeOut() + scaleOut(targetScale = 0.94f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 500f))
    ) {
        val target = confirmDeleteTarget
        if (target != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { if (!deleteBusy) confirmDeleteTarget = null }) {
                Surface(
                    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f)),
                    shape = RoundedCornerShape(30.dp), color = surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.35f)),
                    shadowElevation = 24.dp
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFFF5C67).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DeleteForever, null, tint = Color(0xFFFF727C), modifier = Modifier.size(30.dp))
                        }
                        Text("Delete this for real?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "“${parseMap(customNames)[target.id.toString()] ?: target.name}” will be removed from your device, not just Photo Finder. This may be difficult or impossible to undo. Android will show its own final confirmation.",
                            color = muted, style = MaterialTheme.typography.bodyMedium
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { confirmDeleteTarget = null }, enabled = !deleteBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                                Text("Keep it")
                            }
                            Button(
                                onClick = { requestPermanentDelete(target) }, enabled = !deleteBusy,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74755), contentColor = Color.White),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                if (deleteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                else Icon(Icons.Rounded.DeleteForever, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (deleteBusy) "Waiting…" else "Delete forever")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGeminiPhotoAnswer) {
        AlertDialog(
            onDismissRequest = { showGeminiPhotoAnswer = false },
            title = { Text("Ask AI · Photo analysis") },
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

private fun visualLabelsMatch(query: String, labels: List<String>): Boolean {
    val q = query.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").trim()
    val words = labels.map { it.lowercase(Locale.ROOT) }
    val aliases = when {
        q.contains("beach") || q.contains("seaside") || q.contains("coast") || q.contains("shore") ->
            listOf("beach", "coast", "coastline", "shore", "shoreline", "seaside", "sea", "ocean", "sand", "water", "wave")
        q.contains("sea") || q.contains("ocean") ->
            listOf("sea", "ocean", "water", "beach", "coast", "wave", "shore")
        q.contains("dog") || q.contains("puppy") -> listOf("dog", "puppy", "canine", "pet")
        q.contains("cat") || q.contains("kitten") -> listOf("cat", "kitten", "feline", "pet")
        q.contains("car") || q.contains("vehicle") -> listOf("car", "vehicle", "automobile", "truck", "transport")
        q.contains("sunset") || q.contains("sunrise") -> listOf("sunset", "sunrise", "sun", "sky", "dusk", "dawn")
        q.contains("food") || q.contains("meal") -> listOf("food", "meal", "dish", "cuisine", "fruit", "vegetable")
        q.contains("mountain") -> listOf("mountain", "hill", "cliff", "landscape")
        q.contains("forest") || q.contains("woods") -> listOf("forest", "wood", "tree", "nature", "plant")
        q.contains("person") || q.contains("people") -> listOf("person", "people", "human", "crowd")
        else -> listOf(q)
    }
    return aliases.any { alias -> words.any { label -> label == alias || label.contains(alias) || alias.contains(label) && label.length >= 4 } }
}

@Composable
private fun VideoViewerDialog(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF101210),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 24.dp
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close video", tint = Color.White) }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    factory = { ctx ->
                        val player = VideoView(ctx)
                        val controls = MediaController(ctx)
                        controls.setAnchorView(player)
                        player.setMediaController(controls)
                        player.setVideoURI(item.uri)
                        player.tag = item.uri.toString()
                        player.setOnPreparedListener { it.isLooping = false; player.start() }
                        player
                    },
                    update = { player -> if (player.tag != item.uri.toString()) { player.tag = item.uri.toString(); player.setVideoURI(item.uri) } }
                )
                Text("Playing inside Photo Finder", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
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
            var entered by remember(item.id) { mutableStateOf(false) }
            LaunchedEffect(item.id) { entered = true }
            val itemScale by animateFloatAsState(if (entered) 1f else 0.92f, animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f), label = "photo-card-scale")
            val itemAlpha by animateFloatAsState(if (entered) 1f else 0f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f), label = "photo-card-alpha")
            Box(
                Modifier.fillMaxWidth().height(132.dp).background(surface)
                    .graphicsLayer(scaleX = itemScale, scaleY = itemScale).alpha(itemAlpha)
                    .animateContentSize(animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f))
            ) {
                AsyncImage(model = item.uri, contentDescription = names[item.id.toString()] ?: item.name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)).clickable { onOpen(item) }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
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
    currentPage: Page, onNavigate: (Page) -> Unit,
    onAskAI: () -> Unit,
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
            IconButton(onClick = onAskAI, enabled = !geminiBusy) { if (geminiBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, "Analyze photo", tint = if (darkMode) Color.White else Color.Black) }
        }
        if (label.isNotBlank()) Text("AI: $label", Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg.copy(alpha = 0.72f)).padding(12.dp), color = if (darkMode) Color.White else Color.Black)
        Row(Modifier.align(Alignment.CenterStart).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous photo", tint = if (darkMode) Color.White else Color.Black) }
        }
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNext, enabled = index < total - 1) { Icon(Icons.Rounded.ChevronRight, "Next photo", tint = if (darkMode) Color.White else Color.Black) }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 22.dp, end = 22.dp, bottom = 8.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            val slot = (position.x / size.width.coerceAtLeast(1).toFloat() * 3).toInt().coerceIn(0, 2)
                            onNavigate(when (slot) { 0 -> Page.PHOTOS; 1 -> Page.ALBUMS; else -> Page.FAVORITES })
                        },
                        onDrag = { change, _ ->
                            val slot = (change.position.x / size.width.coerceAtLeast(1).toFloat() * 3).toInt().coerceIn(0, 2)
                            onNavigate(when (slot) { 0 -> Page.PHOTOS; 1 -> Page.ALBUMS; else -> Page.FAVORITES })
                            change.consume()
                        }
                    )
                },
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.24f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            shadowElevation = 8.dp
        ) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.height(68.dp)) {
                NavigationBarItem(selected = currentPage == Page.PHOTOS, onClick = { onNavigate(Page.PHOTOS) }, icon = { Icon(Icons.Rounded.PhotoLibrary, null) }, label = { Text("Photos") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.White.copy(alpha = 0.15f)))
                NavigationBarItem(selected = currentPage == Page.ALBUMS, onClick = { onNavigate(Page.ALBUMS) }, icon = { Icon(Icons.Rounded.Collections, null) }, label = { Text("Albums") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.White.copy(alpha = 0.15f)))
                NavigationBarItem(selected = currentPage == Page.FAVORITES, onClick = { onNavigate(Page.FAVORITES) }, icon = { Icon(Icons.Rounded.Favorite, null) }, label = { Text("Favorites") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.White.copy(alpha = 0.15f)))
            }
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


private fun extractGallerySearchTerm(request: String): String {
    var term = request.trim().lowercase(Locale.ROOT)
    term = term.replace(Regex("^(please\\s+)?(can you\\s+|could you\\s+|i want to\\s+|i need to\\s+)?"), "")
    term = term.replace(Regex("^(please\\s+)?(find|search for|search|show me|look for|locate)\\s+"), "")
    term = term.replace(Regex("^(me\\s+)"), "")
    term = term.replace(Regex("^(a|an|the|some|any)\\s+"), "")
    term = term.replace(Regex("^(photo|picture|image|pic|photos|pictures|images)\\s+"), "")
    term = term.replace(Regex("^(that (was|were) |which (was|were) )?(taken|shot|captured)\\s+(in|at|on|near)\\s+"), "")
    term = term.replace(Regex("^(of|with|in|on|at|from|near|showing|containing|that show|that has|that have)\\s+"), "")
    term = term.replace(Regex("^(a|an|the|some|any)\\s+"), "")
    term = term.replace(Regex("^(taken|shot|captured)\\s+(in|at|on|near)\\s+"), "")
    term = term.replace(Regex("^(in|at|on|near|from)\\s+"), "")
    term = term.replace(Regex("\\s+(please|for me|in my gallery|in my photos)$"), "").trim()
    return term
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
