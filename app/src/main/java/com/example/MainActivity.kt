package com.example

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.BanglaStoreApp
import com.example.ui.BanglaStoreViewModel
import com.example.ui.theme.BanglaStoreTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BanglaStoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.handleLaunchIntent(intent)
        setContent {
            BanglaStoreTheme {
                BanglaStoreApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleLaunchIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        runCatching { CookieManager.getInstance().flush() }
    }
}
