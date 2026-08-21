package com.cone.agent.remote

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over AndroidX BiometricPrompt for the "passkey"-style unlock of the saved desktop-remote
 * login. Allows a strong/weak biometric OR the device PIN/pattern as a fallback. The encrypted login
 * itself lives in the Android Keystore (see [RemoteViewModel.unlockSaved]); this gates access to it.
 */
object RemoteBiometric {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device can authenticate via biometrics or a device credential. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
        // With DEVICE_CREDENTIAL allowed, a negative button must NOT be set.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
