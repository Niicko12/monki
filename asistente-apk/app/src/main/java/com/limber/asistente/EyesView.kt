package com.limber.asistente

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.random.Random

/** Expresiones de la cara. Cada una cambia la forma de los ojos. */
enum class Expression { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, THINKING, LISTENING, SPEAKING, SLEEPY, LOVE, WINK, CONFUSED,
    LAUGHING, CRYING, EMBARRASSED, SHOCKED, RELAXED, KISSY, CONFIDENT, SILLY }

fun Emotion.toExpression() = when (this) {
    Emotion.FELIZ, Emotion.DELICIOSO -> Expression.HAPPY
    Emotion.RISA -> Expression.LAUGHING
    Emotion.COOL, Emotion.SEGURO -> Expression.CONFIDENT
    Emotion.LLORANDO -> Expression.CRYING
    Emotion.APENADO -> Expression.EMBARRASSED
    Emotion.IMPACTADO -> Expression.SHOCKED
    Emotion.RELAJADO -> Expression.RELAXED
    Emotion.BESO -> Expression.KISSY
    Emotion.TONTO -> Expression.SILLY
    Emotion.GUIÑO -> Expression.WINK
    Emotion.AMOR -> Expression.LOVE
    Emotion.SORPRESA -> Expression.SURPRISED
    Emotion.PENSANDO -> Expression.THINKING
    Emotion.TRISTE -> Expression.SAD
    Emotion.ENOJO -> Expression.ANGRY
    Emotion.CONFUNDIDO -> Expression.CONFUSED
    Emotion.SUEÑO -> Expression.SLEEPY
    Emotion.NEUTRAL -> Expression.NEUTRAL
}

/**
 * Cara animada al estilo de la pantalla de xiaozhi: dos ojos que parpadean solos, miran a los lados
 * y cambian de forma con la expresión, y una boca que se mueve mientras suena la voz.
 */
class EyesView(context: Context) : View(context) {

    var expression = Expression.NEUTRAL
        set(v) {
            if (field != v) { pop.start(); if (v != Expression.SLEEPY) blinkAnim.start() }   // cambio con rebote y parpadeo
            field = v; invalidate()
        }

    /** El color cambia con un desvanecido corto en vez de saltar. */
    var eyeColor = Color.parseColor("#6CC79C")
        set(v) {
            if (field == v) return
            val from = paint.color
            field = v
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, v).apply {
                duration = 450
                addUpdateListener { paint.color = it.animatedValue as Int; glow.color = paint.color; invalidate() }
                start()
            }
        }
    private var colorAnim: ValueAnimator? = null

    // Animaciones de "vida": respiración, brillo, rebote al cambiar de expresión y detalles por estado
    private var t = 0f                     // segundos desde que se mostró
    private var popScale = 1f
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6CC79C") }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val pop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 380; interpolator = android.view.animation.OvershootInterpolator(2.5f)
        addUpdateListener { popScale = 0.86f + 0.14f * (it.animatedValue as Float); invalidate() }
    }
    private var lastFrame = 0L
    private val life = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFrame < 33) return@addUpdateListener   // ~30 cuadros por segundo: suave y sin gastar batería
            lastFrame = now
            t = now / 1000f
            invalidate()
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6CC79C"); style = Paint.Style.FILL }
    private val lid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val path = Path()

    private var blink = 0f       // 0 abierto, 1 cerrado
    private var lookX = 0f       // -1 izquierda, 1 derecha
    private var lookY = 0f
    private var bounce = 0f      // para hablar / escuchar

    /** true mientras suena música alegre: la cara se mece al ritmo. */
    var dancing = false
        set(v) { field = v; invalidate() }

    /** true mientras suena la voz: la boca se abre y se cierra. */
    var talking = false
        set(v) {
            if (field == v) return
            field = v
            if (v) { mouthLevelAt = 0L; talkMouth.start() } else { talkMouth.cancel(); mouthOpen = 0f; invalidate() }
        }

    /** Volumen real de la voz (0..1), si se puede medir; si no llega, la boca se mueve sola. */
    fun setMouthLevel(level: Float) {
        mouthLevel = level.coerceIn(0f, 1f); mouthLevelAt = System.currentTimeMillis()
    }

    private var mouthOpen = 0f   // 0 cerrada, 1 muy abierta
    private var mouthLevel = 0f
    private var mouthLevelAt = 0L
    private var mouthTarget = 0f
    private var nextSyllable = 0L
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val talkMouth = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val now = System.currentTimeMillis()
            if (now - mouthLevelAt < 300) mouthTarget = mouthLevel
            else if (now >= nextSyllable) {
                // Sin medición: sílabas al azar, con alguna pausa cerrada
                mouthTarget = if (Random.nextFloat() < 0.15f) 0.05f else 0.3f + Random.nextFloat() * 0.7f
                nextSyllable = now + 70 + Random.nextLong(110)
            }
            mouthOpen += (mouthTarget - mouthOpen) * 0.45f
            invalidate()
        }
    }

    private val blinkAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 180
        addUpdateListener { blink = it.animatedValue as Float; invalidate() }
    }
    private val idle = object : Runnable {
        override fun run() {
            if (expression != Expression.SLEEPY && Random.nextFloat() < 0.7f) {
                blinkAnim.start()
                // A veces parpadeo doble, como las personas
                if (Random.nextFloat() < 0.2f) postDelayed({ if (!blinkAnim.isRunning) blinkAnim.start() }, 260)
            }
            if (Random.nextFloat() < 0.4f) glance(Random.nextFloat() * 2 - 1, Random.nextFloat() * 0.6f - 0.3f)
            postDelayed(this, 1800L + Random.nextLong(2200))
        }
    }
    private val talk = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { bounce = it.animatedValue as Float; if (isAnimated()) invalidate() }
    }

    private fun isAnimated() = dancing || expression == Expression.SPEAKING || expression == Expression.LISTENING ||
        expression == Expression.LAUGHING || expression == Expression.CRYING

    private fun glance(x: Float, y: Float) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            val sx = lookX; val sy = lookY
            duration = 220; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { val t = it.animatedValue as Float; lookX = sx + (x - sx) * t; lookY = sy + (y - sy) * t; invalidate() }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(idle, 1200)
        talk.start()
        life.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idle); talk.cancel(); blinkAnim.cancel(); talkMouth.cancel(); life.cancel(); pop.cancel(); colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        lid.color = (background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        // Ojos arriba y boca abajo
        // Respiración: los ojos se inflan un poco y la cara flota (más lento si duerme)
        val breath = kotlin.math.sin(t * 2f * Math.PI.toFloat() / if (expression == Expression.SLEEPY) 5f else 3.5f)
        val eyeW = minOf(w * 0.3f, h * 0.45f) * popScale
        var eyeH = minOf(h * 0.48f, minOf(w * 0.3f, h * 0.45f) * 1.15f) * popScale * (1f + breath * 0.025f)
        val gap = w * 0.12f
        val cy = h * 0.4f + lookY * h * 0.06f + breath * h * 0.012f
        val shift = lookX * w * 0.06f
        when (expression) {
            Expression.SURPRISED -> eyeH *= 1.15f
            Expression.SHOCKED -> eyeH *= 1.25f
            Expression.RELAXED -> eyeH *= 0.45f
            Expression.LISTENING -> eyeH *= 1.05f + bounce * 0.08f
            Expression.SPEAKING -> eyeH *= 0.9f + bounce * 0.12f
            Expression.SLEEPY -> eyeH *= 0.25f
            else -> {}
        }
        val jump = (if (expression == Expression.LAUGHING) -bounce * h * 0.05f else 0f) +
            (if (dancing) (bounce - 0.5f) * h * 0.06f else 0f)
        val left = w / 2 - gap / 2 - eyeW + shift
        val right = w / 2 + gap / 2 + shift
        drawEye(c, left, cy + jump, eyeW, eyeH, isLeft = true)
        drawEye(c, right, cy + jump, eyeW, if (expression == Expression.SILLY) eyeH * 0.55f else eyeH, isLeft = false)
        drawShine(c, left, cy + jump, eyeW, eyeH)
        drawShine(c, right, cy + jump, eyeW, if (expression == Expression.SILLY) eyeH * 0.55f else eyeH)
        drawStateDetails(c, w, h, right + eyeW, cy + jump - eyeH / 2)
        // Detalles: lágrima, sonrojo, beso
        val extra = Paint(Paint.ANTI_ALIAS_FLAG)
        when (expression) {
            Expression.CRYING -> {
                extra.color = Color.parseColor("#6FB7FF")
                val ty = cy + eyeH * 0.45f + bounce * h * 0.12f
                c.drawCircle(left + eyeW * 0.5f, ty, eyeW * 0.09f, extra)
                c.drawCircle(right + eyeW * 0.5f, ty, eyeW * 0.09f, extra)
            }
            Expression.EMBARRASSED -> {
                extra.color = Color.parseColor("#66F08A9C")
                c.drawOval(RectF(left - eyeW * 0.1f, cy + eyeH * 0.42f, left + eyeW * 0.55f, cy + eyeH * 0.62f), extra)
                c.drawOval(RectF(right + eyeW * 0.45f, cy + eyeH * 0.42f, right + eyeW * 1.1f, cy + eyeH * 0.62f), extra)
            }
            Expression.KISSY -> drawHeart(c, w / 2 + eyeW * 0.6f, h * 0.78f, eyeW * 0.14f)
            else -> {}
        }
        drawMouth(c, w / 2 + shift, h * 0.81f + jump, eyeW)
    }

    private fun drawMouth(c: Canvas, cx: Float, cy: Float, eyeW: Float) {
        val mw = eyeW * 0.75f
        val sw = eyeW * 0.09f
        mouthPaint.color = eyeColor; mouthPaint.strokeWidth = sw
        val r = height * 0.09f
        if (talking) {
            // Boca abierta que sigue la voz
            val mh = sw + mouthOpen * r * 1.6f
            val ww = mw * (0.75f + mouthOpen * 0.25f)
            rect.set(cx - ww / 2, cy - mh / 2, cx + ww / 2, cy + mh / 2)
            c.drawRoundRect(rect, minOf(ww, mh) / 2, minOf(ww, mh) / 2, paint)
            return
        }
        path.reset()
        when (expression) {
            Expression.HAPPY, Expression.CONFIDENT, Expression.RELAXED, Expression.LOVE, Expression.WINK, Expression.EMBARRASSED -> {
                path.moveTo(cx - mw / 2, cy - r * 0.3f); path.quadTo(cx, cy + r * 0.9f, cx + mw / 2, cy - r * 0.3f)
                c.drawPath(path, mouthPaint)
            }
            Expression.LAUGHING, Expression.SILLY -> {
                // Sonrisa abierta (D acostada); la de "tonto" saca la lengua
                val open = if (expression == Expression.LAUGHING) 0.8f + bounce * 0.4f else 1f
                rect.set(cx - mw / 2, cy - r * 0.9f, cx + mw / 2, cy + r * 0.9f * open)
                c.drawArc(rect, 0f, 180f, true, paint)
                if (expression == Expression.SILLY) {
                    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F08A9C") }
                    c.drawCircle(cx + mw * 0.12f, cy + r * 0.75f, mw * 0.16f, t)
                }
            }
            Expression.SAD, Expression.CRYING -> {
                path.moveTo(cx - mw / 2, cy + r * 0.4f); path.quadTo(cx, cy - r * 0.7f, cx + mw / 2, cy + r * 0.4f)
                c.drawPath(path, mouthPaint)
            }
            Expression.SURPRISED, Expression.SHOCKED -> {
                val s = if (expression == Expression.SHOCKED) 1.3f else 1f
                rect.set(cx - mw * 0.22f * s, cy - r * 0.7f * s, cx + mw * 0.22f * s, cy + r * 0.7f * s)
                c.drawOval(rect, paint)
            }
            Expression.KISSY, Expression.SLEEPY -> c.drawCircle(cx, cy, mw * 0.11f, paint)
            Expression.ANGRY -> {
                path.moveTo(cx - mw / 2, cy + r * 0.2f); path.quadTo(cx, cy - r * 0.25f, cx + mw / 2, cy + r * 0.2f)
                c.drawPath(path, mouthPaint)
            }
            Expression.CONFUSED, Expression.THINKING -> {
                // Boca chueca, hacia un lado
                path.moveTo(cx - mw * 0.35f, cy + r * 0.15f); path.lineTo(cx + mw * 0.3f, cy - r * 0.2f)
                c.drawPath(path, mouthPaint)
            }
            else -> c.drawLine(cx - mw * 0.35f, cy, cx + mw * 0.35f, cy, mouthPaint)
        }
    }

    private fun drawEye(c: Canvas, x: Float, cy: Float, w: Float, h0: Float, isLeft: Boolean) {
        val closed = if ((expression == Expression.WINK || expression == Expression.KISSY) && !isLeft) 0.85f else blink
        val h = (h0 * (1f - closed)).coerceAtLeast(h0 * 0.08f)
        val r = w * 0.32f
        when (expression) {
            Expression.HAPPY, Expression.LAUGHING, Expression.EMBARRASSED, Expression.RELAXED -> {
                // Ojo sonriente (^‿^): el ojo entero y un párpado curvo que tapa la parte de abajo
                rect.set(x, cy - h0 / 2, x + w, cy + h0 / 2)
                c.drawRoundRect(rect, r, r, paint)
                val cover = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lidColor() }
                c.drawOval(RectF(x - w * 0.25f, cy - h0 * 0.05f, x + w * 1.25f, cy + h0 * 1.1f), cover)
            }
            Expression.LOVE -> drawHeart(c, x + w / 2, cy, w * 0.55f * (1f + 0.08f * kotlin.math.sin(t * 7f).toFloat()))
            Expression.CONFUSED -> {
                val hh = if (isLeft) h else h * 0.55f
                rect.set(x, cy - hh / 2, x + w, cy + hh / 2); c.drawRoundRect(rect, r, r, paint)
            }
            else -> {
                rect.set(x, cy - h / 2, x + w, cy + h / 2)
                c.drawRoundRect(rect, r, r, paint)
                // Párpados que dan la expresión
                path.reset()
                when (expression) {
                    Expression.ANGRY -> if (isLeft) {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h * 0.05f)
                    } else {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x - 2, cy - h * 0.05f)
                    }
                    Expression.SAD -> if (isLeft) {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x - 2, cy - h * 0.1f)
                    } else {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h * 0.1f)
                    }
                    Expression.CONFIDENT -> path.addRect(x - 2, cy - h / 2 - 2, x + w + 2, cy - h * 0.12f, Path.Direction.CW)
                    Expression.CRYING -> if (isLeft) {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x - 2, cy - h * 0.1f)
                    } else {
                        path.moveTo(x - 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h / 2 - 2); path.lineTo(x + w + 2, cy - h * 0.1f)
                    }
                    Expression.THINKING -> if (!isLeft) {
                        path.addRect(x - 2, cy - h / 2 - 2, x + w + 2, cy - h * 0.15f, Path.Direction.CW)
                    }
                    else -> {}
                }
                path.close()
                if (lid.color != Color.TRANSPARENT) c.drawPath(path, lid)
                else { lid.color = Color.parseColor("#1C2320"); c.drawPath(path, lid); lid.color = Color.TRANSPARENT }
            }
        }
    }

    /** Destello de luz arriba a la izquierda del ojo: le da brillo y vida (se mueve un poco con la mirada). */
    private fun drawShine(c: Canvas, x: Float, cy: Float, w: Float, h0: Float) {
        // Solo en ojos sin párpado: en triste, pensando o seguro el destello caía encima del párpado
        val withShine = expression in listOf(Expression.NEUTRAL, Expression.LISTENING, Expression.SPEAKING, Expression.SURPRISED,
            Expression.SHOCKED, Expression.CONFUSED)
        if (!withShine || blink > 0.4f) return
        val h = h0 * (1f - blink)
        shine.alpha = 120
        val sx = x + w * (0.26f - lookX * 0.06f); val sy = cy - h * (0.26f + lookY * 0.04f)
        c.drawCircle(sx, sy, w * 0.085f, shine)
        shine.alpha = 70
        c.drawCircle(sx + w * 0.13f, sy + h * 0.06f, w * 0.04f, shine)
    }

    /** Detalles animados: puntitos al pensar, "z" al dormir. */
    private fun drawStateDetails(c: Canvas, w: Float, h: Float, eyeRight: Float, eyeTop: Float) {
        glow.color = paint.color
        when (expression) {
            Expression.THINKING -> for (i in 0..2) {
                val phase = ((t * 1.6f - i * 0.25f) % 1f + 1f) % 1f
                glow.alpha = (90 + 165 * kotlin.math.sin(phase * Math.PI).toFloat()).toInt()
                c.drawCircle(eyeRight - w * 0.02f + i * w * 0.035f, eyeTop - h * 0.06f - i * h * 0.035f, w * 0.012f + i * w * 0.004f, glow)
            }
            Expression.SLEEPY -> for (i in 0..2) {
                val phase = ((t * 0.35f + i / 3f) % 1f)
                textPaint.color = paint.color
                textPaint.alpha = (255 * (1f - phase)).toInt()
                textPaint.textSize = h * (0.06f + 0.05f * phase)
                c.drawText("z", eyeRight + w * 0.02f + phase * w * 0.06f, eyeTop + h * 0.1f - phase * h * 0.3f, textPaint)
            }
            else -> {}
        }
        glow.alpha = 255
    }

    private fun lidColor() = (background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.parseColor("#1C2320")

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Paint(paint).apply { color = Color.parseColor("#F08A9C") }
        path.reset()
        path.moveTo(cx, cy + s * 0.55f)
        path.cubicTo(cx - s * 1.1f, cy - s * 0.1f, cx - s * 0.45f, cy - s * 0.85f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.45f, cy - s * 0.85f, cx + s * 1.1f, cy - s * 0.1f, cx, cy + s * 0.55f)
        path.close()
        c.drawPath(path, p)
    }
}
