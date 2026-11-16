package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notifications.ChatNotificationHelper
import com.example.ui.ChatViewModel
import com.example.ui.components.BlockedAlertDialog
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.HelloSirTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        setContent {
            HelloSirTheme {
                val context = LocalContext.current
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                var hasNotificationPermission by remember {
                    mutableStateOf(checkNotificationPermission(context))
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasNotificationPermission = granted
                    if (!granted) {
                        Toast.makeText(
                            context,
                            "Enable notifications in settings to receive live chat alerts.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // Request POST_NOTIFICATIONS on Android 13+ once logged in
                LaunchedEffect(uiState.session) {
                    if (uiState.session != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasNotificationPermission
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Show Android Toast for 403 blocklist or send errors
                LaunchedEffect(Unit) {
                    viewModel.toastEvents.collect { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }

                val blockedMessage = uiState.blockedAlertMessage
                if (blockedMessage != null) {
                    BlockedAlertDialog(
                        message = blockedMessage,
                        onDismiss = viewModel::dismissBlockedAlert
                    )
                }

                if (uiState.session == null) {
                    LoginScreen(
                        isLoading = uiState.isRegistering,
                        errorMessage = uiState.registerError,
                        onRegister = viewModel::register,
                        onClearError = viewModel::clearRegisterError
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        notificationsEnabled = hasNotificationPermission,
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !hasNotificationPermission
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Message notifications are active.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onRefresh = viewModel::refreshFeed,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onSelectTarget = viewModel::selectTarget,
                        onSendMessage = viewModel::sendMessage,
                        onSignOut = viewModel::signOut
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val target = intent?.getStringExtra(ChatNotificationHelper.EXTRA_CHAT_TARGET)
        if (!target.isNullOrBlank()) {
            viewModel.selectTarget(target)
        }
    }

    private fun checkNotificationPermission(context: android.content.Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
