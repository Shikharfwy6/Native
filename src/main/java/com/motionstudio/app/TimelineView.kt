package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class TimelineView(context: Context, private val p: Project) : View(context) {
    var onSeek: ((Float) -> Unit)? = null
    var onSelect: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val nameW = dp(84f)
    private val rulerH = dp(28f)
    private val rowH = dp(38f)
    private val pps = dp(100f) // pixels per second
    private var scrollX = 0f
    private var scrollY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = C_LINE
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = C_ACCENT
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val path = Path()

    private var mode = 0 // 1 = scrub, 2 = scroll
    private var lx = 0f
    private var ly = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun clampScroll() {
        val maxX = max(0f, p.duration * pps + dp(60f) - (width - nameW))
        val maxY = max(0f, p.layers.size * rowH - (height - rulerH))
        scrollX = scrollX.coerceIn(0f, maxX)
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    fun follow() {
        val px = nameW + p.time * pps - scrollX
        if (px < nameW + dp(10f) || px > width - dp(10f)) {
            scrollX = p.time * pps - (width - nameW) * 0.3f
            clampScroll()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C_PANEL)
        clampScroll()
        val rows = p.layers.asReversed()
        val w = width.toFloat()
        val h = height.toFloat()

        // tracks
        c.save()
        c.clipRect(nameW, rulerH, w, h)
        for ((i, l) in rows.withIndex()) {
            val top = rulerH + i * rowH - scrollY
            if (top > h || top + rowH < rulerH) continue
            if (l === p.sel) {
                fill.color = 0x22FF5A7A
                c.drawRect(nameW, top, w, top + rowH, fill)
            }
            c.drawLine(nameW, top + rowH, w, top + rowH, line)
            val x0 = nameW + l.start * pps - scrollX
            val x1 = nameW + l.end * pps - scrollX
            fill.color = 0x40FF5A7A
            c.drawRoundRect(x0, top + dp(7f), x1, top + rowH - dp(7f), dp(5f), dp(5f), fill)
            val times = HashSet<Float>()
            for (ks in l.keys) for (k in ks) times.add(k.t)
            fill.color = C_KEY
            for (t in times) {
                val cx = nameW + t * pps - scrollX
                val cy = top + rowH / 2f
                val r = dp(6f)
                path.reset()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r, cy)
                path.lineTo(cx, cy + r)
                path.lineTo(cx - r, cy)
                path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()

        // playhead line
        val px = nameW + p.time * pps - scrollX
        if (px >= nameW) c.drawLine(px, 0f, px, h, head)

        // ruler
        c.save()
        c.clipRect(nameW, 0f, w, rulerH)
        fill.color = C_PANEL2
        c.drawRect(nameW, 0f, w, rulerH, fill)
        txt.color = C_DIM
        val step = if (pps >= dp(60f)) 1 else 2
        var s = 0
        val last = ceil(p.duration).toInt()
        while (s <= last) {
            val x = nameW + s * pps - scrollX
            c.drawLine(x, rulerH * 0.55f, x, rulerH, line)
            c.drawText("${s}s", x + dp(3f), rulerH * 0.5f, txt)
            s += step
        }
        if (px >= nameW) {
            fill.color = C_ACCENT
            path.reset()
            path.moveTo(px - dp(6f), 0f)
            path.lineTo(px + dp(6f), 0f)
            path.lineTo(px, dp(10f))
            path.close()
            c.drawPath(path, fill)
        }
        c.restore()

        // names column
        c.save()
        c.clipRect(0f, 0f, nameW, h)
        fill.color = C_PANEL
        c.drawRect(0f, 0f, nameW, h, fill)
        fill.color = C_PANEL2
        c.drawRect(0f, 0f, nameW, rulerH, fill)
        for ((i, l) in rows.withIndex()) {
            val top = rulerH + i * rowH - scrollY
            if (top > h || top + rowH < rulerH) continue
            txt.color = if (l === p.sel) C_ACCENT else C_TEXT
            val label = if (l.name.length > 10) l.name.take(9) + "…" else l.name
            c.drawText(label, dp(8f), top + rowH / 2f + dp(4f), txt)
            c.drawLine(0f, top + rowH, nameW, top + rowH, line)
        }
        c.drawLine(nameW, 0f, nameW, h, line)
        c.restore()
    }

    private fun seek(x: Float) {
        onSeek?.invoke(((x - nameW + scrollX) / pps).coerceIn(0f, p.duration))
    }

    private fun tap(x: Float, y: Float) {
        val rows = p.layers.asReversed()
        if (y < rulerH) return
        val i = ((y - rulerH + scrollY) / rowH).toInt()
        if (i !in rows.indices) return
        val l = rows[i]
        if (p.sel !== l) {
            p.sel = l
            onSelect?.invoke()
        }
        if (x >= nameW) {
            var best = -1f
            var bd = dp(16f)
            for (ks in l.keys) for (k in ks) {
                val dist = abs(nameW + k.t * pps - scrollX - x)
                if (dist < bd) {
                    bd = dist
                    best = k.t
                }
            }
            if (best >= 0f) onSeek?.invoke(best)
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                lx = e.x
                ly = e.y
                moved = false
                mode = if (e.y < rulerH && e.x >= nameW) 1 else 2
                if (mode == 1) seek(e.x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > dp(8f) || abs(e.y - downY) > dp(8f)) moved = true
                if (mode == 1) {
                    seek(e.x)
                } else {
                    scrollX -= e.x - lx
                    scrollY -= e.y - ly
                    clampScroll()
                    invalidate()
                }
                lx = e.x
                ly = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 2 && !moved) tap(e.x, e.y)
            }
        }
        return true
    }
}
