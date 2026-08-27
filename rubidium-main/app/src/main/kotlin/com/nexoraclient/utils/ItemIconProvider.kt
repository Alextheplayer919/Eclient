package com.rubidiumclient.utils

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

object ItemIconProvider {
    private var resources: Resources? = null
    private var packageName: String? = null
    private val cache = ConcurrentHashMap<String, Bitmap?>()

    fun init(context: Context) {
        resources   = context.resources
        packageName = context.packageName
    }

    fun get(identifier: String?): Bitmap? {
        if (identifier.isNullOrBlank()) return null
        val name = identifier.removePrefix("minecraft:")
        return cache.getOrPut(name) { load(name) }
    }

    private fun load(name: String): Bitmap? {
        val res = resources ?: return null
        val pkg = packageName ?: return null
        return try {
            val resId = res.getIdentifier("item_$name", "drawable", pkg)
            if (resId == 0) null else BitmapFactory.decodeResource(res, resId)
        } catch (_: Exception) {
            null
        }
    }
}
