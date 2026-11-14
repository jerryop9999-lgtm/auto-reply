package com.oliver.autoreply

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView

    private val refresh = object : Runnable {
        override fun run() {
            logView.text = AppLog.text()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            NotificationListenerService.requestRebind(ComponentName(this, ReplyService::class.java))
        } catch (e: Exception) { }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        box.addView(TextView(this).apply {
            text = "AI Auto Reply (debug)"
            textSize = 20f
        })
        box.addView(Button(this).apply {
            text = "1. Notification Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })
        box.addView(Button(this).apply {
            text = "2. Test server (USB)"
            setOnClickListener {
                AppLog.add("TEST: sending...")
                thread {
                    try {
                        val r = Api.ask("test", "hello")
                        AppLog.add("TEST OK: $r")
                    } catch (e: Exception) {
                        AppLog.add("TEST FAIL: ${e.message}")
                    }
                }
            }
        })
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val scroll = ScrollView(this)
        scroll.addView(logView)
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(box)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }
}
