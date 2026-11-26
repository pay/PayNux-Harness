package com.jarves.mh.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.runtime.RuntimeInstaller
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PxBg = Color(0xFF0B0F14)
private val PxPanel = Color(0xFF111820)
private val PxRed = Color(0xFFDC2626)
private val PxOrange = Color(0xFFF48A4F)
private val PxMuted = Color(0xFFA1A1AA)
private val PxKey = Color(0xFF1B2430)
private val PxCircle = Color(0xFF2A3038)
private val PxLine = Color(0xFF3A414B)

private const val MAX_TABS = 8
private const val MAX_LOGS = 20

class PaynuxTab(val id: Int, val session: TerminalSession)

/**
 * Owns the PayNux-style terminal sessions (tabs) shown by the bottom-bar Terminal tab.
 * It lives in MainViewModel so the shells survive tab switches.
 */
class PaynuxTerminalController(
    private val app: Application,
    private val installer: RuntimeInstaller,
    private val projectSlugs: () -> List<Pair<String, String>> = { emptyList() },
) : TerminalSessionClient, TerminalViewClient {

    val tabs = mutableStateListOf<PaynuxTab>()
    var selectedId by mutableIntStateOf(-1)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    val currentSession: TerminalSession?
        get() = tabs.firstOrNull { it.id == selectedId }?.session

    internal var view: TerminalView? = null
    private var fontPx = (14 * app.resources.displayMetrics.density).toInt()
    private var starting = false
    private var shuttingDown = false
    private var nextId = 1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // PayNux palette: #0B0F14 background, light text, red cursor.
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        colors[256] = 0xFFE5E7EB.toInt() // foreground
        colors[257] = 0xFF0B0F14.toInt() // background
        colors[258] = 0xFFDC2626.toInt() // cursor
    }

    val textSizePx: Int get() = fontPx

    /** Creates the first tab once; must run on the main thread (TerminalSession needs a Looper). */
    suspend fun ensureStarted() {
        if (tabs.isNotEmpty() || starting) return
        addTab()
    }

    fun newTab() {
        scope.launch { addTab() }
    }

    private suspend fun addTab() {
        if (starting || tabs.size >= MAX_TABS) return
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
            val session = TerminalSession(
                launch.executable,
                launch.cwd,
                launch.args.toTypedArray(),
                launch.env.toTypedArray(),
                4000,
                this,
            )
            val tab = PaynuxTab(nextId++, session)
            tabs.add(tab)
            selectedId = tab.id
        } catch (e: Exception) {
            message = "Could not start terminal: ${e.message.orEmpty()}"
        } finally {
            starting = false
        }
    }

    fun selectTab(id: Int) {
        if (tabs.any { it.id == id }) selectedId = id
    }

    fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        removeTab(tab)
        tab.session.finishIfRunning()
    }

    private fun removeTab(tab: PaynuxTab) {
        saveLog(tab)
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (selectedId == tab.id) {
            selectedId = tabs.getOrNull((index - 1).coerceAtLeast(0))?.id ?: -1
        }
        if (tabs.isEmpty() && !shuttingDown) newTab()
    }

    fun shutdown() {
        shuttingDown = true
        tabs.toList().forEach {
            saveLog(it)
            it.session.finishIfRunning()
        }
        scope.cancel()
    }

    // ---- actions ----
    fun paste() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        if (!text.isNullOrEmpty()) currentSession?.emulator?.paste(text)
    }

    fun copyScreen() {
        val text = currentSession?.emulator?.screen?.transcriptText?.trimEnd().orEmpty()
        if (text.isNotEmpty()) copyToClipboard(text)
    }

    /** Saves the transcript to the log history, then clears the screen without confirmation. */
    fun clearScreen() {
        val tab = tabs.firstOrNull { it.id == selectedId } ?: return
        saveLog(tab)
        tab.session.reset()
        tab.session.write("\u000c") // Ctrl-L: ask the shell to redraw its prompt
    }

    fun sendEnter() {
        currentSession?.write("\r")
    }

    fun toggleKeyboard(visible: Boolean) {
        view?.let { v ->
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (visible) {
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            } else {
                v.requestFocus()
                imm.showSoftInput(v, 0)
            }
        }
    }

    fun showKeyboard() {
        view?.let {
            it.requestFocus()
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, 0)
        }
    }

    private fun consumeModifiers(): Int {
        var mod = 0
        if (ctrl) { mod = mod or KeyHandler.KEYMOD_CTRL; ctrl = false }
        if (alt) { mod = mod or KeyHandler.KEYMOD_ALT; alt = false }
        return mod
    }

    fun sendKey(keyCode: Int) {
        view?.handleKeyCode(keyCode, consumeModifiers())
    }

    fun sendChar(c: Char) {
        view?.inputCodePoint(c.code, false, false)
    }

    private fun copyToClipboard(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PayNux terminal", text))
    }

    // ---- log history (survives app restarts) ----
    private val logDir: File get() = File(app.filesDir, "paynux-terminal-logs")

    private fun saveLog(tab: PaynuxTab) {
        runCatching {
            val text = tab.session.emulator?.screen?.transcriptText?.trimEnd().orEmpty()
            if (text.isBlank()) return
            val dir = logDir.apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "$stamp-tab${tab.id}.log").writeText(text.takeLast(200_000))
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_LOGS)?.forEach { it.delete() }
        }
    }

    fun listLogs(): List<File> =
        logDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Prints a saved log into the current terminal screen (display only, nothing is executed). */
    fun loadLog(file: File) {
        val emulator = currentSession?.emulator ?: return
        val body = runCatching { file.readText() }.getOrNull().orEmpty()
            .takeLast(100_000).replace("\r\n", "\n").replace("\n", "\r\n")
        val bytes = ("\r\n--- saved log: ${file.name} ---\r\n$body\r\n--- end of saved log ---\r\n").toByteArray()
        emulator.append(bytes, bytes.size)
        view?.onScreenUpdated()
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === currentSession) view?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        tabs.firstOrNull { it.session === finishedSession }?.let { removeTab(it) }
    }
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
            val density = app.resources.displayMetrics.density
            val step = (2 * density).toInt().coerceAtLeast(1)
            fontPx = (fontPx + if (scale > 1f) step else -step).coerceIn((8 * density).toInt(), (32 * density).toInt())
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaynuxTerminalScreen(controller: PaynuxTerminalController) {
    LaunchedEffect(controller) { controller.ensureStarted() }
    DisposableEffect(controller) { onDispose { controller.view = null } }
    val imeVisible = WindowInsets.isImeVisible
    var showHistory by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(PxBg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        PaynuxToolbar(controller, imeVisible, onHistory = { showHistory = true })
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(PxRed, PxOrange))),
        )
        PaynuxTabs(controller)
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
                    val s = controller.currentSession
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
    if (showHistory) PaynuxHistoryDialog(controller) { showHistory = false }
}

@Composable
private fun PaynuxToolbar(controller: PaynuxTerminalController, imeVisible: Boolean, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PxOrange)) { append("> ") }
                withStyle(SpanStyle(color = Color.White)) { append("Chris Pay") }
                withStyle(SpanStyle(color = PxRed)) { append("Nux") }
            },
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CircleButton(Icons.Default.History, "Saved logs", PxCircle, Color.White, onHistory)
        CircleButton(Icons.Default.ContentCopy, "Copy", PxCircle, Color.White) { controller.copyScreen() }
        CircleButton(Icons.Default.ContentPaste, "Paste", PxCircle, Color.White) { controller.paste() }
        CircleButton(Icons.Default.Delete, "Clear", Color(0xFF3A1417), PxRed) { controller.clearScreen() }
        CircleButton(Icons.Default.KeyboardReturn, "Enter", PxCircle, Color.White) { controller.sendEnter() }
        CircleButton(Icons.Default.Keyboard, "Keyboard", Color(0xFF3A2414), PxOrange) { controller.toggleKeyboard(imeVisible) }
    }
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaynuxTabs(controller: PaynuxTerminalController) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            controller.tabs.forEach { tab ->
                key(tab.id) {
                    val active = tab.id == controller.selectedId
                    var menu by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(34.dp)
                            .combinedClickable(onClick = { controller.selectTab(tab.id) }, onLongClick = { menu = true }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(26.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    brush = if (active) Brush.horizontalGradient(listOf(PxRed, PxOrange)) else SolidColor(PxLine),
                                ),
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Close session", color = PxRed) },
                                onClick = {
                                    menu = false
                                    controller.closeTab(tab.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.size(width = 40.dp, height = 34.dp).clickable { controller.newTab() },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = PxOrange, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun PaynuxExtraKeys(controller: PaynuxTerminalController) {
    val pager = rememberPagerState(pageCount = { 2 })
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF2B3440), RoundedCornerShape(14.dp))
            .background(PxPanel)
            .padding(6.dp),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (page == 0) {
                    KeyRow {
                        ExtraKey("ESC") { controller.sendKey(KeyEvent.KEYCODE_ESCAPE) }
                        ExtraKey("TAB") { controller.sendKey(KeyEvent.KEYCODE_TAB) }
                        ExtraKey("HOME") { controller.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
                        ExtraKey("↑") { controller.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
                        ExtraKey("END") { controller.sendKey(KeyEvent.KEYCODE_MOVE_END) }
                        ExtraKey("PGUP") { controller.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
                    }
                    KeyRow {
                        ExtraKey("CTRL", active = controller.ctrl) { controller.ctrl = !controller.ctrl }
                        ExtraKey("ALT", active = controller.alt) { controller.alt = !controller.alt }
                        ExtraKey("←") { controller.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
                        ExtraKey("↓") { controller.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                        ExtraKey("→") { controller.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
                        ExtraKey("PGDN") { controller.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
                    }
                } else {
                    KeyRow { "/-|~_:".forEach { c -> ExtraKey(c.toString()) { controller.sendChar(c) } } }
                    KeyRow { "\\$=\"'*".forEach { c -> ExtraKey(c.toString()) { controller.sendChar(c) } } }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(2) { index ->
                val on = pager.currentPage == index
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (on) 16.dp else 7.dp, height = 7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) PxOrange else PxLine),
                )
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun RowScope.ExtraKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) PxOrange else PxKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PaynuxHistoryDialog(controller: PaynuxTerminalController, onDismiss: () -> Unit) {
    val logs = remember { controller.listLogs() }
    val format = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PxPanel,
        titleContentColor = Color.White,
        textContentColor = PxMuted,
        title = { Text("Saved logs") },
        text = {
            if (logs.isEmpty()) {
                Text("No saved logs yet. A log is saved when you clear the screen or close a session.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    logs.forEach { file ->
                        Text(
                            "${format.format(Date(file.lastModified()))} · ${(file.length() / 1024).coerceAtLeast(1)} KB",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.loadLog(file)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = PxOrange) } },
    )
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
