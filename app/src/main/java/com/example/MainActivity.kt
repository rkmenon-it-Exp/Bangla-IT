package com.example

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import com.example.sync.NetworkClientProvider
import com.example.ui.BanglaStoreApp
import com.example.ui.BanglaStoreViewModel
import com.example.ui.theme.BanglaStoreTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BanglaStoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .okHttpClient { NetworkClientProvider.getClient(applicationContext) }
                .crossfade(true)
                .build()
        )
        viewModel.handleLaunchIntent(intent)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            BanglaStoreTheme(brandPrimaryHex = uiState.config.themeColorHex) {
                BanglaStoreApp(
                    viewModel = viewModel,
                    onRebootApp = ::rebootApp
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun rebootApp() {
        runCatching { CookieManager.getInstance().flush() }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val component = launchIntent?.component
        if (component != null) {
            val restartIntent = Intent.makeRestartActivityTask(component)
            startActivity(restartIntent)
            finishAffinity()
            Process.killProcess(Process.myPid())
        } else {
            recreate()
        }
    }
}
