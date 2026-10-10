package dev.krita.jpsub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The subtitle card. Used twice: as the floating overlay (with [interactive] gestures) and as the live
 * preview in the settings screen, so what the user tunes is exactly what they get.
 *
 * Overlay gestures: drag anywhere to move, drag the corner dot to resize width, single tap to show the
 * toolbar (font −/+, opacity −/+, lock, stop). When locked the card ignores drags; tap still unlocks.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CaptionOverlayView(ctx: Context, private val interactive: Boolean) : LinearLayout(ctx) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val card = FrameLayout(ctx)
    private val text = TextView(ctx)
    private val stats = TextView(ctx)
    private val handle = View(ctx)
    private val lockBadge = TextView(ctx)
    private val toolbar = LinearLayout(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = dp(18f) }

    var textSp = 20f; private set
    var bgAlpha = 0.55f; private set
    var locked = false; private set

    /** Overlay host callbacks (unused for the preview). */
    var onMove: (dx: Int, dy: Int) -> Unit = { _, _ -> }
    var onMoveEnd: () -> Unit = {}
    var onResize: (dw: Int) -> Unit = {}
    var onStyleChanged: () -> Unit = {}
    var onStop: () -> Unit = {}
    /** Mode button tapped: the host flips "only finished sentences" in prefs and calls [setFinalOnly]. */
    var onToggleMode: () -> Unit = {}
    private val modeBtn = TextView(ctx).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
        setOnClickListener { onToggleMode(); bumpToolbarTimer() }
    }
    /** Shows the current mode on the toolbar button; the label is what is on screen now. */
    fun setFinalOnly(v: Boolean) {
        modeBtn.text = if (v) "只看定稿" else "边出边改"
        modeBtn.contentDescription = if (v) "当前只显示说完的句子，点一下改为边出边改" else "当前边出边改，点一下改为只显示说完的句子"
    }
    /** Toolbar opened/closed: the host widens the window while it is open so the toolbar is never clipped. */
    var onToolbarShown: (Boolean) -> Unit = {}

    /** Caption card width in px (the window may be wider while the toolbar is open). */
    fun setCardWidth(px: Int) {
        val lp = card.layoutParams
        if (lp.width != px) { lp.width = px; card.layoutParams = lp }
    }

    /**
     * Caps caption and status heights so the toolbar keeps room on screen. Both are bottom-aligned, so when
     * they overflow (narrow card, big font) TextView itself keeps the newest lines in view, also after a
     * font or width change.
     */
    fun setMaxContentHeight(captionPx: Int, statusPx: Int) {
        if (text.maxHeight != captionPx) text.maxHeight = captionPx
        if (stats.maxHeight != statusPx) stats.maxHeight = statusPx
    }

    /** Natural width of the toolbar, independent of the current window width. */
    fun toolbarWidthPx(): Int {
        toolbar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return toolbar.measuredWidth
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false

        text.apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setShadowLayer(dp(3f), 0f, dp(1f), 0xCC000000.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2f), 1f)
        }
        stats.apply {
            setTextColor(0xFFB4ACFA.toInt())
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            visibility = GONE
        }
        val column = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        card.background = bg
        card.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // resize dot, bottom-right
        handle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0xE6FFFFFF.toInt()); setStroke(dp(2f).toInt(), 0xFF6D5DF6.toInt())
        }
        handle.visibility = GONE
        card.addView(handle, FrameLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(4f).toInt(), dp(4f).toInt())
        })
        lockBadge.apply {
            text = "🔒"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); visibility = GONE
        }
        card.addView(lockBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(4f).toInt(), dp(8f).toInt(), 0)
        })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildToolbar()
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        if (interactive) installGestures()
        applyStyle(textSp, bgAlpha)
    }

    fun applyStyle(sizeSp: Float, alpha: Float) {
        textSp = sizeSp; bgAlpha = alpha
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        bg.setColor(Color.argb((alpha * 255).toInt().coerceIn(0, 255), 16, 14, 26))
        // with a fully transparent card the text keeps a stronger shadow so it stays readable on video
        text.setShadowLayer(dp(if (alpha < 0.2f) 4f else 3f), 0f, dp(1f), 0xEE000000.toInt())
    }

    /** One entry per visible line: Chinese text, optional Japanese, draft flag. */
    class Line(val zh: String, val ja: String?, val isFinal: Boolean)

    fun setLines(lines: List<Line>, statsLine: String?) {
        val sb = SpannableStringBuilder()
        val jaPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp * 0.62f, resources.displayMetrics)).toInt()
        for (l in lines) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (!l.ja.isNullOrEmpty()) {
                val s = sb.length
                sb.append(l.ja).append('\n')
                sb.setSpan(AbsoluteSizeSpan(jaPx), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xCCDCD8F0.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            sb.append(l.zh)
            if (!l.isFinal) sb.setSpan(ForegroundColorSpan(0xFFB9B5C9.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = sb
        stats.text = statsLine ?: ""
        stats.visibility = if (statsLine.isNullOrEmpty()) GONE else VISIBLE
    }

    private fun buildToolbar() {
        toolbar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(0xF01D1B24.toInt()) }
            setPadding(dp(6f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt())
            elevation = dp(6f)
            visibility = GONE
        }
        fun btn(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon); contentDescription = label
            background = null; setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun tag(t: String) = TextView(context).apply {
            text = t; setTextColor(0xFFB4ACFA.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
        }
        fun sep() = View(context).apply { setBackgroundColor(0x33FFFFFF); layoutParams = LayoutParams(dp(1f).toInt(), dp(22f).toInt()).apply { marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt() } }

        fun tbtn(label: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
            text = label; contentDescription = desc
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minWidth = dp(40f).toInt()
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        // Size / opacity scroll sideways when the screen is too narrow (small display, big system font);
        // lock and stop sit outside the scroller, so they always stay visible and tappable.
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tbtn("A−", "字号减小") { setSize(textSp - 2) })
            addView(tag("字号"))
            addView(tbtn("A+", "字号增大") { setSize(textSp + 2) })
            addView(sep())
            addView(tbtn("−", "背景更透明") { setAlphaStep(bgAlpha - 0.15f) })
            addView(tag("背景"))
            addView(tbtn("+", "背景更不透明") { setAlphaStep(bgAlpha + 0.15f) })
            addView(sep())
            addView(modeBtn)
        }
        toolbar.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(controls)
            setOnScrollChangeListener { _, _, _, _, _ -> bumpToolbarTimer() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(sep())
        toolbar.addView(btn(R.drawable.ic_lock, "锁定位置") { toggleLock() })
        toolbar.addView(btn(R.drawable.ic_close, "停止字幕") { onStop() }.apply { setColorFilter(0xFFF87171.toInt()) })
    }

    private fun setSize(v: Float) { applyStyle(v.coerceIn(MIN_SP, MAX_SP), bgAlpha); onStyleChanged() }
    private fun setAlphaStep(v: Float) { applyStyle(textSp, v.coerceIn(0f, 1f)); onStyleChanged() }

    fun setLocked(v: Boolean) {
        locked = v
        lockBadge.visibility = if (v) VISIBLE else GONE
        handle.visibility = if (!v && toolbar.visibility == VISIBLE) VISIBLE else GONE
    }
    private fun toggleLock() { setLocked(!locked); if (locked) hideToolbar() }

    private val hideRunnable = Runnable { hideToolbar() }
    private fun bumpToolbarTimer() { removeCallbacks(hideRunnable); postDelayed(hideRunnable, 4_000) }
    private fun showToolbar() {
        toolbar.visibility = VISIBLE
        handle.visibility = if (locked) GONE else VISIBLE
        bumpToolbarTimer()
        onToolbarShown(true)
    }
    private fun hideToolbar() {
        if (toolbar.visibility == GONE) return
        toolbar.visibility = GONE; handle.visibility = GONE
        onToolbarShown(false)
    }

    private fun installGestures() {
        val slop = dp(8f)
        var downX = 0f; var downY = 0f; var lastX = 0f; var lastY = 0f; var dragging = false
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && !locked && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                    if (dragging) {
                        onMove((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                        lastX = e.rawX; lastY = e.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) onMoveEnd()
                    else if (toolbar.visibility == VISIBLE) hideToolbar() else showToolbar()
                }
            }
            true
        }
        var hx = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hx = e.rawX; removeCallbacks(hideRunnable) }
                MotionEvent.ACTION_MOVE -> { onResize((e.rawX - hx).toInt()); hx = e.rawX }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onMoveEnd(); bumpToolbarTimer() }
            }
            true
        }
    }

    companion object {
        /** Caption size range, shared with the settings slider. */
        const val MIN_SP = 12f
        const val MAX_SP = 36f
    }
}
