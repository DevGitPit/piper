package com.brahmadeo.piper.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.brahmadeo.piper.tts.ui.QueueScreen
import com.brahmadeo.piper.tts.ui.theme.SupertonicTheme
import com.brahmadeo.piper.tts.utils.QueueManager

class QueueActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SupertonicTheme {
                val showClearDialog = remember { mutableStateOf(false) }

                if (showClearDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog.value = false },
                        title = { Text("Clear Queue") },
                        text = { Text("Are you sure you want to remove all items from the queue?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    QueueManager.clear()
                                    showClearDialog.value = false
                                }
                            ) {
                                Text("Yes")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog.value = false }) {
                                Text("No")
                            }
                        }
                    )
                }

                QueueScreen(
                    onBackClick = { finish() },
                    onClearClick = { showClearDialog.value = true }
                )
            }
        }
    }
}
