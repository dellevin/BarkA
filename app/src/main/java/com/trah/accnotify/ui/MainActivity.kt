package com.trah.accnotify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.trah.accnotify.R
import com.trah.accnotify.bark.BarkPrefs
import com.trah.accnotify.bark.BarkPushService
import com.trah.accnotify.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeFragment: BarkHomeFragment
    private lateinit var messagesFragment: MessagesFragment
    private lateinit var activeFragment: Fragment

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startPushServiceIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation(savedInstanceState)
        checkNotificationPermission()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNotificationIntent(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, binding.bottomNavigation.selectedItemId)
    }

    private fun handleNotificationIntent(intent: Intent) {
        if (!intent.getBooleanExtra("open_messages", false)) return
        binding.bottomNavigation.post {
            binding.bottomNavigation.selectedItemId = R.id.nav_messages
        }
        intent.removeExtra("open_messages")
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            homeFragment = BarkHomeFragment()
            messagesFragment = MessagesFragment()
            activeFragment = homeFragment
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, messagesFragment, "messages").hide(messagesFragment)
                .add(R.id.fragmentContainer, homeFragment, "home")
                .commit()
        } else {
            homeFragment = (supportFragmentManager.findFragmentByTag("home") as? BarkHomeFragment)
                ?: BarkHomeFragment()
            messagesFragment = (supportFragmentManager.findFragmentByTag("messages") as? MessagesFragment)
                ?: MessagesFragment()
            activeFragment = if (!homeFragment.isHidden) homeFragment else messagesFragment
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (supportFragmentManager.isStateSaved) return@setOnItemSelectedListener false
            when (item.itemId) {
                R.id.nav_home -> showFragment(homeFragment)
                R.id.nav_messages -> showFragment(messagesFragment)
                else -> false
            }
        }
    }

    private fun showFragment(target: Fragment): Boolean {
        if (activeFragment == target) return true
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
        return true
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPushServiceIfReady()
        }
    }

    private fun startPushServiceIfReady() {
        if (BarkPrefs(this).isReady()) {
            BarkPushService.start(this)
        }
    }

    companion object {
        private const val STATE_SELECTED_TAB = "state_selected_tab"
    }
}
