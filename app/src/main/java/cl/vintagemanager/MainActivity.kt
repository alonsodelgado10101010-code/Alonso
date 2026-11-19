package cl.vintagemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.LoadingBox
import cl.vintagemanager.ui.lock.LockScreen
import cl.vintagemanager.ui.navigation.AppNavHost
import cl.vintagemanager.ui.theme.VintageTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ROUTE = "nav_route"
    }

    @Inject lateinit var repository: VintageRepository

    private var pendingRoute by mutableStateOf<String?>(null)
    private var lockState by mutableStateOf<LockState>(LockState.Checking)

    private sealed interface LockState {
        data object Checking : LockState
        data class Locked(val biometric: Boolean) : LockState
        data object Unlocked : LockState
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* la app funciona igual sin permiso */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        requestNotificationPermissionIfNeeded()

        lifecycleScope.launch {
            val settings = repository.query { getSettings() }.getOrNull()
            lockState = if (settings != null && settings.lockEnabled && settings.pinHash != null) {
                LockState.Locked(settings.biometricEnabled && biometricAvailable())
            } else {
                LockState.Unlocked
            }
        }

        setContent {
            VintageTheme {
                when (val state = lockState) {
                    LockState.Checking -> LoadingBox()
                    is LockState.Locked -> LockScreen(
                        biometricAvailable = state.biometric,
                        onBiometric = ::showBiometricPrompt,
                        verifyPin = { pin -> repository.query { verifyPin(pin) }.getOrDefault(false) },
                        onUnlocked = { lockState = LockState.Unlocked },
                    )
                    LockState.Unlocked -> AppNavHost(
                        pendingRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lockState = LockState.Unlocked
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear Vintage Manager")
            .setSubtitle("Usa tu huella")
            .setNegativeButtonText("Usar PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

}
