package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

val C_INK = 0xFF101118.toInt()
val C_PANEL = 0xFF171923.toInt()
val C_PANEL2 = 0xFF202330.toInt()
val C_LINE = 0xFF2C3040.toInt()
val C_TEXT = 0xFFECEEF5.toInt()
val C_DIM = 0xFF8D92A8.toInt()
val C_ACCENT = 0xFFFF5A7A.toInt()
val C_KEY = 0xFFFFC247.toInt()

const val P_X = 0
const val P_Y = 1
const val P_SCALE = 2
const val P_ROT = 3
const val P_ALPHA = 4

object Ease {
    val names = arrayOf("Linear", "Ease in", "Ease out", "Ease in-out", "Overshoot", "Bounce")

    fun at(i: Int, t: Float): Float = when (i) {
        1 -> t * t * t
        2 -> 1f - (1f - t).pow(3)
        3 -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        4 -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
        }
        5 -> bounce(t)
        else -> t
    }

    private fun bounce(x: Float): Float {
        var t = x
        val n = 7.5625f
        val d = 2.75f
        return if (t < 1f / d) {
            n * t * t
        } else if (t < 2f / d) {
            t -= 1.5f / d
            n * t * t + 0.75f
        } else if (t < 2.5f / d) {
            t -= 2.25f / d
            n * t * t + 0.9375f
        } else {
            t -= 2.625f / d
            n * t * t + 0.984375f
        }
    }
}

class Key(var t: Float, var v: Float, var ease: Int)

/** type: 0 = text, 1 = rectangle, 2 = circle, 3 = image */
class Layer(val id: Int, val type: Int, var name: String) {
    var text = "Your text"
    var size = 96f
    var color = Color.WHITE
    var w = 420f
    var h = 260f
    var radius = 28f
    var start = 0f
    var end = 5f
    var bitmap: Bitmap? = null

    // x, y, scale, rotation (degrees), alpha (0..1)
    val base = floatArrayOf(0f, 0f, 1f, 0f, 1f)
    val keys = Array(5) { mutableListOf<Key>() }

    fun eval(p: Int, t: Float): Float {
        val k = keys[p]
        if (k.isEmpty()) return base[p]
        if (t <= k.first().t) return k.first().v
        if (t >= k.last().t) return k.last().v
        for (i in 0 until k.size - 1) {
            val a = k[i]
            val b = k[i + 1]
            if (t >= a.t && t <= b.t) {
                val u = (t - a.t) / (b.t - a.t)
                return a.v + (b.v - a.v) * Ease.at(a.ease, u)
            }
        }
        return k.last().v
    }

    fun keyAt(p: Int, t: Float): Key? = keys[p].firstOrNull { abs(it.t - t) < 0.017f }

    fun upsert(p: Int, t: Float, v: Float, ease: Int) {
        val ex = keyAt(p, t)
        if (ex != null) {
            ex.v = v
            ex.ease = ease
        } else {
            keys[p].add(Key(t, v, ease))
            keys[p].sortBy { it.t }
        }
    }

    fun removeKey(p: Int, t: Float) {
        val ex = keyAt(p, t) ?: return
        keys[p].remove(ex)
        if (keys[p].isEmpty()) base[p] = ex.v
    }

    /** Change a property at time t. If it has keyframes, a keyframe is added/updated. */
    fun set(p: Int, t: Float, v: Float, ease: Int) {
        if (keys[p].isEmpty()) base[p] = v else upsert(p, t, v, ease)
    }

    fun visible(t: Float) = t >= start - 1e-4f && t <= end + 1e-4f

    fun duplicate(newId: Int): Layer {
        val c = Layer(newId, type, "$name copy")
        c.text = text; c.size = size; c.color = color; c.w = w; c.h = h
        c.radius = radius; c.start = start; c.end = end; c.bitmap = bitmap
        for (i in 0 until 5) {
            c.base[i] = base[i]
            for (k in keys[i]) c.keys[i].add(Key(k.t, k.v, k.ease))
        }
        c.base[P_X] += 30f
        c.base[P_Y] += 30f
        for (k in c.keys[P_X]) k.v += 30f
        for (k in c.keys[P_Y]) k.v += 30f
        return c
    }
}

class Project {
    var W = 720
    var H = 1280
    var duration = 5f
    var bg = Color.parseColor("#0D0E14")
    val layers = mutableListOf<Layer>()
    var sel: Layer? = null
    var time = 0f
    var easeIdx = 2
    private var nextId = 1

    fun nextLayerId() = nextId++

    fun newLayer(type: Int): Layer {
        val names = arrayOf("Text", "Rectangle", "Circle", "Image")
        val n = layers.count { it.type == type } + 1
        val l = Layer(nextId++, type, "${names[type]} $n")
        l.base[P_X] = W / 2f
        l.base[P_Y] = H / 2f
        l.end = duration
        if (type == 1) l.color = Color.parseColor("#FF5A7A")
        if (type == 2) {
            l.w = 300f
            l.h = 300f
            l.color = Color.parseColor("#FFC247")
        }
        return l
    }

    fun setAspect(w: Int, h: Int) {
        val rx = w.toFloat() / W
        val ry = h.toFloat() / H
        for (l in layers) {
            l.base[P_X] *= rx
            l.base[P_Y] *= ry
            for (k in l.keys[P_X]) k.v *= rx
            for (k in l.keys[P_Y]) k.v *= ry
        }
        W = w
        H = h
    }
}

object Renderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun sizeOf(l: Layer): FloatArray {
        if (l.type != 0) return floatArrayOf(l.w, l.h)
        tp.textSize = l.size
        val lines = l.text.split("\n")
        var w = 20f
        for (ln in lines) w = max(w, tp.measureText(ln))
        return floatArrayOf(w, lines.size * l.size * 1.2f)
    }

    fun draw(c: Canvas, p: Project, t: Float) {
        c.drawColor(p.bg)
        for (l in p.layers) if (l.visible(t)) drawLayer(c, l, t)
    }

    private fun drawLayer(c: Canvas, l: Layer, t: Float) {
        val x = l.eval(P_X, t)
        val y = l.eval(P_Y, t)
        val s = l.eval(P_SCALE, t)
        val r = l.eval(P_ROT, t)
        val a = (l.eval(P_ALPHA, t).coerceIn(0f, 1f) * 255f).toInt()
        c.save()
        c.translate(x, y)
        c.rotate(r)
        c.scale(s, s)
        when (l.type) {
            0 -> {
                tp.color = l.color
                tp.alpha = a
                tp.textSize = l.size
                val fm = tp.fontMetrics
                val lines = l.text.split("\n")
                val lh = l.size * 1.2f
                for ((i, ln) in lines.withIndex()) {
                    val yy = (i - (lines.size - 1) / 2f) * lh - (fm.ascent + fm.descent) / 2f
                    c.drawText(ln, 0f, yy, tp)
                }
            }
            1 -> {
                paint.color = l.color
                paint.alpha = a
                rect.set(-l.w / 2f, -l.h / 2f, l.w / 2f, l.h / 2f)
                c.drawRoundRect(rect, l.radius, l.radius, paint)
            }
            2 -> {
                paint.color = l.color
                paint.alpha = a
                rect.set(-l.w / 2f, -l.h / 2f, l.w / 2f, l.h / 2f)
                c.drawOval(rect, paint)
            }
            3 -> {
                val bmp = l.bitmap
                if (bmp != null) {
                    paint.color = Color.WHITE
                    paint.alpha = a
                    rect.set(-l.w / 2f, -l.h / 2f, l.w / 2f, l.h / 2f)
                    c.drawBitmap(bmp, null, rect, paint)
                }
            }
        }
        c.restore()
    }
}
