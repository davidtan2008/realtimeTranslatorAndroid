package com.duiyi.android.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.duiyi.android.designsystem.DuiyiTheme
import com.duiyi.android.designsystem.Tokens
import com.duiyi.android.features.conversation.ConversationScreen
import com.duiyi.android.features.history.HistoryScreen
import com.duiyi.android.features.history.TaskDetailScreen
import com.duiyi.android.features.home.HomeScreen
import com.duiyi.android.features.interpretation.InterpretationScreen
import com.duiyi.android.features.meeting.MeetingScreen
import com.duiyi.android.features.models.ModelsScreen
import com.duiyi.android.features.onboarding.OnboardingScreen
import com.duiyi.android.services.permissions.PermissionBridge
import com.duiyi.android.services.permissions.PermissionService
import com.duiyi.android.services.speech.HonorGoogleSpeech
import com.duiyi.android.services.speech.SpeechIntentBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: PreferencesStore
    @Inject lateinit var permissionService: PermissionService
    @Inject lateinit var speechIntentBridge: SpeechIntentBridge

    private var permissionWaiter: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionWaiter?.complete(granted)
        permissionWaiter = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSpeechResult(intent)
        speechIntentBridge.attach(this) { intent ->
            if (!HonorGoogleSpeech.isGoogleDictationTarget(this, intent)) {
                error("not a Google dictation target")
            }
            startActivity(intent)
        }
        val bridge = PermissionBridge(permissionService)
        bridge.attach {
            val existing = permissionWaiter
            if (existing != null) return@attach existing.await()
            val waiter = CompletableDeferred<Boolean>()
            permissionWaiter = waiter
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            waiter.await()
        }
        enableEdgeToEdge()
        val start = if (preferences.hasCompletedOnboarding) "home" else "onboarding"
        setContent {
            DuiyiTheme {
                val nav = rememberNavController()
                NavHost(
                    navController = nav,
                    startDestination = start,
                    modifier = Modifier.fillMaxSize().background(Tokens.canvas),
                ) {
                    composable("onboarding") {
                        OnboardingScreen(hiltViewModel()) {
                            nav.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    }
                    composable("home") {
                        HomeScreen(hiltViewModel(), onOpen = { nav.navigate(it) })
                    }
                    composable("conversation") {
                        ConversationScreen(
                            viewModel = hiltViewModel(),
                            onHome = { nav.popBackStack() },
                            onHistory = { nav.navigate("history") },
                            onModels = { nav.navigate("models") },
                        )
                    }
                    composable("interpretation") {
                        InterpretationScreen(
                            viewModel = hiltViewModel(),
                            onHome = { nav.popBackStack() },
                            onHistory = { nav.navigate("history") },
                            onModels = { nav.navigate("models") },
                        )
                    }
                    composable("meeting") {
                        MeetingScreen(
                            viewModel = hiltViewModel(),
                            onHome = { nav.popBackStack() },
                            onHistory = { nav.navigate("history") },
                            onModels = { nav.navigate("models") },
                        )
                    }
                    composable("models") {
                        ModelsScreen(
                            viewModel = hiltViewModel(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("history") {
                        HistoryScreen(
                            viewModel = hiltViewModel(),
                            onBack = { nav.popBackStack() },
                            onOpen = { nav.navigate("history/$it") },
                        )
                    }
                    composable(
                        "history/{taskId}",
                        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                    ) {
                        TaskDetailScreen(
                            viewModel = hiltViewModel(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpeechResult(intent)
    }

    override fun onStop() {
        speechIntentBridge.onHostStopped()
        super.onStop()
    }

    private fun handleSpeechResult(intent: Intent?) {
        val text = HonorGoogleSpeech.bestText(intent)
        if (text.isBlank()) return
        speechIntentBridge.onSpeechText(text, this)
    }

    override fun onResume() {
        super.onResume()
        speechIntentBridge.onHostResumed()
    }

    override fun onDestroy() {
        speechIntentBridge.detach(this)
        PermissionBridge(permissionService).detach()
        super.onDestroy()
    }
}
