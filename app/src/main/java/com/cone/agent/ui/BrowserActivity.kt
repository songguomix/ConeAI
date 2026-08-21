package com.cone.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import com.cone.agent.MainActivity
import com.cone.agent.R
import com.cone.agent.ui.theme.ConeAgentTheme
import com.cone.agent.web.SearchEngine

/**
 * The app's built-in browser, shown **on screen** on purpose: the agent reads search results and
 * pages the same way it reads any app — through its screenshot + accessibility observation — so it
 * must be visible, not headless. The caller (agent / user) hands it a fully-resolved URL to load
 * plus the search engine to use for anything typed into the address bar. The chrome is plain Compose
 * Material3 to match the rest of the app.
 */
class BrowserActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var address by mutableStateOf("")
    private var loadProgress by mutableStateOf(0)
    private var engine: SearchEngine = SearchEngine.BAIDU

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = SearchEngine.from(intent?.getStringExtra(EXTRA_ENGINE)) ?: SearchEngine.BAIDU

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) this@BrowserActivity.address = url
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@BrowserActivity.loadProgress = newProgress
                }
            }
        }

        setContent {
            ConeAgentTheme {
                BrowserScreen(
                    webView = webView,
                    address = address,
                    progress = loadProgress,
                    onAddressChange = { address = it },
                    onGo = { load(engine.urlFor(it)) },
                    onReload = { webView.reload() },
                    onBack = { if (webView.canGoBack()) webView.goBack() else finish() },
                    onBackToMain = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        finish()
                    },
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        load(initialUrl(intent))
    }

    /** Reused while already open (singleTop): load the new target instead of the stale page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SearchEngine.from(intent.getStringExtra(EXTRA_ENGINE))?.let { engine = it }
        load(initialUrl(intent))
    }

    override fun onDestroy() {
        // A WebView outlives its Activity if not torn down explicitly — free it to avoid a leak.
        runCatching {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun initialUrl(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: engine.homeUrl

    private fun load(url: String) {
        webView.loadUrl(url)
        address = url
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_ENGINE = "extra_engine"

        /**
         * Opens the browser at [url] (already fully resolved by the caller). [engineId] is the search
         * engine to use for anything typed into the address bar.
         */
        fun intent(context: Context, url: String, engineId: String): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ENGINE, engineId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    webView: WebView,
    address: String,
    progress: Int,
    onAddressChange: (String) -> Unit,
    onGo: (String) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onBackToMain: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                    title = {
                        TextField(
                            value = address,
                            onValueChange = onAddressChange,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                            trailingIcon = {
                                if (address.isNotEmpty()) {
                                    IconButton(onClick = { onAddressChange("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo(address) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = {
                        TextButton(onClick = onBackToMain) {
                            Text(stringResource(R.string.browser_back_to_main))
                        }
                        IconButton(onClick = onReload) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_reload))
                        }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
