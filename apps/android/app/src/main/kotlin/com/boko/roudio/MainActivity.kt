package com.boko.roudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.boko.roudio.api.models.GoogleSignInRequest
import com.boko.roudio.auth.GoogleAuth
import com.boko.roudio.auth.GoogleSignInCancelled
import com.boko.roudio.data.ApiException
import com.boko.roudio.data.errorDetail
import com.boko.roudio.recorder.RecorderService
import com.boko.roudio.ui.setup.KeepRunningRoute
import com.boko.roudio.ui.setup.PermissionsRoute
import com.boko.roudio.ui.setup.UnlinkPlaudScreen
import com.boko.roudio.ui.setup.WelcomeScreen
import com.boko.roudio.ui.signin.RecorderCheckScreen
import com.boko.roudio.ui.signin.SignInError
import com.boko.roudio.ui.signin.SignInScreen
import com.boko.roudio.ui.theme.RoudioTheme
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Screens, as Navigation 3 keys. Setup follows flow-and-states.md ("First run"). */
@Serializable data object Welcome : NavKey // 11a
@Serializable data object SignIn : NavKey // 11b
@Serializable data object Permissions : NavKey // 11c
@Serializable data object KeepRunning : NavKey // 11d
@Serializable data object UnlinkPlaud : NavKey // 11e
// Temporary: the recorder check screen stands in for 11f–11i (step 6) and Today (step 7).
@Serializable data object FindRecorder : NavKey
@Serializable data object Home : NavKey

class MainActivity : ComponentActivity() {
    private val app get() = application as RoudioApp

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            googleAuth.onConsentResult(result.resultCode, result.data)
        }

    private val googleAuth: GoogleAuth by lazy {
        GoogleAuth(this, BuildConfig.GOOGLE_SERVER_CLIENT_ID, consentLauncher)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RoudioTheme { Roudio() } }
    }

    @Composable
    private fun Roudio() {
        val signedIn by app.session.signedIn.collectAsState()
        val recorder by app.recorder.state.collectAsState()
        val linked = recorder.linked != null
        val start: NavKey = debugScreen() ?: when {
            !signedIn -> Welcome
            linked -> Home
            else -> Permissions
        }
        val backStack = rememberNavBackStack(start)

        // Signing in or out, or linking a recorder, starts a new section; Back doesn't undo it.
        LaunchedEffect(signedIn, linked) {
            val section = backStack.lastOrNull()
            val inRightSection = when {
                !signedIn -> section == Welcome || section == SignIn
                linked -> section == Home
                else -> section != Welcome && section != SignIn && section != Home
            }
            if (!inRightSection && debugScreen() == null) backStack.resetTo(start)
        }
        // The service keeps the link alive with the screen off; start it once there's a recorder.
        LaunchedEffect(linked) {
            if (linked) RecorderService.startIfLinked(this@MainActivity)
        }

        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish() },
            entryProvider = entryProvider {
                entry<Welcome> { WelcomeScreen(onGetStarted = { backStack.add(SignIn) }) }
                entry<SignIn> { SignInRoute(onBack = { backStack.removeAt(backStack.lastIndex) }) }
                entry<Permissions> {
                    PermissionsRoute(
                        onContinue = { backStack.add(KeepRunning) },
                        // First screen after a restart mid-setup: nothing to go back to.
                        onBack = if (backStack.size > 1) ({ backStack.removeAt(backStack.lastIndex) }) else null,
                    )
                }
                entry<KeepRunning> {
                    KeepRunningRoute(
                        onContinue = { backStack.add(UnlinkPlaud) },
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }
                entry<UnlinkPlaud> {
                    UnlinkPlaudScreen(
                        onUnlinked = { backStack.add(FindRecorder) },
                        onNeverLinked = { backStack.add(FindRecorder) },
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }
                entry<FindRecorder> { RecorderCheck() }
                entry<Home> { RecorderCheck() }
            },
        )
    }

    /**
     * Debug builds only: open one screen to check it on the phone without changing any state,
     * e.g. `adb shell am start -n com.boko.roudio/.MainActivity --es screen permissions`.
     */
    private fun debugScreen(): NavKey? {
        if (!BuildConfig.DEBUG) return null
        return when (intent.getStringExtra("screen")) {
            "welcome" -> Welcome
            "signin" -> SignIn
            "permissions" -> Permissions
            "keeprunning" -> KeepRunning
            "unlink" -> UnlinkPlaud
            else -> null
        }
    }

    private fun NavBackStack<NavKey>.resetTo(key: NavKey) {
        clear()
        add(key)
    }

    @Composable
    private fun SignInRoute(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<SignInError?>(null) }
        SignInScreen(busy = busy, error = error, onBack = onBack, onContinue = {
            scope.launch {
                busy = true
                error = null
                error = try {
                    signIn()
                    null
                } catch (e: GoogleSignInCancelled) {
                    null
                } catch (e: ApiException) {
                    if (e.detail?.code == "not_owner") SignInError.NotOwner(e.detail.email)
                    else SignInError.Other(e.message.orEmpty())
                } catch (e: IOException) {
                    SignInError.Other(getString(R.string.no_connection))
                } catch (e: Exception) {
                    SignInError.Other(e.message ?: getString(R.string.sign_in_failed_title))
                }
                busy = false
            }
        })
    }

    private suspend fun signIn() {
        val google = googleAuth.signIn()
        val response = app.api.auth.signInWithGoogle(
            GoogleSignInRequest(
                client = GoogleSignInRequest.Client.ANDROID,
                idToken = google.idToken,
                driveAuthCode = google.driveAuthCode,
            ),
        )
        val tokens = response.body()
        if (!response.isSuccessful || tokens == null) throw ApiException(response.errorDetail(), response.code())
        app.session.store(tokens.accessToken, tokens.accessExpiresAt.toInstant(), tokens.refreshToken)
    }

    private fun signOut() {
        app.appScope.launch {
            runCatching { app.api.auth.signOut() }
            app.session.clear()
            app.plaudTokens.clear()
        }
    }

    @Composable
    private fun RecorderCheck() {
        val scope = rememberCoroutineScope()
        val recorder = app.recorder
        val state by recorder.state.collectAsState()
        RecorderCheckScreen(
            state = state,
            onSearch = recorder::search,
            onLink = recorder::link,
            onRefresh = recorder::refresh,
            onReconnect = recorder::reconnect,
            onDisconnect = { recorder.clearLink() },
            onUnlink = { scope.launch { recorder.unlink() } },
            onSignOut = ::signOut,
        )
    }
}
