package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.AiAssistantUiState
import com.example.ai.AiChatMessage
import com.example.ai.AiGenerationUiState
import com.example.model.AppWorkspace

private data class QuickAiPrompt(
    val label: String,
    val prompt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantBottomSheet(
    aiState: AiAssistantUiState,
    activeWorkspace: AppWorkspace,
    onAttachImageUri: (android.net.Uri?) -> Unit,
    onClearImage: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onClearChat: () -> Unit,
    onCopiedMessage: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    var promptInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAttachImageUri(uri)
        }
    }

    val isBusy = aiState.generationState is AiGenerationUiState.Loading ||
        aiState.generationState is AiGenerationUiState.Streaming

    val quickPrompts = remember(activeWorkspace) {
        if (activeWorkspace == AppWorkspace.ISP) {
            listOf(
                QuickAiPrompt(
                    label = "ISP কাস্টমার রিপ্লাই",
                    prompt = "ইন্টারনেট স্লো হওয়া বা রাউটার রিস্টার্ট নিয়ে কাস্টমারের জন্য একটি ভদ্র বাংলা সাপোর্ট মেসেজ লিখে দাও।"
                ),
                QuickAiPrompt(
                    label = "বকেয়া বিল রিমাইন্ডার",
                    prompt = "ISP মাসিক ইন্টারনেট বিল পরিশোধের জন্য কাস্টমারকে পাঠানোর উপযোগী একটি সংক্ষিপ্ত ও ভদ্র বাংলা এসএমএস/হোয়াটসঅ্যাপ রিমাইন্ডার লিখে দাও।"
                ),
                QuickAiPrompt(
                    label = "নেটওয়ার্ক ডাউন নোটিশ",
                    prompt = "ফাইবার মেইনটেন্যান্সের কারণে সাময়িক ইন্টারনেট বিচ্ছিন্ন থাকবে — গ্রাহকদের জন্য একটি পেশাদার বাংলা নোটিশ তৈরি করো।"
                )
            )
        } else {
            listOf(
                QuickAiPrompt(
                    label = "স্টাফ অ্যাটেন্ডেন্স নোটিশ",
                    prompt = "স্টাফদের সময়মতো অ্যাপ থেকে GPS অ্যাটেন্ডেন্স জমা দেওয়ার জন্য একটি সংক্ষিপ্ত ও আন্তরিক বাংলা নোটিশ লিখে দাও।"
                ),
                QuickAiPrompt(
                    label = "টাস্ক চেকলিস্ট",
                    prompt = "দোকানের প্রতিদিনের ওপেনিং এবং ক্লোজিং স্টাফ টাস্কের জন্য একটি সহজ চেকলিস্ট তৈরি করে দাও।"
                ),
                QuickAiPrompt(
                    label = "দৈনিক হিসাব সারাংশ",
                    prompt = "দোকানের দৈনিক বিক্রি, বাকি ও খরচের হিসাব সাজিয়ে রাখার একটি পরিষ্কার ফরম্যাট দেখাও।"
                )
            )
        }
    }

    val streamingText = (aiState.generationState as? AiGenerationUiState.Streaming)?.text.orEmpty()
    LaunchedEffect(aiState.messages.size, streamingText.length, aiState.generationState) {
        val totalItems = aiState.messages.size + if (isBusy) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.ai_assistant_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${activeWorkspace.labelBn} • Gemini 3.8 Flash",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (aiState.messages.isNotEmpty() && !isBusy) {
                        IconButton(
                            onClick = onClearChat,
                            modifier = Modifier.testTag("ai_clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.cd_ai_clear_chat)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("ai_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_ai_close)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Quick prompts
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(quickPrompts) { item ->
                    AssistChip(
                        onClick = {
                            if (!isBusy) {
                                onSendPrompt(item.prompt)
                            }
                        },
                        enabled = !isBusy,
                        label = { Text(item.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chat History / Welcome / Error
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
                    .testTag("ai_messages_list")
            ) {
                if (aiState.messages.isEmpty() && !isBusy && aiState.generationState !is AiGenerationUiState.Error) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "আপনার স্টোর ও ISP কাজের AI সহায়ক",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "দোকানের হিসাব, স্টাফ নোটিশ, টাস্ক চেকলিস্ট, ISP কাস্টমার রিপ্লাই লিখতে বা রসিদ/রাউটারের ছবি বিশ্লেষণ করতে নিচে প্রশ্ন লিখুন অথবা উপরের দ্রুত বাটনে ট্যাপ করুন।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                items(aiState.messages, key = { it.id }) { msg ->
                    AiMessageBubble(
                        message = msg,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.text))
                            onCopiedMessage()
                        }
                    )
                }

                if (aiState.generationState is AiGenerationUiState.Loading) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .testTag("ai_loading_row")
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = "Gemini চিন্তা করছে...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (aiState.generationState is AiGenerationUiState.Streaming) {
                    item {
                        AiMessageBubble(
                            message = AiChatMessage(
                                id = "streaming_preview",
                                isUser = false,
                                text = aiState.generationState.text
                            ),
                            onCopy = {}
                        )
                    }
                }

                if (aiState.generationState is AiGenerationUiState.Error) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_error_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = aiState.generationState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // Attached Image Thumbnail Preview
            aiState.attachedBitmap?.let { bmp ->
                Spacer(modifier = Modifier.height(8.dp))
                AttachedImagePreview(
                    bitmap = bmp,
                    onRemove = onClearImage
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("ai_attach_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.cd_ai_attach_image),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            text = "Gemini-কে লিখুন...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    enabled = !isBusy,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_prompt_input")
                )

                IconButton(
                    onClick = {
                        val trimmed = promptInput.trim()
                        val effectivePrompt = if (trimmed.isNotEmpty()) {
                            trimmed
                        } else if (aiState.attachedBitmap != null) {
                            "এই ছবিটি বিশ্লেষণ করে মূল তথ্যগুলো বাংলায় সংক্ষেপে লিখে দাও।"
                        } else {
                            ""
                        }
                        if (effectivePrompt.isNotEmpty()) {
                            promptInput = ""
                            onSendPrompt(effectivePrompt)
                        }
                    },
                    enabled = !isBusy && (promptInput.isNotBlank() || aiState.attachedBitmap != null),
                    modifier = Modifier.testTag("ai_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.cd_ai_send),
                        tint = if (!isBusy && (promptInput.isNotBlank() || aiState.attachedBitmap != null)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiMessageBubble(
    message: AiChatMessage,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = if (message.isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (message.isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (message.hasImage) {
                    Text(
                        text = "[সংযুক্ত ছবি]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!message.isUser && message.id != "streaming_preview") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onCopy,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "কপি",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachedImagePreview(
    bitmap: Bitmap,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Attached image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Text(
                    text = "ছবি সংযুক্ত করা হয়েছে",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove attached image"
                )
            }
        }
    }
}
