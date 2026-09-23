package android.util

open class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }
    @Synchronized fun get(k: K): V? = map[k]
    @Synchronized fun put(k: K, v: V): V? = map.put(k, v)
    @Synchronized fun evictAll() = map.clear()
}
