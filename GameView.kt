package com.sefa.hillclimb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val ST_MENU = 0
private const val ST_SELECT = 1
private const val ST_PLAY = 2
private const val ST_PAUSE = 3
private const val ST_OVER = 4

class Vehicle(
    val name: String, val price: Int, val engine: Float, val vmax: Float, val r: Float,
    val wb: Float, val fuelUse: Float, val inertia: Float, val color: Int, val desc: String
)

class GameMap(
    val name: String, val desc: String, val amp: Float, val freq: Float, val grip: Float,
    val skyTop: Int, val skyBot: Int, val grass: Int, val dirt: Int, val hill1: Int, val hill2: Int
)

class GameView(ctx: Context) : View(ctx) {

    // ---------- Veriler ----------
    private val d = ctx.resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("tepe_tirmanis", Context.MODE_PRIVATE)

    private val vehicles = listOf(
        Vehicle("JİP", 0, 450f, 700f, 16f, 32f, 4f, 2500f, Color.rgb(229, 57, 53), "Dengeli ve kolay kullanım"),
        Vehicle("BAGGY", 150, 520f, 850f, 14f, 30f, 5f, 2000f, Color.rgb(255, 143, 0), "Çok hızlı ama yakıt yer"),
        Vehicle("TRAKTÖR", 300, 650f, 550f, 22f, 38f, 3.5f, 3500f, Color.rgb(67, 160, 71), "Yavaş ama çok güçlü")
    )

    private val maps = listOf(
        GameMap(
            "ÇAYIR", "Kolay • yeşil tepeler", 1.0f, 1.0f, 1.0f,
            Color.rgb(95, 180, 255), Color.rgb(214, 240, 255), Color.rgb(76, 175, 80),
            Color.rgb(122, 74, 34), Color.rgb(156, 207, 154), Color.rgb(120, 184, 119)
        ),
        GameMap(
            "ÇÖL", "Orta • büyük kumullar", 1.25f, 0.8f, 0.9f,
            Color.rgb(255, 183, 77), Color.rgb(255, 236, 179), Color.rgb(237, 201, 125),
            Color.rgb(181, 130, 60), Color.rgb(230, 180, 110), Color.rgb(205, 150, 80)
        ),
        GameMap(
            "KAR", "Zor • kaygan zemin", 1.0f, 1.1f, 0.7f,
            Color.rgb(120, 150, 200), Color.rgb(225, 238, 250), Color.rgb(245, 250, 255),
            Color.rgb(120, 144, 170), Color.rgb(200, 215, 235), Color.rgb(160, 185, 215)
        )
    )

    private var totalCoins = prefs.getInt("coins", 0)
    private var owned = prefs.getInt("owned", 1) or 1
    private var selVeh = prefs.getInt("veh", 0).coerceIn(0, vehicles.size - 1)
    private var selMap = prefs.getInt("map", 0).coerceIn(0, maps.size - 1)

    // ---------- Oyun durumu ----------
    private var state = ST_MENU
    private var sw = 0f
    private var sh = 0f
    private var sc = 1f
    private val rects = HashMap<String, RectF>()

    private val G = 900f
    private val K = 6000f
    private val C = 70f

    private var carX = 150f
    private var carY = 0f
    private var carVX = 0f
    private var carVY = 0f
    private var carA = 0f
    private var carW = 0f
    private var wheelRot = 0f
    private var fuel = 100f
    private var runCoins = 0
    private var noFuel = 0f
    private val taken = HashSet<Int>()
    private var camX = 0f
    private var camY = 0f
    private var gasDown = false
    private var brakeDown = false
    private var ignoreTouch = false
    private var lastT = 0L
    private var time = 0f
    private var msg = ""
    private var msgT = 0f

    private var overTitle = ""
    private var overDist = 0
    private var overBest = false

    // ---------- Boyama araçları ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()
    private var bgShader: LinearGradient? = null

    init {
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.setShadowLayer(3f * d, 0f, 2f * d, Color.argb(150, 0, 0, 0))
    }

    // ---------- Yardımcılar ----------
    private fun cl(v: Float, a: Float, b: Float): Float = if (v < a) a else if (v > b) b else v

    private fun rnd(i: Int): Float {
        val s = sin(i * 127.1 + 311.7) * 43758.5453
        return (s - floor(s)).toFloat()
    }

    private fun isOwned(i: Int): Boolean = (owned and (1 shl i)) != 0

    private fun bestOf(m: Int): Int = prefs.getInt("best$m", 0)

    private fun savePrefs() {
        prefs.edit()
            .putInt("coins", totalCoins)
            .putInt("owned", owned)
            .putInt("veh", if (isOwned(selVeh)) selVeh else 0)
            .putInt("map", selMap)
            .apply()
    }

    private fun flash(s: String) {
        msg = s
        msgT = 2f
    }

    private fun terr(x: Float): Float {
        val m = maps[selMap]
        val a = min(1.4f, max(0f, (x - 500f) / 3500f)) * m.amp
        val f = m.freq
        var y = 380f + a * (sin(x * 0.0030f * f) * 80f + sin(x * 0.0071f * f + 1.3f) * 38f + sin(x * 0.0173f * f + 0.5f) * 12f)
        if (x < 0f) y += x * 2.5f
        return y
    }

    private fun distance(): Int = max(0, ((carX - 150f) / 50f).toInt())

    private fun hit(k: String, x: Float, y: Float): Boolean {
        val r = rects[k] ?: return false
        return r.contains(x, y)
    }

    private fun put(k: String, cx: Float, cy: Float, bw: Float, bh: Float) {
        rects[k] = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }

    // ---------- Yerleşim ----------
    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        sw = nw.toFloat()
        sh = nh.toFloat()
        sc = min(sw / 800f, sh / 450f)
        put("start", sw * 0.5f, sh * 0.55f, 240f * d, 58f * d)
        put("garage", sw * 0.5f, sh * 0.55f + 74f * d, 240f * d, 58f * d)
        put("back", 66f * d, 36f * d, 104f * d, 44f * d)
        put("vprev", sw * 0.07f, sh * 0.45f, 52f * d, 64f * d)
        put("vnext", sw * 0.43f, sh * 0.45f, 52f * d, 64f * d)
        put("mprev", sw * 0.57f, sh * 0.45f, 52f * d, 64f * d)
        put("mnext", sw * 0.93f, sh * 0.45f, 52f * d, 64f * d)
        put("buy", sw * 0.25f, sh * 0.85f, 210f * d, 50f * d)
        put("play", sw * 0.75f, sh * 0.85f, 210f * d, 50f * d)
        put("pause", sw - 36f * d, 36f * d, 52f * d, 52f * d)
        put("resume", sw * 0.5f, sh * 0.45f, 240f * d, 54f * d)
        put("tomenu", sw * 0.5f, sh * 0.45f + 70f * d, 240f * d, 54f * d)
        put("retry", sw * 0.5f, sh * 0.58f, 240f * d, 54f * d)
        put("overmenu", sw * 0.5f, sh * 0.58f + 70f * d, 240f * d, 54f * d)
        buildBg()
        resetRun()
    }

    private fun buildBg() {
        if (sh <= 0f) return
        val m = maps[selMap]
        bgShader = LinearGradient(0f, 0f, 0f, sh, m.skyTop, m.skyBot, Shader.TileMode.CLAMP)
    }

    // ---------- Oyun akışı ----------
    private fun resetRun() {
        val v = vehicles[selVeh]
        carX = 150f
        carY = terr(150f) - 14f - v.r - 1f
        carVX = 0f
        carVY = 0f
        carA = 0f
        carW = 0f
        wheelRot = 0f
        fuel = 100f
        runCoins = 0
        noFuel = 0f
        taken.clear()
        gasDown = false
        brakeDown = false
        if (sc > 0f) {
            camX = carX - (sw / sc) * 0.3f
            camY = carY - (sh / sc) * 0.6f
        }
    }

    private fun startRun() {
        if (!isOwned(selVeh)) {
            state = ST_SELECT
            flash("Önce aracı satın al!")
            return
        }
        savePrefs()
        resetRun()
        ignoreTouch = true
        state = ST_PLAY
    }

    fun pauseGame() {
        if (state == ST_PLAY) {
            state = ST_PAUSE
            gasDown = false
            brakeDown = false
        }
    }

    fun onBack(): Boolean {
        when (state) {
            ST_PLAY -> pauseGame()
            ST_PAUSE, ST_OVER, ST_SELECT -> {
                state = ST_MENU
                resetRun()
            }
            else -> return false
        }
        return true
    }

    private fun endGame(reason: String) {
        if (state != ST_PLAY) return
        state = ST_OVER
        gasDown = false
        brakeDown = false
        val dist = distance()
        overTitle = reason
        overDist = dist
        overBest = dist > bestOf(selMap)
        if (overBest) prefs.edit().putInt("best$selMap", dist).apply()
        totalCoins += runCoins
        savePrefs()
    }

    // ---------- Dokunma ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (state == ST_PLAY) {
            val ending = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL
            if (ignoreTouch) {
                if (ending) ignoreTouch = false
                gasDown = false
                brakeDown = false
                return true
            }
            var g = false
            var b = false
            if (!ending) {
                val skip = if (a == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                val isDown = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN
                for (i in 0 until e.pointerCount) {
                    if (i == skip) continue
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (hit("pause", x, y)) {
                        if (isDown && i == e.actionIndex) {
                            pauseGame()
                            return true
                        }
                        continue
                    }
                    if (x < sw / 2f) b = true else g = true
                }
            }
            gasDown = g
            brakeDown = b
            return true
        }
        if (a == MotionEvent.ACTION_DOWN) handleTap(e.x, e.y)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        when (state) {
            ST_MENU -> {
                if (hit("start", x, y)) startRun()
                else if (hit("garage", x, y)) state = ST_SELECT
            }
            ST_SELECT -> {
                if (hit("back", x, y)) {
                    savePrefs()
                    state = ST_MENU
                    resetRun()
                } else if (hit("vprev", x, y)) {
                    selVeh = (selVeh + vehicles.size - 1) % vehicles.size
                    resetRun()
                    savePrefs()
                } else if (hit("vnext", x, y)) {
                    selVeh = (selVeh + 1) % vehicles.size
                    resetRun()
                    savePrefs()
                } else if (hit("mprev", x, y)) {
                    selMap = (selMap + maps.size - 1) % maps.size
                    buildBg()
                    resetRun()
                    savePrefs()
                } else if (hit("mnext", x, y)) {
                    selMap = (selMap + 1) % maps.size
                    buildBg()
                    resetRun()
                    savePrefs()
                } else if (hit("buy", x, y)) {
                    val v = vehicles[selVeh]
                    if (!isOwned(selVeh)) {
                        if (totalCoins >= v.price) {
                            totalCoins -= v.price
                            owned = owned or (1 shl selVeh)
                            savePrefs()
                            flash(v.name + " satın alındı!")
                        } else {
                            flash("Yeterli altın yok!")
                        }
                    }
                } else if (hit("play", x, y)) {
                    startRun()
                }
            }
            ST_PAUSE -> {
                if (hit("resume", x, y)) {
                    ignoreTouch = true
                    state = ST_PLAY
                } else if (hit("tomenu", x, y)) {
                    state = ST_MENU
                    resetRun()
                }
            }
            ST_OVER -> {
                if (hit("retry", x, y)) startRun()
                else if (hit("overmenu", x, y)) {
                    state = ST_MENU
                    resetRun()
                }
            }
        }
    }

    // ---------- Fizik ----------
    private fun step(dt: Float, gas: Boolean, brake: Boolean) {
        val v = vehicles[selVeh]
        val m = maps[selMap]
        var fx = 0f
        var fy = G
        var tq = 0f
        var grounded = 0
        val ca = cos(carA)
        val sa = sin(carA)
        for (i in 0..1) {
            val lx = if (i == 0) -v.wb else v.wb
            val ly = 14f
            val rx = lx * ca - ly * sa
            val ry = lx * sa + ly * ca
            val wx = carX + rx
            val wy = carY + ry
            val gy = terr(wx)
            val s = (terr(wx + 1f) - terr(wx - 1f)) / 2f
            val len = sqrt(1f + s * s)
            val nx = s / len
            val ny = -1f / len
            val tx = 1f / len
            val ty = s / len
            val dist = (gy - wy) / len
            val pen = v.r - dist
            if (pen > 0f) {
                grounded++
                val vx = carVX - carW * ry
                val vy = carVY + carW * rx
                val vn = vx * nx + vy * ny
                val vt = vx * tx + vy * ty
                val fn = cl(K * pen - C * vn, 0f, 9000f)
                var ft: Float
                if (brake) {
                    ft = cl((-200f - vt) * 8f, -700f, 700f)
                } else if (gas) {
                    ft = v.engine * max(0f, 1f - vt / v.vmax) - vt * 0.15f
                } else {
                    ft = -vt * (if (abs(vt) < 40f) 6f else 0.15f)
                }
                val lim = (fn * 1.5f + 150f) * m.grip
                ft = cl(ft, -lim, lim)
                val ffx = fn * nx + ft * tx
                val ffy = fn * ny + ft * ty
                fx += ffx
                fy += ffy
                tq += rx * ffy - ry * ffx
            }
        }
        carVX += fx * dt
        carVY += fy * dt
        carW += tq / v.inertia * dt
        if (grounded == 0) {
            if (gas) carW -= 9f * dt
            if (brake) carW += 9f * dt
        }
        carW *= (1f - 0.8f * dt)
        carW = cl(carW, -8f, 8f)
        val sp = sqrt(carVX * carVX + carVY * carVY)
        if (sp > 1600f) {
            carVX *= 1600f / sp
            carVY *= 1600f / sp
        }
        carX += carVX * dt
        carY += carVY * dt
        carA += carW * dt
        wheelRot += carVX / v.r * dt
    }

    private fun headHit(): Boolean {
        val ca = cos(carA)
        val sa = sin(carA)
        val ly = -24f
        val hx = carX - ly * sa
        val hy = carY + ly * ca
        return hy + 9f > terr(hx)
    }

    private fun coinX(i: Int): Float = 600f + i * 230f
    private fun fuelX(j: Int): Float = 1800f + j * 4000f

    private fun collect() {
        val i0 = floor((carX - 70f - 600f) / 230f).toInt()
        val i1 = ceil((carX + 70f - 600f) / 230f).toInt()
        for (i in max(0, i0)..i1) {
            if (rnd(i) > 0.35f && !taken.contains(i)) {
                val cx = coinX(i)
                val cy = terr(cx) - 55f
                val dx = cx - carX
                val dy = cy - carY
                if (dx * dx + dy * dy < 2500f) {
                    taken.add(i)
                    runCoins++
                }
            }
        }
        val j0 = floor((carX - 70f - 1800f) / 4000f).toInt()
        val j1 = ceil((carX + 70f - 1800f) / 4000f).toInt()
        for (j in max(0, j0)..j1) {
            val key = -(j + 1)
            if (!taken.contains(key)) {
                val fxp = fuelX(j)
                val fyp = terr(fxp) - 50f
                val dx = fxp - carX
                val dy = fyp - carY
                if (dx * dx + dy * dy < 3025f) {
                    taken.add(key)
                    fuel = min(100f, fuel + 45f)
                }
            }
        }
    }

    private fun update(dt: Float) {
        val v = vehicles[selVeh]
        val gas = gasDown && fuel > 0f
        val brake = brakeDown
        val n = max(1, (dt / (1f / 240f)).roundToInt())
        val sdt = dt / n
        for (k in 0 until n) step(sdt, gas, brake)
        fuel -= dt * (if (gas) v.fuelUse else 1f)
        if (fuel < 0f) fuel = 0f
        collect()
        if (headHit()) {
            endGame("KAFAN YERE ÇARPTI!")
            return
        }
        if (carY > terr(carX) + 400f) {
            endGame("DÜŞTÜN!")
            return
        }
        if (fuel <= 0f) {
            val sp = sqrt(carVX * carVX + carVY * carVY)
            if (sp < 15f) noFuel += dt else noFuel = 0f
            if (noFuel > 1.5f) endGame("YAKIT BİTTİ!")
        }
    }

    // ---------- Çizim: genel ----------
    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        tp.textSize = size * d
        tp.color = color
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun btn(c: Canvas, k: String, label: String, color: Int, size: Float = 20f, enabled: Boolean = true) {
        val r = rects[k] ?: return
        p.style = Paint.Style.FILL
        p.color = if (enabled) color else Color.rgb(110, 110, 110)
        c.drawRoundRect(r, 14f * d, 14f * d, p)
        txt(c, label, r.centerX(), r.centerY() + size * d * 0.35f, size, Color.WHITE)
    }

    private fun coinIcon(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.rgb(255, 196, 0)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = Color.rgb(184, 134, 11)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
    }

    private fun hills(c: Canvas, color: Int, f: Float, base: Float, amp: Float, fr: Float) {
        p.style = Paint.Style.FILL
        p.color = color
        path.reset()
        path.moveTo(0f, sh)
        var sx = 0f
        while (sx <= sw + 12f) {
            val wx = sx + camX * sc * f
            path.lineTo(sx, sh * base + sin(wx * fr) * amp * sc + sin(wx * fr * 2.3f) * 15f * sc)
            sx += 12f
        }
        path.lineTo(sw + 12f, sh)
        path.close()
        c.drawPath(path, p)
    }

    private fun drawBackground(c: Canvas) {
        val m = maps[selMap]
        p.style = Paint.Style.FILL
        p.shader = bgShader
        c.drawRect(0f, 0f, sw, sh, p)
        p.shader = null
        hills(c, m.hill1, 0.15f, 0.62f, 50f, 0.004f)
        hills(c, m.hill2, 0.35f, 0.72f, 40f, 0.007f)
    }

    // ---------- Çizim: dünya ----------
    private fun drawTerrain(c: Canvas) {
        val m = maps[selMap]
        val x0 = floor(camX / 10f) * 10f - 10f
        val x1 = camX + sw / sc + 20f
        val bottom = camY + sh / sc + 60f
        path.reset()
        path.moveTo(x0, bottom)
        var x = x0
        var lastX = x0
        while (x <= x1) {
            path.lineTo(x, terr(x))
            lastX = x
            x += 10f
        }
        path.lineTo(lastX, bottom)
        path.close()
        p.style = Paint.Style.FILL
        p.color = m.dirt
        c.drawPath(path, p)

        path.reset()
        x = x0
        var first = true
        while (x <= x1) {
            if (first) {
                path.moveTo(x, terr(x) + 3f)
                first = false
            } else {
                path.lineTo(x, terr(x) + 3f)
            }
            x += 10f
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 12f
        p.strokeJoin = Paint.Join.ROUND
        p.color = m.grass
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun drawPickups(c: Canvas) {
        val viewL = camX - 60f
        val viewR = camX + sw / sc + 60f
        val i0 = max(0, floor((viewL - 600f) / 230f).toInt())
        val i1 = ceil((viewR - 600f) / 230f).toInt()
        for (i in i0..i1) {
            if (rnd(i) > 0.35f && !taken.contains(i)) {
                val cx = coinX(i)
                val cy = terr(cx) - 55f + sin(time * 3f + i) * 4f
                val wd = abs(cos(time * 4f + i)) * 11f + 2f
                tmp.set(cx - wd, cy - 11f, cx + wd, cy + 11f)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(255, 196, 0)
                c.drawOval(tmp, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                p.color = Color.rgb(184, 134, 11)
                c.drawOval(tmp, p)
            }
        }
        val j0 = max(0, floor((viewL - 1800f) / 4000f).toInt())
        val j1 = ceil((viewR - 1800f) / 4000f).toInt()
        for (j in j0..j1) {
            if (!taken.contains(-(j + 1))) {
                val fx = fuelX(j)
                val fy = terr(fx) - 50f + sin(time * 2f + j) * 4f
                p.style = Paint.Style.FILL
                p.color = Color.rgb(211, 47, 47)
                c.drawRect(fx - 12f, fy - 16f, fx + 12f, fy + 16f, p)
                p.color = Color.rgb(183, 28, 28)
                c.drawRect(fx - 6f, fy - 22f, fx + 6f, fy - 15f, p)
                p.color = Color.WHITE
                c.drawRect(fx - 3f, fy - 8f, fx + 3f, fy + 8f, p)
                c.drawRect(fx - 8f, fy - 3f, fx + 8f, fy + 3f, p)
            }
        }
    }

    private fun drawWheel(c: Canvas, x: Float, y: Float, r: Float, rot: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(rot * 57.29578f)
        p.style = Paint.Style.FILL
        p.color = Color.rgb(34, 34, 34)
        c.drawCircle(0f, 0f, r, p)
        p.color = Color.rgb(189, 189, 189)
        c.drawCircle(0f, 0f, r * 0.55f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.rgb(85, 85, 85)
        val q = r * 0.55f
        c.drawLine(-q, 0f, q, 0f, p)
        c.drawLine(0f, -q, 0f, q, p)
        p.style = Paint.Style.FILL
        c.restore()
    }

    // Araç, (0,0) noktası gövde merkezi olacak şekilde çizilir
    private fun drawVehicle(c: Canvas, v: Vehicle, rot: Float) {
        val k = v.wb / 32f
        path.reset()
        path.moveTo(-46f * k, 6f)
        path.lineTo(-46f * k, -6f)
        path.lineTo(-20f * k, -8f)
        path.lineTo(-10f * k, -12f)
        path.lineTo(20f * k, -12f)
        path.lineTo(28f * k, -6f)
        path.lineTo(46f * k, -4f)
        path.lineTo(46f * k, 8f)
        path.close()
        p.style = Paint.Style.FILL
        p.color = v.color
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.argb(140, 0, 0, 0)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = Color.rgb(21, 101, 192)
        c.drawRect(-6f, -20f, 6f, -10f, p)
        p.color = Color.rgb(255, 204, 153)
        c.drawCircle(0f, -24f, 9f, p)
        p.color = Color.rgb(253, 216, 53)
        tmp.set(-9f, -33f, 9f, -15f)
        c.drawArc(tmp, 180f, 180f, true, p)
        drawWheel(c, -v.wb, 14f, v.r, rot)
        drawWheel(c, v.wb, 14f, v.r, rot)
    }

    private fun drawWorld(c: Canvas) {
        c.save()
        c.scale(sc, sc)
        c.translate(-camX, -camY)
        drawTerrain(c)
        drawPickups(c)
        c.save()
        c.translate(carX, carY)
        c.rotate(carA * 57.29578f)
        drawVehicle(c, vehicles[selVeh], wheelRot)
        c.restore()
        c.restore()
    }

    // ---------- Çizim: arayüz ----------
    private fun drawHud(c: Canvas) {
        txt(c, distance().toString() + " m", 16f * d, 34f * d, 26f, Color.WHITE, Paint.Align.LEFT)
        p.style = Paint.Style.FILL
        p.color = Color.argb(130, 0, 0, 0)
        tmp.set(16f * d, 46f * d, 166f * d, 62f * d)
        c.drawRoundRect(tmp, 8f * d, 8f * d, p)
        val fw = 150f * d * cl(fuel / 100f, 0f, 1f)
        p.color = if (fuel < 25f) Color.rgb(229, 57, 53) else Color.rgb(76, 175, 80)
        tmp.set(16f * d, 46f * d, 16f * d + fw, 62f * d)
        c.drawRoundRect(tmp, 8f * d, 8f * d, p)
        coinIcon(c, 25f * d, 86f * d, 9f * d)
        txt(c, runCoins.toString(), 42f * d, 94f * d, 20f, Color.WHITE, Paint.Align.LEFT)
        if (fuel < 25f && fuel > 0f && ((time * 4f).toInt() % 2 == 0)) {
            txt(c, "YAKIT AZ!", sw / 2f, 40f * d, 24f, Color.rgb(255, 82, 82))
        }
        // duraklat düğmesi
        val r = rects["pause"]
        if (r != null) {
            p.color = Color.argb(110, 0, 0, 0)
            c.drawRoundRect(r, 12f * d, 12f * d, p)
            p.color = Color.WHITE
            val cx = r.centerX()
            val cy = r.centerY()
            c.drawRect(cx - 9f * d, cy - 10f * d, cx - 3f * d, cy + 10f * d, p)
            c.drawRect(cx + 3f * d, cy - 10f * d, cx + 9f * d, cy + 10f * d, p)
        }
        pedal(c, 66f * d, sh - 66f * d, 46f * d, "FREN", brakeDown, Color.rgb(229, 57, 53))
        pedal(c, sw - 66f * d, sh - 66f * d, 46f * d, "GAZ", gasDown, Color.rgb(67, 160, 71))
    }

    private fun pedal(c: Canvas, x: Float, y: Float, r: Float, label: String, on: Boolean, col: Int) {
        p.style = Paint.Style.FILL
        p.color = if (on) Color.argb(210, Color.red(col), Color.green(col), Color.blue(col)) else Color.argb(110, 0, 0, 0)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * d
        p.color = Color.argb(220, 255, 255, 255)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
        txt(c, label, x, y + 6f * d, 18f, Color.WHITE)
    }

    private fun drawMenu(c: Canvas) {
        c.drawColor(Color.argb(90, 0, 0, 0))
        txt(c, "TEPE TIRMANIŞ", sw / 2f, sh * 0.26f, 44f, Color.WHITE)
        txt(c, "Dağları aş, altın topla, rekor kır!", sw / 2f, sh * 0.26f + 34f * d, 16f, Color.rgb(255, 235, 59))
        btn(c, "start", "BAŞLA", Color.rgb(67, 160, 71), 24f)
        btn(c, "garage", "GARAJ / HARİTA", Color.rgb(25, 118, 210), 20f)
        coinIcon(c, sw - 24f * d, 28f * d, 10f * d)
        txt(c, totalCoins.toString(), sw - 42f * d, 36f * d, 22f, Color.WHITE, Paint.Align.RIGHT)
        txt(
            c, maps[selMap].name + "  •  " + vehicles[selVeh].name + "  •  Rekor: " + bestOf(selMap) + " m",
            sw / 2f, sh - 22f * d, 16f, Color.WHITE
        )
    }

    private fun bar(c: Canvas, label: String, frac: Float, x0: Float, x1: Float, y: Float) {
        txt(c, label, x0, y + 5f * d, 13f, Color.WHITE, Paint.Align.LEFT)
        val bl = x0 + 66f * d
        p.style = Paint.Style.FILL
        p.color = Color.argb(90, 255, 255, 255)
        tmp.set(bl, y - 5f * d, x1, y + 5f * d)
        c.drawRoundRect(tmp, 5f * d, 5f * d, p)
        p.color = Color.rgb(255, 193, 7)
        tmp.set(bl, y - 5f * d, bl + (x1 - bl) * cl(frac, 0f, 1f), y + 5f * d)
        c.drawRoundRect(tmp, 5f * d, 5f * d, p)
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(120, 0, 0, 0)
        tmp.set(l, t, r, b)
        c.drawRoundRect(tmp, 18f * d, 18f * d, p)
    }

    private fun drawSelect(c: Canvas) {
        c.drawColor(Color.argb(60, 0, 0, 0))
        txt(c, "GARAJ", sw / 2f, sh * 0.12f, 28f, Color.WHITE)
        btn(c, "back", "< GERİ", Color.argb(170, 0, 0, 0), 16f)
        coinIcon(c, sw - 24f * d, 28f * d, 10f * d)
        txt(c, totalCoins.toString(), sw - 42f * d, 36f * d, 22f, Color.WHITE, Paint.Align.RIGHT)
        if (msgT > 0f) txt(c, msg, sw / 2f, sh * 0.18f, 18f, Color.rgb(255, 235, 59))

        // Araç paneli
        val vl = sw * 0.12f
        val vr = sw * 0.38f
        panel(c, vl, sh * 0.2f, vr, sh * 0.76f)
        val v = vehicles[selVeh]
        txt(c, v.name, (vl + vr) / 2f, sh * 0.28f, 24f, Color.WHITE)
        c.save()
        c.translate((vl + vr) / 2f, sh * 0.43f)
        val ps = sh / 300f
        c.scale(ps, ps)
        drawVehicle(c, v, time * 2f)
        c.restore()
        txt(c, v.desc, (vl + vr) / 2f, sh * 0.58f, 14f, Color.rgb(255, 235, 59))
        bar(c, "GÜÇ", v.engine / 700f, vl + 14f * d, vr - 14f * d, sh * 0.64f)
        bar(c, "HIZ", v.vmax / 900f, vl + 14f * d, vr - 14f * d, sh * 0.69f)
        bar(c, "YAKIT", (6f - v.fuelUse) / 3f, vl + 14f * d, vr - 14f * d, sh * 0.74f)
        btn(c, "vprev", "<", Color.argb(170, 0, 0, 0), 28f)
        btn(c, "vnext", ">", Color.argb(170, 0, 0, 0), 28f)

        // Harita paneli
        val ml = sw * 0.62f
        val mr = sw * 0.88f
        panel(c, ml, sh * 0.2f, mr, sh * 0.76f)
        val m = maps[selMap]
        txt(c, m.name, (ml + mr) / 2f, sh * 0.28f, 24f, Color.WHITE)
        val pl = ml + 14f * d
        val pr = mr - 14f * d
        val pt = sh * 0.32f
        val pb = sh * 0.52f
        p.style = Paint.Style.FILL
        p.color = m.skyBot
        tmp.set(pl, pt, pr, pb)
        c.drawRoundRect(tmp, 12f * d, 12f * d, p)
        c.save()
        c.clipRect(tmp)
        path.reset()
        path.moveTo(pl, pb)
        var xx = pl
        while (xx <= pr) {
            val u = (xx - pl) / (pr - pl)
            path.lineTo(xx, pb - (pb - pt) * (0.30f + 0.14f * sin(u * 9.4f)))
            xx += 6f
        }
        path.lineTo(pr, pb)
        path.close()
        p.color = m.grass
        c.drawPath(path, p)
        c.restore()
        txt(c, m.desc, (ml + mr) / 2f, sh * 0.59f, 14f, Color.rgb(255, 235, 59))
        txt(c, "En iyi: " + bestOf(selMap) + " m", (ml + mr) / 2f, sh * 0.66f, 16f, Color.WHITE)
        btn(c, "mprev", "<", Color.argb(170, 0, 0, 0), 28f)
        btn(c, "mnext", ">", Color.argb(170, 0, 0, 0), 28f)

        // Alt düğmeler
        if (isOwned(selVeh)) {
            btn(c, "buy", "SEÇİLİ", Color.rgb(46, 125, 50), 18f)
        } else {
            val ok = totalCoins >= v.price
            btn(c, "buy", "SATIN AL  " + v.price, Color.rgb(245, 124, 0), 18f, ok)
        }
        btn(c, "play", "OYNA", Color.rgb(67, 160, 71), 22f, isOwned(selVeh))
    }

    private fun drawPause(c: Canvas) {
        c.drawColor(Color.argb(150, 0, 0, 0))
        txt(c, "DURAKLATILDI", sw / 2f, sh * 0.28f, 34f, Color.WHITE)
        btn(c, "resume", "DEVAM", Color.rgb(67, 160, 71), 22f)
        btn(c, "tomenu", "ANA MENÜ", Color.rgb(25, 118, 210), 20f)
    }

    private fun drawOver(c: Canvas) {
        c.drawColor(Color.argb(160, 0, 0, 0))
        txt(c, overTitle, sw / 2f, sh * 0.2f, 32f, Color.rgb(255, 82, 82))
        txt(c, "Mesafe: " + overDist + " m", sw / 2f, sh * 0.31f, 24f, Color.WHITE)
        txt(c, "Toplanan altın: " + runCoins, sw / 2f, sh * 0.39f, 20f, Color.rgb(255, 235, 59))
        if (overBest) {
            txt(c, "YENİ REKOR!", sw / 2f, sh * 0.47f, 22f, Color.rgb(129, 199, 132))
        } else {
            txt(c, "Rekor: " + bestOf(selMap) + " m", sw / 2f, sh * 0.47f, 18f, Color.WHITE)
        }
        btn(c, "retry", "TEKRAR OYNA", Color.rgb(67, 160, 71), 22f)
        btn(c, "overmenu", "ANA MENÜ", Color.rgb(25, 118, 210), 20f)
    }

    // ---------- Ana döngü ----------
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastT == 0L) 0.016f else (now - lastT) / 1e9f
        lastT = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0.016f
        time += dt
        if (msgT > 0f) msgT -= dt

        if (state == ST_PLAY) update(dt)

        if (sc > 0f) {
            val tx = carX - (sw / sc) * 0.3f
            val ty = carY - (sh / sc) * 0.6f
            val f = min(1f, dt * 6f)
            camX += (tx - camX) * f
            camY += (ty - camY) * f
        }

        drawBackground(c)
        if (state != ST_SELECT) drawWorld(c)

        when (state) {
            ST_MENU -> drawMenu(c)
            ST_SELECT -> drawSelect(c)
            ST_PLAY -> drawHud(c)
            ST_PAUSE -> {
                drawHud(c)
                drawPause(c)
            }
            ST_OVER -> drawOver(c)
        }
        postInvalidateOnAnimation()
    }
}
