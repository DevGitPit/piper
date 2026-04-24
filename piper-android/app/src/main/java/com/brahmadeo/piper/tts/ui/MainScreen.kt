package com.brahmadeo.piper.tts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brahmadeo.piper.tts.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    placeholderText: String,
    isInitializing: Boolean,
    isSynthesizing: Boolean,
    onSynthesizeClick: () -> Unit,

    // Voice & Language
    languages: Map<String, String>,
    currentLangCode: String,
    onLangChange: (String) -> Unit,

    voices: Map<String, String>,
    selectedVoiceFile: String,
    onVoiceChange: (String) -> Unit,

    // Speed & Threads
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,

    // Menu Actions
    onResetClick: () -> Unit,
    onSavedAudioClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLexiconClick: () -> Unit,
    onDeleteV2Click: () -> Unit,
    onOpenEbookClick: () -> Unit,
    isV2Ready: Boolean,

    // Mini Player
    showMiniPlayer: Boolean,
    miniPlayerTitle: String,
    miniPlayerIsPlaying: Boolean,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayerPlayPauseClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Piper TTS") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(text = { Text("Reset") }, onClick = { showMenu = false; onResetClick() })
                        DropdownMenuItem(text = { Text("Open Ebook (EPUB/PDF)") }, onClick = { showMenu = false; onOpenEbookClick() })
                        DropdownMenuItem(text = { Text("Saved Audio") }, onClick = { showMenu = false; onSavedAudioClick() })
                        DropdownMenuItem(text = { Text("History") }, onClick = { showMenu = false; onHistoryClick() })
                        DropdownMenuItem(text = { Text("Queue") }, onClick = { showMenu = false; onQueueClick() })
                        DropdownMenuItem(
                            text = { Text("Lexicon") },
                            onClick = { showMenu = false; onLexiconClick() },
                            enabled = currentLangCode == "en"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        floatingActionButton = {
            val buttonText = when {
                isInitializing -> "Initializing..."
                isSynthesizing -> "Synthesizing..."
                else -> "Synthesize"
            }
            val isLoading = isInitializing || isSynthesizing

            ExtendedFloatingActionButton(
                onClick = onSynthesizeClick,
                text = { Text(buttonText) },
                icon = { Icon(painterResource(android.R.drawable.ic_btn_speak_now), contentDescription = null) },
                expanded = true,
                containerColor = if (isLoading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val scrollState = rememberScrollState()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding() // Shrinks the scrollable area when keyboard is up
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Text Input
                var isFocused by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    placeholder = {
                        if (!isFocused) {
                            Text(placeholderText)
                        }
                    },
                    label = { Text("Input") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .onFocusChanged { isFocused = it.isFocused },
                    // Increased maxLines significantly to avoid internal scrolling conflict.
                    // This makes the cursor stay visible as the whole page scrolls instead.
                    maxLines = 40 
                )

                // Controls Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Language Selector
                        DropdownSelector(
                            label = "Language",
                            options = languages.keys.toList(),
                            selectedOption = languages.entries.find { it.value == currentLangCode }?.key ?: "English",
                            onOptionSelected = { name -> onLangChange(languages[name] ?: "en") }
                        )

                        // Voice Selector
                        DropdownSelector(
                            label = "Voice",
                            options = voices.keys.toList().sorted(),
                            selectedOption = voices.entries.find { it.value == selectedVoiceFile }?.key ?: "",
                            onOptionSelected = { name -> onVoiceChange(voices[name] ?: "") }
                        )

                        // Speed
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Speed", style = MaterialTheme.typography.labelMedium)
                                Text(String.format("%.2fx", speed), style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = speed,
                                onValueChange = onSpeedChange,
                                valueRange = 0.5f..2.0f,
                                steps = 14
                            )
                        }

                        // Volume
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Volume (Gain)", style = MaterialTheme.typography.labelMedium)
                                Text(String.format("%.1fx", volume), style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = volume,
                                onValueChange = onVolumeChange,
                                valueRange = 0.0f..1.5f,
                                steps = 14
                            )
                        }

                        // Threads
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Threads", style = MaterialTheme.typography.labelMedium)
                                Text("$threads threads", style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = threads.toFloat(),
                                onValueChange = { onThreadsChange(it.toInt()) },
                                valueRange = 1f..5f,
                                steps = 3
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp)) // Spacing for FAB and MiniPlayer
            }

            // Mini Player Overlay
            if (showMiniPlayer) {
                ElevatedCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    onClick = onMiniPlayerClick
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = miniPlayerTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onMiniPlayerPlayPauseClick) {
                            Icon(
                                imageVector = if (miniPlayerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (miniPlayerIsPlaying) "Pause" else "Play"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
