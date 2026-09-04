package com.rubidiumclient.utils

/**
 * Oyuncunun o an EKRANDA GERÇEKTEN gördüğü FOV değerini modüller arasında
 * paylaşan tekil kaynak.
 *
 * Neden gerekli: ESP gibi worldToScreen() ile ekran-uzayı projeksiyonu yapan
 * her modül, hesaplamada kullandığı FOV değeri gerçek render FOV'uyla
 * BİREBİR eşleşmezse -- özellikle FOVChanger walkSpeed tuzağıyla gerçek FOV'u
 * değiştirdiğinde -- projeksiyon ekran merkezine yakın kabaca doğru, kenarlara
 * gittikçe katlanarak yanlış çıkar (tracer/box'ların rastgele dağılmış
 * görünmesinin sebebi budur).
 *
 * FOVChanger etkinleştiğinde buraya yazar, kapatıldığında varsayılana döner.
 * ESP (ve ekran-uzayı hesabı yapan diğer tüm modüller) kendi ayrı fov
 * slider'ı yerine buradan okuyarak her zaman gerçek FOV ile senkron kalır.
 */
object GameFov {
    /** Bedrock Edition varsayılan görüş açısı (Java'nın 70°'inden farklı, 110°). */
    const val VANILLA_DEFAULT = 110f

    @Volatile
    var current: Float = VANILLA_DEFAULT
        private set

    fun set(value: Float) {
        current = value
    }

    fun reset() {
        current = VANILLA_DEFAULT
    }
}
