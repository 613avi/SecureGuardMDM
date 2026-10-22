package com.secureguard.mdm.kiosk.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.R
import com.secureguard.mdm.kiosk.vm.KioskViewModel
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SingleWebsiteKioskScreen(
    url: String,
    whitelist: String = "",
    viewModel: KioskViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSettingsChoiceDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val uiState by viewModel.uiState.collectAsState()

    // טעינת האייקון בצורה בטוחה (תומך ב-Adaptive Icons)
    val appIconPainter = rememberDrawablePainter(
        drawable = remember(context) {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        }
    )

    LaunchedEffect(viewModel.sideEffect) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is KioskViewModel.SideEffect.NavigateToKioskManagement -> {
                    val intent = Intent(context, com.secureguard.mdm.MainActivity::class.java).apply {
                        putExtra("start_destination", "kiosk_management")
                        putExtra("is_from_kiosk", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(intent)
                }
                is KioskViewModel.SideEffect.NavigateToSettings -> {
                    val intent = Intent(context, com.secureguard.mdm.MainActivity::class.java).apply {
                        putExtra("start_destination", "dashboard")
                        putExtra("is_from_kiosk", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(intent)
                }
                else -> {}
            }
        }
    }

    // חסימת כפתור Back (חוץ מניווט אחורה ב-WebView)
    var webView: WebView? by remember { mutableStateOf(null) }
    
    // חישוב הדומיינים המורשים: האתר הראשי + הרשימה הלבנה
    val allowedHosts = remember(url, whitelist) {
        (listOf(url) + whitelist.split('\n', ',', ' ', ';'))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val formattedUrl = if (entry.startsWith("http")) entry else "https://$entry"
                Uri.parse(formattedUrl).host?.lowercase()?.removePrefix("www.")?.removePrefix("*.")?.takeIf { it.isNotEmpty() }
            }
            .toSet()
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleWebsiteTopBar(
            batteryLevel = uiState.batteryLevel,
            appIconPainter = appIconPainter,
            onWifiClick = { openWifiSettings(context) },
            onAdminClick = { showPasswordDialog = true }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
        // WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val newUrl = request?.url?.toString() ?: return false
                            val newHost = request.url.host?.lowercase() ?: ""
                            
                            // אם המארח החדש ריק (למשל javascript:) או שהוא שייך לאחד הדומיינים המורשים (כולל תת-דומיינים), נאפשר
                            if (newHost.isEmpty() || allowedHosts.any { newHost == it || newHost.endsWith(".$it") }) {
                                return false
                            }
                            
                            // אחרת, נחסום את הניווט
                            android.util.Log.w("KioskWebView", "Blocked navigation to: $newUrl")
                            return true
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(if (url.startsWith("http")) url else "https://$url")
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary
            )
        }
        }

        // Dialogs
        if (showPasswordDialog) {
            PasswordDialog(
                onDismiss = { showPasswordDialog = false },
                onConfirm = { password ->
                    scope.launch {
                        if (viewModel.verifyPasswordResult(password)) {
                            showPasswordDialog = false
                            showSettingsChoiceDialog = true
                        } else {
                            // Toast handles in ViewModel or here
                        }
                    }
                }
            )
        }

        if (showSettingsChoiceDialog) {
            SettingsChoiceDialog(
                onDismiss = { showSettingsChoiceDialog = false },
                onChoice = { choice ->
                    showSettingsChoiceDialog = false
                    if (choice == "kiosk") {
                        viewModel.onSettingsClick()
                    } else {
                        viewModel.onDashboardClick()
                    }
                }
            )
        }
    }
}

@Composable
private fun SingleWebsiteTopBar(
    batteryLevel: Int,
    appIconPainter: androidx.compose.ui.graphics.painter.Painter,
    onWifiClick: () -> Unit,
    onAdminClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // כפתור ניהול (מוגן בסיסמה)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAdminClick),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = appIconPainter,
                    contentDescription = stringResource(id = R.string.kiosk_content_desc_admin),
                    modifier = Modifier.size(28.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // כפתור הגדרות Wi-Fi
            IconButton(onClick = onWifiClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = stringResource(id = R.string.kiosk_desc_wifi_settings),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // מצב סוללה
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BatteryStd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "$batteryLevel%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// Reusing dialogs from KioskScreen.kt (we need to make them public or copy them)
// For now, I'll copy the minimal versions to ensure it works

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.kiosk_dialog_admin_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.kiosk_dialog_admin_message))
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(id = R.string.kiosk_dialog_admin_label_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun SettingsChoiceDialog(
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.kiosk_dialog_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(id = R.string.kiosk_dialog_choice_message))
                
                Button(
                    onClick = { onChoice("kiosk") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(id = R.string.kiosk_dialog_choice_button_kiosk))
                }
                
                OutlinedButton(
                    onClick = { onChoice("dashboard") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(id = R.string.kiosk_dialog_choice_button_dashboard))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}
