package com.boko.roudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.boko.roudio.api.models.GoogleSignInRequest
import com.boko.roudio.auth.GoogleAuth
import com.boko.roudio.auth.GoogleSignInCancelled
import com.boko.roudio.data.ApiException
import com.boko.roudio.data.errorDetail
import com.boko.roudio.ui.signin.SignInError
import com.boko.roudio.ui.signin.RecorderCheckScreen
import com.boko.roudio.ui.signin.SignInScreen
import com.boko.roudio.ui.theme.RoudioTheme
import java.io.IOException
import kotlinx.coroutines.launch

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
        setContent {
            RoudioTheme {
                val signedIn by app.session.signedIn.collectAsState()
                if (signedIn) SignedIn() else SignIn()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SignIn() {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<SignInError?>(null) }
        SignInScreen(busy = busy, error = error, onContinue = {
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

    @androidx.compose.runtime.Composable
    private fun SignedIn() {
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
            onSignOut = {
                scope.launch {
                    runCatching { app.api.auth.signOut() }
                    app.session.clear()
                    app.plaudTokens.clear()
                }
            },
        )
    }
}
