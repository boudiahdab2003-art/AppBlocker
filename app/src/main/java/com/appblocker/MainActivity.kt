package com.appblocker

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Milestone 0 screen. Proves the full build-and-install pipeline works:
 * code on the PC -> APK -> running on the phone. Real app UI comes in M1.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E1726"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "AppBlocker"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Milestone 0 complete ✓\nIt builds and runs on your phone."
            textSize = 16f
            setTextColor(Color.parseColor("#9FB3C8"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        setContentView(root)
    }
}
