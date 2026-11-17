package com.gpt.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gpt.hook.diagnostics.HookConfigParser

/** On-device configuration and metadata-only observation console. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var reportStatus: TextView
    private lateinit var editor: EditText
    private lateinit var report: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Connecting to LSPosed service…" }
        editor = EditText(this).apply {
            setText(DEFAULT_CONFIG)
            minLines = 10
            gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
        }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Hook configuration (JSON)" })
        root.addView(ScrollView(this).apply {
            addView(editor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
        })
        root.addView(Button(this).apply {
            text = "Validate and apply configuration"
            setOnClickListener { saveConfig() }
        })
        root.addView(Button(this).apply {
            text = "Restore empty safe configuration"
            setOnClickListener { editor.setText(DEFAULT_CONFIG) }
        })
        root.addView(reportStatusText().also { reportStatus = it })
        root.addView(TextView(this).apply { text = "Latest metadata-only observations" })
        report = EditText(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            minLines = 6
            typeface = android.graphics.Typeface.MONOSPACE
            setText(EMPTY_REPORT)
        }
        root.addView(ScrollView(this).apply {
            addView(report)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Refresh"
                setOnClickListener { refreshReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Copy JSON"
                setOnClickListener { copyReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Share to AI"
                setOnClickListener { shareReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        setContentView(root)
        refreshStatus()
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshReport()
    }

    private fun reportStatusText() = TextView(this).apply { text = "Observation transport: waiting" }

    private fun refreshStatus() {
        val service = DshApplication.currentService
        status.text = if (service == null) {
            "LSPosed service unavailable. Enable the module and open it again."
        } else {
            "Connected: ${service.frameworkName} / API ${service.apiVersion}\n" +
                "Scope: ${service.scope.joinToString()}"
        }
    }

    private fun saveConfig() {
        val service = DshApplication.currentService
        if (service == null) {
            status.text = "Cannot save: LSPosed service is not connected."
            return
        }
        try {
            val source = editor.text.toString()
            val parsed = HookConfigParser.parse(source)
            service.getRemotePreferences("dsh").edit()
                .putString("hook_config", source)
                .apply()
            status.text = "Saved: ${parsed.rules.size} rule(s). The target process validates and applies the update."
        } catch (t: Throwable) {
            status.text = "Rejected: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun refreshReport() {
        try {
            val result = contentResolver.call(
                Uri.parse("content://${ObservationProvider.AUTHORITY}"),
                ObservationProvider.METHOD_READ, null, null
            )
            val json = result?.getString(ObservationProvider.KEY_REPORT) ?: EMPTY_REPORT
            report.setText(json)
            val updated = result?.getLong(ObservationProvider.KEY_UPDATED) ?: 0L
            reportStatus.text = if (updated == 0L) {
                "No observation report received yet. Trigger a configured method in the target app."
            } else {
                "Report received: ${updated} (epoch ms), ${json.length} characters"
            }
        } catch (t: Throwable) {
            reportStatus.text = "Report read failed: ${t.javaClass.simpleName}"
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH observations", report.text))
        reportStatus.text = "Observation JSON copied to clipboard."
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "DSH metadata-only Hook observations")
            putExtra(Intent.EXTRA_TEXT, report.text.toString())
        }
        startActivity(Intent.createChooser(send, "Send sanitized report to an AI assistant"))
    }

    companion object {
        private const val EMPTY_REPORT = """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
        private const val DEFAULT_CONFIG = """{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}"""
    }
}
