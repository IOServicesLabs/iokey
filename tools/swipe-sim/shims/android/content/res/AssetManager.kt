package android.content.res

class AssetManager(private val dir: java.io.File) {
    fun open(name: String): java.io.InputStream = java.io.FileInputStream(java.io.File(dir, name))
}
