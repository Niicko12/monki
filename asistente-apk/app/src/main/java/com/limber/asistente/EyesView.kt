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
 * Dos ojos animados al estilo de la pantalla de xiaozhi: parpadean solos, miran a los lados
 * y cambian de forma con la expresión.
 */
class EyesView(context: Context) : View(context) {

    var expression = Expression.NEUTRAL
        set(v) { field = v; invalidate() }

    var eyeColor = Color.parseColor("#6CC79C")
        set(v) { field = v; paint.color = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eyeColor; style = Paint.Style.FILL }
    private val lid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val path = Path()

    private var blink = 0f       // 0 abierto, 1 cerrado
    private var lookX = 0f       // -1 izquierda, 1 derecha
    private var lookY = 0f
    private var bounce = 0f      // para hablar / escuchar

    private val blinkAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 180
        addUpdateListener { blink = it.animatedValue as Float; invalidate() }
    }
    private val idle = object : Runnable {
        override fun run() {
            if (expression != Expression.SLEEPY && Random.nextFloat() < 0.7f) blinkAnim.start()
            if (Random.nextFloat() < 0.4f) glance(Random.nextFloat() * 2 - 1, Random.nextFloat() * 0.6f - 0.3f)
            postDelayed(this, 1800L + Random.nextLong(2200))
        }
    }
    private val talk = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { bounce = it.animatedValue as Float; if (isAnimated()) invalidate() }
    }

    private fun isAnimated() = expression == Expression.SPEAKING || expression == Expression.LISTENING ||
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
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idle); talk.cancel(); blinkAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        lid.color = (background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        val eyeW = minOf(w * 0.28f, h * 0.55f)
        var eyeH = h * 0.62f
        val gap = w * 0.12f
        val cy = h / 2 + lookY * h * 0.08f
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
        val jump = if (expression == Expression.LAUGHING) -bounce * h * 0.05f else 0f
        val left = w / 2 - gap / 2 - eyeW + shift
        val right = w / 2 + gap / 2 + shift
        drawEye(c, left, cy + jump, eyeW, eyeH, isLeft = true)
        drawEye(c, right, cy + jump, eyeW, if (expression == Expression.SILLY) eyeH * 0.55f else eyeH, isLeft = false)
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
            Expression.KISSY -> drawHeart(c, w / 2, cy + eyeH * 0.55f, eyeW * 0.18f)
            else -> {}
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
            Expression.LOVE -> drawHeart(c, x + w / 2, cy, w * 0.55f)
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
