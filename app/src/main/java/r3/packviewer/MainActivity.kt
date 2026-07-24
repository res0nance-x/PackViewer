package r3.packviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import r3.content.BinaryContent
import r3.encryption.EncryptedSource
import r3.hash.hash256
import r3.http.ContentHandler
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.math.EncryptedSequence
import r3.pack.BinaryPack
import r3.pack.Pack
import r3.packviewer.ui.theme.PackViewerTheme
import r3.pke.Password256
import java.io.File

class MainActivity : ComponentActivity() {
	private var webServer: WebServer? = null
	private var intentUri = mutableStateOf<Uri?>(null)
	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		handleIntent(intent)

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
		webServer?.stop()
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	fun PackViewerApp(initialUri: Uri? = null) {
		var currentUrl by remember { mutableStateOf<String?>(null) }
		var showPasswordDialog by remember { mutableStateOf(false) }
		var pendingUri by remember { mutableStateOf<Uri?>(null) }
		var errorMessage by remember { mutableStateOf<String?>(null) }
		val coroutineScope = rememberCoroutineScope()
		fun processUri(uri: Uri) {
			val fileName = getFileName(uri)
			if (fileName.endsWith(".epack", ignoreCase = true)) {
				pendingUri = uri
				showPasswordDialog = true
			} else {
				coroutineScope.launch {
					try {
						loadPack(uri, null) { url ->
							currentUrl = url
						}
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
									loadPack(uri, password) { url ->
										currentUrl = url
									}
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
				currentUrl = null
				webServer?.stop()
				webServer = null
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
							webChromeClient = WebChromeClient()
						}
					},
					update = { webView ->
						webView.loadUrl(currentUrl!!)
					},
					modifier = Modifier.fillMaxSize()
				)
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

	private suspend fun loadPack(uri: Uri, passwordStr: String?, onUrlReady: (String) -> Unit) {
		withContext(Dispatchers.IO) {
			val source = UriSource(contentResolver, uri)
			val fileName = getFileName(uri)
			val pack: Pack = if (fileName.endsWith(".epack", ignoreCase = true) && !passwordStr.isNullOrEmpty()) {
				val pass = Password256(passwordStr.toByteArray().hash256())
				val sequence = EncryptedSequence.createSequence(pass)
				val encryptedSrc = EncryptedSource(sequence, source)
				BinaryPack(encryptedSrc)
			} else {
				BinaryPack(source)
			}

			webServer?.stop()
			val tmpDir = File(cacheDir, "server_tmp")
			tmpDir.mkdirs()
			val ws = WebServer(null, 0, tmpDir)
			ws.handlers.add(HandlerFactory.createLogRouter())
			ws.handlers.add(HandlerFactory.createWelcomeHandler())
			ws.handlers.add(HandlerFactory.createPackHandler(pack))
			ws.handlers.add(ContentHandler { header, _ ->
				if (header.optString("path") == "/index.html") {
					try {
						assets.open("index.html").use { inputStream ->
							val bytes = inputStream.readBytes()
							BinaryContent(bytes, "index.html", "html")
						}
					} catch (e: Exception) {
						null
					}
				} else null
			})

			ws.start(0, false)
			webServer = ws

			withContext(Dispatchers.Main) {
				onUrlReady("http://localhost:${ws.listeningPort}/")
			}
		}
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