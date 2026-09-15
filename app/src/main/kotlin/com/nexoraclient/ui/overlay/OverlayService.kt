package com.rubidiumclient.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rubidiumclient.R
import com.rubidiumclient.config.Config
import com.rubidiumclient.config.OverlayPositions
import com.rubidiumclient.config.Pos
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.misc.ComboShortcut
import com.rubidiumclient.module.misc.CommandHelper
import com.rubidiumclient.module.misc.Performance
import com.rubidiumclient.module.social.FriendManager
import com.rubidiumclient.session.SessionManager
import com.rubidiumclient.ui.dashboard.OverlayUiStore
import com.rubidiumclient.ui.dashboard.OverlayUiStyle
import com.rubidiumclient.ui.theme.*
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val CHANNEL_ID = "ox_overlay"
        private const val NOTIF_ID   = 1002

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }

    private val lcReg   = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lcReg

    private val ssrCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrCtrl.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wm: android.view.WindowManager
    private var isAttached = false

    private var menuView : ComposeView? = null
    private var totemView : ComposeView? = null
    private var perfHudView : ComposeView? = null
    private var fabView   : ComposeView? = null
    private var espView  : ESPOverlayView? = null
    private val shortcutViews = mutableMapOf<String, ComposeView>()
    private val commandShortcutViews = mutableMapOf<String, ComposeView>()

    private var volumeToggleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        createChannel()
        OverlayPositions.onChanged = { serviceScope.launch { applyPositionsToViews() } }
        volumeToggleJob?.cancel()
        volumeToggleJob = serviceScope.launch {
            VolumeToggleBus.events.collect { toggleMenu() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        showOverlay()
        lcReg.currentState = Lifecycle.State.RESUMED
        OverlayState.setOverlayVisible(true)
        startStatsPoller()
        return START_STICKY
    }

    override fun onDestroy() {
        lcReg.currentState = Lifecycle.State.DESTROYED
        OverlayPositions.onChanged = null
        OverlayState.setOverlayVisible(false)
        volumeToggleJob?.cancel()
        removeAllOverlays()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStatsPoller() {
        serviceScope.launch {
            while (true) {
                OverlayState.updateActiveModuleCount(ModuleManager.enabledCount())
                val invSnapshot = EntityTracker.getInventorySnapshot()
                val totemCount = invSnapshot.count { (slot, item) ->
                    (slot in 0..35 || slot == 119) && InventoryUtil.isTotem(item)
                }
                OverlayState.updateTotemCount(totemCount)

                delay(500L)
            }
        }
    }

    // FIX (overlayı sağlamlaştırma): removeViewImmediate bir sebeple exception
    // atarsa (view zaten attach değil, servis kısa süreliğine recreate oldu,
    // vb.) sessizce yutup "kaldırıldı" varsaymak hayalet pencere bırakıyordu
    // (bkz. hideMenu()'daki asıl bug). Bütün view kaldırma noktaları artık bu
    // tek merkezi, toleranslı yardımcıdan geçiyor: önce removeViewImmediate,
    // o başarısız olursa removeView ile tekrar dener, GERÇEKTEN kaldırılıp
    // kaldırılmadığını (true/false) döner — çağıran taraf sadece başarılıysa
    // kendi takip state'ini (map/referans) temizlemeli, aksi halde bir
    // sonraki refresh aynı view'in üstüne ikinci bir tane daha ekler.
    private fun safeRemoveView(view: android.view.View?): Boolean {
        if (view == null) return false
        return try {
            wm.removeViewImmediate(view)
            true
        } catch (_: Exception) {
            try {
                wm.removeView(view)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun overlayParams(
        w: Int, h: Int, x: Float = 0f, y: Float = 0f,
        focusable: Boolean = false, touchable: Boolean = true
    ): android.view.WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE

        var flags = if (focusable)
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        else
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (!touchable) flags = flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return android.view.WindowManager.LayoutParams(
            w, h, type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            this.x  = x.roundToInt()
            this.y  = y.roundToInt()
        }
    }

    private fun showOverlay() {
        if (isAttached) return
        try {
            val espParams = overlayParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false
            )
            espView = ESPOverlayView(this)
            wm.addView(espView, espParams)
            espView?.startRenderLoop()

            val totemParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.totem.x, OverlayPositions.totem.y
            )
            totemView = composeView {
                TotemCounterIcon(
                    onDrag = { dx, dy ->
                        val next = Pos(OverlayPositions.totem.x + dx, OverlayPositions.totem.y + dy)
                        OverlayPositions.totem = next
                        totemParams.x = next.x.roundToInt()
                        totemParams.y = next.y.roundToInt()
                        safeUpdate(totemView, totemParams)
                    }
                )
            }
            wm.addView(totemView, totemParams)

            val perfHudParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.perfHud.x, OverlayPositions.perfHud.y
            )
            perfHudView = composeView {
                PerformanceHudLabel(
                    onDrag = { dx, dy ->
                        val next = Pos(OverlayPositions.perfHud.x + dx, OverlayPositions.perfHud.y + dy)
                        OverlayPositions.perfHud = next
                        perfHudParams.x = next.x.roundToInt()
                        perfHudParams.y = next.y.roundToInt()
                        safeUpdate(perfHudView, perfHudParams)
                    }
                )
            }
            wm.addView(perfHudView, perfHudParams)

            // FIX: FAB önceden sadece Classic stilinde oluşturuluyordu (menüyü
            // açmanın tek yolu volume tuşu kalıyordu). Grid Menu seçiliyken de
            // aynı FAB gösterilmeli, stil koşulu kaldırıldı.
            val fabParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.fab.x, OverlayPositions.fab.y
            )
            fabView = composeView {
                MenuFab(
                    onClick = { toggleMenu() },
                    onDrag  = { dx, dy ->
                        val next = Pos(OverlayPositions.fab.x + dx, OverlayPositions.fab.y + dy)
                        OverlayPositions.fab = next
                        fabParams.x = next.x.roundToInt()
                        fabParams.y = next.y.roundToInt()
                        safeUpdate(fabView, fabParams)
                    }
                )
            }
            wm.addView(fabView, fabParams)

            refreshShortcuts()
            refreshCommandShortcuts()
            isAttached = true
        } catch (_: Exception) {}
    }

    private fun refreshShortcuts() {
        val unlockedShortcutModules = ModuleManager.shortcutModules()
        val active = unlockedShortcutModules.map { it.name }.toSet()
        shortcutViews.entries.filter { it.key !in active }.forEach { (name, view) ->
            if (safeRemoveView(view)) shortcutViews.remove(name)
        }
        unlockedShortcutModules.forEachIndexed { idx, mod ->
            if (mod.name !in shortcutViews) {
                val pos = OverlayPositions.shortcuts.getOrPut(mod.name) { Pos(50f, 420f + idx * 50f) }
                val params = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    pos.x, pos.y
                )
                val view = composeView {
                    ShortcutButton(
                        module   = mod,
                        onDrag   = { dx, dy ->
                            val cur = OverlayPositions.shortcuts[mod.name] ?: Pos(0f, 0f)
                            val next = Pos(cur.x + dx, cur.y + dy)
                            OverlayPositions.shortcuts[mod.name] = next
                            params.x = next.x.roundToInt(); params.y = next.y.roundToInt()
                            safeUpdate(shortcutViews[mod.name], params)
                        },
                        onToggle = { ModuleManager.toggle(mod) }
                    )
                }
                shortcutViews[mod.name] = view
                try { wm.addView(view, params) } catch (_: Exception) {}
            }
        }
    }

    private fun refreshCommandShortcuts() {
        val helper = ModuleManager.byName("CommandHelper") as? CommandHelper
        val activeEntries = if (helper?.isEnabled == true) helper.entries.toSet() else emptySet()

        commandShortcutViews.entries.filter { it.key !in activeEntries }.forEach { (entry, view) ->
            if (safeRemoveView(view)) {
                commandShortcutViews.remove(entry)
                OverlayPositions.commandShortcuts.remove(entry)
            }
        }
        activeEntries.forEachIndexed { idx, entry ->
            if (entry !in commandShortcutViews) {
                val pos = OverlayPositions.commandShortcuts.getOrPut(entry) { Pos(50f, 620f + idx * 50f) }
                val params = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    pos.x, pos.y
                )
                val view = composeView {
                    CommandEntryButton(
                        text   = entry,
                        onDrag = { dx, dy ->
                            val cur = OverlayPositions.commandShortcuts[entry] ?: Pos(0f, 0f)
                            val next = Pos(cur.x + dx, cur.y + dy)
                            OverlayPositions.commandShortcuts[entry] = next
                            params.x = next.x.roundToInt(); params.y = next.y.roundToInt()
                            safeUpdate(commandShortcutViews[entry], params)
                        },
                        onTap = { helper?.send(entry) }
                    )
                }
                commandShortcutViews[entry] = view
                try { wm.addView(view, params) } catch (_: Exception) {}
            }
        }
    }

    private fun toggleMenu() { if (menuView != null) hideMenu() else showMenu() }

    private fun showMenu() {
        if (menuView != null) return
        val params = overlayParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true
        )
        menuView = composeView {
            val moduleVersion by ModuleManager.version.collectAsState()
            val uiStyle = remember { OverlayUiStore.get(this@OverlayService) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // FIX (siyah ekran): burası daha önce non-GRID (Classic/HileMenu)
                    // stilinde tam ekranı kaplayan MATCH_PARENT pencerenin arka planını
                    // düz Color.Black yapıyordu. HileMenu sadece dar bir yan panel
                    // (Alignment.CenterStart) — geri kalan tüm ekran bilerek şeffaf
                    // olmalı ki arkadaki oyun görünsün. GRID zaten Transparent
                    // kullanıyordu, aynı davranışı buraya da uyguladım.
                    .background(Color.Transparent)
                    .pointerInput(Unit) { detectTapGestures { hideMenu() } }
            ) {
                if (uiStyle == OverlayUiStyle.GRID) {
                    GridMenu(
                        onClose           = { hideMenu() },
                        moduleVersion     = moduleVersion,
                        onShortcutChanged = { refreshShortcuts(); refreshCommandShortcuts() },
                        modifier          = Modifier.fillMaxSize()
                    )
                } else if (uiStyle == OverlayUiStyle.CSGO) {
                    CsgoMenu(
                        onClose           = { hideMenu() },
                        moduleVersion     = moduleVersion,
                        onShortcutChanged = { refreshShortcuts(); refreshCommandShortcuts() },
                        modifier          = Modifier.fillMaxSize()
                    )
                } else {
                    HileMenu(
                        onClose               = { hideMenu() },
                        moduleVersion         = moduleVersion,
                        onShortcutChanged     = { refreshShortcuts(); refreshCommandShortcuts() },
                        modifier              = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
        }
        try {
            wm.addView(menuView, params)
            OverlayState.setMenuOpen(true)
        } catch (_: Exception) {
            menuView = null
        }
    }

    private fun hideMenu() {
        val view = menuView ?: return
        if (!safeRemoveView(view)) return // kaldıramadık, state'i senkron tutma (çift hayalet pencereyi önle)
        menuView = null
        OverlayState.setMenuOpen(false)
        GridSettingsPopup.close() // menü kapanınca ortada açık kalmış ayar kutusu da kapansın
    }

    private fun removeAllOverlays() {
        hideMenu()
        listOfNotNull(totemView, perfHudView, fabView, espView).plus(shortcutViews.values).plus(commandShortcutViews.values).forEach { v ->
            safeRemoveView(v)
        }
        totemView = null; perfHudView = null; fabView = null; espView = null; shortcutViews.clear(); commandShortcutViews.clear(); isAttached = false
    }

    private fun safeUpdate(view: ComposeView?, params: android.view.WindowManager.LayoutParams) {
        view?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    /** After Config.load() (while the overlay is already visible), moves every element to its saved position. */
    private fun applyPositionsToViews() {
        fun snap(view: ComposeView?, pos: Pos) {
            view ?: return
            val params = view.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            params.x = pos.x.roundToInt(); params.y = pos.y.roundToInt()
            safeUpdate(view, params)
        }
        snap(totemView, OverlayPositions.totem)
        snap(perfHudView, OverlayPositions.perfHud)
        snap(fabView, OverlayPositions.fab)
        shortcutViews.forEach { (name, view) -> OverlayPositions.shortcuts[name]?.let { snap(view, it) } }
        commandShortcutViews.forEach { (entry, view) -> OverlayPositions.commandShortcuts[entry]?.let { snap(view, it) } }
    }

    private fun composeView(content: @Composable () -> Unit) =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Eclient Overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_rubidium_logo)
        .setContentTitle("Eclient Overlay")
        .setContentText("HUD aktif")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

object VolumeToggleBus {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: kotlinx.coroutines.flow.SharedFlow<Unit> = _events

    fun emitToggle() { _events.tryEmit(Unit) }
}


@Composable
private fun PerformanceHudLabel(onDrag: (Float, Float) -> Unit) {
    var totalDrag by remember { mutableFloatStateOf(0f) }

    val perfModule = remember { ModuleManager.byName("Performance") as? Performance }
    var hudVisible by remember { mutableStateOf(perfModule?.showPerformanceHud?.value ?: false) }
    // BoolSetting'in kendi Flow'u yok, bu yüzden ayar değişimini diğer
    // ayarlar gibi (AutoTotem'in enabledFlow'u hariç) hafif bir polling ile
    // takip ediyoruz — menüden açılıp kapatıldığında HUD anında tepki verir.
    // perfModule bulunamazsa (ör. henüz kayıtlı değilse) döngüye hiç girmiyoruz.
    LaunchedEffect(perfModule) {
        val mod = perfModule ?: return@LaunchedEffect
        while (isActive) {
            hudVisible = mod.showPerformanceHud.value
            delay(300L)
        }
    }

    val fps  = OverlayState.currentFps
    val ping = OverlayState.currentPingMs

    val fpsColor = when {
        fps in 1..29 -> Color(0xFFFF453A)
        fps == 0     -> Color.White.copy(alpha = 0.65f)
        else         -> Color.White.copy(alpha = 0.9f)
    }
    val pingColor = when {
        ping >= 150 -> Color(0xFFFF453A)
        ping >= 80  -> Color(0xFFFFD60A)
        else        -> Color.White.copy(alpha = 0.9f)
    }

    AnimatedVisibility(
        visible = hudVisible,
        enter   = fadeIn() + expandHorizontally(),
        exit    = fadeOut() + shrinkHorizontally()
    ) {
    Row(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD1E1E2E))
            .border(1.dp, Color(0xFF585B70), RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd   = { },
                    onDrag      = { change, offset ->
                        change.consume()
                        totalDrag += kotlin.math.abs(offset.x) + kotlin.math.abs(offset.y)
                        onDrag(offset.x, offset.y)
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text       = "$fps FPS",
            fontSize   = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color      = fpsColor
        )
        Text(
            text       = "${ping}ms",
            fontSize   = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color      = pingColor
        )
    }
    }
}

@Composable
private fun TotemCounterIcon(onDrag: (Float, Float) -> Unit) {
    val count      = OverlayState.totemCount
    var totalDrag  by remember { mutableFloatStateOf(0f) }

    val autoTotem  = remember { ModuleManager.byName("AutoTotem") }
    var autoTotemEnabled by remember { mutableStateOf(autoTotem?.isEnabled ?: false) }
    LaunchedEffect(autoTotem) {
        autoTotem?.enabledFlow?.collect { autoTotemEnabled = it }
    }

    AnimatedVisibility(
        visible = autoTotemEnabled,
        enter   = fadeIn() + expandHorizontally(),
        exit    = fadeOut() + shrinkHorizontally()
    ) {
    Row(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(Color(0xDD1E1E2E))
            .border(1.dp, Color(0xFF585B70), RoundedCornerShape(50.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd   = { },
                    onDrag      = { change, offset ->
                        change.consume()
                        totalDrag += kotlin.math.abs(offset.x) + kotlin.math.abs(offset.y)
                        onDrag(offset.x, offset.y)
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.foundation.Image(
            painter            = painterResource(id = R.drawable.ic_totem),
            contentDescription = "Totem",
            modifier           = Modifier.size(22.dp)
        )
        Text(
            text       = "$count",
            fontSize   = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color      = when {
                count > 5 -> Color.White
                else      -> Color(0xFFFF453A)
            }
        )
    }
    }
}

@Composable
private fun MenuFab(onClick: () -> Unit, onDrag: (Float, Float) -> Unit) {
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xDD181825))
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onClick() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onClick() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter            = painterResource(id = R.mipmap.ic_rubidium_logo),
            contentDescription = "Eclient",
            modifier           = Modifier
                .size(30.dp)
                .clip(CircleShape)
        )
    }
}


@Composable
private fun ShortcutButton(module: BaseModule, onDrag: (Float, Float) -> Unit, onToggle: () -> Unit) {
    var enabled    by remember { mutableStateOf(module.isEnabled) }
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val bgColor = if (enabled) RubidiumSurfaceVar else RubidiumSurface
    val borderColor = if (enabled) RubidiumAccentLight.copy(0.9f) else RubidiumOutline.copy(0.5f)
    val textColor   = if (enabled) Color.White else RubidiumOnSurface.copy(0.6f)

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(bgColor)
            .border(if (enabled) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(50.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onToggle() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onToggle() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            module.name,
            fontSize = 12.sp,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
private fun CommandEntryButton(text: String, onDrag: (Float, Float) -> Unit, onTap: () -> Unit) {
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(RubidiumSurfaceVar)
            .border(1.5.dp, RubidiumAccentLight.copy(0.9f), RoundedCornerShape(50.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onTap() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onTap() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp)
        )
    }
}

private enum class MenuSection(val displayName: String) {
    COMBAT("Combat"), MOVEMENT("Movement"), VISUAL("Visual"), PLAYER("Player"), WORLD("World"), MISC("Misc"),
    FRIENDS("Friends"), CONFIG("Config")
}

private fun MenuSection.toModuleCategory(): ModuleCategory? = when (this) {
    MenuSection.COMBAT   -> ModuleCategory.COMBAT
    MenuSection.MOVEMENT -> ModuleCategory.MOVEMENT
    MenuSection.VISUAL   -> ModuleCategory.VISUAL
    MenuSection.PLAYER   -> ModuleCategory.PLAYER
    MenuSection.WORLD    -> ModuleCategory.WORLD
    MenuSection.MISC     -> ModuleCategory.MISC
    MenuSection.FRIENDS  -> null
    MenuSection.CONFIG   -> null
}


@Composable
private fun HileMenu(
    onClose               : () -> Unit,
    moduleVersion         : Int,
    onShortcutChanged     : () -> Unit,
    modifier              : Modifier = Modifier
) {
    var section by remember { mutableStateOf(MenuSection.COMBAT) }
    val cat  = section.toModuleCategory()
    val mods = remember(moduleVersion, cat) {
        cat?.let { ModuleManager.byCategory(it) } ?: emptyList()
    }
    val relayActive by SessionManager.isActive.collectAsState()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.verticalGradient(
                    listOf(RubidiumBackground.copy(alpha = 0.72f), Color(0xFF11111B).copy(alpha = 0.72f))
                )
            )
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RubidiumSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Eclient",
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        color = RubidiumOnBackground)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (relayActive) RubidiumSuccess.copy(0.2f) else RubidiumError.copy(0.15f))
                            .border(1.dp,
                                if (relayActive) RubidiumSuccess.copy(0.5f) else RubidiumError.copy(0.4f),
                                RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(if (relayActive) "Connected" else "Disconnected",
                            fontSize = 9.sp,
                            color = if (relayActive) RubidiumSuccess else RubidiumError,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(RubidiumError.copy(0.15f))
                        .border(1.dp, RubidiumError.copy(0.4f), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("x", color = RubidiumError, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = RubidiumOutlineStrong)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RubidiumSurface.copy(0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MenuSection.entries.forEach { s ->
                    val sel = s == section
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50.dp))
                            .background(if (sel) RubidiumSurfaceVar else Color.Transparent)
                            .border(1.dp,
                                if (sel) RubidiumOutlineStrong else RubidiumOutline,
                                RoundedCornerShape(50.dp))
                            .clickable { section = s }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(s.displayName, fontSize = 11.sp,
                            color = if (sel) RubidiumOnBackground else RubidiumOnSurface,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }            }

            HorizontalDivider(color = RubidiumOutlineStrong)

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (section == MenuSection.CONFIG) {
                    ConfigSection()
                } else if (section == MenuSection.FRIENDS) {
                    FriendsSection()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(mods) { mod ->
                            ModuleCard(module = mod, onShortcutChanged = onShortcutChanged)
                        }
                    }
                }
            }
        }
    }
}


/**
 * "Grid Menu" overlay style: same category tabs and module data as HileMenu,
 * but rendered as a centered card with modules laid out in a compact
 * multi-column grid instead of a single scrolling list — optimized for
 * portrait phone use (big tap targets, no nested horizontal columns like a
 * desktop client would use).
 */
/**
 * "Grid Menu" — Horion tarzı: ekranın tamamını kaplayan, üstte 6 yatay
 * sekme (Combat/Movement/Visual/Misc/Friends/Config), altında seçili
 * kategorinin modülleri aşağı doğru akan, dokununca yerinde (Dialog
 * AÇMADAN) genişleyip ayarlarını gösteren kartlar halinde listelenir.
 * Tema sadece koyu siyah + açık mavi/lacivert (HileMenu'nün Rubidium
 * paletinden bilerek bağımsız).
 */
private enum class GridCategory(val display: String, val category: ModuleCategory) {
    COMBAT("Combat", ModuleCategory.COMBAT),
    MOVEMENT("Movement", ModuleCategory.MOVEMENT),
    VISUAL("Visual", ModuleCategory.VISUAL),
    PLAYER("Player", ModuleCategory.PLAYER),
    WORLD("World", ModuleCategory.WORLD),
    MISC("Misc", ModuleCategory.MISC)
}

private val GridHeaderTop      = Color(0xFF333338)
private val GridHeaderBottom   = Color(0xFF0A0A0B)
private val GridPanelTop       = Color(0xFF1B1B1E)
private val GridPanelBottom    = Color(0xFF0C0C0E)
private val GridRowTopIdle     = Color(0xFF232326)
private val GridRowBottomIdle  = Color(0xFF141416)
private val GridRowTopOn       = Color(0xFF2E2E34)
private val GridRowBottomOn    = Color(0xFF17181B)
private val GridBorderIdle     = Color(0xFF3A3A40).copy(alpha = 0.55f)
private val GridBorderOn       = Color(0xFFD8D8DE).copy(alpha = 0.65f)
private val GridGlow           = Color(0xFFFFFFFF).copy(alpha = 0.06f)
private val GridTextOn         = Color(0xFFFFFFFF)
private val GridTextDim        = Color(0xFFE4E4E8)
private val GridPlusBg         = Color(0xFF303034)
private val GridStarOn         = Color(0xFFFFFFFF)
private val GridStarOff        = Color(0xFF6E6E74)

private object GridShortcuts {
    private val _names = MutableStateFlow(setOf<String>())
    val names: StateFlow<Set<String>> = _names.asStateFlow()

    fun isOn(name: String) = name in _names.value

    fun toggle(name: String) {
        _names.value = if (name in _names.value) _names.value - name else _names.value + name
    }
}

// Hangi modülün ayar penceresi ortada açık — GridModuleRow'un kendi local
// "expanded" state'i yerine tek bir global tutucu: aynı anda sadece bir
// modülün ayar kutusu ekranın ortasında görünsün istiyoruz (Horion/HileMenu
// tarzı). Bir BaseModule referansı tutmak state restore gerektirmediği için
// (overlay Service her zaman canlı) sorun değil.
private object GridSettingsPopup {
    var current by mutableStateOf<BaseModule?>(null)
        private set

    fun open(module: BaseModule) { current = module }
    fun toggle(module: BaseModule) { current = if (current === module) null else module }
    fun close() { current = null }
}

// GridSettingsPopup ile aynı desen: ModuleCategory kolonlarının dışında,
// Config/Friends için yeni bir GridCategory kolonu AÇMADAN aynı sahte-Dialog
// (Box üstü scrim + ortalanmış kart) mekanizmasını kullanan minik bir toggle.
// Baba grid menüden Config/Friends sekmelerini kaldırmıştı (yer kaplıyordu);
// bu obje o iki bölümü header'daki iki küçük ikondan popup olarak geri getirir.
private enum class GridExtraSection { CONFIG, FRIENDS }

private object GridExtraPopup {
    var current by mutableStateOf<GridExtraSection?>(null)
        private set

    fun toggle(section: GridExtraSection) {
        current = if (current == section) null else section
    }
    fun close() { current = null }
}

// Grid ızgarasının hücre/kolon boyutları — referans görseldeki gibi 5 kolonun
// tek ekrana sığması için eskiye göre belirgin şekilde küçültüldü
// (168dp -> 112dp kolon genişliği, daha küçük fontlar/padding).
private val GridColumnWidth   = 112.dp
private val GridColumnGap     = 8.dp
private val GridOuterPadding  = 8.dp

@Composable
private fun GridMenu(
    onClose           : () -> Unit,
    moduleVersion     : Int,
    onShortcutChanged : () -> Unit,
    modifier          : Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        // Kolonlar artık her zaman açık (kendi expanded/collapse'ı yok) ve
        // daha küçük satırlar kullanıyor, dolayısıyla maksimum liste
        // yüksekliği de daha dar bir üst/alt boşlukla hesaplanabilir —
        // bu da grid'in ekrana daha düzgün sığmasını sağlıyor.
        // Header (Config/Friends ikonları) eklendiği için üstten pay ayrıldı.
        val maxListHeight = (screenHeightDp - 96.dp - 34.dp).coerceAtLeast(120.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            GridExtraHeader()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = GridOuterPadding, vertical = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GridColumnGap)
            ) {
                GridCategory.entries.forEach { gc ->
                    val mods = remember(moduleVersion, gc) { ModuleManager.byCategory(gc.category) }
                    GridCategoryColumn(
                        title             = gc.display,
                        mods              = mods,
                        maxListHeight     = maxListHeight,
                        onShortcutChanged = onShortcutChanged,
                        onClose           = onClose
                    )
                }
            }
        }

        // Ortada açılan modül ayarları kutusu. DİKKAT: burada bilinçli olarak
        // androidx.compose.ui.window.Dialog KULLANILMIYOR — Dialog() kendi
        // platform Window'unu (yeni bir android.view.Window) açar ve bunun
        // için bir Activity Window gerekir; bu menü ise OverlayService (bir
        // Service) tarafından WindowManager.addView ile eklenmiş TEK bir
        // pencere içinde çalışıyor. Aynı sorunun daha önce ModuleCard'da
        // (HileMenu tarafında) yaşanıp çözüldüğü koda bakınız. Onun yerine
        // aynı Compose ağacının İÇİNDE, bu Box'ın en üst (son) child'ı olarak
        // tam ekran bir scrim + ortalanmış bir kart çiziyoruz — görsel olarak
        // tam bir Dialog gibi davranır ama ekstra pencere açmaz, bu yüzden
        // service context'inde çökmez.
        val popupModule = GridSettingsPopup.current
        if (popupModule != null) {
            ModuleSettingsPopup(
                module            = popupModule,
                onShortcutChanged = onShortcutChanged,
                onDismiss         = { GridSettingsPopup.close() }
            )
        }

        // Config/Friends popup'ı — aynı sahte-Dialog deseni, GridSettingsPopup
        // ile aynı Box'ın en üstünde. İkisi aynı anda açılmaz (her ikisi de
        // Grid* tıklamalarıyla kontrol ediliyor, tab değişmiyor).
        val extraSection = GridExtraPopup.current
        if (extraSection != null) {
            GridExtraSectionPopup(
                section   = extraSection,
                onDismiss = { GridExtraPopup.close() }
            )
        }
    }
}

@Composable
private fun GridExtraHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridOuterPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GridExtraHeaderIcon(
            label   = "\u2699",   // ⚙
            active  = GridExtraPopup.current == GridExtraSection.CONFIG,
            onClick = { GridExtraPopup.toggle(GridExtraSection.CONFIG) }
        )
        Spacer(modifier = Modifier.width(6.dp))
        GridExtraHeaderIconCanvas(
            active  = GridExtraPopup.current == GridExtraSection.FRIENDS,
            onClick = { GridExtraPopup.toggle(GridExtraSection.FRIENDS) },
            draw    = { color -> drawFriendsIcon(color) }
        )
    }
}

@Composable
private fun GridExtraHeaderIcon(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) GridRowTopOn else GridRowTopIdle)
            .border(1.dp, if (active) GridBorderOn else GridBorderIdle, RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = GridTextOn)
    }
}

// FIX: Friends sekmesi eskiden "☺" (U+263A) emoji'siyle gösteriliyordu -
// bu, sistem emoji fontuyla renkli/çocuksu bir yüz ikonu olarak render
// oluyordu ve Config'in yanındaki sade ⚙ ile de tutarsız duruyordu. Emoji
// glyph'e bağımlı olmayan, Canvas ile çizilen sade bir "iki kişi" (friends)
// silüeti kullanıyoruz - tema rengini alıyor, cihazdan cihaza emoji fontuna
// göre değişmiyor.
@Composable
private fun GridExtraHeaderIconCanvas(active: Boolean, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) GridRowTopOn else GridRowTopIdle)
            .border(1.dp, if (active) GridBorderOn else GridBorderIdle, RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            draw(GridTextOn)
        }
    }
}

private fun DrawScope.drawFriendsIcon(color: Color) {
    val w = size.width
    val h = size.height
    val headR = w * 0.17f

    val backCx = w * 0.36f
    val frontCx = w * 0.64f
    val headCy = h * 0.30f

    // Arkadaki kişi (soluk) - kısmen arkada kalıyor izlenimi için önce çizilir
    val backColor = color.copy(alpha = 0.5f)
    drawCircle(color = backColor, radius = headR * 0.92f, center = Offset(backCx, headCy * 0.95f))
    drawArc(
        color = backColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(backCx - headR, h * 0.52f),
        size = Size(headR * 2f, h * 0.46f)
    )

    // Öndeki kişi (tam opak)
    drawCircle(color = color, radius = headR, center = Offset(frontCx, headCy))
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(frontCx - headR * 1.15f, h * 0.56f),
        size = Size(headR * 2.3f, h * 0.44f)
    )
}

@Composable
private fun GridExtraSectionPopup(section: GridExtraSection, onDismiss: () -> Unit) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxPopupHeight = (screenHeightDp - 120.dp).coerceAtLeast(160.dp)
    val title = when (section) {
        GridExtraSection.CONFIG  -> "Config"
        GridExtraSection.FRIENDS -> "Friends"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(section) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 320.dp)
                .heightIn(max = maxPopupHeight)
                .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = GridGlow, spotColor = GridGlow)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(GridPanelTop, GridPanelBottom)))
                .border(1.dp, GridBorderOn, RoundedCornerShape(14.dp))
                .pointerInput(section) { detectTapGestures { } }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(GridHeaderTop, GridHeaderBottom)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = GridTextOn,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "\u2715",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GridTextOn,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxPopupHeight - 44.dp)) {
                when (section) {
                    GridExtraSection.CONFIG  -> ConfigSection()
                    GridExtraSection.FRIENDS -> FriendsSection()
                }
            }
        }
    }
}

@Composable
private fun GridCategoryColumn(
    title             : String,
    mods              : List<BaseModule>,
    maxListHeight     : androidx.compose.ui.unit.Dp,
    onShortcutChanged : () -> Unit,
    onClose           : () -> Unit = {}
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .width(GridColumnWidth)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .pointerInput(Unit) { detectTapGestures { /* touch event'i yut, dışarıya (onClose) geçirme */ } }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(10.dp), ambientColor = GridGlow, spotColor = GridGlow)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(GridHeaderTop, GridHeaderBottom)))
                .border(1.dp, GridBorderIdle, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        dragOffset += Offset(drag.x, drag.y)
                    }
                }
                .padding(vertical = 7.dp, horizontal = 8.dp)
        ) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = GridTextOn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxListHeight)
                .shadow(5.dp, RoundedCornerShape(10.dp), ambientColor = GridGlow, spotColor = GridGlow)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(GridPanelTop, GridPanelBottom)))
                .border(1.dp, GridBorderIdle, RoundedCornerShape(10.dp))
        ) {
            if (mods.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Empty", fontSize = 9.sp, color = GridTextDim.copy(alpha = 0.5f))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mods.forEach { mod ->
                        GridModuleRow(module = mod, onShortcutChanged = onShortcutChanged)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridModuleRow(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled by remember { mutableStateOf(module.isEnabled) }
    val gridShortcutNames by GridShortcuts.names.collectAsState()
    val isGridShortcut = module.name in gridShortcutNames
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val hasSettings = module.settings.isNotEmpty()
    val popupModule = GridSettingsPopup.current
    val isPopupOpen = popupModule === module

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                if (enabled) 2.dp else 0.dp,
                RoundedCornerShape(7.dp),
                ambientColor = GridGlow,
                spotColor = GridGlow
            )
            .clip(RoundedCornerShape(7.dp))
            .background(
                Brush.verticalGradient(
                    if (enabled) listOf(GridRowTopOn, GridRowBottomOn)
                    else listOf(GridRowTopIdle, GridRowBottomIdle)
                )
            )
            .border(
                1.dp,
                if (isPopupOpen) GridStarOn else if (enabled) GridBorderOn else GridBorderIdle,
                RoundedCornerShape(7.dp)
            )
            .clickable { ModuleManager.toggle(module); onShortcutChanged() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            module.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = GridTextOn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isGridShortcut) "\u2605" else "\u2606",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGridShortcut) GridStarOn else GridStarOff,
                modifier = Modifier
                    .clickable { GridShortcuts.toggle(module.name) }
                    .padding(2.dp)
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .then(
                        if (hasSettings)
                            Modifier.clickable { GridSettingsPopup.toggle(module) }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isPopupOpen) "\u2212" else "+",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Bir modülün ayarlarını ekranın ortasında, tam ekran karartılmış bir scrim
 * üzerinde gösteren kutu. Gerçek bir Dialog() DEĞİL — bkz. GridMenu
 * içindeki not: bu, mevcut Compose ağacının en üstüne çizilen normal bir
 * Box/Column'dur, bu yüzden Service tarafından eklenen overlay penceresinde
 * güvenle çalışır.
 */
@Composable
private fun ModuleSettingsPopup(
    module            : BaseModule,
    onShortcutChanged : () -> Unit,
    onDismiss         : () -> Unit,
    // Frame colors default to the grid's dark palette; other menus can pass
    // themed values (the CS:GO window does) without duplicating the popup.
    headerBrushTop    : Color = GridHeaderTop,
    headerBrushBottom : Color = GridHeaderBottom,
    panelBrushTop     : Color = GridPanelTop,
    panelBrushBottom  : Color = GridPanelBottom,
    borderColor       : Color = GridBorderOn,
    glowColor         : Color = GridGlow,
    titleColor        : Color = GridTextOn,
    mutedColor        : Color = GridTextDim
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxPopupHeight = (screenHeightDp - 120.dp).coerceAtLeast(160.dp)

    // Scrim: dışarı tıklanınca kapansın, ama arkadaki grid'e tıklamayı
    // sızdırmasın (pointerInput ile tüketiliyor zaten clickable üstünden).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(module) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .heightIn(max = maxPopupHeight)
                .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = glowColor, spotColor = glowColor)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(panelBrushTop, panelBrushBottom)))
                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                // Kart içine tıklamalar dışarıdaki dismiss'i tetiklemesin.
                .pointerInput(module) { detectTapGestures { } }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(headerBrushTop, headerBrushBottom)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    module.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "\u2715",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (module.settings.isEmpty()) {
                    Text("Bu modülde ayarlanabilir bir şey yok", fontSize = 11.sp, color = mutedColor)
                } else {
                    module.settings.forEach { s ->
                        if (module is ComboShortcut && s.name == "Modules") return@forEach
                        SettingRow(setting = s, onShortcutChanged = onShortcutChanged)
                    }
                    if (module is ComboShortcut) ComboShortcutPanel(module = module)
                    if (module is CommandHelper) CommandHelperPanel(module = module, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Horion tarzı tema — Grid menüsü bilerek Rubidium* renk paletinden bağımsız:
// sadece koyu siyah + açık mavi/lacivert. HileMenu (yan panel) dokunulmadı.
// ─────────────────────────────────────────────────────────────────────────
private val HorionBg            = Color(0xFF07090E)
private val HorionSurface       = Color(0xFF0D111B)
private val HorionSurfaceRaised = Color(0xFF141A28)
private val HorionCardActive    = Color(0xFF122036)
private val HorionOutline       = Color(0xFF1B2233)
private val HorionOutlineStrong = Color(0xFF232B41)
private val HorionAccent        = Color(0xFF4C9BFF)
private val HorionAccentDim     = Color(0xFF4C9BFF).copy(alpha = 0.16f)
private val HorionOnBg          = Color(0xFFE9EEFC)
private val HorionOnBgDim       = Color(0xFF7E8AAD)
private val HorionSuccess       = Color(0xFF3DDC84)
private val HorionError         = Color(0xFFFF5C6C)

/**
 * Grid menüsündeki modül kartı — Dialog() KULLANMAZ.
 *
 * FIX: eski GridModuleTile burada bir androidx.compose.ui.window.Dialog
 * açıyordu. Dialog(), altta bir Activity Window'u gerektirir; bu menü ise
 * OverlayService (bir Service, Activity değil) tarafından WindowManager ile
 * eklenen bir ComposeView içinde çalışıyor. Herhangi bir modülün ayar
 * ikonuna basılınca Dialog pencere oluşturmaya çalışıp çöküyordu — üstteki
 * tam ekran, dokunulabilir menü penceresi hâlâ takılı kaldığı için de ekranı
 * kilitliyordu. Çözüm: HileMenu'deki ModuleCard'da olduğu gibi ayarları yeni
 * bir pencere açmadan, aynı Compose ağacı içinde AnimatedVisibility ile
 * aşağı doğru açıp kapatıyoruz.
 */
@Composable
private fun ConfigSection() {
    val scope = rememberCoroutineScope()
    val profiles      by Config.profiles.collectAsState(initial = emptyList())
    val activeProfile by Config.activeProfile.collectAsState(initial = null)
    var newName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                placeholder = { Text("Profile name", fontSize = 11.sp, color = RubidiumOnSurfaceDim) },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { Config.save(trimmed) }
                            newName = ""
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        HorizontalDivider(color = RubidiumOutlineStrong)

        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved profiles yet.", fontSize = 12.sp, color = RubidiumOnSurfaceDim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(profiles) { profile ->
                    ConfigProfileRow(
                        name     = profile.name,
                        active   = profile.name == activeProfile,
                        onLoad   = { scope.launch { Config.load(profile.name) } },
                        onDelete = { scope.launch { Config.delete(profile.name) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigProfileRow(
    name    : String,
    active  : Boolean,
    onLoad  : () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) RubidiumSurfaceVar else RubidiumSurface)
            .border(1.dp,
                if (active) RubidiumAccentLight.copy(0.6f) else RubidiumOutline,
                RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = RubidiumOnBackground,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLoad, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumAccentLight)) {
                Text("Load", fontSize = 11.sp)
            }
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)) {
                Text("Del", fontSize = 11.sp)
            }
        }
    }
}


@Composable
private fun FriendsSection() {
    var newName by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf(FriendManager.getAll()) }

    fun refresh() { friends = FriendManager.getAll() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                placeholder = { Text("Player name", fontSize = 11.sp, color = RubidiumOnSurfaceDim) },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            FriendManager.addFriend(trimmed)
                            newName = ""
                            refresh()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${friends.size} friends • ignored by combat/movement modules",
                fontSize = 10.sp,
                color = RubidiumOnSurfaceDim
            )
            if (friends.isNotEmpty()) {
                TextButton(
                    onClick = { FriendManager.clear(); refresh() },
                    colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)
                ) {
                    Text("Clear all", fontSize = 11.sp)
                }
            }
        }

        HorizontalDivider(color = RubidiumOutlineStrong)

        if (friends.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No friends added yet.", fontSize = 12.sp, color = RubidiumOnSurfaceDim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(friends) { name ->
                    FriendRow(
                        name     = name,
                        onRemove = { FriendManager.removeFriend(name); refresh() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = RubidiumOnBackground,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)) {
            Text("Remove", fontSize = 11.sp)
        }
    }
}


@Composable
private fun ModuleCard(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled  by remember { mutableStateOf(module.isEnabled) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val cardBg = when {
        expanded -> RubidiumModuleExpanded
        enabled  -> RubidiumModuleActive
        else     -> RubidiumSurface
    }
    val borderColor = when {
        enabled -> RubidiumModuleActiveBorder
        else    -> RubidiumOutline.copy(0.6f)
    }
    val textColor = if (enabled) RubidiumModuleActiveText else RubidiumOnSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .clip(RoundedCornerShape(11.dp))
            .background(cardBg)
            .border(if (enabled) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(11.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (module.settings.isNotEmpty()) expanded = !expanded
                    else ModuleManager.toggle(module)
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                module.name,
                fontSize = 13.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { ModuleManager.toggle(module); onShortcutChanged() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor   = RubidiumModuleActiveBorder,
                    checkedThumbColor   = Color.White,
                    uncheckedTrackColor = RubidiumOutlineStrong,
                    uncheckedThumbColor = RubidiumOnSurfaceDim
                ),
                modifier = Modifier
                    .scale(0.8f)
                    .height(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded && module.settings.isNotEmpty(),
            enter   = expandVertically(
                          animationSpec = tween(320, easing = FastOutSlowInEasing),
                          expandFrom    = Alignment.Top
                      ) + fadeIn(animationSpec = tween(280, delayMillis = 40, easing = LinearOutSlowInEasing)),
            exit    = shrinkVertically(
                          animationSpec = tween(260, easing = FastOutSlowInEasing),
                          shrinkTowards = Alignment.Top
                      ) + fadeOut(animationSpec = tween(160, easing = LinearEasing))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                module.settings.forEach { s ->
                    if (module is ComboShortcut && s.name == "Modules") return@forEach
                    SettingRow(setting = s, onShortcutChanged = onShortcutChanged)
                }
                if (module is ComboShortcut) {
                    ComboShortcutPanel(module = module)
                }
                if (module is CommandHelper) {
                    CommandHelperPanel(module = module, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}


@Composable
private fun SettingRow(setting: ModuleSetting<*>, onShortcutChanged: () -> Unit) {
    when (setting) {
        is FloatSetting -> {
            var v by remember { mutableFloatStateOf(setting.value) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                    Text("%.2f".format(v), fontSize = 12.sp, color = RubidiumAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    valueRange = setting.min..setting.max,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = RubidiumAccentLight,
                        activeTrackColor   = RubidiumAccent,
                        inactiveTrackColor = RubidiumOutlineStrong
                    )
                )
            }
        }
        is IntSetting -> {
            var v by remember { mutableFloatStateOf(setting.value.toFloat()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                    Text(v.roundToInt().toString(), fontSize = 12.sp, color = RubidiumAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                // FIX (donma/çökme): steps = (max - min - 1) aralığı geniş olan
                // ayarlarda (örn. Performance modülündeki staleEntityTimeoutMs:
                // 5.000-120.000 -> ~115.000 step) Compose Slider'a on binlerce
                // ayrık tık noktası veriyordu. Slider bunları hesaplayıp her
                // recomposition'da çizmeye çalışınca ana thread'i kilitleyip
                // ekranın donmasına/uygulamanın çökmesine yol açıyordu. Aralık
                // küçükse (<= 100) ayrık adım tutmanın bir faydası var (elle
                // hassas seçim), büyükse sürekli (steps = 0) sürgüye geçip
                // değeri onValueChange'de zaten yuvarlıyoruz — görsel/işlevsel
                // fark yok, performans riski sıfır.
                val rawSteps = setting.max - setting.min - 1
                val steps = if (rawSteps in 1..100) rawSteps else 0
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it.roundToInt() },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                    steps = steps,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = RubidiumAccentLight,
                        activeTrackColor   = RubidiumAccent,
                        inactiveTrackColor = RubidiumOutlineStrong
                    )
                )
            }
        }
        is BoolSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            val isShortcut = setting.name == "Shortcut"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                if (isShortcut) {
                    ShortcutToggle(checked = v) {
                        v = it; setting.value = it; onShortcutChanged()
                    }
                } else {
                    Switch(
                        checked = v,
                        onCheckedChange = { v = it; setting.value = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor   = RubidiumAccent,
                            checkedThumbColor   = Color.White,
                            uncheckedTrackColor = RubidiumOutlineStrong,
                            uncheckedThumbColor = RubidiumOnSurfaceDim
                        )
                    )
                }
            }
        }
        is EnumSetting<*> -> {
            @Suppress("UNCHECKED_CAST")
            val es = setting as EnumSetting<Enum<*>>
            var sel by remember { mutableStateOf(es.value) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(es.name, fontSize = 12.sp, color = RubidiumOnSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    es.values.forEach { opt ->
                        val isSel = sel == opt
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (isSel) RubidiumSurfaceRaised else RubidiumSurface)
                                .border(1.dp,
                                    if (isSel) RubidiumOutlineStrong else RubidiumOutline,
                                    RoundedCornerShape(50.dp))
                                .clickable { sel = opt; es.value = opt }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                opt.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                color = if (isSel) RubidiumOnBackground else RubidiumOnSurface,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        is StringSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface,
                    modifier = Modifier.weight(0.4f))
                OutlinedTextField(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = RubidiumOnSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = RubidiumAccent,
                        unfocusedBorderColor = RubidiumOutline
                    )
                )
            }
        }
        else -> {}
    }
}

@Composable
private fun ComboShortcutPanel(module: ComboShortcut) {
    var selected by remember(module) {
        mutableStateOf(
            module.targets.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )
    }

    val allModules = remember(module) {
        ModuleCategory.values()
            .flatMap { ModuleManager.byCategory(it) }
            .distinctBy { it.name }
            .filter { it.name != module.name }
    }

    HorizontalDivider(color = RubidiumOutline.copy(0.5f), modifier = Modifier.padding(vertical = 2.dp))
    Text("Select modules", fontSize = 11.sp, color = RubidiumOnSurfaceDim)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        allModules.forEach { mod ->
            val isChecked = mod.name in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isChecked) RubidiumSurface else Color.Transparent)
                    .clickable {
                        selected = if (isChecked) selected - mod.name else selected + mod.name
                        module.targets.value = selected.joinToString(", ")
                    }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isChecked) RubidiumAccent else Color.Transparent)
                        .border(
                            1.dp,
                            if (isChecked) RubidiumAccent else RubidiumOutlineStrong,
                            RoundedCornerShape(3.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecked) {
                        Text("✓", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    mod.name,
                    fontSize = 11.sp,
                    color = RubidiumOnSurface.copy(alpha = if (isChecked) 1f else 0.7f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (selected.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(RubidiumAccent)
                .clickable {
                    selected.forEach { name ->
                        ModuleManager.byName(name)?.let { ModuleManager.enable(it) }
                    }
                }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                module.comboName.value.ifBlank { "Combo" },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = RubidiumOnBackground
            )
        }
    }
}

@Composable
private fun CommandHelperPanel(module: CommandHelper, onShortcutChanged: () -> Unit) {
    var entries by remember(module) { mutableStateOf(module.entries) }
    var adding by remember(module) { mutableStateOf(false) }
    var newEntry by remember(module) { mutableStateOf("") }

    HorizontalDivider(color = RubidiumOutline.copy(0.5f), modifier = Modifier.padding(vertical = 2.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Saved commands", fontSize = 11.sp, color = RubidiumOnSurfaceDim)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(RubidiumSurfaceRaised)
                .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
                .clickable { adding = !adding }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (adding) "×" else "+",
                fontSize = 13.sp,
                color = RubidiumAccentLight,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (adding) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newEntry,
                onValueChange = { newEntry = it },
                singleLine = true,
                placeholder = { Text("/gamemode creative or a chat message", fontSize = 10.sp) },
                modifier = Modifier.weight(1f).height(40.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        if (newEntry.isNotBlank()) {
                            module.addEntry(newEntry)
                            entries = module.entries
                            newEntry = ""
                            adding = false
                            onShortcutChanged()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text("Add", fontSize = 11.sp, color = RubidiumOnBackground, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (entries.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(RubidiumSurface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry,
                        fontSize = 11.sp,
                        color = RubidiumOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { module.send(entry) }
                    )
                    Text(
                        "×",
                        fontSize = 13.sp,
                        color = RubidiumOnSurfaceDim,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                module.removeEntry(entry)
                                entries = module.entries
                                onShortcutChanged()
                            }
                            .padding(start = 8.dp)
                    )
                }
            }
        }
    } else if (!adding) {
        Text("No saved commands yet", fontSize = 10.sp, color = RubidiumOnSurfaceDim)
    }
}

@Composable
private fun ShortcutToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(if (checked) RubidiumSurfaceRaised else Color.Transparent)
            .border(1.5.dp,
                if (checked) RubidiumOutlineStrong else RubidiumOutline,
                RoundedCornerShape(50.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (checked) "On" else "Off",
            fontSize = 11.sp,
            color = if (checked) RubidiumOnBackground else RubidiumOnSurfaceDim,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────
// Compact module search field — embedded in the CS:GO header bar between
// the title and the close button (results render in ModuleSearchPanel).
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun MenuSearchHeader(
    query         : String,
    onQueryChange : (String) -> Unit,
    modifier      : Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onQueryChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                "Search modules…",
                fontSize = 10.sp,
                color = RubidiumOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                Text(
                    "×",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = RubidiumOnSurfaceDim,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onQueryChange("") }
                        .padding(4.dp)
                )
            }
        } else null,
        modifier = modifier.height(38.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 11.sp, color = RubidiumOnSurface, fontFamily = FontFamily.Monospace
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = RubidiumAccent,
            unfocusedBorderColor    = RubidiumOutline,
            focusedContainerColor   = RubidiumBackground.copy(alpha = 0.6f),
            unfocusedContainerColor = RubidiumBackground.copy(alpha = 0.6f)
        )
    )
}

/**
 * Search results: every module whose name (or category) matches the query,
 * with a category chip and a working toggle — replaces the menu content
 * while a query is typed.
 */
@Composable
private fun ModuleSearchPanel(
    query             : String,
    moduleVersion     : Int,
    onShortcutChanged : () -> Unit
) {
    val results = remember(query, moduleVersion) {
        val q = query.trim().lowercase()
        ModuleManager.getAll()
            .filter {
                it.name.lowercase().contains(q) ||
                it.category.displayName.lowercase().contains(q)
            }
            .sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RubidiumBackground.copy(alpha = 0.9f))
            .border(1.dp, RubidiumOutline.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "${results.size} match${if (results.size == 1) "" else "es"}",
            fontSize = 10.sp,
            color = RubidiumOnSurfaceDim,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No modules match \"$query\"", fontSize = 12.sp, color = RubidiumOnSurfaceDim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(results) { mod ->
                    SearchResultRow(module = mod, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled by remember { mutableStateOf(module.isEnabled) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) RubidiumSurfaceVar else RubidiumSurface)
            .border(
                if (enabled) 1.5.dp else 1.dp,
                if (enabled) RubidiumModuleActiveBorder else RubidiumOutline.copy(0.6f),
                RoundedCornerShape(10.dp)
            )
            .clickable { ModuleManager.toggle(module); onShortcutChanged() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            module.name,
            fontSize = 13.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
            color = if (enabled) RubidiumOnBackground else RubidiumOnSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Category chip — the search points out where the module lives.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(RubidiumAccent.copy(0.14f))
                .border(1.dp, RubidiumAccent.copy(0.45f), RoundedCornerShape(50.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                module.category.displayName.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = RubidiumAccentLight,
                fontFamily = FontFamily.Monospace
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { ModuleManager.toggle(module); onShortcutChanged() },
            colors = SwitchDefaults.colors(
                checkedTrackColor   = RubidiumModuleActiveBorder,
                checkedThumbColor   = Color.White,
                uncheckedTrackColor = RubidiumOutlineStrong,
                uncheckedThumbColor = RubidiumOnSurfaceDim
            ),
            modifier = Modifier.scale(0.8f).height(18.dp)
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────
// CS:GO style click GUI — layout idea follows WClient's OverlayClickGUI
// (centered window, category tab sidebar, module rows), restyled to the
// Rubidium/Catppuccin theme. Own in-window module search, plus the Config
// and Friends sections from the classic menu as sidebar tabs. Settings
// reuse a themed variant of the grid's service-safe popup.
// ─────────────────────────────────────────────────────────────────────────
private enum class CsgoTab(val display: String, val category: ModuleCategory?) {
    COMBAT("Combat", ModuleCategory.COMBAT),
    MOVEMENT("Movement", ModuleCategory.MOVEMENT),
    VISUAL("Visual", ModuleCategory.VISUAL),
    PLAYER("Player", ModuleCategory.PLAYER),
    WORLD("World", ModuleCategory.WORLD),
    MISC("Misc", ModuleCategory.MISC),
    CONFIG("Config", null),
    FRIENDS("Friends", null)
}

@Composable
private fun CsgoSidebarTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) RubidiumAccent.copy(0.16f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) RubidiumAccent.copy(0.55f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) RubidiumAccentLight else RubidiumOnSurfaceDim,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CsgoMenu(
    onClose           : () -> Unit,
    moduleVersion     : Int,
    onShortcutChanged : () -> Unit,
    modifier          : Modifier = Modifier
) {
    val cfg  = LocalConfiguration.current
    val winW = (cfg.screenWidthDp.dp - 28.dp).coerceAtMost(620.dp)
    val winH = (cfg.screenHeightDp.dp - 36.dp).coerceAtMost(360.dp)

    var tab by remember { mutableStateOf(CsgoTab.COMBAT) }
    var searchQuery by remember { mutableStateOf("") }
    val mods = remember(moduleVersion, tab) {
        tab.category?.let { ModuleManager.byCategory(it) } ?: emptyList()
    }
    val searching = searchQuery.isNotBlank()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(winW)
                .height(winH)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(RubidiumBackground.copy(alpha = 0.94f))
                .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(14.dp))
                // Panel taps must not close the menu (root closes on outside taps).
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            // ── Header bar: title on the left, close on the right, and the
            //    module search field embedded in the space between them. ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RubidiumSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "ECLIENT",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = RubidiumAccentLight,
                    fontFamily = FontFamily.Monospace
                )
                MenuSearchHeader(
                    query = searchQuery,
                    onQueryChange = { q ->
                        searchQuery = q
                        if (q.isNotBlank()) GridSettingsPopup.close()
                    },
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(RubidiumError.copy(0.15f))
                        .border(1.dp, RubidiumError.copy(0.4f), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("x", color = RubidiumError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = RubidiumOutlineStrong)

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (searching) {
                    ModuleSearchPanel(
                        query             = searchQuery,
                        moduleVersion     = moduleVersion,
                        onShortcutChanged = onShortcutChanged
                    )
                } else {
                    // ── Category sidebar + content ──
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(92.dp)
                                .fillMaxHeight()
                                .background(RubidiumSurfaceVar.copy(alpha = 0.55f))
                                .border(1.dp, RubidiumOutline.copy(0.6f))
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CsgoTab.entries.forEach { t ->
                                if (t == CsgoTab.CONFIG) {
                                    // Divider separates module categories from sections
                                    HorizontalDivider(
                                        color = RubidiumOutline.copy(0.7f),
                                        modifier = Modifier.padding(vertical = 3.dp)
                                    )
                                }
                                CsgoSidebarTab(label = t.display, selected = tab == t) { tab = t }
                            }
                        }

                        when (tab) {
                            CsgoTab.CONFIG -> Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) { ConfigSection() }
                            CsgoTab.FRIENDS -> Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) { FriendsSection() }
                            else -> Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        tab.display.uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = RubidiumOnBackground,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        "${mods.size} modules",
                                        fontSize = 9.sp,
                                        color = RubidiumOnSurfaceDim,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                if (mods.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Empty", fontSize = 10.sp, color = RubidiumOnSurfaceDim)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(mods) { mod ->
                                            CsgoModuleRow(module = mod, onShortcutChanged = onShortcutChanged)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Module settings popup — grid's service-safe fake-Dialog, themed to match.
        val popupModule = GridSettingsPopup.current
        if (popupModule != null) {
            ModuleSettingsPopup(
                module            = popupModule,
                onShortcutChanged = onShortcutChanged,
                onDismiss         = { GridSettingsPopup.close() },
                headerBrushTop    = RubidiumSurfaceVar,
                headerBrushBottom = RubidiumSurface,
                panelBrushTop     = RubidiumBackground,
                panelBrushBottom  = RubidiumSurface,
                borderColor       = RubidiumAccent.copy(0.5f),
                glowColor         = RubidiumAccent.copy(0.08f),
                titleColor        = RubidiumOnBackground,
                mutedColor        = RubidiumOnSurfaceDim
            )
        }
    }
}

@Composable
private fun CsgoModuleRow(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled by remember { mutableStateOf(module.isEnabled) }
    val popupModule  = GridSettingsPopup.current
    val isPopupOpen  = popupModule === module
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isPopupOpen -> RubidiumAccent.copy(0.24f)
                    enabled     -> RubidiumAccent.copy(0.16f)
                    else        -> RubidiumSurface
                }
            )
            .border(
                1.dp,
                when {
                    isPopupOpen -> RubidiumAccentLight
                    enabled     -> RubidiumAccent.copy(0.6f)
                    else        -> RubidiumOutline.copy(0.6f)
                },
                RoundedCornerShape(6.dp)
            )
            .clickable { ModuleManager.toggle(module); onShortcutChanged() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            module.name,
            fontSize = 12.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
            color = if (enabled) RubidiumOnBackground else RubidiumOnSurfaceDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (module.settings.isNotEmpty()) {
            Text(
                "⚙",   // open the (service-safe) module settings popup
                fontSize = 12.sp,
                color = RubidiumOnSurfaceDim,
                modifier = Modifier
                    .clickable { GridSettingsPopup.toggle(module) }
                    .padding(2.dp)
            )
        }
        Text(
            if (enabled) "ON" else "OFF",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) RubidiumAccentLight else RubidiumOnSurfaceDim.copy(0.5f)
        )
    }
}
