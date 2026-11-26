package com.jarves.mh.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.runtime.RuntimeInstaller
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PxBg = Color(0xFF0B0F14)
private val PxRed = Color(0xFFDC2626)
private val PxMuted = Color(0xFFA1A1AA)
private val PxKey = Color(0xFF161B22)

/**
 * Owns the PayNux-style terminal session shown by the bottom-bar Terminal tab.
 * It lives in MainViewModel so the shell survives tab switches.
 */
class PaynuxTerminalController(
    private val app: Application,
    private val installer: RuntimeInstaller,
    private val projectSlugs: () -> List<Pair<String, String>> = { emptyList() },
) : TerminalSessionClient, TerminalViewClient {

    var session by mutableStateOf<TerminalSession?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    internal var view: TerminalView? = null
    private var fontPx = (14 * app.resources.displayMetrics.density).toInt()
    private var starting = false

    init {
        // PayNux palette: #0B0F14 background, light text, red cursor.
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        colors[256] = 0xFFE5E7EB.toInt() // foreground
        colors[257] = 0xFF0B0F14.toInt() // background
        colors[258] = 0xFFDC2626.toInt() // cursor
    }

    val textSizePx: Int get() = fontPx

    /** Creates the session once; must be called on the main thread (TerminalSession needs a Looper). */
    suspend fun ensureStarted() {
        if (session != null || starting) return
        starting = true
        try {
            val slugs = projectSlugs()
            val launch = withContext(Dispatchers.IO) {
                if (!installer.isInstalled()) null else installer.interactiveShellLaunch(slugs)
            }
            if (launch == null) {
                message = "Linux runtime is not installed yet. Set it up first, then reopen this tab."
                return
            }
            message = null
            ended = false
            session = TerminalSession(
                launch.executable,
                launch.cwd,
                launch.args.toTypedArray(),
                launch.env.toTypedArray(),
                4000,
                this,
            )
        } catch (e: Exception) {
            message = "Could not start terminal: ${e.message.orEmpty()}"
        } finally {
            starting = false
        }
    }

    suspend fun restart() {
        session?.finishIfRunning()
        session = null
        ensureStarted()
    }

    fun shutdown() {
        session?.finishIfRunning()
    }

    fun paste() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    fun copyScreen() {
        val text = session?.emulator?.screen?.transcriptText?.trimEnd().orEmpty()
        if (text.isNotEmpty()) copyToClipboard(text)
    }

    fun showKeyboard() {
        view?.let {
            it.requestFocus()
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, 0)
        }
    }

    fun sendKey(keyCode: Int) {
        view?.handleKeyCode(keyCode, 0)
    }

    fun sendChar(c: Char) {
        view?.inputCodePoint(c.code, false, false)
    }

    private fun copyToClipboard(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PayNux terminal", text))
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { ended = true }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { if (!text.isNullOrEmpty()) copyToClipboard(text) }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { paste() }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ---- shared logging (both interfaces) ----
    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(tag, message.orEmpty(), e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "error", e) }

    // ---- TerminalViewClient ----
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val step = (2 * app.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val min = (8 * app.resources.displayMetrics.density).toInt()
            val max = (32 * app.resources.displayMetrics.density).toInt()
            fontPx = (fontPx + if (scale > 1f) step else -step).coerceIn(min, max)
            view?.setTextSize(fontPx)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrl.also { if (it) ctrl = false }
    override fun readAltKey(): Boolean = alt.also { if (it) alt = false }
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
}

@Composable
fun PaynuxTerminalScreen(controller: PaynuxTerminalController) {
    LaunchedEffect(controller) { controller.ensureStarted() }
    DisposableEffect(controller) { onDispose { controller.view = null } }

    Column(
        Modifier
            .fillMaxSize()
            .background(PxBg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        PaynuxTerminalHeader(controller)
        PaynuxStorageBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(controller)
                        setTextSize(controller.textSizePx)
                        setBackgroundColor(0xFF0B0F14.toInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = { v ->
                    controller.view = v
                    val s = controller.session
                    if (s != null && v.currentSession !== s) {
                        s.updateTerminalSessionClient(controller)
                        v.attachSession(s)
                        v.requestFocus()
                    }
                },
            )
            controller.message?.let {
                Text(
                    it,
                    color = PxMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
        PaynuxExtraKeys(controller)
    }
}

@Composable
private fun PaynuxTerminalHeader(controller: PaynuxTerminalController) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(PxRed),
            contentAlignment = Alignment.Center,
        ) { Text(">_", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Pay") }
                    withStyle(SpanStyle(color = PxRed)) { append("Nux") }
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text(
                if (controller.ended) "Session ended" else "Ubuntu · PRoot",
                color = PxMuted,
                fontSize = 10.sp,
            )
        }
        IconButton(onClick = { controller.copyScreen() }, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.ContentCopy, "Copy", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { controller.paste() }, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.ContentPaste, "Paste", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { controller.showKeyboard() }, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        IconButton(
            onClick = { scope.launch { controller.restart() } },
            modifier = Modifier.size(42.dp),
        ) {
            Icon(Icons.Default.Refresh, "New session", tint = if (controller.ended) PxRed else Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PaynuxExtraKeys(controller: PaynuxTerminalController) {
    Column(Modifier.fillMaxWidth().background(PxBg).padding(horizontal = 4.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ExtraKey("ESC") { controller.sendKey(KeyEvent.KEYCODE_ESCAPE) }
            ExtraKey("/") { controller.sendChar('/') }
            ExtraKey("-") { controller.sendChar('-') }
            ExtraKey("HOME") { controller.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            ExtraKey("↑") { controller.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            ExtraKey("END") { controller.sendKey(KeyEvent.KEYCODE_MOVE_END) }
            ExtraKey("PGUP") { controller.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ExtraKey("TAB") { controller.sendKey(KeyEvent.KEYCODE_TAB) }
            ExtraKey("CTRL", active = controller.ctrl) { controller.ctrl = !controller.ctrl }
            ExtraKey("ALT", active = controller.alt) { controller.alt = !controller.alt }
            ExtraKey("←") { controller.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            ExtraKey("↓") { controller.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            ExtraKey("→") { controller.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ExtraKey("PGDN") { controller.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        }
    }
}

@Composable
private fun RowScope.ExtraKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) PxRed else PxKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}

private fun hasAllFilesAccess(context: Context): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.os.Environment.isExternalStorageManager()
    } else {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** Shown only while shared storage (/sdcard, /storage) is still blocked for the terminal. */
@Composable
private fun PaynuxStorageBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by androidx.compose.runtime.remember { mutableStateOf(hasAllFilesAccess(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = hasAllFilesAccess(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val legacyLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasAllFilesAccess(context) }
    if (granted) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(PxKey)
            .clickable {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val pkg = android.net.Uri.parse("package:" + context.packageName)
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg,
                    )
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacyLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tap to allow storage access (/sdcard, /storage)",
            color = PxRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
