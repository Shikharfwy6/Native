package com.motionstudio.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

class MainActivity : Activity() {
    private val p = Project()
    private lateinit var editor: EditorView
    private lateinit var timeline: TimelineView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: Button
    private lateinit var easeBtn: Button
    private lateinit var aspectBtn: Button
    private lateinit var durText: TextView
    private lateinit var sbScale: SeekBar
    private lateinit var sbRot: SeekBar
    private lateinit var sbAlpha: SeekBar
    private val keyBtns = HashMap<String, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var lastNs = 0L
    private var syncing = false

    private val aspects = arrayOf(
        Triple("9:16", 720, 1280), Triple("16:9", 1280, 720),
        Triple("1:1", 900, 900), Triple("4:5", 864, 1080)
    )
    private var aspectIdx = 0
    private val palette = intArrayOf(
        Color.WHITE, Color.parseColor("#FF5A7A"), Color.parseColor("#FFC247"),
        Color.parseColor("#4ADE80"), Color.parseColor("#38BDF8"),
        Color.parseColor("#A78BFA"), Color.BLACK
    )

    private val presets = linkedMapOf<String, (Layer, Float) -> Unit>(
        "Fade in" to { l: Layer, t: Float ->
            l.upsert(P_ALPHA, t, 0f, 2); l.upsert(P_ALPHA, t + 0.6f, 1f, 2)
        },
        "Fade out" to { l: Layer, t: Float ->
            l.upsert(P_ALPHA, t, 1f, 1); l.upsert(P_ALPHA, t + 0.6f, 0f, 1)
        },
        "Slide up" to { l: Layer, t: Float ->
            val y = l.eval(P_Y, t)
            l.upsert(P_Y, t, y + 220f, 2); l.upsert(P_Y, t + 0.7f, y, 2)
            l.upsert(P_ALPHA, t, 0f, 2); l.upsert(P_ALPHA, t + 0.5f, 1f, 2)
        },
        "Pop in" to { l: Layer, t: Float ->
            l.upsert(P_SCALE, t, 0f, 4); l.upsert(P_SCALE, t + 0.6f, 1f, 4)
        },
        "Spin in" to { l: Layer, t: Float ->
            l.upsert(P_ROT, t, -180f, 2); l.upsert(P_ROT, t + 0.8f, 0f, 2)
            l.upsert(P_SCALE, t, 0f, 2); l.upsert(P_SCALE, t + 0.8f, 1f, 2)
        },
        "Bounce drop" to { l: Layer, t: Float ->
            val y = l.eval(P_Y, t)
            l.upsert(P_Y, t, y - 500f, 5); l.upsert(P_Y, t + 1f, y, 5)
        }
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(C_TEXT)
        b.setBackgroundColor(C_PANEL2)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(12), 0, dp(12), 0)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): HorizontalScrollView {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.setPadding(dp(5), 0, dp(5), 0)
        for (v in views) ll.addView(v)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(ll)
        return hs
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(C_DIM)
        t.textSize = 12f
        t.setPadding(dp(10), dp(10), dp(10), dp(2))
        return t
    }

    private fun keyRow(name: String, props: IntArray, sb: SeekBar?): LinearLayout {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        ll.setPadding(dp(10), 0, dp(10), 0)
        val tv = TextView(this)
        tv.text = name
        tv.setTextColor(C_TEXT)
        tv.textSize = 13f
        ll.addView(tv, LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (sb != null) {
            ll.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val hint = TextView(this)
            hint.text = "Drag on the canvas"
            hint.setTextColor(C_DIM)
            hint.textSize = 12f
            ll.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val kb = btn("\u25C6") {
            val l = p.sel
            if (l == null) {
                toast("Select a layer first")
            } else {
                val has = props.all { l.keyAt(it, p.time) != null }
                for (q in props) {
                    if (has) l.removeKey(q, p.time) else l.upsert(q, p.time, l.eval(q, p.time), p.easeIdx)
                }
                refreshProps()
                timeline.invalidate()
            }
        }
        kb.layoutParams = LinearLayout.LayoutParams(dp(46), dp(40))
        keyBtns[name] = kb
        ll.addView(kb)
        return ll
    }

    private fun slider(max: Int, onChange: (Layer, Int) -> Unit): SeekBar {
        val sb = SeekBar(this)
        sb.max = max
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val l = p.sel
                if (fromUser && !syncing && l != null) {
                    onChange(l, v)
                    editor.invalidate()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                refreshProps()
                timeline.invalidate()
            }
        })
        return sb
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_INK
        window.navigationBarColor = C_INK
        starter()

        editor = EditorView(this, p)
        timeline = TimelineView(this, p)

        editor.onSelect = { refreshProps(); timeline.invalidate() }
        editor.onEdit = { refreshProps() }
        editor.onCommit = { timeline.invalidate(); refreshProps() }
        timeline.onSeek = { stopPlay(); setTime(it) }
        timeline.onSelect = { editor.invalidate(); refreshProps() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_INK)

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(C_PANEL)
        top.setPadding(dp(12), dp(4), dp(6), dp(4))
        val title = TextView(this)
        title.text = "Motion Studio"
        title.setTextColor(C_TEXT)
        title.textSize = 17f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val exportBtn = btn("Export video") { doExport() }
        exportBtn.setBackgroundColor(C_ACCENT)
        exportBtn.setTextColor(0xFF1D060B.toInt())
        top.addView(exportBtn)
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // transport
        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        tr.gravity = Gravity.CENTER_VERTICAL
        tr.setBackgroundColor(C_PANEL)
        tr.setPadding(dp(6), dp(2), dp(10), dp(2))
        playBtn = btn("\u25B6") { if (playing) stopPlay() else startPlay() }
        val toStart = btn("\u23EE") { stopPlay(); setTime(0f) }
        val prev = btn("\u2039") { stopPlay(); setTime(p.time - 1f / 30f) }
        val next = btn("\u203A") { stopPlay(); setTime(p.time + 1f / 30f) }
        tr.addView(toStart); tr.addView(playBtn); tr.addView(prev); tr.addView(next)
        seek = SeekBar(this)
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser && !syncing) { stopPlay(); setTime(v / 1000f * p.duration) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tr.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeText = TextView(this)
        timeText.setTextColor(C_TEXT)
        timeText.textSize = 13f
        tr.addView(timeText)
        root.addView(tr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        // control panel
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL

        panel.addView(label("Add layer"))
        panel.addView(row(
            btn("Text") { addLayer(0) }, btn("Rectangle") { addLayer(1) },
            btn("Circle") { addLayer(2) }, btn("Image") { pickImage() }
        ))

        panel.addView(label("Properties. Tap the diamond to set a keyframe at the playhead."))
        panel.addView(keyRow("Position", intArrayOf(P_X, P_Y), null))
        sbScale = slider(500) { l, v -> l.set(P_SCALE, p.time, v / 100f, p.easeIdx) }
        panel.addView(keyRow("Scale", intArrayOf(P_SCALE), sbScale))
        sbRot = slider(720) { l, v -> l.set(P_ROT, p.time, (v - 360).toFloat(), p.easeIdx) }
        panel.addView(keyRow("Rotation", intArrayOf(P_ROT), sbRot))
        sbAlpha = slider(100) { l, v -> l.set(P_ALPHA, p.time, v / 100f, p.easeIdx) }
        panel.addView(keyRow("Opacity", intArrayOf(P_ALPHA), sbAlpha))
        easeBtn = btn("Easing: ${Ease.names[p.easeIdx]}") {
            p.easeIdx = (p.easeIdx + 1) % Ease.names.size
            easeBtn.text = "Easing: ${Ease.names[p.easeIdx]}"
            val l = p.sel
            if (l != null) for (q in 0 until 5) l.keyAt(q, p.time)?.ease = p.easeIdx
        }
        panel.addView(row(easeBtn))

        panel.addView(label("Animation presets for the selected layer. They start at the playhead."))
        val presetBtns = presets.map { (name, fn) ->
            btn(name) {
                val l = p.sel
                if (l == null) {
                    toast("Select a layer first")
                } else {
                    fn(l, p.time)
                    refreshProps(); timeline.invalidate(); editor.invalidate()
                }
            }
        }
        panel.addView(row(*presetBtns.toTypedArray()))

        panel.addView(label("Selected layer"))
        panel.addView(row(
            btn("Edit text") { editText() }, btn("Colour") { cycleColour() },
            btn("Bigger") { resize(1.15f) }, btn("Smaller") { resize(1f / 1.15f) }
        ))
        panel.addView(row(
            btn("Duplicate") { duplicate() }, btn("Move up") { move(1) },
            btn("Move down") { move(-1) }, btn("Delete") { deleteLayer() }
        ))
        panel.addView(row(
            btn("Starts here") { trim(true) }, btn("Ends here") { trim(false) }
        ))

        panel.addView(label("Project"))
        aspectBtn = btn("Size: ${aspects[aspectIdx].first}") { cycleAspect() }
        durText = TextView(this)
        durText.setTextColor(C_TEXT)
        durText.textSize = 13f
        durText.setPadding(dp(8), 0, dp(8), 0)
        panel.addView(row(
            aspectBtn, btn("\u2212 1s") { changeDuration(-1f) }, durText,
            btn("+ 1s") { changeDuration(1f) }, btn("New project") { newProject() }
        ))
        val pad = View(this)
        panel.addView(pad, LinearLayout.LayoutParams(1, dp(24)))

        val sv = ScrollView(this)
        sv.setBackgroundColor(C_PANEL)
        sv.addView(panel)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))

        setContentView(root)
        setTime(0f)
    }

    /* ---------- project helpers ---------- */

    private fun starter() {
        val card = p.newLayer(1)
        card.name = "Card"; card.w = 560f; card.h = 360f; card.radius = 40f
        val tx = p.newLayer(0)
        tx.name = "Title"; tx.text = "Motion\nStudio"; tx.size = 120f
        p.layers.add(card)
        p.layers.add(tx)
        presets.getValue("Pop in")(card, 0.2f)
        presets.getValue("Slide up")(tx, 0.6f)
        p.sel = tx
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun afterStructure() {
        timeline.invalidate(); editor.invalidate(); refreshProps()
    }

    private fun addLayer(type: Int) {
        val l = p.newLayer(type)
        p.layers.add(l)
        p.sel = l
        afterStructure()
    }

    private fun duplicate() {
        val l = p.sel ?: return toast("Select a layer first")
        val c = l.duplicate(p.nextLayerId())
        p.layers.add(p.layers.indexOf(l) + 1, c)
        p.sel = c
        afterStructure()
    }

    private fun move(dir: Int) {
        val l = p.sel ?: return toast("Select a layer first")
        val i = p.layers.indexOf(l)
        val j = i + dir
        if (j < 0 || j >= p.layers.size) return
        p.layers.removeAt(i)
        p.layers.add(j, l)
        afterStructure()
    }

    private fun deleteLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        p.layers.remove(l)
        p.sel = null
        afterStructure()
    }

    private fun trim(isStart: Boolean) {
        val l = p.sel ?: return toast("Select a layer first")
        if (isStart) { if (p.time < l.end) l.start = p.time } else { if (p.time > l.start) l.end = p.time }
        afterStructure()
    }

    private fun resize(f: Float) {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type == 0) l.size = (l.size * f).coerceIn(20f, 400f) else { l.w *= f; l.h *= f }
        editor.invalidate()
    }

    private fun cycleColour() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type == 3) return toast("Images have no colour")
        val i = palette.indexOf(l.color)
        l.color = palette[(i + 1) % palette.size]
        editor.invalidate()
    }

    private fun editText() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type != 0) return toast("Select a text layer")
        val et = EditText(this)
        et.setText(l.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this)
            .setTitle("Edit text")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                l.text = et.text.toString().ifEmpty { " " }
                editor.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspects.size
        val a = aspects[aspectIdx]
        p.setAspect(a.second, a.third)
        aspectBtn.text = "Size: ${a.first}"
        editor.invalidate()
    }

    private fun changeDuration(delta: Float) {
        val old = p.duration
        val d = (old + delta).coerceIn(2f, 60f)
        p.duration = d
        for (l in p.layers) if (l.end >= old - 1e-3f || l.end > d) l.end = d
        setTime(p.time)
        afterStructure()
    }

    private fun newProject() {
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setMessage("The current project will be cleared.")
            .setPositiveButton("Clear") { _, _ ->
                stopPlay()
                p.layers.clear(); p.sel = null; p.duration = 5f
                aspectIdx = 0
                p.setAspect(720, 1280)
                aspectBtn.text = "Size: 9:16"
                starter()
                setTime(0f)
                afterStructure()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------- time / playback ---------- */

    private fun setTime(t: Float) {
        p.time = t.coerceIn(0f, p.duration)
        syncing = true
        seek.progress = (p.time / p.duration * 1000f).toInt()
        syncing = false
        timeText.text = String.format("%.2fs", p.time)
        durText.text = String.format("%.0fs", p.duration)
        editor.invalidate()
        timeline.invalidate()
        refreshProps()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.nanoTime()
            var t = p.time + (now - lastNs) / 1e9f
            lastNs = now
            if (t >= p.duration) t = 0f
            setTime(t)
            timeline.follow()
            handler.postDelayed(this, 16)
        }
    }

    private fun startPlay() {
        if (playing) return
        playing = true
        playBtn.text = "\u275A\u275A"
        lastNs = System.nanoTime()
        handler.post(tick)
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(tick)
        playBtn.text = "\u25B6"
    }

    private fun refreshProps() {
        val l = p.sel
        syncing = true
        sbScale.progress = ((l?.eval(P_SCALE, p.time) ?: 1f) * 100f).toInt().coerceIn(0, 500)
        sbRot.progress = ((l?.eval(P_ROT, p.time) ?: 0f) + 360f).toInt().coerceIn(0, 720)
        sbAlpha.progress = ((l?.eval(P_ALPHA, p.time) ?: 1f) * 100f).toInt().coerceIn(0, 100)
        syncing = false
        val groups = mapOf(
            "Position" to intArrayOf(P_X, P_Y), "Scale" to intArrayOf(P_SCALE),
            "Rotation" to intArrayOf(P_ROT), "Opacity" to intArrayOf(P_ALPHA)
        )
        for ((name, props) in groups) {
            val b = keyBtns[name] ?: continue
            val on = l != null && props.all { l.keyAt(it, p.time) != null }
            val has = l != null && props.any { l.keys[it].isNotEmpty() }
            b.setBackgroundColor(if (on) C_KEY else C_PANEL2)
            b.setTextColor(if (on) 0xFF2A1D00.toInt() else if (has) C_KEY else C_DIM)
        }
    }

    /* ---------- images ---------- */

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Choose an image"), 11)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 11 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val bmp = decode(uri)
        if (bmp == null) {
            toast("Could not open that image")
            return
        }
        val l = p.newLayer(3)
        l.bitmap = bmp
        val fit = minOf(1f, p.W * 0.7f / bmp.width, p.H * 0.5f / bmp.height)
        l.w = bmp.width * fit
        l.h = bmp.height * fit
        p.layers.add(l)
        p.sel = l
        afterStructure()
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > 1600) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Exception) {
            null
        }
    }

    /* ---------- export ---------- */

    private fun doExport() {
        stopPlay()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Rendering video")
            .setMessage("0%")
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            var error: String? = null
            val tmp = File(cacheDir, "export.mp4")
            try {
                Exporter.export(p, tmp, 30) { pct ->
                    runOnUiThread { dlg.setMessage("$pct%") }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val where = if (error == null) saveToGallery(tmp) else null
            runOnUiThread {
                dlg.dismiss()
                if (error != null) toast("Export failed: $error")
                else toast(if (where != null) "Saved to $where" else "Could not save the video")
            }
        }.start()
    }

    private fun saveToGallery(src: File): String? {
        return try {
            val name = "motion-${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MotionStudio")
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
                "Movies/MotionStudio"
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
                dir.mkdirs()
                val dst = File(dir, name)
                src.copyTo(dst, true)
                dst.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
