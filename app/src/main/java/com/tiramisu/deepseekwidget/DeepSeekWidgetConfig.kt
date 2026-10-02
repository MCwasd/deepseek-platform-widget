package com.tiramisu.deepseekwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.work.*

/**
 * Configuration activity shown when adding the widget to the desktop.
 *
 * The user logs in with their DeepSeek platform email + password.
 * On success, the Bearer Token is stored in EncryptedSharedPreferences.
 *
 * History pitfalls avoided:
 * - Uses plain Activity, NOT AppCompatActivity (no AppCompat theme conflict)
 * - Login runs on calling thread (not a coroutine) — matches WidgetUpdateWorker pattern
 * - Token stored via EncryptedSharedPreferences, same as old API Key
 */
class DeepSeekWidgetConfig : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.config_layout)

        // Get the widget ID from the intent
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If invalid, finish immediately
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Set result to cancelled by default (user must login to confirm)
        setResult(RESULT_CANCELED)

        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val spTz = findViewById<Spinner>(R.id.sp_tz)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        val btnDiag = findViewById<Button>(R.id.btn_diag)
        val tvStatus = findViewById<TextView>(R.id.tv_config_status)

        // Pre-fill if already configured (re-configuring)
        val accountManager = DeepSeekAccountManager(this)
        val existingEmail = accountManager.loadEmail()
        if (!existingEmail.isNullOrBlank()) {
            etEmail.setText(existingEmail)
        }
        spTz.setSelection(DeepSeekWidget.getTzMode(this))

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isBlank()) {
                Toast.makeText(this, "请输入邮箱", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.isBlank()) {
                Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show loading
            tvStatus.text = "登录验证中..."
            tvStatus.visibility = View.VISIBLE
            btnLogin.isEnabled = false

            // Login in background thread (same pattern as WidgetUpdateWorker — no coroutines)
            Thread {
                try {
                    accountManager.login(email, password)
                    // 保存用量统计时区（开放平台支持自定义时区后，月/日边界按此计算）
                    DeepSeekWidget.setTzMode(this, spTz.selectedItemPosition)

                    // Back on UI thread to finish
                    runOnUiThread {
                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        setResult(RESULT_OK, resultValue)
                        scheduleWork()
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvStatus.text = "❌ ${e.message}"
                        btnLogin.isEnabled = true
                    }
                }
            }.start()
        }

        btnCancel.setOnClickListener {
            finish()
        }

        // 诊断：抓取“最近一次刷新”同口径的原始响应，复制到剪贴板，用于问题反馈
        btnDiag.setOnClickListener {
            val token = accountManager.getValidToken()
            if (token.isNullOrBlank()) {
                Toast.makeText(this, "请先登录成功后再获取诊断", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "正在获取诊断信息..."
            btnDiag.isEnabled = false
            Thread {
                val text = try {
                    DeepSeekApiClient(token, DeepSeekWidget.getUsageTimeZone(this)).fetchDiagnostics()
                } catch (e: Exception) {
                    "诊断获取失败: ${e.message}"
                }
                runOnUiThread {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsdiag", text))
                    tvStatus.text = "✅ 诊断信息已复制到剪贴板，请发送给开发者"
                    btnDiag.isEnabled = true
                }
            }.start()
        }
    }

    private fun scheduleWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            DeepSeekWidget.UPDATE_INTERVAL_MINUTES,
            java.util.concurrent.TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WIDGET_UPDATE_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }
}
