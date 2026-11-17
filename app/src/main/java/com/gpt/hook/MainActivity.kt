package com.gpt.hook

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small on-device editor for declarative Hook rules. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Connecting to LSPosed service…" }
        editor = EditText(this).apply {
            setText(DEFAULT_CONFIG)
            minLines = 14
            gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
        }
        root.addView(status)
        root.addView(ScrollView(this).apply {
            addView(editor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
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
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

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
            // The hooked process independently validates this JSON before applying it.
            val parsed = com.gpt.hook.diagnostics.HookConfigParser.parse(source)
            service.getRemotePreferences("dsh").edit()
                .putString("hook_config", source)
                .apply()
            status.text = "Saved and dispatched: ${parsed.rules.size} rule(s). " +
                "The target process applies changes through its preference listener."
        } catch (t: Throwable) {
            status.text = "Rejected: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    companion object {
        private const val DEFAULT_CONFIG = """{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}"""
    }
}
