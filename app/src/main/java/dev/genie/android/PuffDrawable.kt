package dev.genie.android

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class PuffState { Idle, Listening, Thinking, Searching, Speaking, Done, Error }

// Puff, Genie's character: five soft puffs, with eyes painted on a sphere around the head so they
// curve with it and the far eye narrows when Puff looks to one side. Shapes and timings match the
// Puff Motion mockup. Puff opens with the summon animation, then plays whichever state is set.
class PuffDrawable(fill: Int = Color.rgb(242, 242, 244), eye: Int = Color.BLACK) : Drawable() {
    /** The state to play. A change waits until the summon animation has finished. */
    var state = PuffState.Listening
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Microphone level from 0 to 1, used while listening. */
    var level = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eye }
    private val eyePath = Path()
    private val xs = FloatArray(EYE_POINTS)
    private val ys = FloatArray(EYE_POINTS)
    private val random = Random(7)
    private val particles = List(18) { Particle() }
    private val timers = FloatArray(2)

    // Puff starts summoning: every puff and both eyes closed.
    private var playing: PuffState? = null
    private var start = SystemClock.uptimeMillis()
    private var stateStart = start
    private var last = start
    private val pose = Pose().apply { bs.fill(0f); open = 0f; gy = 10f }
    private var heard = 0f

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val still = !ValueAnimator.areAnimatorsEnabled()
        val dt = ((now - last) / 1000f).coerceIn(0f, 0.05f)
        last = now
        if (playing != state && (playing != null || still || now - stateStart > SUMMON_MS)) {
            playing = state
            stateStart = now
            timers.fill(0f)
        }
        val t = if (still) 0f else (now - start) / 1000f
        val q = if (still) 10f else (now - stateStart) / 1000f
        heard += (level - heard) * min(1f, dt * 14f)
        val current = playing
        val target = if (current == null) summon(q) else target(current, q, t)
        val f = if (still) 1f else 1f - exp(-dt * smoothing(current))
        pose.approach(target, f)
        if (!still) {
            current?.let { emit(it, q, dt) }
            particles.forEach { it.update(dt) }
        }
        render(canvas, t)
        // Keep animating while Puff is on screen; the host view stops drawing it once hidden.
        if (!still) invalidateSelf()
    }

    private fun render(canvas: Canvas, t: Float) {
        // The bounds show the viewBox from the Paper design; particles may drift past them.
        val b = bounds
        val unit = b.width() / VIEW_WIDTH
        canvas.save()
        canvas.translate(b.left.toFloat(), b.exactCenterY() - VIEW_HEIGHT * unit / 2)
        canvas.scale(unit, unit)
        canvas.translate(-VIEW_LEFT, -VIEW_TOP)
        for (p in particles) if (p.alive) {
            fillPaint.alpha = (255 * p.opacity).toInt()
            canvas.drawCircle(p.x, p.y, p.r, fillPaint)
        }
        val alpha = (255 * pose.alpha.coerceIn(0f, 1f)).toInt()
        canvas.translate(pose.gx, pose.gy)
        canvas.translate(PIVOT_X, PIVOT_Y)
        canvas.rotate(pose.rot)
        canvas.scale(pose.sx * pose.sc, pose.sy * pose.sc)
        canvas.translate(-PIVOT_X, -PIVOT_Y)
        fillPaint.alpha = alpha
        for (i in BODY.indices) {
            val (x, y, r) = BODY[i]
            val radius = r * max(0f, pose.bs[i]) * (1 + pose.amp * sin(t * pose.rate + i * 1.1f))
            if (radius > 0) canvas.drawCircle(x, y, radius, fillPaint)
        }
        val eyes = (alpha * ((pose.bs[1] - 0.6f) / 0.3f).coerceIn(0f, 1f)).toInt()
        if (eyes > 0) {
            eyePaint.alpha = eyes
            val open = pose.open.coerceIn(0f, 1f) * if (pose.blink > 0.5f) blinkAt(t + 0.7f) else 1f
            for (k in 0..1) {
                capsulePath(k, open)
                canvas.drawPath(eyePath, eyePaint)
            }
        }
        canvas.restore()
    }

    // ---- Eyes ----
    // Each eye is a capsule of even thickness placed by latitude and longitude on the sphere. The
    // sphere is tipped toward the viewer, then flattened onto the screen.

    private fun project(lon: Float, lat: Float, i: Int) {
        val x = cos(lat) * sin(lon)
        val y = sin(lat)
        val z = cos(lat) * cos(lon)
        xs[i] = SPHERE_X + SPHERE_R * x
        ys[i] = SPHERE_Y - SPHERE_R * (y * cos(PITCH) - z * sin(PITCH))
    }

    private fun eyeLon(k: Int) = (pose.g.coerceIn(-1.2f, 1.2f) * LON_RANGE + if (k == 1) SPREAD else -SPREAD) * DEG

    private fun eyeLat() = (EYE_LAT + pose.gv.coerceIn(-1f, 1f) * LAT_RANGE) * DEG

    // open squashes the eye along its length on the sphere, which reads as a lid closing.
    private fun capsulePath(k: Int, open: Float) {
        val lon0 = eyeLon(k)
        val lat0 = eyeLat()
        val half = HALF * pose.size
        val rad = RAD * pose.size
        val o = max(0.04f, open)
        var n = 0
        fun add(s: Float, t: Float) {
            val lat = lat0 + s * o
            project(lon0 + t / cos(lat), lat, n++)
        }
        for (i in 0..CAP) { val a = PI.toFloat() * i / CAP; add(half + rad * sin(a), rad * cos(a)) }
        for (i in 1..SIDE) add(half - 2 * half * i / (SIDE + 1), -rad)
        for (i in 0..CAP) { val a = PI.toFloat() * i / CAP; add(-half - rad * sin(a), -rad * cos(a)) }
        for (i in 1..SIDE) add(-half + 2 * half * i / (SIDE + 1), rad)
        // Closed Catmull-Rom spline through the points.
        eyePath.rewind()
        eyePath.moveTo(xs[0], ys[0])
        for (i in 0 until n) {
            val p0 = (i - 1 + n) % n
            val p2 = (i + 1) % n
            val p3 = (i + 2) % n
            eyePath.cubicTo(
                xs[i] + (xs[p2] - xs[p0]) / 6, ys[i] + (ys[p2] - ys[p0]) / 6,
                xs[p2] - (xs[p3] - xs[i]) / 6, ys[p2] - (ys[p3] - ys[i]) / 6,
                xs[p2], ys[p2],
            )
        }
        eyePath.close()
    }

    // ---- States ----

    private fun summon(q: Float) = Pose().apply {
        SUMMON_STARTS.forEachIndexed { i, ti -> bs[i] = if (q < ti) 0f else easeOutBack(((q - ti) / 0.38f).coerceIn(0f, 1f)) }
        gy = lerp(10f, 0f, easeInOut(((q - 0.3f) / 0.9f).coerceIn(0f, 1f)))
        open = ((q - 1.35f) / 0.12f).coerceIn(0f, 1f)
        blink = 0f
        g = 0.1f
        gv = 0.25f
    }

    private fun target(state: PuffState, q: Float, t: Float) = Pose().apply {
        when (state) {
            PuffState.Idle -> {
                g = IDLE_GLANCES[floor(t / 3.6f).toInt() % IDLE_GLANCES.size]
                gy = 1.2f * sin(t * 2 * PI.toFloat() / 3.2f)
                gv = 0.1f
                amp = 0.025f
            }
            PuffState.Listening -> {
                sc = 1 + 0.035f * heard
                gy = -1.5f
                rot = -2f
                // Small glances, so Puff stays lively while you're quiet.
                g = 0.3f + 0.1f * sin(t * 0.8f) + 0.25f * sin(t * 0.37f)
                gv = 0.3f
                size = 0.9f
                amp = 0.02f + 0.06f * heard
                rate = 7f
            }
            PuffState.Thinking -> {
                g = 0.62f + 0.18f * sin(t * 0.9f)
                gv = 0.8f
                open = 0.82f
                rot = 3 * sin(t * 1.1f)
                gy = 1.4f * sin(t * 1.8f)
                amp = 0.03f
                rate = 1.4f
            }
            PuffState.Searching -> {
                g = SCAN[floor(t / 0.42f).toInt() % SCAN.size]
                gv = 0.05f
                sx = 1.06f
                sy = 0.95f
                rot = -5f
                gx = 1.6f * sin(t * 2.6f)
                gy = 1.2f * sin(t * 5.2f)
                amp = 0.045f
                rate = 8f
            }
            PuffState.Speaking -> {
                val syllable = max(0f, sin(t * 10.5f)) * (0.45f + 0.55f * max(0f, sin(t * 2.2f + 0.4f)))
                gy = -3.2f * syllable
                sy = 1 + 0.045f * syllable
                sx = 1 - 0.03f * syllable
                g = 0.15f
                gv = 0.12f
                open = 0.92f
                amp = 0.03f
                rate = 3f
            }
            PuffState.Done -> {
                // A big hop, then a small bounce every few seconds while Puff looks around, blinking as usual.
                val c = if (q < 1.8f) q else (q - 1.8f) % 3.2f
                val height = if (q < 1.8f) 9f else 4f
                if (c < 0.42f) {
                    val h = sin(PI.toFloat() * c / 0.42f)
                    gy = -height * h; sy = 1 + 0.05f * h; sx = 1 - 0.04f * h
                } else if (c < 0.6f) {
                    val s = sin(PI.toFloat() * (c - 0.42f) / 0.18f)
                    sy = 1 - 0.08f * s; sx = 1 + 0.07f * s; gy = 1.5f * s
                }
                g = if (q < 1.8f) 0.1f else IDLE_GLANCES[floor(t / 2.8f).toInt() % IDLE_GLANCES.size]
                gv = 0.2f
            }
            PuffState.Error -> {
                // A shake that settles, then a glance down and away, peeking back now and then.
                val w = sin(q * 26) * exp(-q * 3)
                rot = 10 * w
                gx = 1.5f * w
                size = 0.72f
                g = lerp(0f, -0.4f, ((q - 0.5f) / 0.5f).coerceIn(0f, 1f)) + if (q > 2f) 0.3f * max(0f, sin(t * 0.9f)) else 0f
                gv = -0.25f
                open = 0.9f
                amp = 0.015f
            }
        }
        // Every state floats, sways and breathes a little, so Puff never stands still.
        gy += 1.1f * sin(t * 2 * PI.toFloat() / 3.2f)
        rot += 1.5f * sin(t * 0.7f + 0.4f)
        amp = max(amp, 0.025f)
    }

    private fun smoothing(state: PuffState?) = when (state) {
        null -> 60f
        PuffState.Idle -> 6f
        PuffState.Listening -> 12f
        PuffState.Thinking -> 5f
        PuffState.Searching -> 14f
        PuffState.Speaking -> 22f
        PuffState.Done -> 40f
        PuffState.Error -> 30f
    }

    private fun emit(state: PuffState, q: Float, dt: Float) {
        when (state) {
            // Thought puffs rise from beside the head.
            PuffState.Thinking -> if (every(0, 0.75f, dt)) spawn(48 + random.nextFloat() * 3, 12f, 1.2f, 3.6f, 2.5f, -7f, 1.9f, Kind.Bubble)
            // A trail of puffs drifts off behind Puff as it leans in.
            PuffState.Searching -> if (every(1, 0.16f, dt)) spawn(9 + random.nextFloat() * 2, 34 + (random.nextFloat() - 0.5f) * 12,
                2.4f + random.nextFloat() * 1.6f, 0.5f, -16 - random.nextFloat() * 6, -2f, 0.9f, Kind.Trail)
            PuffState.Done -> if (q >= 0.2f && q - dt < 0.2f) {
                spawn(10f, 14f, 0.4f, 2.2f, -4f, -6f, 0.6f, Kind.Spark)
                spawn(58f, 12f, 0.4f, 2.2f, 4f, -6f, 0.6f, Kind.Spark)
            }
            else -> Unit
        }
    }

    private fun every(timer: Int, seconds: Float, dt: Float): Boolean {
        timers[timer] += dt
        if (timers[timer] < seconds) return false
        timers[timer] -= seconds
        return true
    }

    private fun spawn(x: Float, y: Float, r0: Float, r1: Float, vx: Float, vy: Float, life: Float, kind: Kind) {
        particles.firstOrNull { !it.alive }?.reset(x, y, r0, r1, vx, vy, life, kind)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private class Pose {
        var gx = 0f; var gy = 0f; var rot = 0f; var sx = 1f; var sy = 1f; var sc = 1f; var alpha = 1f
        var g = 0f; var gv = 0f; var size = EYE_SIZE; var open = 1f; var amp = 0.02f; var rate = 2f; var blink = 1f
        val bs = FloatArray(5) { 1f }

        fun approach(to: Pose, f: Float) {
            gx = lerp(gx, to.gx, f); gy = lerp(gy, to.gy, f); rot = lerp(rot, to.rot, f)
            sx = lerp(sx, to.sx, f); sy = lerp(sy, to.sy, f); sc = lerp(sc, to.sc, f); alpha = lerp(alpha, to.alpha, f)
            g = lerp(g, to.g, f); gv = lerp(gv, to.gv, f); size = lerp(size, to.size, f); open = lerp(open, to.open, f)
            amp = lerp(amp, to.amp, f); rate = lerp(rate, to.rate, f); blink = lerp(blink, to.blink, f)
            for (i in bs.indices) bs[i] = lerp(bs[i], to.bs[i], f)
        }
    }

    private enum class Kind { Bubble, Trail, Spark }

    private class Particle {
        var alive = false
        var x = 0f; var y = 0f; var r = 0f; var opacity = 0f
        private var r0 = 0f; private var r1 = 0f; private var vx = 0f; private var vy = 0f
        private var life = 1f; private var age = 0f; private var kind = Kind.Trail

        fun reset(x: Float, y: Float, r0: Float, r1: Float, vx: Float, vy: Float, life: Float, kind: Kind) {
            this.x = x; this.y = y; this.r0 = r0; this.r1 = r1; this.vx = vx; this.vy = vy
            this.life = life; this.kind = kind; age = 0f; alive = true
        }

        fun update(dt: Float) {
            if (!alive) return
            age += dt
            val u = age / life
            if (u >= 1) { alive = false; return }
            x += vx * dt
            y += vy * dt
            r = when (kind) {
                Kind.Bubble -> lerp(r0, r1, easeOutBack((u * 2.2f).coerceIn(0f, 1f)))
                Kind.Spark -> r1 * sin(PI.toFloat() * u)
                Kind.Trail -> lerp(r0, r1, u)
            }.coerceAtLeast(0f)
            opacity = if (kind == Kind.Bubble) (if (u > 0.7f) 1 - (u - 0.7f) / 0.3f else 1f) else 1 - u * u
        }
    }

    private companion object {
        // Puff's body in a 64-unit box: x, y and radius. Index 1 is the head.
        val BODY = listOf(Triple(20f, 35f, 13f), Triple(34f, 26f, 16f), Triple(47f, 34f, 13f), Triple(28f, 45f, 11f), Triple(43f, 45f, 11f))
        const val PIVOT_X = 34f
        const val PIVOT_Y = 38f
        // The area the bounds show, matching the Paper design's viewBox. It centres the cloud.
        const val VIEW_LEFT = 1.86f
        const val VIEW_TOP = 2f
        const val VIEW_WIDTH = 64f
        const val VIEW_HEIGHT = 62f

        // The eyes sit on a sphere a little bigger than the head, tipped 16° toward the viewer.
        const val SPHERE_X = 34f
        const val SPHERE_Y = 28.5f
        const val SPHERE_R = 22f
        const val DEG = (PI / 180).toFloat()
        val PITCH = 16 * DEG
        const val HALF = 0.17f
        const val RAD = 0.135f
        const val SPREAD = 14f
        const val LON_RANGE = 38f
        const val EYE_LAT = 20f
        // Eyes are a little smaller than the head suggests; they shrink further when Puff didn't catch you.
        const val EYE_SIZE = 0.82f
        const val LAT_RANGE = 16f
        const val CAP = 10
        const val SIDE = 3
        const val EYE_POINTS = 2 * (CAP + 1) + 2 * SIDE

        // Base puffs, then sides, then the head; the eyes open at 1.35 s.
        val SUMMON_STARTS = floatArrayOf(0.6f, 0.85f, 0.7f, 0.35f, 0.45f)
        const val SUMMON_MS = 1600L
        val IDLE_GLANCES = floatArrayOf(0.25f, 0.6f, -0.35f, 0.1f)
        val SCAN = floatArrayOf(1f, 0.5f, 0.95f, 0.7f, 1f, 0.4f)

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun easeOutBack(t: Float): Float {
            val c1 = 1.70158f
            return 1 + (c1 + 1) * (t - 1).pow(3) + c1 * (t - 1).pow(2)
        }

        fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2

        // Every 4.6 s, with every third blink a double blink.
        fun blinkAt(t: Float): Float {
            val period = 4.6f
            val ph = ((t % period) + period) % period
            fun one(p: Float) = if (p in 0f..0.16f) 1 - 0.95f * sin(PI.toFloat() * p / 0.16f) else 1f
            return if (floor(t / period).toInt() % 3 == 2) min(one(ph), one(ph - 0.26f)) else one(ph)
        }
    }
}
