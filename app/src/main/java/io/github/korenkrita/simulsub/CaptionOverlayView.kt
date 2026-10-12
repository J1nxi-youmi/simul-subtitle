package io.github.korenkrita.simulsub

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
 * Overlay gestures, unlocked: drag anywhere to move, drag any of the four corner marks to resize width and height,
 * single tap to show the toolbar (font −/+, opacity −/+, mode, lock, stop). Locked: position and size are fixed and a tap
 * only shows an unlock button in the bottom-right corner.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CaptionOverlayView(ctx: Context, private val interactive: Boolean) : LinearLayout(ctx) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val card = FrameLayout(ctx)
    private val text = TextView(ctx)
    private val stats = TextView(ctx)
    /** resize marks, one per corner: top-left, top-right, bottom-right, bottom-left */
    private val corners = List(4) { android.widget.ImageView(ctx) }
    private val unlockBtn = ImageButton(ctx)
    private val lockBadge = TextView(ctx)
    private val toolbar = LinearLayout(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = dp(12f) }

    var textSp = 13f; private set
    var bgAlpha = DEFAULT_ALPHA; private set
    var colors = PALETTES[0]; private set
    private var lastLines: List<Line> = emptyList()
    private var lastStats: String? = null
    var locked = false; private set

    /** Overlay host callbacks (unused for the preview). */
    var onMove: (dx: Int, dy: Int) -> Unit = { _, _ -> }
    var onMoveEnd: () -> Unit = {}
    /** A corner dragged by dx/dy; [left]/[top] say which corner, so the opposite edges stay put. */
    var onResize: (dx: Int, dy: Int, left: Boolean, top: Boolean) -> Unit = { _, _, _, _ -> }
    var onStyleChanged: () -> Unit = {}
    var onStop: () -> Unit = {}
    /** Mode button tapped: the host flips "only finished sentences" in prefs and calls [setFinalOnly]. */
    var onToggleMode: () -> Unit = {}
    private val modeBtn = TextView(ctx).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        minHeight = dp(40f).toInt(); minWidth = dp(40f).toInt()
        setOnClickListener { onToggleMode(); bumpToolbarTimer() }
    }
    /** Shows the current mode on the toolbar button; the label is what is on screen now. */
    fun setFinalOnly(v: Boolean) {
        modeBtn.text = if (v) "说完再出" else "边听边出"
        modeBtn.contentDescription = if (v) "现在每句说完才显示，点一下改为边听边出" else "现在边听边出，点一下改为每句说完再显示"
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
        if (stats.maxHeight != statusPx) stats.maxHeight = statusPx
        captionMaxPx = captionPx
        applyCaptionHeight()
    }

    private var captionMaxPx = Int.MAX_VALUE
    private var captionFixedPx = 0
    /**
     * Fixed caption area height in px (0 = fit the text, up to the max height). Text stays bottom-aligned, so
     * when it does not fit the newest lines are shown and older ones are cut off at the top.
     */
    fun setCaptionHeight(px: Int) { captionFixedPx = px; applyCaptionHeight() }
    private fun applyCaptionHeight() {
        val want = if (captionFixedPx > 0) captionFixedPx.coerceIn(minCaptionPx(), captionMaxPx.coerceAtLeast(minCaptionPx())) else 0
        if (want > 0) { if (text.minHeight != want) text.minHeight = want; if (text.maxHeight != want) text.maxHeight = want }
        else { if (text.minHeight != 0) text.minHeight = 0; if (text.maxHeight != captionMaxPx) text.maxHeight = captionMaxPx }
    }
    /** one caption line: the area never gets smaller than that */
    fun minCaptionPx() = text.lineHeight + text.paddingTop + text.paddingBottom
    /** Current caption area height (for starting a resize drag from what is on screen). */
    fun captionHeightPx() = text.height

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
            setTextColor(0xFFA5B4FC.toInt())
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

        // resize marks: a faint "›" in each corner, pointing out of the card, in the caption colour. Only drawn;
        // the card's touch listener finds the corner (see installGestures).
        val markRot = floatArrayOf(225f, 315f, 45f, 135f)
        val markGrav = intArrayOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.END, Gravity.BOTTOM or Gravity.START)
        corners.forEachIndexed { k, c ->
            c.visibility = GONE
            c.setImageResource(R.drawable.ic_chevron_right); c.rotation = markRot[k]; c.alpha = 0.5f
            card.addView(c, FrameLayout.LayoutParams(dp(14f).toInt(), dp(14f).toInt(), markGrav[k]).apply {
                val m = dp(3f).toInt(); setMargins(m, m, m, m)
            })
        }
        lockBadge.apply {
            text = "🔒"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); visibility = GONE
        }
        card.addView(lockBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(4f).toInt(), dp(8f).toInt(), 0)
        })
        // locked: a tap shows this in the bottom-right corner for a few seconds; tapping it unlocks
        unlockBtn.apply {
            setImageResource(R.drawable.ic_lock_open); contentDescription = "解锁位置和大小"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE626262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x33FFFFFF) }
            val p = dp(8f).toInt(); setPadding(p, p, p, p)
            visibility = GONE
            setOnClickListener { setLocked(false) }
        }
        card.addView(unlockBtn, FrameLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            val m = dp(3f).toInt(); setMargins(m, m, m, m)
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
        applyCaptionHeight() // a fixed height is clamped to one line, which depends on the font size
        bg.setColor(colors.bg and 0x00FFFFFF or ((alpha * 255).toInt().coerceIn(0, 255) shl 24))
        // with a fully transparent card the text keeps a stronger shadow so it stays readable on video;
        // dark text on a light card has none (a black shadow would only blur it)
        if (colors.shadow) text.setShadowLayer(dp(if (alpha < 0.2f) 4f else 3f), 0f, dp(1f), 0xEE000000.toInt())
        else text.setShadowLayer(0f, 0f, 0f, 0)
        // magpie's hairline edge on a dark card, so it stands off a dark video; none once the card is nearly clear
        bg.setStroke(dp(0.5f).toInt().coerceAtLeast(1), if (colors.shadow && alpha >= 0.2f) 0x1AFFFFFF else 0)
        corners.forEach { it.setColorFilter(colors.zh) }
    }

    /** Text / background colours of the captions; the background keeps the user's opacity. */
    class Palette(val id: String, val label: String, val zh: Int, val ja: Int, val draft: Int, val stats: Int, val bg: Int, val shadow: Boolean = true)

    fun setColors(p: Palette) {
        if (p === colors) return
        colors = p
        text.setTextColor(p.zh); stats.setTextColor(p.stats)
        applyStyle(textSp, bgAlpha)
        setLines(lastLines, lastStats) // spans carry the old colours
    }

    /** One entry per visible line: Chinese text, optional Japanese, draft flag. */
    class Line(val zh: String, val ja: String?, val isFinal: Boolean)

    fun setLines(lines: List<Line>, statsLine: String?) {
        lastLines = lines; lastStats = statsLine
        val sb = SpannableStringBuilder()
        val jaPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp * 0.62f, resources.displayMetrics)).toInt()
        for (l in lines) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (!l.ja.isNullOrEmpty()) {
                val s = sb.length
                sb.append(l.ja).append('\n')
                sb.setSpan(AbsoluteSizeSpan(jaPx), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(colors.ja), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            // the draft line is visibly separate from the finished sentences above: dimmer, smaller, marked "…"
            if (!l.isFinal) sb.append("… ")
            sb.append(l.zh)
            if (!l.isFinal) {
                sb.setSpan(ForegroundColorSpan(colors.draft), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        text.text = sb
        stats.text = statsLine ?: ""
        stats.visibility = if (statsLine.isNullOrEmpty()) GONE else VISIBLE
    }

    private fun buildToolbar() {
        toolbar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(0xF526262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x1AFFFFFF) }
            setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            elevation = dp(6f)
            visibility = GONE
        }
        fun btn(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon); contentDescription = label
            background = null; setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(dp(40f).toInt(), dp(40f).toInt())
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun tag(t: String) = TextView(context).apply {
            text = t; setTextColor(0xFF8B8B94.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
        }
        fun sep() = View(context).apply { setBackgroundColor(0x1AFFFFFF); layoutParams = LayoutParams(dp(1f).toInt(), dp(18f).toInt()).apply { marginStart = dp(3f).toInt(); marginEnd = dp(3f).toInt() } }

        fun tbtn(label: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
            text = label; contentDescription = desc
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minWidth = dp(40f).toInt(); minHeight = dp(40f).toInt()
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
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
        toolbar.addView(btn(R.drawable.ic_lock, "锁定位置和大小") { toggleLock() })
        toolbar.addView(btn(R.drawable.ic_close, "停止字幕") { onStop() }.apply { setColorFilter(0xFFF28B82.toInt()) })
    }

    private fun setSize(v: Float) { applyStyle(v.coerceIn(MIN_SP, MAX_SP), bgAlpha); onStyleChanged() }
    private fun setAlphaStep(v: Float) { applyStyle(textSp, v.coerceIn(0f, 1f)); onStyleChanged() }

    fun setLocked(v: Boolean) {
        locked = v
        lockBadge.visibility = if (v) VISIBLE else GONE
        hideUnlock()
        if (interactive) corners.forEach { it.visibility = if (v) GONE else VISIBLE }
    }
    private fun toggleLock() { setLocked(!locked); if (locked) hideToolbar() }

    private val hideRunnable = Runnable { hideToolbar() }
    private fun bumpToolbarTimer() { removeCallbacks(hideRunnable); postDelayed(hideRunnable, 4_000) }
    private fun showToolbar() {
        toolbar.visibility = VISIBLE
        bumpToolbarTimer()
        onToolbarShown(true)
    }
    private fun hideToolbar() {
        if (toolbar.visibility == GONE) return
        toolbar.visibility = GONE
        onToolbarShown(false)
    }
    private val hideUnlockRunnable = Runnable { unlockBtn.visibility = GONE }
    private fun hideUnlock() { removeCallbacks(hideUnlockRunnable); unlockBtn.visibility = GONE }
    private fun showUnlock() { unlockBtn.visibility = VISIBLE; removeCallbacks(hideUnlockRunnable); postDelayed(hideUnlockRunnable, 3_000) }

    /** A tap on the card: the toolbar when unlocked; when locked only the unlock button. */
    private fun tap() {
        if (locked) { if (unlockBtn.visibility == VISIBLE) hideUnlock() else showUnlock(); return }
        if (toolbar.visibility == VISIBLE) hideToolbar() else showToolbar()
    }

    private fun installGestures() {
        val slop = dp(8f)
        val grab = dp(28f)
        setLocked(false) // shows the corner marks
        var downX = 0f; var downY = 0f; var lastX = 0f; var lastY = 0f; var moved = false
        // the corner under the finger at ACTION_DOWN (-1: none). One listener on the card picks it, so the four grab
        // areas never overlap: on a one-line card each corner gets at most half the height.
        var corner = -1
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; moved = false
                    val gx = minOf(grab, card.width / 2f); val gy = minOf(grab, card.height / 2f)
                    val l = e.x < gx; val r = e.x > card.width - gx; val t = e.y < gy; val b = e.y > card.height - gy
                    corner = if (locked) -1 else when { t && l -> 0; t && r -> 1; b && r -> 2; b && l -> 3; else -> -1 }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                    if (moved && !locked) {
                        // whole pixels only: keep the fraction for the next move, or slow drags would not move at all
                        val dx = (e.rawX - lastX).toInt(); val dy = (e.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            if (corner >= 0) onResize(dx, dy, corner == 0 || corner == 3, corner <= 1) else onMove(dx, dy)
                            lastX += dx; lastY += dy
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) tap() else if (!locked) onMoveEnd()
                MotionEvent.ACTION_CANCEL -> if (moved && !locked) onMoveEnd()
            }
            true
        }
    }


    companion object {
        /** Caption size range, shared with the settings slider. */
        const val MIN_SP = 5f
        const val MAX_SP = 36f
        /** Card opacity when the user has not set one: magpie's ink card at 78%. */
        const val DEFAULT_ALPHA = 0.78f

        /** Caption colour schemes; the first is the default. ids are stored in prefs, keep them stable. */
        val PALETTES = listOf(
            // magpie's ink card (#1a1a1e) with its quiet greys and indigo; the default since 0.6
            Palette("ink", "墨色", 0xFFFFFFFF.toInt(), 0xCCDCDCE4.toInt(), 0xFFB9B9C1.toInt(), 0xFFA5B4FC.toInt(), 0xFF1A1A1E.toInt()),
            Palette("white", "经典白", 0xFFFFFFFF.toInt(), 0xCCDCD8F0.toInt(), 0xFFB9B5C9.toInt(), 0xFFB4ACFA.toInt(), 0xFF100E1A.toInt()),
            Palette("yellow", "影院黄", 0xFFFFE066.toInt(), 0xCCFFF3C4.toInt(), 0xFFCFC48F.toInt(), 0xFFFFC94D.toInt(), 0xFF000000.toInt()),
            Palette("mint", "薄荷绿", 0xFFA7F3D0.toInt(), 0xCCD1FAE5.toInt(), 0xFF8CBBA6.toInt(), 0xFF6EE7B7.toInt(), 0xFF0B1F1A.toInt()),
            Palette("sky", "天空蓝", 0xFFBAE6FD.toInt(), 0xCCE0F2FE.toInt(), 0xFF93B4C9.toInt(), 0xFF7DD3FC.toInt(), 0xFF0B1726.toInt()),
            Palette("sakura", "樱花粉", 0xFFFBCFE8.toInt(), 0xCCFCE7F3.toInt(), 0xFFC9A3B8.toInt(), 0xFFF9A8D4.toInt(), 0xFF24101C.toInt()),
            Palette("paper", "白底黑字", 0xFF16151C.toInt(), 0xCC3D3B48.toInt(), 0xFF6E6C7A.toInt(), 0xFF5B4BD6.toInt(), 0xFFFFFFFF.toInt(), shadow = false),
        )
        fun palette(id: String?) = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]
    }
}
