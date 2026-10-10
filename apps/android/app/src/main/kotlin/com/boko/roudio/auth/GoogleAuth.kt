package com.boko.roudio.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

class GoogleSignInCancelled : Exception()

class NoGoogleAccount : Exception("Add your Google account to this phone in Settings, then try again.")

data class GoogleCredentials(val idToken: String, val driveAuthCode: String?)

/**
 * Google sign-in for 11b: an ID token from Credential Manager, then the offline
 * `drive.file` grant as a one-time code for the backend. If Drive is refused, sign-in still
 * goes ahead and the app says backups aren't connected.
 */
class GoogleAuth(
    private val activity: Activity,
    private val serverClientId: String,
    private val consentLauncher: ActivityResultLauncher<IntentSenderRequest>,
) {
    private var pendingConsent: CompletableDeferred<Intent?>? = null

    /** Call from the activity's result callback for [consentLauncher]. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        pendingConsent?.complete(if (resultCode == Activity.RESULT_OK) data else null)
    }

    suspend fun signIn(): GoogleCredentials {
        check(serverClientId.isNotBlank()) { "Google sign-in isn't set up (roudio.googleServerClientId)." }
        return GoogleCredentials(idToken(), driveAuthCode())
    }

    private suspend fun idToken(): String {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw GoogleSignInCancelled()
        } catch (e: NoCredentialException) {
            throw NoGoogleAccount()
        }
        return GoogleIdTokenCredential.createFrom(result.credential.data).idToken
    }

    private suspend fun driveAuthCode(): String? {
        val client = Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .requestOfflineAccess(serverClientId)
            .build()
        val result = runCatching { client.authorize(request).await() }.getOrNull() ?: return null
        val pendingIntent = result.pendingIntent
        if (!result.hasResolution() || pendingIntent == null) return result.serverAuthCode

        val consent = CompletableDeferred<Intent?>().also { pendingConsent = it }
        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        val data = consent.await() ?: return null
        return runCatching { client.getAuthorizationResultFromIntent(data).serverAuthCode }.getOrNull()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
