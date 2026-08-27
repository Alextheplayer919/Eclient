package com.rubidiumclient.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.misc.Performance
import com.rubidiumclient.module.visual.ArrayListModule
import com.rubidiumclient.module.visual.ArmorHudModule
import com.rubidiumclient.module.visual.ChunkFinder
import com.rubidiumclient.module.visual.ESP
import com.rubidiumclient.module.visual.ShulkerPreview
import com.rubidiumclient.module.visual.TargetESP
import com.rubidiumclient.module.visual.Xray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class ESPOverlayView(context: Context) : View(context) {

    init {
        // FIX (siyah ekran): LAYER_TYPE_HARDWARE, bu View bir Activity'nin değil
        // doğrudan WindowManager.addView ile eklenen bir Service penceresinin kökü
        // olduğu için sorunluydu — pencere PixelFormat.TRANSLUCENT olsa bile,
        // bağımsız (Activity dışı) bir kök View'a uygulanan hardware layer birçok
        // cihazda/GPU sürücüsünde alfa kanalını kaybedip opak SİYAH render
        // ediyordu; tam ekranı kaplayan ESP overlay'inde bu, oyunun üstünü
        // kaplayan dev bir siyah dikdörtgen olarak görünüyordu. Bu View zaten her
        // frame postInvalidateOnAnimation() ile yeniden çiziliyor, yani statik
        // içerik önbellekleyen bir hardware layer'dan performans kazancı da yok —
        // kaldırmak hem hatayı çözüyor hem gereksiz layer maliyetini kaldırıyor.
        // setBackgroundColor(TRANSPARENT) da OEM'lerin varsayılan opak arka plan
        // davranışına karşı ek güvence.
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val espModule: ESP? by lazy { ModuleManager.byName("BaseFinderESP") as? ESP }
    private val xrayModule: Xray? by lazy { ModuleManager.byName("Xray") as? Xray }
    private val arrayListModule: ArrayListModule? by lazy { ModuleManager.byName("ArrayList") as? ArrayListModule }
    private val chunkFinderModule: ChunkFinder? by lazy { ModuleManager.byName("ChunkFinder") as? ChunkFinder }
    private val armorHudModule: ArmorHudModule? by lazy { ModuleManager.byName("ArmorHud") as? ArmorHudModule }
    // BUG FIX: TargetESP bu dosyaya hiç eklenmemişti — modül var, render()
    // fonksiyonu çalışıyor ama HİÇBİR ZAMAN çağrılmıyordu (ne enabledFlow
    // combine'ına ne onDraw çağrısına dahildi). "TargetESP hiç çalışmıyor"
    // şikayetinin tek sebebi buydu.
    private val targetEspModule: TargetESP? by lazy { ModuleManager.byName("TargetESP") as? TargetESP }
    private val shulkerPreviewModule: ShulkerPreview? by lazy { ModuleManager.byName("ShulkerPreview") as? ShulkerPreview }
    private val performanceModule: Performance? by lazy { ModuleManager.byName("Performance") as? Performance }

    // FPS/Lag HUD için: her frame'i saymak yerine ~500ms'lik pencerelerde
    // frame sayısını FPS'e çevirip OverlayState'e yazıyoruz — her onDraw'da
    // state güncellemek gereksiz recomposition'a yol açardı.
    private var fpsWindowStartMs = System.currentTimeMillis()
    private var fpsFrameCount = 0

    // ────────────────────────────────────────────────────────────────────
    // FPS OPTİMİZASYONU
    // ────────────────────────────────────────────────────────────────────
    // ÖNCEKİ DAVRANIŞ: onDraw() sonunda koşulsuz postInvalidateOnAnimation()
    // çağrılıyordu, yani hiçbir görsel modül (ESP/Xray/ArrayList/ChunkFinder)
    // açık olmasa BİLE bu View, ekranın yenileme hızında (60/90/120Hz)
    // sonsuza kadar tekrar tekrar invalidate ediliyor ve boş bir Canvas
    // çiziliyordu. Bu, oyun zaten kendi render loop'unu çalıştırırken üstüne
    // tamamen faydasız, sürekli bir View-invalidate + draw çağrısı bindiriyor
    // ve gereksiz CPU/GPU/pil tüketiyordu.
    //
    // YENİ DAVRANIŞ: hiçbir görsel modül aktif değilse (ve performans HUD'u
    // kapalıysa) döngü kendini durdurur; bir modül tekrar açıldığı anda
    // (module.enabledFlow üzerinden reaktif olarak) döngü otomatik devam
    // eder. Performans HUD'u açıkken FPS ölçümü doğası gereği her frame
    // sayması gerektiği için o modda döngü aktif kalır.
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null

    @Volatile private var hasActiveVisualModule = false
    @Volatile private var isLoopRunning = false

    // showPerformanceHud bir Setting nesnesi (StateFlow değil), bu yüzden onu
    // reaktif combine() içine katamıyoruz; bunun yerine ucuz, düşük frekanslı
    // bir polling ile (idle iken 400ms'de bir) kontrol ediyoruz — bu, her
    // frame'de çalışan eski koddan onlarca kat daha az iş demek.
    private val idlePoller = Handler(Looper.getMainLooper())
    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            if (!isLoopRunning && shouldRender()) {
                resumeLoop()
            }
            if (!isLoopRunning) {
                idlePoller.postDelayed(this, 400L)
            }
        }
    }

    private fun shouldRender(): Boolean {
        return hasActiveVisualModule || performanceModule?.showPerformanceHud?.value == true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeJob?.cancel()
        activeJob = renderScope.launch {
            val esp = espModule?.enabledFlow ?: flowOf(false)
            val xray = xrayModule?.enabledFlow ?: flowOf(false)
            val arrayList = arrayListModule?.enabledFlow ?: flowOf(false)
            val chunkFinder = chunkFinderModule?.enabledFlow ?: flowOf(false)
            val armorHud = armorHudModule?.enabledFlow ?: flowOf(false)
            val targetEsp = targetEspModule?.enabledFlow ?: flowOf(false)
            val shulkerPreview = shulkerPreviewModule?.enabledFlow ?: flowOf(false)

            // 5'ten fazla flow: iki katmanlı combine
            val baseActive = combine(esp, xray, arrayList, chunkFinder) { a, b, c, d -> a || b || c || d }
            combine(baseActive, armorHud, targetEsp, shulkerPreview) { base, armor, target, shulker -> base || armor || target || shulker }
                .collect { anyActive ->
                    hasActiveVisualModule = anyActive
                    if (anyActive && !isLoopRunning) resumeLoop()
                }
        }
        idlePoller.removeCallbacks(idleCheckRunnable)
        idlePoller.post(idleCheckRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeJob?.cancel()
        idlePoller.removeCallbacks(idleCheckRunnable)
        isLoopRunning = false
    }

    private fun resumeLoop() {
        if (isLoopRunning) return
        isLoopRunning = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val esp = espModule
        if (esp != null && esp.isEnabled) {
            try { esp.render(canvas, width, height) } catch (_: Exception) {}
        }

        val xray = xrayModule
        if (xray != null && xray.isEnabled) {
            try { xray.render(canvas, width, height) } catch (_: Exception) {}
        }

        val arrayList = arrayListModule
        if (arrayList != null && arrayList.isEnabled) {
            try { arrayList.render(canvas, width, height) } catch (_: Exception) {}
        }

        val chunkFinder = chunkFinderModule
        if (chunkFinder != null && chunkFinder.isEnabled) {
            try { chunkFinder.render(canvas, width, height) } catch (_: Exception) {}
        }

        val armorHud = armorHudModule
        if (armorHud != null && armorHud.isEnabled) {
            try { armorHud.render(canvas, width, height) } catch (_: Exception) {}
        }

        val targetEsp = targetEspModule
        if (targetEsp != null && targetEsp.isEnabled) {
            try { targetEsp.render(canvas, width, height) } catch (_: Exception) {}
        }

        val shulkerPreview = shulkerPreviewModule
        if (shulkerPreview != null && shulkerPreview.isEnabled) {
            try { shulkerPreview.render(canvas, width, height) } catch (_: Exception) {}
        }

        val hudOn = performanceModule?.showPerformanceHud?.value == true
        if (hudOn) {
            updateFpsWindow()
        }

        // Sadece hâlâ bir şey çizilmesi gerekiyorsa bir sonraki frame'i
        // planla; aksi halde döngüyü durdur ve idle-poller'a devret.
        if (hasActiveVisualModule || hudOn) {
            postInvalidateOnAnimation()
        } else {
            isLoopRunning = false
            idlePoller.removeCallbacks(idleCheckRunnable)
            idlePoller.post(idleCheckRunnable)
        }
    }

    private fun updateFpsWindow() {
        fpsFrameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 500L) {
            val fps = ((fpsFrameCount * 1000L) / elapsed).toInt()
            OverlayState.updatePerformanceStats(fps, EntityTracker.selfPingMs)
            fpsFrameCount = 0
            fpsWindowStartMs = now
        }
    }

    fun startRenderLoop() {
        resumeLoop()
    }
}
