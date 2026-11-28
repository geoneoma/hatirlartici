package com.arda.hatirlatici

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Notlar ekranlarının ortak renkleri ve küçük çizim yardımcıları. */
object Ui {
    val bg: Int = Color.parseColor("#0E1020")
    val card: Int = Color.parseColor("#1A1E36")
    val bar: Int = Color.parseColor("#232848")
    val sub: Int = Color.parseColor("#9AA0C0")
    val a1: Int = Color.parseColor("#7C5CFF")
    val a2: Int = Color.parseColor("#00C2FF")
    val green: Int = Color.parseColor("#2ED47A")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        return g
    }

    fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    fun gradientOval(): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a1, a2))
        g.shape = GradientDrawable.OVAL
        return g
    }

    fun gradientRounded(ctx: Context, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a1, a2))
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        return g
    }

    fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    fun circle(ctx: Context, label: String, sizeDp: Int, textSize: Float, bg: GradientDrawable, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = label
        t.textSize = textSize
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = bg
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        return t
    }

    fun pill(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 14f, Color.WHITE, true)
        t.background = rounded(ctx, bar, 18)
        t.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(ctx, 8)
        t.layoutParams = lp
        return t
    }
}
