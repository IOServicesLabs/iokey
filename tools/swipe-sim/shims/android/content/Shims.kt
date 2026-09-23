package android.content

import android.content.res.AssetManager

open class Context(val assetDir: java.io.File) {
    val assets: AssetManager = AssetManager(assetDir)
    val applicationContext: Context get() = this
}
