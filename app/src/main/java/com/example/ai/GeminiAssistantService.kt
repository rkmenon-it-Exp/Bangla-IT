package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface AiGenerationUiState {
    data object Idle : AiGenerationUiState
    data object Loading : AiGenerationUiState
    data class Streaming(val text: String) : AiGenerationUiState
    data object Success : AiGenerationUiState
    data class Error(val message: String) : AiGenerationUiState
}

data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val hasImage: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class AiAssistantUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val generationState: AiGenerationUiState = AiGenerationUiState.Idle,
    val attachedBitmap: Bitmap? = null
)

class GeminiAssistantService {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            systemInstruction = content {
                text(
                    """
                    You are an intelligent, concise bilingual (Bengali and English) business & ISP operations assistant built into the Bangla Store Android app (banglait.net).
                    Help store owners, admins, and staff with:
                    - Drafting clear Bengali or English staff attendance reminders, task instructions, and customer notices.
                    - Summarizing daily shop sales, expenses, stock checks, and billing calculations.
                    - Drafting polite ISP customer support responses (router reboot, fiber check, bill reminder).
                    - Analyzing attached photos of bills, receipts, stock lists, or network equipment.
                    Keep answers practical, accurate, and easy to copy-paste. Reply in Bengali by default when the user writes in Bengali or asks general store/ISP questions.
                    """.trimIndent()
                )
            }
        )
    }

    private var activeChat: Chat? = null

    private fun getOrCreateChat(): Chat {
        return activeChat ?: model.startChat().also { activeChat = it }
    }

    fun sendMessageStream(prompt: String, image: Bitmap?): Flow<String> {
        val chat = getOrCreateChat()
        val requestContent: Content = content {
            if (image != null) {
                image(image)
            }
            text(prompt)
        }
        return kotlinx.coroutines.flow.flow {
            chat.sendMessageStream(requestContent).collect { chunk ->
                val chunkText = chunk.text
                if (!chunkText.isNullOrEmpty()) {
                    emit(chunkText)
                }
            }
        }
    }

    fun resetConversation() {
        activeChat = null
    }

    suspend fun decodeScaledBitmap(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int = 1280
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            val rawWidth = boundsOptions.outWidth
            val rawHeight = boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return@withContext null

            var sampleSize = 1
            while (rawWidth / sampleSize > maxDimensionPx || rawHeight / sampleSize > maxDimensionPx) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            val scale = minOf(
                maxDimensionPx.toFloat() / decoded.width,
                maxDimensionPx.toFloat() / decoded.height,
                1f
            )
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else {
                decoded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode attached image", e)
            null
        }
    }

    companion object {
        private const val TAG = "GeminiAssistantService"
        private const val MODEL_NAME = "gemini-3.8-flash"
    }
}
