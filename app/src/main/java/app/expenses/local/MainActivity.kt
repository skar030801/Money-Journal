package app.expenses.local

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: ExpensesViewModel by viewModels()
    private var fileKind by mutableStateOf("")
    private var pendingFile by mutableStateOf<Uri?>(null)
    private val createFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) pendingFile = result.data?.data
        }
    private val openFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingFile = uri
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ExpensesApp(vm, ::biometric, ::pickFile, pendingFile, fileKind) { pendingFile = null }
        }
    }

    private fun pickFile(kind: String) {
        fileKind = kind
        if (kind == "restore") openFile.launch(arrayOf("application/octet-stream", "*/*"))
        else {
            val mime =
                when (kind) {
                    "csv" -> "text/csv"
                    "pdf" -> "application/pdf"
                    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "application/octet-stream"
                }
            val ext = if (kind == "backup") "expensesbackup" else kind
            createFile.launch(
                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(
                        android.content.Intent.EXTRA_TITLE,
                        "Money-Journal-${vm.state.value.month}.$ext",
                    )
                }
            )
        }
    }

    private fun biometric(enroll: Boolean) {
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            vm.error(
                "Strong biometrics are unavailable. Set up a fingerprint or supported face unlock in Android settings, or use your PIN/password."
            )
            return
        }
        try {
            val cipher = vm.security.biometricCipher(enroll)
            val prompt =
                BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            result.cryptoObject?.cipher?.let { vm.biometricSuccess(it, enroll) }
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            if (
                                code != BiometricPrompt.ERROR_USER_CANCELED &&
                                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            )
                                vm.error(
                                    "Biometric authentication is unavailable. Use your PIN/password."
                                )
                        }
                    },
                )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(if (enroll) "Enable biometric unlock" else "Unlock Money Journal")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Use PIN/password")
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
        } catch (e: Exception) {
            vm.error(
                "Biometric keys are unavailable or changed. Unlock with your PIN/password and enable biometrics again."
            )
        }
    }

    override fun onStop() {
        super.onStop()
        vm.lock()
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
