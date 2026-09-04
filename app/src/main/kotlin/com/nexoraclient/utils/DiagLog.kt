package com.rubidiumclient.utils

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rubidiumclient.RubidiumClientApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

// DiagLog: CrystalAura/AnchorAura/WorldBlockTracker gibi modüllerin "verbose"
// tanı loglarını artık oyun içi chat'e (TextPacket) basmak yerine buraya
// yönlendiriyoruz. Chat'e basmak hem gereksiz spam yaratıyordu (her tick
// tekrar eden "Kendi etrafım: 0 zemin" gibi satırlar gerçek sunucu
// mesajlarını görünmez kılıyordu) hem de RibidiumClientApp'in crash logger'ı
// için zaten kullanılan "baba.txt" (Downloads) dosyasına yazmıyordu. Bu obje
// aynı dosyaya (append modunda, crash logger'ın writeText ile ÜZERİNE
// yazmasından farklı olarak) sürekli ekleme yapar.
//
// Yazma işlemi tek bir arka plan thread'inde kuyruktan çekilerek, gerçek I/O
// (dosya açma/MediaStore sorgusu) UI/paket işleme thread'lerini asla
// bloklamaz - onPacket/tick içinden çağrılması güvenlidir.
object DiagLog {

    private const val FILE_NAME = "baba.txt"
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var writerStarted = false
    @Volatile private var cachedUri: Uri? = null

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        queue.offer(line)
        ensureWriter()
    }

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            Thread({ writerLoop() }, "DiagLogWriter").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun writerLoop() {
        while (true) {
            try {
                val first = queue.take()
                val batch = StringBuilder(first).append('\n')
                // Kuyrukta bekleyen diğer satırları da (varsa) tek yazmada
                // topla - her satır için ayrı ayrı dosya/MediaStore açmak
                // yerine az sayıda I/O çağrısı yapılır.
                var drained = 0
                while (drained < 500) {
                    val extra = queue.poll() ?: break
                    batch.append(extra).append('\n')
                    drained++
                }
                appendToFile(batch.toString())
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Bir yazma denemesi patlarsa logger'ın kendisi asla
                // uygulamayı çökertmemeli - sıradaki satırlarla devam.
            }
        }
    }

    private fun appendToFile(content: String) {
        val ctx = runCatching { RubidiumClientApp.instance }.getOrNull() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendViaMediaStore(ctx, content)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, FILE_NAME).appendText(content)
            }
        } catch (_: Exception) {
        }
    }

    private fun appendViaMediaStore(ctx: android.content.Context, content: String) {
        val resolver = ctx.contentResolver
        val uri = cachedUri ?: findOrCreateUri(resolver) ?: return
        cachedUri = uri
        try {
            resolver.openOutputStream(uri, "wa")?.use { it.write(content.toByteArray()) }
        } catch (e: Exception) {
            // Uri bir önceki uygulama oturumundan kalıp artık geçersiz olabilir
            // (kullanıcı dosyayı silmiş vs.) - bir dahaki log çağrısında yeniden
            // aranıp/oluşturulsun diye cache'i temizle.
            cachedUri = null
        }
    }

    private fun findOrCreateUri(resolver: android.content.ContentResolver): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(FILE_NAME), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}
