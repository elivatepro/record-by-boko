package com.boko.roudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.boko.roudio.system.PhoneSettings
import com.boko.roudio.ui.setup.FindRecorderRoute
import com.boko.roudio.ui.setup.KeepRunningRoute
import com.boko.roudio.ui.setup.LinkedRoute
import com.boko.roudio.ui.setup.LinkingRoute
import com.boko.roudio.ui.setup.PermissionRefusedRoute
import com.boko.roudio.ui.setup.PermissionsRoute
import com.boko.roudio.ui.setup.UnlinkPlaudScreen
import com.boko.roudio.ui.setup.WelcomeScreen
import com.boko.roudio.ui.signin.SignInError
import com.boko.roudio.ui.signin.SignInScreen
import com.boko.roudio.ui.recorder.RecorderScreen
import com.boko.roudio.ui.recorder.rememberNow
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.today.TodayModel
import com.boko.roudio.ui.today.TodayScreen
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Screens, as Navigation 3 keys. Setup follows flow-and-states.md ("First run"). */
@Serializable data object Welcome : NavKey // 11a
@Serializable data object SignIn : NavKey // 11b
@Serializable data object Permissions : NavKey // 11c
@Serializable data object KeepRunning : NavKey // 11d
@Serializable data object UnlinkPlaud : NavKey // 11e
@Serializable data object PermissionRefused : NavKey // 12c
@Serializable data object FindRecorder : NavKey // 11f, 11g (12a, 12b, 12c over them)
@Serializable data object Linking : NavKey // 11h (12b, 12d, 12e over it)
@Serializable data object Linked : NavKey // 11i
@Serializable data object Home : NavKey // 01 Today
@Serializable data object RecorderDetails : NavKey // 13a, 13b

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
        // Linking finishes on 11h/11i, which belong to both sides.
        LaunchedEffect(signedIn, linked) {
            val section = backStack.lastOrNull()
            val inRightSection = when {
                !signedIn -> section == Welcome || section == SignIn
                linked -> section in setOf(Home, RecorderDetails, Linking, Linked)
                else -> section !in setOf(Welcome, SignIn, Home, RecorderDetails, Linked)
            }
            if (!inRightSection && debugScreen() == null) backStack.resetTo(start)
        }
        // The service keeps the link alive with the screen off; start it once there's a recorder.
        LaunchedEffect(linked) {
            if (linked) RecorderService.startIfLinked(this@MainActivity)
        }

        val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish() }
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish() },
            entryProvider = entryProvider {
                entry<Welcome> { WelcomeScreen(onGetStarted = { backStack.add(SignIn) }) }
                entry<SignIn> { SignInRoute(onBack = { backStack.removeAt(backStack.lastIndex) }) }
                entry<Permissions> {
                    PermissionsRoute(
                        onContinue = { backStack.add(KeepRunning) },
                        onNearbyRefused = { backStack.add(PermissionRefused) },
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
                entry<PermissionRefused> {
                    PermissionRefusedRoute(onGranted = pop, onBack = pop)
                }
                entry<FindRecorder> {
                    FindRecorderRoute(
                        recorder = app.recorder,
                        onLinking = { backStack.add(Linking) },
                        onUnlinkHelp = { backStack.popTo(UnlinkPlaud) },
                        onBack = pop,
                    )
                }
                entry<Linking> {
                    LinkingRoute(
                        recorder = app.recorder,
                        onLinked = { backStack.resetTo(Linked) },
                        onBack = pop,
                    )
                }
                entry<Linked> { LinkedRoute(app.recorder, onDone = { backStack.resetTo(Home) }) }
                entry<Home> { Today(onRecorder = { backStack.add(RecorderDetails) }) }
                entry<RecorderDetails> {
                    val scope = rememberCoroutineScope()
                    val state by app.recorder.state.collectAsState()
                    val turnOnBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
                    val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
                    val progress by app.transfers.progress.collectAsState()
                    val stopped by app.transfers.stopped.collectAsState()
                    RecorderScreen(
                        state = state,
                        now = rememberNow(),
                        recordings = recordings,
                        progress = progress,
                        stopped = stopped,
                        onReconnect = app.recorder::reconnect,
                        onTurnOnBluetooth = { turnOnBluetooth.launch(PhoneSettings.enableBluetooth(this@MainActivity)) },
                        onUnlink = { scope.launch { app.recorder.unlink() } },
                        onBack = pop,
                    )
                }
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
            "refused" -> PermissionRefused
            "find" -> FindRecorder
            "recorder" -> RecorderDetails
            else -> null
        }
    }

    private fun NavBackStack<NavKey>.resetTo(key: NavKey) {
        clear()
        add(key)
    }

    /** Back to [key] if it's on the stack (12a "It might still be linked"); else open it. */
    private fun NavBackStack<NavKey>.popTo(key: NavKey) {
        val at = lastIndexOf(key)
        if (at < 0) {
            add(key)
        } else {
            while (lastIndex > at) removeAt(lastIndex)
        }
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

    /** 01 Today with live data. A past day isn't a new screen: Back returns to today. */
    @Composable
    private fun Today(onRecorder: () -> Unit) {
        val recorder by app.recorder.state.collectAsState()
        val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
        val progress by app.transfers.progress.collectAsState()
        val online by app.network.online.collectAsState()
        val now = rememberNow()
        var daysBack by rememberSaveable { mutableIntStateOf(0) }
        BackHandler(enabled = daysBack > 0) { daysBack = 0 }
        val offset = ZoneId.systemDefault().rules.getOffset(now)
        val day = now.atOffset(offset).toLocalDate().minusDays(daysBack.toLong())
        val model = remember(recordings, recorder, progress, day, now) {
            TodayModel.build(recordings, recorder, progress, day, now, offset)
        }
        TodayScreen(
            model = model,
            recorder = recorder,
            online = online,
            now = now,
            onRecorder = onRecorder,
            onPreviousDay = { daysBack++ },
            onBackToToday = { daysBack = 0 },
        )
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
}
