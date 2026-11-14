package com.oliver.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppLog {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$t $s")
        while (lines.size > 80) lines.removeLast()
    }
    @Synchronized fun text(): String = lines.joinToString("\n")
}

object Api {
    // Throws on error
    fun ask(sender: String, text: String): String? {
        val c = URL("http://127.0.0.1:3000/reply").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 40000
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("sender", sender).put("text", text).toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        val r = JSONObject(c.inputStream.bufferedReader().readText())
        return if (r.isNull("reply")) null else r.getString("reply")
    }
}

class ReplyService : NotificationListenerService() {

    private val seen = LinkedHashSet<String>()
    private val messengerPkgs = setOf("com.facebook.orca", "com.facebook.mlite")

    override fun onListenerConnected() { AppLog.add("LISTENER CONNECTED") }
    override fun onListenerDisconnected() { AppLog.add("LISTENER DISCONNECTED") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification
        val isMsgr = pkg in messengerPkgs
        val action = findReplyAction(n)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extractText(n)

        AppLog.add((if (isMsgr) "[MSGR] " else "") + pkg +
            " | reply=" + (if (action != null) "YES" else "NO") +
            " | " + (title ?: "-") + ": " + (text ?: "-"))

        if (!isMsgr) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { AppLog.add("  skip: group summary"); return }
        if (title == null || text == null) { AppLog.add("  skip: no title/text"); return }
        if (action == null) { AppLog.add("  skip: no Reply button"); return }

        val id = sbn.key + "|" + text
        synchronized(seen) {
            if (!seen.add(id)) return
            if (seen.size > 200) seen.remove(seen.first())
        }

        thread {
            try {
                val reply = Api.ask(title, text)
                if (reply == null) { AppLog.add("  server: AI disabled/empty"); return@thread }
                AppLog.add("  AI: $reply")
                sendReply(action, reply)
                AppLog.add("  SENT")
            } catch (e: Exception) {
                AppLog.add("  ERROR: ${e.message}")
            }
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }?.let { return it }
        return try {
            Notification.WearableExtender(n).actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        } catch (e: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun extractText(n: Notification): String? {
        val ex = n.extras
        ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { if (it.isNotBlank()) return it }
        ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { if (it.isNotBlank()) return it }
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = msgs?.lastOrNull() as? Bundle
        return last?.getCharSequence("text")?.toString()
    }

    private fun sendReply(action: Notification.Action, reply: String) {
        val inputs = action.remoteInputs
        val intent = Intent()
        val bundle = Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, reply) }
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        action.actionIntent.send(this, 0, intent)
    }
}
