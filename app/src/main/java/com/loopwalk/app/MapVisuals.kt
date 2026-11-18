package com.loopwalk.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

class MapVisuals(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val cache = HashMap<String, Drawable>()

    private fun dp(v: Float): Float = v * density

    fun userPuck(bearingDeg: Float?): Drawable {
        val bucket = if (bearingDeg != null && bearingDeg > 0f) {
            ((bearingDeg / 15f).roundToInt() * 15) % 360
        } else -1
        return cache.getOrPut("user_$bucket") {
            val size = dp(48f).roundToInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Внешнее радарное свечение
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(46, 0, 255, 102)
            c.drawCircle(cx, cy, dp(21f), paint)

            // Кольцо радара
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = Color.argb(140, 0, 255, 102)
            c.drawCircle(cx, cy, dp(14f), paint)

            // Стрелка курса при движении
            if (bucket >= 0) {
                c.save()
                c.rotate(bucket.toFloat(), cx, cy)
                val arrow = Path().apply {
                    moveTo(cx, cy - dp(21f))
                    lineTo(cx - dp(5f), cy - dp(12f))
                    lineTo(cx + dp(5f), cy - dp(12f))
                    close()
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(0, 255, 102)
                c.drawPath(arrow, paint)
                c.restore()
            }

            // Тёмная подложка ядра
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(5, 12, 8)
            c.drawCircle(cx, cy, dp(8.5f), paint)

            // Изумрудное ядро
            paint.color = Color.rgb(0, 255, 102)
            c.drawCircle(cx, cy, dp(6.2f), paint)

            // Белый блик в центре
            paint.color = Color.WHITE
            c.drawCircle(cx, cy, dp(2.2f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }

    fun homeBadge(): Drawable {
        return cache.getOrPut("home_badge") {
            val w = dp(52f).roundToInt()
            val h = dp(44f).roundToInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = w / 2f
            val cy = dp(15f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Свечение
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 255, 176, 0)
            c.drawCircle(cx, cy, dp(14f), paint)

            // Круглый знак старта
            paint.color = Color.rgb(12, 10, 4)
            c.drawCircle(cx, cy, dp(10f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.rgb(255, 176, 0)
            c.drawCircle(cx, cy, dp(10f), paint)

            // Иконка домика внутри
            val house = Path().apply {
                moveTo(cx, cy - dp(5f))
                lineTo(cx + dp(5f), cy - dp(0.5f))
                lineTo(cx + dp(3.5f), cy - dp(0.5f))
                lineTo(cx + dp(3.5f), cy + dp(4.5f))
                lineTo(cx - dp(3.5f), cy + dp(4.5f))
                lineTo(cx - dp(3.5f), cy - dp(0.5f))
                lineTo(cx - dp(5f), cy - dp(0.5f))
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 176, 0)
            c.drawPath(house, paint)

            // Плашка "ДОМ" снизу
            val rect = RectF(dp(7f), dp(27f), w - dp(7f), h - dp(2f))
            paint.color = Color.argb(235, 10, 10, 10)
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Color.rgb(255, 176, 0)
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 176, 0)
            paint.textSize = dp(8.5f)
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            c.drawText("ДОМ", cx, dp(37.5f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }

    fun beaconDiamond(collected: Boolean, index: Int): Drawable {
        return cache.getOrPut("beacon_${collected}_$index") {
            val w = dp(56f).roundToInt()
            val h = dp(48f).roundToInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = w / 2f
            val cy = dp(16f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val mainColor = if (collected) Color.rgb(0, 255, 102) else Color.rgb(0, 229, 255)
            val glowColor = if (collected) Color.argb(45, 0, 255, 102) else Color.argb(65, 0, 229, 255)

            // Ореол
            paint.style = Paint.Style.FILL
            paint.color = glowColor
            c.drawCircle(cx, cy, dp(15f), paint)

            // Внешний ромб
            val r = dp(11f)
            val diamond = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            paint.color = Color.rgb(6, 12, 16)
            c.drawPath(diamond, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = mainColor
            c.drawPath(diamond, paint)

            // Внутренний ромб / точка
            val rIn = dp(4.5f)
            val inner = Path().apply {
                moveTo(cx, cy - rIn)
                lineTo(cx + rIn, cy)
                lineTo(cx, cy + rIn)
                lineTo(cx - rIn, cy)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = mainColor
            c.drawPath(inner, paint)

            // Плашка под ромбом
            val label = if (collected) "OK #$index" else "М-$index"
            val rect = RectF(dp(8f), dp(29f), w - dp(8f), h - dp(2f))
            paint.color = Color.argb(235, 6, 12, 16)
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = mainColor
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.FILL
            paint.color = mainColor
            paint.textSize = dp(8.5f)
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            c.drawText(label, cx, dp(39.5f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }

    fun directionArrow(bearingDeg: Float): Drawable {
        val bucket = ((bearingDeg / 10f).roundToInt() * 10 + 360) % 360
        return cache.getOrPut("arrow_$bucket") {
            val size = dp(24f).roundToInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f

            c.save()
            c.rotate(bucket.toFloat(), cx, cy)

            val chevron = Path().apply {
                moveTo(cx, cy - dp(7f))
                lineTo(cx + dp(6f), cy + dp(4f))
                lineTo(cx, cy + dp(1.2f))
                lineTo(cx - dp(6f), cy + dp(4f))
                close()
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.4f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.rgb(4, 16, 8)
            c.drawPath(chevron, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawPath(chevron, paint)

            c.restore()
            BitmapDrawable(context.resources, bmp)
        }
    }

    fun milepostPill(text: String): Drawable {
        return cache.getOrPut("mile_$text") {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(9.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val textW = paint.measureText(text)
            val w = (textW + dp(14f)).roundToInt()
            val h = dp(20f).roundToInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val rect = RectF(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(235, 8, 14, 10)
            c.drawRoundRect(rect, dp(10f), dp(10f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.color = Color.argb(200, 0, 255, 102)
            c.drawRoundRect(rect, dp(10f), dp(10f), paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(210, 255, 226)
            paint.textAlign = Paint.Align.CENTER
            c.drawText(text, w / 2f, h / 2f + dp(3.3f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }

    fun targetAnchorReticle(): Drawable {
        return cache.getOrPut("target_anchor") {
            val w = dp(64f).roundToInt()
            val h = dp(52f).roundToInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = w / 2f
            val cy = dp(17f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Свечение
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 255, 176, 0)
            c.drawCircle(cx, cy, dp(15f), paint)

            // Прицел
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.rgb(255, 176, 0)
            c.drawCircle(cx, cy, dp(10f), paint)

            c.drawLine(cx - dp(14f), cy, cx - dp(6f), cy, paint)
            c.drawLine(cx + dp(6f), cy, cx + dp(14f), cy, paint)
            c.drawLine(cx, cy - dp(14f), cx, cy - dp(6f), paint)
            c.drawLine(cx, cy + dp(6f), cx, cy + dp(14f), paint)

            paint.style = Paint.Style.FILL
            c.drawCircle(cx, cy, dp(2.5f), paint)

            // Плашка "ВЕКТОР"
            val rect = RectF(dp(7f), dp(32f), w - dp(7f), h - dp(2f))
            paint.color = Color.argb(235, 14, 10, 4)
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Color.rgb(255, 176, 0)
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 176, 0)
            paint.textSize = dp(8.5f)
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            c.drawText("ВЕКТОР", cx, dp(42.5f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }

    fun nextTurnDot(): Drawable {
        return cache.getOrPut("next_turn_dot") {
            val size = dp(26f).roundToInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70, 255, 176, 0)
            c.drawCircle(cx, cy, dp(12f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.rgb(255, 176, 0)
            c.drawCircle(cx, cy, dp(8f), paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawCircle(cx, cy, dp(3.5f), paint)

            BitmapDrawable(context.resources, bmp)
        }
    }
}
