package com.somecatcode.ebookreader.reader.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.somecatcode.ebookreader.reader.BookRef
import com.somecatcode.ebookreader.reader.BookSource
import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderHostImpl
import com.somecatcode.ebookreader.reader.ReaderRequestProxyImpl
import com.somecatcode.ebookreader.reader.ReaderSettings
import com.somecatcode.ebookreader.reader.ReaderView
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only harness: opens the bundled `sample.fb2` through the real WebView + request proxy and logs
 * every page event (tag `ReaderDebug`). Start with
 * `adb shell am start -n com.somecatcode.ebookreader.debug/com.somecatcode.ebookreader.reader.debug.ReaderDebugActivity [--ez eink true] [--es theme dark]`.
 * Further commands via `--es cmd next|prev` on a new intent.
 */
class ReaderDebugActivity : ComponentActivity() {

    private lateinit var host: ReaderHostImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(cacheDir, "sample.fb2")
        assets.open("sample.fb2").use { input -> file.outputStream().use { input.copyTo(it) } }
        val proxy = ReaderRequestProxyImpl(backendFor = { _, _ -> null }, localFile = { _, _ -> file })
        proxy.bind("debug", 1, online = false)
        host = ReaderHostImpl(proxy)
        lifecycleScope.launch {
            host.events.collect { Log.i("ReaderDebug", it.toString().take(300)) }
        }
        host.send(
            HostToReader.Open(
                book = BookRef("debug", 1, "fb2", "Sample"),
                source = BookSource.File(fileName = "sample.fb2"),
                settings = ReaderSettings(
                    theme = intent.getStringExtra("theme") ?: "auto",
                    einkMode = intent.getBooleanExtra("eink", false),
                    flow = intent.getStringExtra("flow") ?: "paginated",
                ),
            ),
        )
        setContent { ReaderView(host, Modifier.fillMaxSize(), volumeKeysTurnPages = true) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("cmd")) {
            "next" -> host.send(HostToReader.Next)
            "prev" -> host.send(HostToReader.Prev)
            "toc" -> host.send(HostToReader.GoTo(href = "c3.xhtml"))
        }
    }

    override fun onDestroy() {
        host.destroy()
        super.onDestroy()
    }
}
