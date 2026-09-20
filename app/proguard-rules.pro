# ============================================================
# Nexora Client release R8/ProGuard kurallari
# Amac: maksimum obfuscation + kaynak/string korumasi,
# ama Netty / CloudburstMC Protocol / jose4j gibi reflection ve
# ServiceLoader (META-INF/services) tabanli kutuphaneleri kirmadan.
#
# ONEMLI NOT (onceki cokmenin muhtemel sebebi):
# -repackageclasses + -overloadaggressively + -mergeinterfacesaggressively
# kombinasyonu, Netty/jose4j gibi kutuphanelerin ServiceLoader ile
# META-INF/services dosyalarindaki eski class-name referanslarini
# bulamamasina -> ClassNotFoundException / NoSuchMethodError'a yol acar.
# Bu yuzden bu uc kural KALDIRILDI. Bunlar olmadan da isimler
# tamamen obfuscate edilir, sadece paket tek harfe indirgenmiyor.
# ============================================================

# ---------- Netty ----------
-dontwarn io.netty.**
-keep class io.netty.util.internal.** { *; }
-keep class io.netty.channel.** { *; }
-keep class io.netty.buffer.** { *; }
-keep class io.netty.handler.** { *; }
-keepclassmembers class io.netty.** { *; }
-keepnames class io.netty.**

# ---------- CloudburstMC Protocol / NBT ----------
-dontwarn org.cloudburstmc.**
-keep class org.cloudburstmc.** { *; }
-keepnames class org.cloudburstmc.**

# ---------- jose4j (JWT/JWS/ECDSA) ----------
# jose4j, JCE provider ve algoritma siniflarini Class.forName /
# ServiceLoader ile bulur -> class isimleri korunmali.
-dontwarn org.bitbucket.b_c.jose4j.**
-keep class org.jose4j.** { *; }
-keepnames class org.jose4j.**

-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# ---------- Gson ----------
-dontwarn com.google.gson.**
-keep class com.google.gson.stream.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepattributes Signature, *Annotation*

# Gson'in reflection ile (de)serialize ettigi model/config siniflari:
# sinif adi obfuscate edilebilir (allowobfuscation), ama alan adlari
# JSON key'leriyle eslesmek zorunda oldugu icin korunuyor.
-keepclassmembers class com.nexoraclient.**.model.** {
    <fields>;
}
-keepclassmembers class com.nexoraclient.**.config.** {
    <fields>;
}
-keep,allowobfuscation,allowoptimization class com.nexoraclient.**.model.** {}
-keep,allowobfuscation,allowoptimization class com.nexoraclient.**.config.** {}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---------- AndroidX Activity / Lifecycle / SavedState / Startup ----------
# Bunlar Class.forName / manifest meta-data / ViewTree tag mekanizmasi
# ile reflection uzerinden bulunuyor. Repackage + obfuscation bunlari
# kirarsa LocalLifecycleOwner set edilmeden Compose agacina ulasip
# "CompositionLocal LocalLifecycleOwner not present" crash'ine yol acar.
-keep class androidx.activity.** { *; }
-keep interface androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep interface androidx.savedstate.** { *; }
-keep class androidx.startup.** { *; }
-keep public class * extends androidx.startup.Initializer
-dontwarn androidx.lifecycle.**
-dontwarn androidx.activity.**
-dontwarn androidx.savedstate.**
-dontwarn androidx.startup.**

# Compose runtime'in kendi CompositionLocal / recomposer kurulumu
# icin de sinif adlarina reflection ile referans verebiliyor.
-keep class androidx.compose.runtime.** { *; }
-keep interface androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }
-dontwarn androidx.compose.**

# ---------- OkHttp / Okio / Coroutines ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keepattributes InnerClasses, EnclosingMethod

# ---------- Uygulama giris noktasi ----------
-keep class com.nexoraclient.NexoraClientApp {
    public <init>(...);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- ServiceLoader / META-INF/services destegi ----------
# Netty, jose4j vb. kutuphaneler META-INF/services altinda class-name
# iceren metin dosyalari kullanabilir. Bu dosyalarin icerigini de
# yeni (obfuscate edilmis) isimlere gore guncelle, yoksa ServiceLoader
# eski adi arar ve bulamaz.
-adaptresourcefilecontents META-INF/services/**

# ---------- Genel obfuscation / optimizasyon (guvenli seviye) ----------
-allowaccessmodification
-optimizationpasses 3
# NOT: -repackageclasses, -overloadaggressively ve
# -mergeinterfacesaggressively kasitli olarak KALDIRILDI (yukaridaki not).
# Bu build stabil calistigini dogruladiktan sonra, tek tek geri ekleyip
# her ekleyiste tekrar test ederek hangisinin sorun cikardigini
# izole edebilirsin.

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}

# ---------- Kaynak kodu / string sizintisini en aza indirme ----------
-adaptclassstrings
-adaptresourcefilenames **.properties,**.xml,**.json
-adaptresourcefilecontents **.properties,META-INF/MANIFEST.MF

# Stack trace'lerde gercek dosya adi/satir no gorunmesin diye:
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable
-keepattributes !LocalVariableTable,!LocalVariableTypeTable

-printmapping mapping.txt

# ---------- Rastgele class/method/field isimleri ----------
# Varsayilan R8 isimlendirmesi a, b, c... seklinde sirali/tahmin edilebilir.
# Kelime listesinden rastgele isim atamak icin dictionary dosyalari.
# Dosyayi app/ modulu icine koy (app/obfuscation-dictionary.txt).
-obfuscationdictionary        obfuscation-dictionary.txt
-classobfuscationdictionary   obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt

# Tum siniflari tek (rastgele isimli) pakette topla — boylece paket
# hiyerarsisinden (com.nexoraclient.module.combat vb.) hicbir ipucu kalmaz.
# NOT: Daha once -overloadaggressively ve -mergeinterfacesaggressively ile
# birlikte kullanildiginda ServiceLoader / META-INF/services sorunlarina yol
# acmisti. Asagidaki -adaptresourcefilecontents META-INF/services/** kurali
# zaten var, bu yuzden -repackageclasses tek basina eklenip test edilebilir.
# Sorun cikarsa once bunu, hala cikarsa digerlerini tek tek geri ekle.
# GEÇICI OLARAK KAPATILDI: bu build'de eklenen -repackageclasses,
# androidx.activity/lifecycle/compose'un reflection ile bulunan
# siniflarini (yukarida artik keep edildi) etkilemiyor olsa da,
# "CompositionLocal LocalLifecycleOwner not present" crash'i bu
# obfuscation seviyesi eklendikten sonra ortaya cikti. Once yukaridaki
# keep kurallariyla test et; hala cokerse bu satiri kapali birak.
# -repackageclasses ''

# ---------- relay/ modulu (NBT + Protocol) — dokunma ----------
# ~/NexoraClient/relay klasoru CloudburstMC'nin nbt ve bedrock-protocol
# kaynak kodu (org.cloudburstmc.nbt.** / org.cloudburstmc.protocol.**).
# Bu paket zaten yukarida "CloudburstMC Protocol / NBT" basligi altinda
# -keep class org.cloudburstmc.** { *; } ile tamamen korunuyor —
# isim/alan/metot hicbiri obfuscate/shrink edilmiyor. Ek kurala gerek yok.
