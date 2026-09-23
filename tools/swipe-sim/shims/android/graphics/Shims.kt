package android.graphics

class PointF(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f) {
    constructor(p: PointF) : this(p.x, p.y)
    fun set(nx: Float, ny: Float) { x = nx; y = ny }
    override fun toString() = "PointF($x, $y)"
}

class RectF(@JvmField var left: Float = 0f, @JvmField var top: Float = 0f,
            @JvmField var right: Float = 0f, @JvmField var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}
