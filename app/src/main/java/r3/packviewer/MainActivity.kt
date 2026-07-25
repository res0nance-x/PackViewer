package r3.packviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import r3.encryption.EncryptedSource
import r3.hash.hash256
import r3.math.EncryptedSequence
import r3.pack.BinaryPack
import r3.packviewer.ui.theme.PackViewerTheme
import r3.pke.Password256
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
	private var intentUri = mutableStateOf<Uri?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		handleIntent(intent)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
			}
		}

		setContent {
			PackViewerTheme {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.background
				) {
					Box(modifier = Modifier.safeDrawingPadding()) {
						PackViewerApp(intentUri.value)
					}
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent?) {
		if (intent?.action == Intent.ACTION_VIEW) {
			intentUri.value = intent.data
		}

		intent?.data?.let { uri ->
			if (uri.scheme == "content") {
				val cursor = contentResolver.query(uri, null, null, null, null)
				cursor?.use {
					if (it.moveToFirst()) {
						val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
						if (nameIndex != -1) {
							val fileName = it.getString(nameIndex)
							if (!fileName.endsWith(".pack", ignoreCase = true) &&
								!fileName.endsWith(".epack", ignoreCase = true)
							) {
								// It's an octet-stream, but not a .pack file.
								// Show a Toast to the user and exit gracefully.
								finish()
								return
							}
						}
					}
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	fun PackViewerApp(initialUri: Uri? = null) {
		val context = LocalContext.current
		var showPasswordDialog by remember { mutableStateOf(false) }
		var pendingUri by remember { mutableStateOf<Uri?>(null) }
		var errorMessage by remember { mutableStateOf<String?>(null) }
		val coroutineScope = rememberCoroutineScope()
		
		var customView by remember { mutableStateOf<View?>(null) }
		var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
		
		val listeningPort = PackHolder.listeningPort
		val currentUrl = if (listeningPort != 0) "http://localhost:$listeningPort/" else null

		fun processUri(uri: Uri) {
			val fileName = getFileName(uri)
			if (fileName.endsWith(".epack", ignoreCase = true)) {
				pendingUri = uri
				showPasswordDialog = true
			} else {
				coroutineScope.launch {
					try {
						loadPack(uri, null)
					} catch (e: Exception) {
						errorMessage = e.message
					}
				}
			}
		}

		LaunchedEffect(initialUri) {
			if (initialUri != null) {
				processUri(initialUri)
			}
		}
		val launcher = rememberLauncherForActivityResult(
			contract = ActivityResultContracts.OpenDocument()
		) { uri: Uri? ->
			if (uri != null) {
				processUri(uri)
			}
		}

		if (showPasswordDialog) {
			var password by remember { mutableStateOf("") }
			AlertDialog(
				onDismissRequest = {
					showPasswordDialog = false
					pendingUri = null
				},
				title = { Text("Enter Password") },
				text = {
					OutlinedTextField(
						value = password,
						onValueChange = { password = it },
						label = { Text("Password") },
						singleLine = true
					)
				},
				confirmButton = {
					TextButton(onClick = {
						val uri = pendingUri
						showPasswordDialog = false
						pendingUri = null
						if (uri != null) {
							coroutineScope.launch {
								try {
									loadPack(uri, password)
								} catch (e: Exception) {
									errorMessage = e.message
								}
							}
						}
					}) {
						Text("OK")
					}
				},
				dismissButton = {
					TextButton(onClick = {
						showPasswordDialog = false
						pendingUri = null
					}) {
						Text("Cancel")
					}
				}
			)
		}

		if (currentUrl != null) {
			BackHandler {
				if (customView != null) {
					customViewCallback?.onCustomViewHidden()
				} else {
					val intent = Intent(context, MediaPlaybackService::class.java)
					intent.action = "STOP"
					context.startService(intent)
				}
			}
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black)
			) {
				AndroidView(
					factory = { context ->
						WebView(context).apply {
							layoutParams = ViewGroup.LayoutParams(
								ViewGroup.LayoutParams.MATCH_PARENT,
								ViewGroup.LayoutParams.MATCH_PARENT
							)
							setBackgroundColor(AndroidColor.BLACK)
							settings.javaScriptEnabled = true
							settings.allowFileAccess = true
							settings.domStorageEnabled = true
							settings.mediaPlaybackRequiresUserGesture = false
							webViewClient = WebViewClient()
							webChromeClient = object : WebChromeClient() {
								override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
									customView = view
									customViewCallback = callback
								}

								override fun onHideCustomView() {
									customView = null
									customViewCallback = null
								}
							}
						}
					},
					update = { webView ->
						if (webView.url != currentUrl) {
							webView.loadUrl(currentUrl)
						}
					},
					modifier = Modifier.fillMaxSize()
				)

				if (customView != null) {
					AndroidView(
						factory = { ctx ->
							FrameLayout(ctx).apply {
								setBackgroundColor(AndroidColor.BLACK)
								(customView?.parent as? ViewGroup)?.removeView(customView)
								addView(customView)
							}
						},
						modifier = Modifier.fillMaxSize()
					)
				}
			}
		} else {
			Column(
				modifier = Modifier.fillMaxSize(),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
					Text("Select Pack File")
				}
				if (errorMessage != null) {
					Spacer(modifier = Modifier.height(16.dp))
					Text(text = "Error: $errorMessage", color = MaterialTheme.colorScheme.error)
				}
			}
		}
	}

	private suspend fun loadPack(uri: Uri, passwordStr: String?) {
		val pack = withContext(Dispatchers.IO) {
			val source = UriSource(contentResolver, uri)
			val fileName = getFileName(uri)
			if (fileName.endsWith(".epack", ignoreCase = true) && !passwordStr.isNullOrEmpty()) {
				val pass = Password256(passwordStr.toByteArray().hash256())
				val sequence = EncryptedSequence.createSequence(pass)
				val encryptedSrc = EncryptedSource(sequence, source)
				BinaryPack(encryptedSrc)
			} else {
				BinaryPack(source)
			}
		}

		PackHolder.currentPack = pack
		val intent = Intent(this, MediaPlaybackService::class.java)
		startForegroundService(intent)
	}

	@SuppressLint("Range")
	private fun getFileName(uri: Uri): String {
		var result: String? = null
		if (uri.scheme == "content") {
			contentResolver.query(uri, null, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
				}
			}
		}
		if (result == null) {
			result = uri.path
			val cut = result?.lastIndexOf('/')
			if (cut != null && cut != -1) {
				result = result.substring(cut + 1)
			}
		}
		return result ?: "unknown"
	}
}