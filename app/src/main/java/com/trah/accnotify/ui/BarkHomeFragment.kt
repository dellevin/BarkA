package com.trah.accnotify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.trah.accnotify.R
import com.trah.accnotify.bark.BarkPrefs
import com.trah.accnotify.bark.BarkPushService
import com.trah.accnotify.bark.BarkRegistrar
import com.trah.accnotify.databinding.FragmentBarkHomeBinding
import com.trah.accnotify.status.RuntimeStatusStore
import com.trah.accnotify.status.WebSocketRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bark-style minimal home: an unregistered device only sees a server URL field
 * and a register button; once registered it shows the device key, copy action,
 * live connection status and a reset entry point.
 */
class BarkHomeFragment : Fragment() {

    private var _binding: FragmentBarkHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: BarkPrefs
    private val registrar by lazy { BarkRegistrar(prefs) }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        prefs = BarkPrefs(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBarkHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRegister.setOnClickListener { register() }
        binding.btnCopyKey.setOnClickListener { copyDeviceKey() }
        binding.btnReset.setOnClickListener { resetRegistration() }
        binding.btnEditServer.setOnClickListener { showEditServerSheet() }
        binding.btnCopyExample.setOnClickListener { copyExampleUrl() }
        binding.btnTestPush.setOnClickListener { sendTestPush() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RuntimeStatusStore.state.collect { status ->
                    binding.tvConnectionStatus.setText(
                        when (status.webSocketState) {
                            WebSocketRuntimeState.CONNECTED -> R.string.websocket_status_connected
                            WebSocketRuntimeState.CONNECTING -> R.string.websocket_status_connecting
                            WebSocketRuntimeState.RECONNECTING -> R.string.bark_status_long_poll
                            WebSocketRuntimeState.STOPPED -> R.string.websocket_status_stopped
                        }
                    )
                    val statusColor = when (status.webSocketState) {
                        WebSocketRuntimeState.CONNECTED -> R.color.status_connected
                        WebSocketRuntimeState.CONNECTING -> R.color.status_connecting
                        WebSocketRuntimeState.RECONNECTING -> R.color.status_error
                        WebSocketRuntimeState.STOPPED -> R.color.status_disconnected
                    }
                    val resolvedStatusColor = androidx.core.content.ContextCompat.getColor(
                        requireContext(), statusColor
                    )
                    binding.statusDot.setColorFilter(resolvedStatusColor)
                    binding.tvConnectionStatus.setTextColor(resolvedStatusColor)
                    binding.tvLsposedStatus.setText(
                        if (status.systemServerBound) {
                            R.string.system_server_status_bound
                        } else {
                            R.string.system_server_status_unbound
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderState() {
        val ready = prefs.isReady()
        binding.registerContainer.visibility = if (ready) View.GONE else View.VISIBLE
        binding.readyContainer.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            binding.tvServerUrl.text = prefs.serverUrl
            binding.tvDeviceKey.text = prefs.deviceKey
            binding.tvExampleUrl.text = buildExampleUrl()
        }
    }

    private fun buildExampleUrl(): String {
        val base = prefs.serverUrl.trimEnd('/')
        val key = prefs.deviceKey
        val title = URLEncoder.encode("title", "UTF-8")
        val body = URLEncoder.encode("content", "UTF-8")
        return "$base/$key/$title/$body?group=BarkA"
    }

    private fun showEditServerSheet() {
        val context = requireContext()
        val sheetView = layoutInflater.inflate(R.layout.sheet_edit_server, null)
        val input = sheetView.findViewById<EditText>(R.id.etSheetServerUrl).apply {
            setText(prefs.serverUrl)
            setSelection(text?.length ?: 0)
        }
        val error = sheetView.findViewById<TextView>(R.id.tvSheetError)
        val save = sheetView.findViewById<TextView>(R.id.btnSheetSave)
        val sheet = BottomSheetDialog(context)

        sheet.setContentView(sheetView)
        sheet.behavior.skipCollapsed = true
        sheet.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        fun saveServer() {
            val newUrl = input.text?.toString().orEmpty().trim().trimEnd('/')
            val message = when {
                newUrl.isEmpty() -> "请输入服务器地址"
                !newUrl.startsWith("http://") && !newUrl.startsWith("https://") ->
                    "地址必须以 http:// 或 https:// 开头"
                else -> null
            }
            if (message != null) {
                error.text = message
                error.visibility = View.VISIBLE
                input.requestFocus()
                return
            }
            sheet.dismiss()
            reRegister(newUrl)
        }

        save.setOnClickListener { saveServer() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveServer()
                true
            } else {
                false
            }
        }
        sheetView.findViewById<TextView>(R.id.btnSheetCancel).setOnClickListener {
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun reRegister(serverUrl: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { registrar.register(serverUrl) }
            result
                .onSuccess {
                    Toast.makeText(requireContext(), "服务器地址已更新", Toast.LENGTH_SHORT).show()
                    BarkPushService.start(requireContext())
                    renderState()
                }
                .onFailure { Toast.makeText(requireContext(), it.message ?: "注册失败", Toast.LENGTH_LONG).show() }
        }
    }

    private fun copyExampleUrl() {
        val url = buildExampleUrl()
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bark example URL", url))
        Toast.makeText(requireContext(), "已复制示例链接", Toast.LENGTH_SHORT).show()
    }

    private fun sendTestPush() {
        val url = buildExampleUrl()
        binding.btnTestPush.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code}")
                        }
                    }
                }
            }
            binding.btnTestPush.isEnabled = true
            result
                .onSuccess { Toast.makeText(requireContext(), "测试推送已发送", Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(requireContext(), "发送失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun register() {
        val serverUrl = binding.etServerUrl.text?.toString().orEmpty().trim().trimEnd('/')
        if (serverUrl.isEmpty()) {
            showError("请输入服务器地址")
            return
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            showError("地址必须以 http:// 或 https:// 开头")
            return
        }
        setRegistering(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { registrar.register(serverUrl) }
            setRegistering(false)
            result
                .onSuccess {
                    binding.tvRegisterError.visibility = View.GONE
                    Toast.makeText(requireContext(), "注册成功", Toast.LENGTH_SHORT).show()
                    BarkPushService.start(requireContext())
                    renderState()
                }
                .onFailure { showError(it.message ?: "注册失败") }
        }
    }

    private fun resetRegistration() {
        prefs.clearRegistration()
        requireContext().stopService(Intent(requireContext(), BarkPushService::class.java))
        RuntimeStatusStore.updateWebSocket(WebSocketRuntimeState.STOPPED)
        binding.etServerUrl.setText(prefs.serverUrl)
        renderState()
    }

    private fun copyDeviceKey() {
        val key = prefs.deviceKey
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bark device key", key))
        Toast.makeText(requireContext(), "已复制设备 Key", Toast.LENGTH_SHORT).show()
    }

    private fun setRegistering(registering: Boolean) {
        binding.registerProgress.visibility = if (registering) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !registering
        if (registering) binding.tvRegisterError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvRegisterError.text = message
        binding.tvRegisterError.visibility = View.VISIBLE
    }
}
