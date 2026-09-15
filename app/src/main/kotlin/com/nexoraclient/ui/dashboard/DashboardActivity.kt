package com.rubidiumclient.ui.dashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.rubidiumclient.auth.AccountManager
import com.rubidiumclient.auth.AuthState
import com.rubidiumclient.auth.DeviceCodeLoginActivity
import com.rubidiumclient.auth.MicrosoftAuthManager
import com.rubidiumclient.auth.SavedAccount
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.config.Config
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.LanServerInfo
import com.rubidiumclient.core.relay.LanServerScanner
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.session.SessionManager
import com.rubidiumclient.ui.overlay.OverlayService
import com.rubidiumclient.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (China)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

data class InstalledAppInfo(
    val packageName : String,
    val label        : String
)

/** Remembers which app the relay should target, across app restarts. */
private object SelectedAppStore {
    private const val PREFS_NAME = "rubidiumclient_prefs"
    private const val KEY_SELECTED_PACKAGE = "selected_package"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PACKAGE, null)

    fun set(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_PACKAGE, packageName).apply()
    }
}

/** The two overlay HUD layouts the user can pick between in Settings. Only the
 *  preference is stored here for now — OverlayService doesn't read it yet. */
enum class OverlayUiStyle(val label: String, val description: String) {
    CLASSIC("Classic", "Classic"),
    GRID("Grid Menu", "Grid")
}


/** Remembers the user's chosen overlay UI style, across app restarts. */
object OverlayUiStore {
    private const val PREFS_NAME = "rubidiumclient_prefs"
    private const val KEY_UI_STYLE = "overlay_ui_style"

    fun get(context: Context): OverlayUiStyle {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_UI_STYLE, null)
        return OverlayUiStyle.values().firstOrNull { it.name == saved } ?: OverlayUiStyle.CLASSIC
    }

    fun set(context: Context, style: OverlayUiStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_UI_STYLE, style.name).apply()
    }
}

private const val HEARTBEAT_URL = "https://oxclient.com.tr/heartbeat"
private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 dakika

/** POSTs a single "I'm active" ping with the signed-in gamertag. Best-effort — failures are swallowed. */
private fun sendActiveHeartbeat() {
    val gamertag = AccountManager.selectedAccount?.gamertag ?: return
    try {
        val url = java.net.URL(HEARTBEAT_URL)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val body = org.json.JSONObject().put("nametag", gamertag).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        conn.responseCode // trigger the request
        conn.disconnect()
    } catch (_: Exception) {
        // sunucuya ulaşılamıyorsa sessizce yut, bir sonraki tick'te tekrar denenecek
    }
}

/** Sends the active heartbeat immediately, then every HEARTBEAT_INTERVAL_MS while collected. */
private suspend fun activeHeartbeatLoop() {
    while (true) {
        withContext(Dispatchers.IO) { sendActiveHeartbeat() }
        delay(HEARTBEAT_INTERVAL_MS)
    }
}

private enum class DashTab { RELAY, CONFIG, ACCOUNTS, SETTINGS }

class DashboardActivity : ComponentActivity() {

    private var overlayPermissionGranted by mutableStateOf(false)
    private var selectedPackage by mutableStateOf("com.mojang.minecraftpe")

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { overlayPermissionGranted = Settings.canDrawOverlays(this) }

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        overlayPermissionGranted = Settings.canDrawOverlays(this)

        // Restore the last app the user picked; fall back to whichever
        // supported package is actually installed, then plain Minecraft.
        selectedPackage = SelectedAppStore.get(this)
            ?: getInstalledGames().firstOrNull()?.first
            ?: SUPPORTED_PACKAGES.first().first

        lifecycleScope.launch {
            MicrosoftAuthManager.authState.collect { state ->
                if (state is AuthState.WaitingForWebView) {
                    startActivity(Intent(this@DashboardActivity, DeviceCodeLoginActivity::class.java))
                }
            }
        }

        // Sends a lightweight "I'm active" heartbeat to the server every 15
        // minutes while this activity is open, tagged with the signed-in
        // account's gamertag (so active users are visible server-side).
        lifecycleScope.launch { activeHeartbeatLoop() }

        setContent {
            RubidiumClientTheme {
                val authState   by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
                val relayActive by SessionManager.isActive.collectAsStateWithLifecycle()

                DashboardScreen(
                        installedApps   = getInstalledGames(),
                        allApps         = getAllInstalledApps(),
                        selectedPackage = selectedPackage,
                        onSelectApp     = { pkg ->
                            selectedPackage = pkg
                            SelectedAppStore.set(this, pkg)
                        },
                        relayActive   = relayActive,
                        onConnect     = { pkg -> startRelay(pkg) },
                        onDisconnect  = { stopRelay() },
                        onLaunchApp   = { pkg -> launchApp(pkg) },
                        onSignIn      = { MicrosoftAuthManager.startSignIn() },
                        onSignOut     = { MicrosoftAuthManager.signOut() },
                        onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() },
                        onSelectAccount = { account -> MicrosoftAuthManager.switchAccount(account) },
                        overlayPermissionGranted   = overlayPermissionGranted,
                        onRequestOverlayPermission = { requestOverlayPermission() }
                    )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
    }

    private fun startRelay(targetPkg: String) {
        stopRelay()
        EntityTracker.init()

        lifecycleScope.launch(Dispatchers.IO) {
            SessionManager.start()
        }

        OverlayService.start(this)
    }

    private fun launchApp(targetPkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(targetPkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } else {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun stopRelay() {
        // SessionManager.stop() now updates state immediately and does the heavy
        // Netty shutdown work on its own background scope (see SessionManager.kt),
        // so it doesn't block the main thread here; no need to wrap it in
        // lifecycleScope (it would be cancelled inside onDestroy() anyway).
        SessionManager.stop()
        PacketEventBus.clear()
        EntityTracker.reset()
        OverlayService.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRelay()
    }

    private fun getInstalledGames(): List<Pair<String, String>> =
        SUPPORTED_PACKAGES.filter { (pkg, _) ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else
                    @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }

    /** Every launchable app on the device, for the "Select an Application" picker. */
    private fun getAllInstalledApps(): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") packageManager.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                InstalledAppInfo(
                    packageName = info.activityInfo.packageName,
                    label       = info.loadLabel(packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    installedApps : List<Pair<String, String>>,
    allApps         : List<InstalledAppInfo> = emptyList(),
    selectedPackage : String,
    onSelectApp     : (String) -> Unit,
    relayActive   : Boolean = false,
    onConnect     : (String) -> Unit,
    onDisconnect  : () -> Unit,
    onLaunchApp   : (String) -> Unit,
    onSignIn      : () -> Unit,
    onSignOut     : () -> Unit,
    onCancelAuth  : () -> Unit,
    onSelectAccount : (SavedAccount) -> Unit,
    overlayPermissionGranted   : Boolean = true,
    onRequestOverlayPermission : () -> Unit = {}
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val scope          = rememberCoroutineScope()
    val serverHost    by ServerConfig.host.collectAsState(initial = ServerConfig.DEFAULT_HOST)
    val serverPort    by ServerConfig.port.collectAsState(initial = ServerConfig.DEFAULT_PORT)
    val recentServers by ServerConfig.recents.collectAsState(initial = emptyList())
    val savedAccounts     by AccountManager.accountsFlow.collectAsStateWithLifecycle()
    val selectedGamertag  by AccountManager.selectedGamertagFlow.collectAsStateWithLifecycle()

    val accountLoggedIn = selectedGamertag != null &&
        savedAccounts.any { it.gamertag == selectedGamertag && it.isRelayReady() }

    var showSignIn      by remember { mutableStateOf(false) }
    var showServerPanel by remember { mutableStateOf(false) }
    var showAppPicker   by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { DashTab.values().size })
    val currentTab = DashTab.values()[pagerState.currentPage]

    LaunchedEffect(authState) {
        if (authState is AuthState.WaitingForWebView) showSignIn = false
    }

    if (showSignIn) {
        AuthDialog(
            authState = authState,
            onDismiss = { showSignIn = false },
            onSignIn  = onSignIn,
            onSignOut = { onSignOut(); showSignIn = false },
            onCancel  = { onCancelAuth(); showSignIn = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(RubidiumBackground)) {
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)
            .background(Brush.verticalGradient(listOf(RubidiumAccentDark.copy(0.30f), Color.Transparent))))

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                HorizontalPager(
                    state    = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondBoundsPageCount = 1
                ) { page ->
                    when (DashTab.values()[page]) {
                        DashTab.RELAY -> DashboardTab(
                            relayActive          = relayActive,
                            selectedPackage      = selectedPackage,
                            onOpenAppPicker      = { showAppPicker = true },
                            onToggle             = { if (relayActive) onDisconnect() else onConnect(selectedPackage) },
                            onLaunchApp          = { onLaunchApp(selectedPackage) },
                            showServerPanel      = showServerPanel,
                            onToggleServerPanel  = { showServerPanel = !showServerPanel },
                            serverHost           = serverHost,
                            serverPort           = serverPort,
                            recentServers        = recentServers,
                            onSaveServer         = { h, p -> scope.launch { ServerConfig.save(h, p) }; showServerPanel = false },
                            onResetServer        = { scope.launch { ServerConfig.reset() } },
                            onDismissServerPanel = { showServerPanel = false },
                            overlayPermissionGranted   = overlayPermissionGranted,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            accountLoggedIn            = accountLoggedIn,
                            onRequestAccountLogin       = { showSignIn = true }
                        )
                        DashTab.ACCOUNTS -> AccountsTab(
                            accounts         = savedAccounts,
                            selectedGamertag = selectedGamertag,
                            onSelectAccount  = onSelectAccount,
                            onAddAccount     = { showSignIn = true }
                        )
                        DashTab.CONFIG -> ConfigTab()
                        DashTab.SETTINGS -> SettingsTab()
                    }
                }
            }
            BottomTabBar(
                current  = currentTab,
                onSelect = { tab ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page = tab.ordinal,
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                        )
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = showAppPicker,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            AppPickerScreen(
                apps       = allApps,
                onSelect   = { pkg -> onSelectApp(pkg); showAppPicker = false },
                onDismiss  = { showAppPicker = false }
            )
        }
    }
}


@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = trailing
    )
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = RubidiumOnBackground,
        fontFamily = FontFamily.Monospace
    )
    if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = RubidiumOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
    }
    Spacer(Modifier.height(20.dp))
}

/** Header used on the Dashboard tab: keeps the version number on the same line as the
 *  title (instead of wrapping below it). */
@Composable
private fun DashboardHeader(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = RubidiumOnBackground,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AddIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RubidiumAccentLight)
    }
}

@Composable
private fun DashboardTab(
    relayActive          : Boolean,
    selectedPackage      : String,
    onOpenAppPicker      : () -> Unit,
    onToggle             : () -> Unit,
    onLaunchApp          : () -> Unit,
    showServerPanel      : Boolean,
    onToggleServerPanel  : () -> Unit,
    serverHost           : String,
    serverPort           : Int,
    recentServers        : List<Pair<String, Int>>,
    onSaveServer         : (String, Int) -> Unit,
    onResetServer        : () -> Unit,
    onDismissServerPanel : () -> Unit,
    overlayPermissionGranted   : Boolean = true,
    onRequestOverlayPermission : () -> Unit = {},
    accountLoggedIn       : Boolean = true,
    onRequestAccountLogin : () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "Eclient"
        )

        AnimatedVisibility(
            visible = !accountLoggedIn,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                DashboardWarningBanner(
                    message = "Account Login Required",
                    onClick = onRequestAccountLogin
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        AnimatedVisibility(
            visible = !overlayPermissionGranted,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                DashboardWarningBanner(
                    message = "Overlay Permission Required",
                    onClick = onRequestOverlayPermission
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        SelectedServerCard(host = serverHost, port = serverPort, onClick = onToggleServerPanel)
        Spacer(Modifier.height(16.dp))

        SelectedApplicationCard(packageName = selectedPackage, onClick = onOpenAppPicker)
        Spacer(Modifier.height(16.dp))

        if (showServerPanel) {
            Dialog(onDismissRequest = onDismissServerPanel) {
                ServerSettingsPanel(
                    currentHost   = serverHost,
                    currentPort   = serverPort,
                    recentServers = recentServers,
                    onSave        = onSaveServer,
                    onReset       = onResetServer,
                    onDismiss     = onDismissServerPanel
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = relayActive,
                enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ConnectedBanner(onLaunchApp = onLaunchApp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ConnectButton(running = relayActive, onToggle = onToggle)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        } catch (_: Exception) { null }
    }
}

private fun appLabelOf(context: Context, packageName: String): String = try {
    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(appInfo).toString()
} catch (_: Exception) { packageName }

private fun versionNameOf(context: Context, packageName: String): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    else
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0).versionName
} catch (_: Exception) { null }

@Composable
private fun SelectedApplicationCard(packageName: String, onClick: () -> Unit) {
    val context   = LocalContext.current
    val icon      = rememberAppIcon(packageName)
    val label     = remember(packageName) { appLabelOf(context, packageName) }
    val version   = remember(packageName) { versionNameOf(context, packageName) }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Text(
            "Selected Application",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = RubidiumOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(RubidiumBackground),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp))
                } else {
                    Text("📦", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = RubidiumOnSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    packageName,
                    fontSize = 10.sp,
                    color = RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Change ›", fontSize = 11.sp, color = RubidiumAccentLight, fontFamily = FontFamily.Monospace)
        }
        if (version != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Current: v$version",
                fontSize = 11.sp,
                color = RubidiumOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SelectedServerCard(host: String, port: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Text(
            "Selected Server",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = RubidiumOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(RubidiumBackground),
                contentAlignment = Alignment.Center
            ) {
                RouterGlyph(tint = RubidiumAccentLight)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    host,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = RubidiumOnSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Port: $port",
                    fontSize = 10.sp,
                    color = RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Change ›", fontSize = 11.sp, color = RubidiumAccentLight, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AppPickerScreen(
    apps      : List<InstalledAppInfo>,
    onSelect  : (String) -> Unit,
    onDismiss : () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(RubidiumBackground).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Text("‹", fontSize = 24.sp, color = RubidiumOnBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Select an Application",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = RubidiumOnBackground,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search for applications", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RubidiumAccent,
                unfocusedBorderColor = RubidiumOutlineStrong,
                cursorColor = RubidiumAccentLight
            ),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = RubidiumOnBackground)
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { app ->
                AppPickerRow(app = app, onClick = { onSelect(app.packageName) })
                Spacer(Modifier.height(4.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppPickerRow(app: InstalledAppInfo, onClick: () -> Unit) {
    val icon = rememberAppIcon(app.packageName)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(RubidiumSurface),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                Text("📦", fontSize = 16.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = RubidiumOnBackground,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                fontSize = 12.sp,
                color = RubidiumOnSurfaceDim,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DashboardWarningBanner(message: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠️", fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = RubidiumOnSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConnectedBanner(onLaunchApp: () -> Unit) {
    var showLaunchButton by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(5000)
        showLaunchButton = false
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(
                    color   = RubidiumSurfaceRaised,
                    topLeft = Offset(-24.dp.toPx(), 0f),
                    size    = Size(size.width + 48.dp.toPx(), size.height)
                )
            }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Connected to MITM proxy",
            fontSize = 15.sp,
            color = RubidiumOnSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        AnimatedVisibility(
            visible = showLaunchButton,
            enter   = fadeIn(tween(150)),
            exit    = fadeOut(tween(200))
        ) {
            Text(
                "Launch App",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RubidiumAccent,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onLaunchApp() }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AccountsTab(
    accounts         : List<SavedAccount>,
    selectedGamertag : String?,
    onSelectAccount  : (SavedAccount) -> Unit,
    onAddAccount     : () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Accounts") {
            AddIconButton(onClick = onAddAccount)
        }

        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No accounts yet.\nTap + to sign in with Microsoft.",
                    color = RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                accounts.forEach { account ->
                    AccountRow(
                        account  = account,
                        selected = account.gamertag == selectedGamertag,
                        onClick  = { onSelectAccount(account) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account  : SavedAccount,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) RubidiumAccentDark else RubidiumSurface)
            .border(1.dp, if (selected) RubidiumAccent else RubidiumOutline, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) RubidiumAccent.copy(alpha = 0.25f) else RubidiumSurfaceVar),
                contentAlignment = Alignment.Center
            ) {
                PersonGlyph(tint = if (selected) RubidiumAccentLight else RubidiumOnSurfaceDim)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    account.gamertag,
                    color = RubidiumOnBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (account.isExpired()) "Token expired — will refresh" else "Signed in",
                    color = if (account.isExpired()) RubidiumWarning else RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        if (selected) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(RubidiumSuccess))
        }
    }
}

@Composable
private fun ConfigTab() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profiles       by Config.profiles.collectAsState(initial = emptyList())
    val activeProfile  by Config.activeProfile.collectAsState(initial = null)

    var showSaveDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    var pendingExportName by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val exportName = pendingExportName
        pendingExportName = null
        if (uri != null && exportName != null) {
            scope.launch {
                val json = Config.exportJson(exportName)
                if (json != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray())
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (text != null) Config.importJson(text)
                } catch (_: Exception) {}
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Configs") {
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                Text("Import", color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            AddIconButton(onClick = { showSaveDialog = true })
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No saved profiles.\nTap + to save current settings.",
                    color = RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                profiles.forEach { profile ->
                    ProfileRow(
                        name     = profile.name,
                        active   = profile.name == activeProfile,
                        onLoad   = { scope.launch { Config.load(profile.name) } },
                        onDelete = { scope.launch { Config.delete(profile.name) } },
                        onExport = {
                            pendingExportName = profile.name
                            exportLauncher.launch("${profile.name}.rubidiumcfg.json")
                        }
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Profile", fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RubidiumAccent,
                        unfocusedBorderColor = RubidiumOutlineStrong,
                        focusedLabelColor = RubidiumAccentLight,
                        cursorColor = RubidiumAccentLight
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        color = RubidiumOnBackground
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newProfileName.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { Config.save(trimmed) }
                            newProfileName = ""
                            showSaveDialog = false
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RubidiumAccent)
                ) {
                    Text("Save", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newProfileName = "" }) {
                    Text("Cancel", color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = RubidiumSurface,
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    var uiStyle by remember { mutableStateOf(OverlayUiStore.get(context)) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Settings")

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSectionLabel("Overlay UI")
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RubidiumSurface)
                        .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(12.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OverlayUiStyle.values().forEach { style ->
                        UiStyleOption(
                            style    = style,
                            selected = uiStyle == style,
                            onClick  = {
                                uiStyle = style
                                OverlayUiStore.set(context, style)
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSectionLabel("Community")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsLinkRow(
                        label = "Discord",
                        subtitle = "Join the server",
                        url   = "https://discord.gg/AxxufgTdsx",
                        context = context
                    ) { tint -> DiscordGlyph(tint = tint) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = RubidiumOnSurfaceDim,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun UiStyleOption(
    style    : OverlayUiStyle,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RubidiumSurfaceVar else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) RubidiumAccent else Color.Transparent)
                .border(1.5.dp, if (selected) RubidiumAccent else RubidiumOutlineStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(RubidiumBackground))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                style.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RubidiumOnSurface,
                fontFamily = FontFamily.Monospace
            )
            Text(
                style.description,
                fontSize = 11.sp,
                color = RubidiumOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(
    label    : String,
    subtitle : String,
    url      : String,
    context  : Context,
    glyph    : @Composable (Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(RubidiumBackground),
            contentAlignment = Alignment.Center
        ) {
            glyph(RubidiumAccentLight)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RubidiumOnSurface,
                fontFamily = FontFamily.Monospace
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = RubidiumOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
        }
        Text("›", fontSize = 18.sp, color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ProfileRow(
    name: String,
    active: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) RubidiumAccentDark else RubidiumSurface)
            .border(1.dp, if (active) RubidiumAccent else RubidiumOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            name,
            color = RubidiumOnBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onLoad,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = RubidiumAccentLight)
            ) {
                Text("Load", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = onExport,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = RubidiumOnSurfaceDim)
            ) {
                Text("Export", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = onDelete,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)
            ) {
                Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InactiveNotice() {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(RubidiumSurfaceVar)
        .border(1.dp, RubidiumOutline, RoundedCornerShape(8.dp))
        .padding(14.dp)
    ) {
        Text("This section is not active yet.", fontSize = 11.sp,
            color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
    }
}


@Composable
private fun BottomTabBar(current: DashTab, onSelect: (DashTab) -> Unit) {
    Column {
        HorizontalDivider(color = RubidiumOutline)
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Black)
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(
                icon     = { tint -> HomeGlyph(tint = tint) },
                label    = "Dashboard",
                selected = current == DashTab.RELAY,
                onClick  = { onSelect(DashTab.RELAY) }
            )
            TabItem(
                icon     = { tint -> DocumentGlyph(tint = tint) },
                label    = "Configs",
                selected = current == DashTab.CONFIG,
                onClick  = { onSelect(DashTab.CONFIG) }
            )
            TabItem(
                icon     = { tint -> PersonGlyph(tint = tint) },
                label    = "Accounts",
                selected = current == DashTab.ACCOUNTS,
                onClick  = { onSelect(DashTab.ACCOUNTS) }
            )
            TabItem(
                icon     = { tint -> GearGlyph(tint = tint) },
                label    = "Settings",
                selected = current == DashTab.SETTINGS,
                onClick  = { onSelect(DashTab.SETTINGS) }
            )
        }
    }
}

@Composable
private fun TabItem(
    icon     : @Composable (Color) -> Unit,
    label    : String,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Column(
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) RubidiumSurfaceVar else Color.Transparent)
                .padding(horizontal = if (selected) 18.dp else 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            icon(if (selected) RubidiumAccentLight else RubidiumOnSurfaceDim)
        }
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Text(
                label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = RubidiumAccentLight
            )
        }
    }
}

@Composable
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue   = if (running) RubidiumAccent else RubidiumConnectIdle,
        animationSpec = tween(300), label = "btnColor"
    )
    val contentColor = if (running) Color.White else RubidiumOnBackground
    Button(
        onClick        = onToggle,
        shape          = RoundedCornerShape(50),
        colors         = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation      = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        modifier       = Modifier.height(52.dp)
    ) {
        RouterGlyph(tint = contentColor)
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "Disconnect" else "Connect",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, color = contentColor
        )
    }
}

@Composable
private fun ServerSettingsPanel(
    currentHost   : String,
    currentPort   : Int,
    recentServers : List<Pair<String, Int>>,
    onSave        : (String, Int) -> Unit,
    onReset       : () -> Unit,
    onDismiss     : () -> Unit
) {
    var hostInput by remember(currentHost) { mutableStateOf(currentHost) }
    var portInput by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    var portError by remember { mutableStateOf(false) }

    // Panel açıkken arka planda LAN üzerindeki dünyaları dinler (kendi cihazında
    // "Visible to LAN Players" açık bir dünya dahil); panel kapanınca durur.
    val lanServers by LanServerScanner.servers.collectAsState()
    DisposableEffect(Unit) {
        LanServerScanner.start()
        onDispose { LanServerScanner.stop() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = RubidiumSurface.copy(alpha = 0.90f)),
        border   = BorderStroke(1.dp, RubidiumOutlineStrong)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TARGET SERVER", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = RubidiumOnBackground, fontFamily = FontFamily.Monospace)
                Text("CLOSE", fontSize = 10.sp, color = RubidiumOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onDismiss() })
            }
            HorizontalDivider(color = RubidiumOutline)

            Text("NEARBY LAN WORLDS", fontSize = 10.sp,
                color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
            if (lanServers.isEmpty()) {
                Text("Searching your network for LAN worlds…", fontSize = 10.sp,
                    color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
            } else {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lanServers.forEach { s ->
                        LanServerRow(server = s, onClick = { onSave(s.host, s.port) })
                    }
                }
            }
            HorizontalDivider(color = RubidiumOutline)

            OutlinedTextField(
                value = hostInput, onValueChange = { hostInput = it },
                label = { Text("Server Address", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RubidiumAccent, unfocusedBorderColor = RubidiumOutlineStrong,
                    focusedLabelColor = RubidiumAccentLight, cursorColor = RubidiumAccentLight),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = RubidiumOnBackground)
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = { portInput = it; portError = it.toIntOrNull()?.let { p -> p < 1 || p > 65535 } ?: true },
                label = { Text("Port", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                singleLine = true, isError = portError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RubidiumAccent, unfocusedBorderColor = RubidiumOutlineStrong,
                    focusedLabelColor = RubidiumAccentLight, cursorColor = RubidiumAccentLight),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = RubidiumOnBackground),
                supportingText = if (portError) {
                    { Text("Valid port range is 1-65535", color = RubidiumError, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                } else null
            )
            if (recentServers.isNotEmpty()) {
                Text("RECENT SERVERS", fontSize = 10.sp,
                    color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentServers.forEach { (h, p) ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(RubidiumSurfaceVar)
                            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
                            .clickable { hostInput = h; portInput = p.toString(); portError = false }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("$h:$p", fontSize = 9.sp,
                                color = RubidiumAccentLight, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, RubidiumOutlineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RubidiumOnSurface)
                ) { Text("DEFAULT", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Button(
                    onClick = {
                        val p = portInput.toIntOrNull()
                        if (hostInput.isBlank() || p == null || p < 1 || p > 65535) { portError = true; return@Button }
                        onSave(hostInput.trim(), p)
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RubidiumAccent)
                ) { Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun LanServerRow(server: LanServerInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(RubidiumSurfaceVar)
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.motd.ifBlank { "Minecraft World" },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = RubidiumOnBackground, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${server.host}:${server.port} · ${server.playerCount}/${server.maxPlayers} · ${server.mcVersion}",
                fontSize = 9.sp, color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(RubidiumSuccess))
    }
}

@Composable
private fun AuthDialog(
    authState : AuthState,
    onDismiss : () -> Unit,
    onSignIn  : () -> Unit,
    onSignOut : () -> Unit,
    onCancel  : () -> Unit
) {
    val canDismiss = authState !is AuthState.Loading && authState !is AuthState.WaitingForWebView
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }) {
        Card(shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RubidiumSurface),
            border = BorderStroke(1.dp, RubidiumOutline)
        ) {
            Column(modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("MICROSOFT ACCOUNT", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = RubidiumOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = RubidiumOutline)
                when (authState) {
                    is AuthState.Success -> {
                        Text("SIGNED IN", color = RubidiumSuccess, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(authState.gamertag, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = RubidiumOnBackground, fontFamily = FontFamily.Monospace)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RubidiumAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN IN WITH ANOTHER ACCOUNT", fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center) }
                        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RubidiumError),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN OUT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.WaitingForWebView, is AuthState.Loading -> {
                        CircularProgressIndicator(color = RubidiumAccentLight, strokeWidth = 2.dp)
                        Text(
                            if (authState is AuthState.WaitingForWebView)
                                "Opening Microsoft sign-in window..."
                            else
                                "Verifying account...",
                            color = RubidiumOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text("CANCEL", color = RubidiumError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Error -> {
                        Text("ERROR: ${authState.message}", color = RubidiumError,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RubidiumAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("RETRY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> {
                        Text("Sign in with your Xbox/Microsoft account.\nThe sign-in page will open inside the app.",
                            color = RubidiumOnSurface, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, fontSize = 13.sp)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RubidiumAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN IN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = RubidiumOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun RouterGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val arcCenter = Offset(w * 0.32f, h * 0.40f)
        for (i in 0..1) {
            val r = h * (0.20f + i * 0.16f)
            drawArc(
                color = tint,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = h * 0.07f, cap = StrokeCap.Round)
            )
        }
        drawCircle(color = tint, radius = h * 0.045f, center = Offset(arcCenter.x, arcCenter.y + h * 0.02f))

        val bodyTop = h * 0.58f
        val bodyHeight = h * 0.30f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, bodyTop),
            size = Size(w * 0.84f, bodyHeight),
            cornerRadius = CornerRadius(bodyHeight * 0.4f)
        )
        val dotY = bodyTop + bodyHeight / 2f
        val dotR = bodyHeight * 0.14f
        listOf(0.30f, 0.5f, 0.70f).forEach { fx ->
            drawCircle(color = RubidiumBackground, radius = dotR, center = Offset(w * fx, dotY))
        }
    }
}

@Composable
private fun PersonGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(color = tint, radius = h * 0.20f, center = Offset(w / 2f, h * 0.32f))
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.88f)
            quadraticBezierTo(w * 0.20f, h * 0.55f, w * 0.5f, h * 0.55f)
            quadraticBezierTo(w * 0.80f, h * 0.55f, w * 0.80f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}

@Composable
private fun HomeGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.90f, h * 0.42f)
            lineTo(w * 0.90f, h * 0.90f)
            lineTo(w * 0.58f, h * 0.90f)
            lineTo(w * 0.58f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.90f)
            lineTo(w * 0.10f, h * 0.90f)
            lineTo(w * 0.10f, h * 0.42f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = h * 0.09f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DocumentGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.08f),
            size = Size(w * 0.64f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = h * 0.07f)
        )
        listOf(0.34f, 0.52f, 0.70f).forEach { fy ->
            drawLine(
                color = tint,
                start = Offset(w * 0.30f, h * fy),
                end   = Offset(w * 0.70f, h * fy),
                strokeWidth = h * 0.06f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun GearGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.5f)
        val outerR = h * 0.40f
        val innerR = h * 0.24f
        val toothLen = h * 0.13f
        val toothCount = 8
        val path = Path()
        for (i in 0 until toothCount * 2) {
            val angle = (Math.PI * 2.0 * i) / (toothCount * 2)
            val r = if (i % 2 == 0) outerR + toothLen else outerR
            val x = center.x + (r * cos(angle)).toFloat()
            val y = center.y + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path = path, color = tint)
        drawCircle(color = RubidiumBackground, radius = innerR, center = center)
    }
}

@Composable
private fun DiscordGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.18f),
            size = Size(w * 0.88f, h * 0.54f),
            cornerRadius = CornerRadius(h * 0.24f)
        )
        drawCircle(color = tint, radius = w * 0.11f, center = Offset(w * 0.22f, h * 0.80f))
        drawCircle(color = tint, radius = w * 0.11f, center = Offset(w * 0.78f, h * 0.80f))
        listOf(0.36f, 0.64f).forEach { fx ->
            drawCircle(color = RubidiumBackground, radius = h * 0.09f, center = Offset(w * fx, h * 0.46f))
        }
    }
}


