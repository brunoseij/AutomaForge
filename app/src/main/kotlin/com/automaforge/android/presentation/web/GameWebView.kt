package com.automaforge.android.presentation.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.automaforge.android.domain.model.*
import java.util.Locale

internal class Speaker(context: android.content.Context) : TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) engine?.language = Locale("pt", "BR")
    }

    fun speak(text: String) {
        engine?.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "automa")
    }

    fun stop() {
        engine?.stop()
    }

    fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

internal class WebBridge(
    private val connection: SessionConnection,
    private val speaker: Speaker,
    private val onSettings: () -> Unit,
    private val onCamera: (CameraRequest) -> Unit,
    private val onRoute: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun lanAddress() = "http://${connection.publicAddress}:${NetworkPolicy.PORT}"

    @JavascriptInterface
    fun configureAi() {
        if (connection.target.controller) main.post(onSettings)
    }

    @JavascriptInterface
    fun speak(text: String?) {
        if (!text.isNullOrBlank()) main.post { speaker.speak(text) }
    }

    @JavascriptInterface
    fun stopSpeech() {
        main.post { speaker.stop() }
    }

    @JavascriptInterface
    fun sessionLocation(url: String?) {
        if (url != null && NetworkPolicy.sameOrigin(url, connection.target.host))
            main.post { onRoute(url) }
    }

    @JavascriptInterface
    fun launchCamera(role: String?, room: String?) {
        val request = CameraRequest(connection.target.host, room.orEmpty(), role.orEmpty())
        if (request.valid()) main.post { onCamera(request) }
    }
}

@Suppress("SetJavaScriptEnabled")
@Composable
fun GameWebView(
    connection: SessionConnection,
    currentUrl: String,
    reloadEpoch: Int,
    modifier: Modifier,
    onSettings: () -> Unit,
    onCamera: (CameraRequest) -> Unit,
    onRoute: (String) -> Unit,
    onLeave: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val data = result.data
            val uris =
                if (result.resultCode != Activity.RESULT_OK || data == null) null
                else
                    data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                        ?: data.data?.let { arrayOf(it) }
            chooser?.onReceiveValue(uris)
            chooser = null
        }
    val speaker = remember { Speaker(context) }
    val web =
        remember(connection) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.mediaPlaybackRequiresUserGesture = false
                addJavascriptInterface(
                    WebBridge(connection, speaker, onSettings, onCamera, onRoute),
                    "AutomaAndroid",
                )
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean =
                            !NetworkPolicy.sameOrigin(
                                request.url.toString(),
                                connection.target.host,
                            )

                        override fun onPageFinished(view: WebView, url: String) {
                            onRoute(url)
                        }
                    }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams,
                        ): Boolean {
                            chooser?.onReceiveValue(null)
                            chooser = callback
                            try {
                                val manual = params.acceptTypes.any { it.contains("pdf") }
                                val pick =
                                    Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = if (manual) "*/*" else "image/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        if (manual)
                                            putExtra(
                                                Intent.EXTRA_MIME_TYPES,
                                                arrayOf("application/pdf", "image/*"),
                                            )
                                    }
                                launcher.launch(
                                    Intent.createChooser(pick, "Selecionar manual ou fotografia")
                                )
                            } catch (failure: Exception) {
                                chooser = null
                                callback.onReceiveValue(null)
                                onError("Não foi possível selecionar o arquivo.")
                            }
                            return true
                        }
                    }
                tag = reloadEpoch
                loadUrl(currentUrl)
            }
        }
    BackHandler { if (web.canGoBack()) web.goBack() else onLeave() }
    DisposableEffect(web) {
        onDispose {
            chooser?.onReceiveValue(null)
            chooser = null
            web.stopLoading()
            web.removeJavascriptInterface("AutomaAndroid")
            web.destroy()
            speaker.close()
        }
    }
    AndroidView(
        factory = { web },
        modifier = modifier,
        update = {
            if (it.tag != reloadEpoch) {
                it.tag = reloadEpoch
                it.reload()
            }
        },
    )
}
