package com.net.routerbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    data class DeviceBookmark(
        val name: String,
        val url: String
    )

    private lateinit var webContainer: FrameLayout
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var credentialTester: CredentialTesterHelper

    private val tabs = mutableListOf<WebView>()
    private var activeTabIndex = -1
    private var isDesktopMode = false
    private var pickingForUserField = false

    private val wordlistPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        if (pickingForUserField) {
            credentialTester.onUserWordlistPicked(uri)
        } else {
            credentialTester.onPassWordlistPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        ensureDefaultDevicesInitialized()
        createNewTab("http://192.168.1.1")
    }

    private fun initViews() {
        webContainer = findViewById(R.id.webContainer)
        etUrl = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)

        credentialTester = CredentialTesterHelper(
            context = this,
            getActiveWebView = { getCurrentWebView() },
            onPickUserWordlist = {
                pickingForUserField = true
                wordlistPickerLauncher.launch(arrayOf("text/plain", "*/*"))
            },
            onPickPassWordlist = {
                pickingForUserField = false
                wordlistPickerLauncher.launch(arrayOf("text/plain", "*/*"))
            }
        )

        credentialTester.attachStatusBar(
            container = findViewById(R.id.testerStatusBar),
            statusText = findViewById(R.id.tvTesterStatus),
            btnStop = findViewById(R.id.btnStopTester)
        )

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            val wv = getCurrentWebView()
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else if (tabs.size > 1) {
                closeCurrentTab()
            }
        }

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            loadInputUrl()
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadInputUrl()
                true
            } else {
                false
            }
        }

        // زر التبويب الجديد
        findViewById<ImageButton>(R.id.btnNewTab).setOnClickListener {
            createNewTab("http://192.168.1.1")
        }

        // زر وضع سطح المكتب (Desktop) في الشريط السفلي
        findViewById<ImageButton>(R.id.btnDesktop).setOnClickListener {
            toggleDesktopMode()
        }

        // زر اختبار القوائم (Wordlist)
        findViewById<ImageButton>(R.id.btnWordlist).setOnClickListener {
            credentialTester.showConfigDialog()
        }

        // زر المحفوظات (كلمات المرور الصحيحة)
        findViewById<ImageButton>(R.id.btnSavedCreds).setOnClickListener {
            credentialTester.showSavedCredentialsDialog()
        }

        // زر الخيارات والخصائص الشامل (الأجهزة، إضافة جهاز، السجل، سطح المكتب، تحديث، التبويبات)
        val btnOptions = findViewById<ImageButton>(R.id.btnOptions)
        btnOptions.setOnClickListener { anchorView ->
            showOptionsMenu(anchorView)
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val desktopTitle = if (isDesktopMode) "وضع سطح المكتب: مفعّل ✓" else "وضع سطح المكتب: متوقف"

        popup.menu.add(0, 1, 1, "عناوين الأجهزة المحفوظة (راوتر / نانو)")
        popup.menu.add(0, 2, 2, "+ إضافة جهاز جديد وتسميته")
        popup.menu.add(0, 3, 3, "سجل البحث والتصفح (History)")
        popup.menu.add(0, 4, 4, desktopTitle)
        popup.menu.add(0, 5, 5, "التبويبات المفتوحة (${tabs.size})")
        popup.menu.add(0, 6, 6, "إعادة تحميل الصفحة")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showDevicesDialog()
                2 -> showAddDeviceDialog()
                3 -> showHistoryDialog()
                4 -> toggleDesktopMode()
                5 -> showTabsDialog()
                6 -> getCurrentWebView()?.reload()
            }
            true
        }
        popup.show()
    }

    private fun ensureDefaultDevicesInitialized() {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        if (prefs.contains("saved_devices")) return

        val defaults = listOf(
            DeviceBookmark("راوتر TP-Link / افتراضي", "http://192.168.1.1"),
            DeviceBookmark("راوتر Tenda / فرعي", "http://192.168.0.1"),
            DeviceBookmark("نانو ستيشن (Ubiquiti AirOS)", "http://192.168.1.20"),
            DeviceBookmark("راوتر Xiaomi", "http://192.168.31.1"),
            DeviceBookmark("راوتر Huawei", "http://192.168.8.1"),
            DeviceBookmark("راوتر MikroTik / شبكة", "http://192.168.88.1"),
            DeviceBookmark("شبكة فرعية (192.168.9.1)", "http://192.168.9.1")
        )
        saveDevicesList(defaults)
    }

    private fun getDevicesList(): MutableList<DeviceBookmark> {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("saved_devices", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<DeviceBookmark>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                DeviceBookmark(
                    name = obj.optString("name"),
                    url = obj.optString("url")
                )
            )
        }
        return list
    }

    private fun saveDevicesList(list: List<DeviceBookmark>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("url", d.url)
            })
        }
        getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_devices", arr.toString())
            .apply()
    }

    private fun showDevicesDialog() {
        val devices = getDevicesList()
        if (devices.isEmpty()) {
            showAddDeviceDialog()
            return
        }

        val labels = devices.map { "${it.name}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("الأجهزة المحفوظة (${devices.size})")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                etUrl.setText(chosen.url)
                getCurrentWebView()?.loadUrl(chosen.url)
            }
            .setPositiveButton("+ إضافة جهاز") { _, _ ->
                showAddDeviceDialog()
            }
            .setNeutralButton("حذف جهاز") { _, _ ->
                showDeleteDeviceDialog(devices)
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showAddDeviceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 12)
        }

        val etName = EditText(this).apply {
            hint = "اسم الجهاز (مثال: نانو السطح أو راوتر البيت)"
        }
        val etIp = EditText(this).apply {
            hint = "عنوان IP أو الرابط (مثال: 192.168.1.20)"
            val currentUrl = getCurrentWebView()?.url ?: ""
            if (currentUrl.isNotEmpty()) {
                setText(currentUrl)
            }
        }

        container.addView(etName)
        container.addView(etIp)

        AlertDialog.Builder(this)
            .setTitle("إضافة جهاز وتسميته")
            .setView(container)
            .setPositiveButton("حفظ") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "جهاز شبكة" }
                var url = etIp.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "أدخل عنوان الجهاز أولاً", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://$url"
                }
                val list = getDevicesList()
                list.add(0, DeviceBookmark(name, url))
                saveDevicesList(list)
                Toast.makeText(this, "تم حفظ ($name)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showDeleteDeviceDialog(devices: MutableList<DeviceBookmark>) {
        val labels = devices.map { "${it.name} (${it.url})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر الجهاز لحذفه")
            .setItems(labels) { _, which ->
                val removed = devices.removeAt(which)
                saveDevicesList(devices)
                Toast.makeText(this, "تم حذف: ${removed.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTabsDialog() {
        val labels = tabs.mapIndexed { idx, wv ->
            val marker = if (idx == activeTabIndex) "● " else ""
            val title = wv.title?.takeIf { it.isNotBlank() } ?: (wv.url ?: "تبويب ${idx + 1}")
            "$marker$title"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("التبويبات المفتوحة (${tabs.size})")
            .setItems(labels) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton("+ تبويب جديد") { _, _ ->
                createNewTab("http://192.168.1.1")
            }
            .setNeutralButton("إغلاق الحالي") { _, _ ->
                closeCurrentTab()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun loadInputUrl() {
        var raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://$raw"
        }
        getCurrentWebView()?.loadUrl(raw)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            applyUserAgent(this, isDesktopMode)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!loadedUrl.isNullOrEmpty() && view == getCurrentWebView()) {
                        etUrl.setText(loadedUrl)
                        saveHistoryItem(loadedUrl)
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == getCurrentWebView()) {
                        if (newProgress in 1..99) {
                            progressBar.visibility = View.VISIBLE
                            progressBar.progress = newProgress
                        } else {
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }

        tabs.add(webView)
        switchToTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        webContainer.removeAllViews()
        val current = tabs[activeTabIndex]
        webContainer.addView(current)
        etUrl.setText(current.url ?: "")
    }

    private fun closeCurrentTab() {
        if (activeTabIndex !in tabs.indices) return
        val toRemove = tabs.removeAt(activeTabIndex)
        webContainer.removeView(toRemove)
        toRemove.destroy()
        if (tabs.isEmpty()) {
            createNewTab("http://192.168.1.1")
        } else {
            switchToTab((activeTabIndex - 1).coerceAtLeast(0))
        }
    }

    private fun getCurrentWebView(): WebView? {
        return if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        tabs.forEach { applyUserAgent(it, isDesktopMode) }
        getCurrentWebView()?.reload()
        val msg = if (isDesktopMode) "تم تفعيل وضع سطح المكتب (Desktop)" else "تم الرجوع لوضع الهاتف"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyUserAgent(webView: WebView, desktop: Boolean) {
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        webView.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        } else {
            defaultUa
        }
    }

    private fun saveHistoryItem(url: String) {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("history_list", "[]") ?: "[]")
        val updated = JSONArray()
        updated.put(url)
        for (i in 0 until arr.length()) {
            val item = arr.optString(i)
            if (item != url && updated.length() < 50) {
                updated.put(item)
            }
        }
        prefs.edit().putString("history_list", updated.toString()).apply()
    }

    private fun showHistoryDialog() {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("history_list", "[]") ?: "[]")
        if (arr.length() == 0) {
            Toast.makeText(this, "سجل التصفح فارغ حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val items = Array(arr.length()) { idx -> arr.optString(idx) }
        AlertDialog.Builder(this)
            .setTitle("سجل التصفح (${items.size})")
            .setItems(items) { _, which ->
                val target = items[which]
                etUrl.setText(target)
                getCurrentWebView()?.loadUrl(target)
            }
            .setNeutralButton("مسح السجل") { _, _ ->
                prefs.edit().remove("history_list").apply()
                Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onDestroy() {
        credentialTester.stopTesting(silent = true)
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}