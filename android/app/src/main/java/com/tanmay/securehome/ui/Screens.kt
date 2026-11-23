package com.tanmay.securehome.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanmay.securehome.BuildConfig
import com.tanmay.securehome.core.Crypto
import com.tanmay.securehome.data.Recent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tanmay.securehome.core.Received
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

@Composable
fun App(vm: MainViewModel, resumes: Int, hasSms: () -> Boolean, openAppSettings: () -> Unit, scan: () -> Unit) {
    val member by vm.member.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    if (vm.screen != Screen.Home) BackHandler {
        when (vm.screen) {
            Screen.Pair -> vm.screen = Screen.Devices
            Screen.Join -> vm.cancelJoin()
            Screen.GetOtp -> vm.leaveGetOtp()
            else -> vm.screen = Screen.Home
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                member == null && vm.screen == Screen.Join -> JoinScreen(vm)
                member == null -> SetupScreen(vm)
                vm.screen == Screen.Devices -> DevicesScreen(vm)
                vm.screen == Screen.Pair -> PairScreen(vm, scan)
                vm.screen == Screen.GetOtp -> GetOtpScreen(vm)
                else -> HomeScreen(vm, resumes, hasSms, openAppSettings)
            }
        }
    }
}

// ---------- shared bits ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack == null) {
                    Mark(26.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, fontWeight = FontWeight.SemiBold)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        windowInsets = WindowInsets(0, 0, 0, 0), // the Scaffold already pads for the status bar
    )
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(
            if (highlight) 2.dp else 1.dp,
            if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) { Column(Modifier.padding(padding)) { content() } }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun KindBadge(kind: String) {
    val b = badgeFor(kind)
    val label = when (kind) { "delivery" -> "Delivery"; "ride" -> "Ride"; else -> "OTP" }
    Text(
        label,
        modifier = Modifier.background(b.bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
        color = b.fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Busy() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

/** Re-renders every [everyMs] so "2 min ago" and countdowns stay true. */
@Composable
private fun rememberNow(everyMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(everyMs) {
        while (true) {
            delay(everyMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun ago(t: Long, now: Long): String =
    if (now - t < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

// ---------- setup ----------

@Composable
private fun SetupScreen(vm: MainViewModel) {
    var name by remember { mutableStateOf("My phone") }
    var relay by remember { mutableStateOf(BuildConfig.DEFAULT_RELAY) }
    var code by remember { mutableStateOf("") }
    // Builds without a default relay (see android/README.md) ask for it up front.
    var advanced by remember { mutableStateOf(BuildConfig.DEFAULT_RELAY.isBlank()) }
    val relayOk = relay.trim().let { it.startsWith("https://") || (BuildConfig.TEST_HOOKS && it.startsWith("http://")) }

    Column(
        // imePadding must wrap verticalScroll: it shrinks the viewport, not just the content's bottom.
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Mark(56.dp)
        Text("SecureHome", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Muted("A private line for your home. Codes from your SMS reach your laptop and family, encrypted end to end.")
        Spacer(Modifier.height(8.dp))
        Panel {
            Text("Join a household", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Muted("SecureHome is already set up on another phone at home. This phone shows a code for that phone to scan.", small = true)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.startJoin(relay) }, enabled = relayOk, modifier = Modifier.fillMaxWidth()) { Text("Join a household") }
        }
        Panel {
            Text("Start a new household", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Muted("For the first phone only. You'll pair your laptop and other phones from it.", small = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60) }, label = { Text("This phone's name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it }, label = { Text("Founding code") },
                supportingText = { Text("The secret set on your relay. Only needed once, to start the household.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.found(name, relay, code) },
                enabled = !vm.busy && code.isNotBlank() && relayOk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.busy) Busy() else Text("Start household")
            }
        }
        if (advanced) {
            OutlinedTextField(
                value = relay, onValueChange = { relay = it.trim() }, label = { Text("Relay") },
                placeholder = { Text("https://securehome.example.workers.dev") },
                supportingText = { Text("The address of your SecureHome relay (see the project README).") },
                isError = relay.isNotEmpty() && !relayOk,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
        } else {
            TextButton(onClick = { advanced = true }, contentPadding = PaddingValues(0.dp)) { Text("Relay settings") }
        }
    }
}

// ---------- joining (this phone shows the QR) ----------

@Composable
private fun JoinScreen(vm: MainViewModel) {
    val now = rememberNow(1_000)
    var showText by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Bar("Join a household", onBack = vm::cancelJoin)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = vm.join) {
                is JoinStep.Showing -> Panel {
                    Text("Scan this from the other phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted("On the phone that already has SecureHome: Devices → Pair a device → Scan QR code.", small = true)
                    Spacer(Modifier.height(16.dp))
                    QrCode(step.qr, Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    val left = ((step.expiresAt - now) / 1000).coerceAtLeast(0)
                    Muted(
                        "A new code appears in ${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                        small = true, modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (showText) {
                        SelectionContainer {
                            Text(step.qr, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        TextButton(onClick = { copy(ctx, step.qr, "Pairing text") }) { Text("Copy pairing text") }
                    } else {
                        TextButton(onClick = { showText = true }) { Text("Can't scan? Show the pairing text") }
                    }
                }
                is JoinStep.Check -> Panel {
                    Text("Check the code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted("${step.pairedBy} should show this code:", small = true)
                    Text(
                        step.sas,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 2.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                    Muted("If it matches, tap They match on ${step.pairedBy}. This phone will be called “${step.name}”.", small = true)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Busy()
                        Spacer(Modifier.width(8.dp))
                        Muted("Waiting for ${step.pairedBy}…", small = true)
                    }
                }
                null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** A QR code drawn on a white square (quiet zone included), so phones can scan it in dark mode too. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(
        modifier.aspectRatio(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)
            .semantics { contentDescription = "Pairing QR code" },
    ) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

// ---------- home ----------

@Composable
private fun HomeScreen(vm: MainViewModel, resumes: Int, hasSms: () -> Boolean, openAppSettings: () -> Unit) {
    val recent by vm.recent.collectAsState()
    val received by vm.received.collectAsState()
    val me = vm.member.collectAsState().value
    val now = rememberNow()
    var granted by remember { mutableStateOf(hasSms()) }
    var askedOnce by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<Recent?>(null) }
    var autoExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) { granted = hasSms() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasSms()
        askedOnce = true
        if (granted) vm.refresh()
    }
    val others = vm.devices.filter { it.id != me?.id }

    Column(Modifier.fillMaxSize()) {
        Bar("SecureHome") { TextButton(onClick = { vm.screen = Screen.Devices }) { Text("Devices") } }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.removed) item {
                Panel {
                    Text("This phone was removed from the household", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted("It can't share or get codes any more. Forget the household on this phone to start again.", small = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::forgetLocally) { Text("Forget household") }
                }
            }
            if (!granted) item {
                Panel {
                    Text("Let SecureHome see your SMS", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted(
                        "SecureHome looks for one-time codes in new SMS and in the last 2 days of your inbox. Messages without a code " +
                            "are ignored. Codes are encrypted on this phone before they're sent.",
                        small = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (askedOnce) {
                        Muted("If Android didn't ask, allow SMS for SecureHome in Settings → Permissions.", small = true)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = openAppSettings) { Text("Open settings") }
                    } else {
                        Button(onClick = { ask.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) {
                            Text("Allow SMS access")
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = vm::startGetOtp,
                    enabled = !vm.removed,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Get OTP") }
            }
            if (received.isNotEmpty()) {
                item { SectionTitle("Shared with me") }
                items(received.take(5), key = { "r:" + it.key }) { ReceivedCard(it, now, highlight = false) }
            }
            item {
                val autoOn = others.filter { vm.auto[it.id] == true }
                val autoSummary = when {
                    others.isEmpty() -> "No other devices yet"
                    autoOn.isEmpty() -> "Off · tap to choose devices"
                    else -> autoOn.joinToString(", ") { it.name }
                }
                Panel {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { autoExpanded = !autoExpanded },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-share", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                autoSummary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (autoExpanded) "Hide" else "Edit",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (autoExpanded) {
                        Spacer(Modifier.height(8.dp))
                        Muted("Others still get codes you share by hand.", small = true)
                        if (others.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Muted("Pair your laptop or another phone to start sharing.", small = true)
                            TextButton(onClick = { vm.screen = Screen.Devices }, contentPadding = PaddingValues(0.dp)) {
                                Text("Pair a device")
                            }
                        } else {
                            others.forEach { d ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                                        Muted(if (d.kind == "phone") "Phone" else "Laptop", small = true)
                                    }
                                    Switch(checked = vm.auto[d.id] ?: false, onCheckedChange = { vm.setAutoTo(d.id, it) })
                                }
                            }
                        }
                    }
                }
            }
            item { SectionTitle("My recent codes") }
            if (recent.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No codes yet", fontWeight = FontWeight.SemiBold)
                    Muted("OTPs from your SMS in the last 2 days appear here.", small = true)
                }
            }
            items(recent, key = { it.id }) { r -> RecentCard(r, now, canShare = others.isNotEmpty(), onShare = { sharing = r }) }
        }
    }
    sharing?.let { r ->
        ShareDialog(r, others, preselect = others.map { it.id }.toSet(), onDismiss = { sharing = null }) { ids ->
            vm.shareTo(r, ids)
            sharing = null
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

private val CompactCardPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp)

/** Badge (for delivery and ride codes), sender and age on one small line. */
@Composable
private fun CardHeader(kind: String, sender: String, age: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        if (kind != "otp") {
            KindBadge(kind)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            sender, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Muted(age, small = true)
    }
}

/** The code, with its actions on the same line. */
@Composable
private fun CodeRow(code: String, actions: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = 2.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        actions()
        TextButton(onClick = { copy(ctx, code, "Code") }, contentPadding = CompactButtonPadding) { Text("Copy") }
    }
}

@Composable
private fun CardFooter(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (text.isEmpty()) return
    Text(
        text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun SmsDetails(smsFrom: String, body: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 6.dp, end = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    if (smsFrom.isNotEmpty()) Muted("From $smsFrom", small = true)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun RecentCard(r: Recent, now: Long, canShare: Boolean, onShare: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Panel(Modifier.clickable { open = !open }, padding = CompactCardPadding) {
        CardHeader(r.kind, r.sender, ago(r.receivedAt, now))
        CodeRow(r.code) {
            if (canShare && r.status != "sending") {
                TextButton(onClick = onShare, contentPadding = CompactButtonPadding) {
                    Text(if (r.status == "sent") "Share again" else "Share")
                }
            }
        }
        val (statusColor, statusText) = when (r.status) {
            "sending" -> MaterialTheme.colorScheme.onSurfaceVariant to r.detail.ifEmpty { "Sending…" }
            "failed" -> MaterialTheme.colorScheme.error to "Not sent: ${r.detail}"
            "expired" -> MaterialTheme.colorScheme.onSurfaceVariant to "Not sent: too old"
            "kept" -> MaterialTheme.colorScheme.onSurfaceVariant to r.detail.ifEmpty { "Not shared" }
            else -> MaterialTheme.colorScheme.onSurfaceVariant to r.detail
        }
        CardFooter(statusText, statusColor)
        if (open) SmsDetails(r.smsFrom, r.body)
    }
}

@Composable
private fun ReceivedCard(r: Received, now: Long, highlight: Boolean) {
    var open by remember { mutableStateOf(false) }
    Panel(Modifier.clickable { open = !open }, highlight = highlight, padding = CompactCardPadding) {
        CardHeader(r.kind, r.sender, ago(r.storedAt, now))
        CodeRow(r.code)
        CardFooter("Shared by ${r.fromName}")
        if (open && r.body.isNotEmpty()) SmsDetails(r.smsFrom, r.body)
    }
}

@Composable
private fun ShareDialog(
    r: Recent,
    devices: List<Crypto.Device>,
    preselect: Set<String>,
    onDismiss: () -> Unit,
    onShare: (Set<String>) -> Unit,
) {
    var picked by remember { mutableStateOf(preselect) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${r.code}") },
        text = {
            Column {
                Muted("${r.sender} · goes only to the devices you pick, encrypted.", small = true)
                Spacer(Modifier.height(8.dp))
                devices.forEach { d ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { picked = if (d.id in picked) picked - d.id else picked + d.id },
                    ) {
                        Checkbox(checked = d.id in picked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(d.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(picked) }, enabled = picked.isNotEmpty()) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Get OTP ----------

@Composable
private fun GetOtpScreen(vm: MainViewModel) {
    val received by vm.received.collectAsState()
    val now = rememberNow(1_000)
    val live = vm.live ?: return
    val shown = received.filter { it.storedAt >= live.startedAt - GET_OTP_BACK_MS }
    Column(Modifier.fillMaxSize()) {
        Bar("Get OTP", onBack = vm::leaveGetOtp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel {
                    when (live.status) {
                        "connecting", "live" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Busy()
                            Spacer(Modifier.width(10.dp))
                            val left = ((live.until - now) / 1000).coerceAtLeast(0)
                            Column {
                                Text(if (live.status == "live") "Listening for new codes" else "Connecting…", fontWeight = FontWeight.SemiBold)
                                Muted("${left / 60}:${(left % 60).toString().padStart(2, '0')} left · codes appear the moment they're shared", small = true)
                            }
                        }
                        else -> Column {
                            Text(if (live.status == "error") "Lost the connection" else "Stopped listening", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = vm::startGetOtp) { Text("Listen again") }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing in the last 5 minutes", fontWeight = FontWeight.SemiBold)
                    Muted("Ask for the code to be sent now; it will show up here.", small = true)
                }
            }
            items(shown, key = { it.key }) { ReceivedCard(it, now, highlight = it.key in vm.fresh) }
        }
    }
}

private fun copy(ctx: Context, text: String, label: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

// ---------- devices ----------

@Composable
private fun DevicesScreen(vm: MainViewModel) {
    val me = vm.member.collectAsState().value ?: return
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        Bar("Devices", onBack = { vm.screen = Screen.Home })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(vm.devices, key = { it.id }) { d ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.SemiBold)
                            val addedBy = vm.devices.firstOrNull { it.id == d.addedBy }?.name
                            Muted(
                                buildString {
                                    append(if (d.kind == "phone") "Phone" else "Laptop")
                                    if (d.id == me.id) append(" · this phone")
                                    else if (addedBy != null && d.addedBy != d.id) append(" · added by $addedBy")
                                },
                                small = true,
                            )
                        }
                        if (d.id != me.id) {
                            if (confirmRemove == d.id) {
                                TextButton(onClick = { confirmRemove = null }) { Text("Keep") }
                                TextButton(
                                    onClick = { confirmRemove = null; vm.remove(d) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) { Text("Remove") }
                            } else {
                                TextButton(onClick = { confirmRemove = d.id }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = vm::startPairing, modifier = Modifier.fillMaxWidth()) { Text("Pair a device") }
            }
            item {
                OutlinedButton(onClick = vm::sendTest, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    if (vm.busy) Busy() else Text("Send a test code")
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                Muted("Relay: ${me.relay}", small = true)
                Spacer(Modifier.height(8.dp))
                if (confirmLeave) {
                    Muted(
                        "This removes this phone and deletes its keys. " +
                            if (me.founderSigPub == me.keys.sigPubB64) "It started the household, so nobody will be able to pair new devices after this." else "",
                        small = true,
                    )
                    Row {
                        TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                        TextButton(
                            onClick = vm::leave,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Leave household") }
                    }
                } else {
                    TextButton(
                        onClick = { confirmLeave = true },
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Leave household…") }
                }
            }
        }
    }
}

// ---------- pairing ----------

@Composable
private fun PairScreen(vm: MainViewModel, scan: () -> Unit) {
    val relay = vm.member.collectAsState().value?.relay.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Bar("Pair a device", onBack = vm::cancelPairing)
        Column(
            // imePadding wraps the scroll so the keyboard shrinks the viewport (see SetupScreen).
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = vm.pair) {
                PairStep.Start -> PairStart(relay, scan, vm::scanned)
                is PairStep.Naming -> PairName(step.code) { vm.answer(step.code, it) }
                PairStep.Working -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is PairStep.Check -> Panel {
                    Text("Check the code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted("${step.pending.name} should now show this code:", small = true)
                    Text(
                        step.pending.sas,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                    Button(onClick = { vm.confirm(step.pending) }, modifier = Modifier.fillMaxWidth()) { Text("They match") }
                    TextButton(onClick = vm::cancelPairing, modifier = Modifier.fillMaxWidth()) { Text("They don't match") }
                }
                is PairStep.Done -> Panel {
                    Text("${step.name} is paired", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Muted("New codes will reach it the moment they arrive. Try \"Send a test code\" on the Devices screen.", small = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.screen = Screen.Devices }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun PairStart(relay: String, scan: () -> Unit, pasted: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Panel {
        Text("On the other device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted("Open ${relay.removePrefix("https://")} in a browser. It shows a pairing QR code.", small = true)
        Spacer(Modifier.height(12.dp))
        Button(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
    }
    Panel {
        Text("Can't scan?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted("Paste the pairing text shown under the QR code.", small = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("securehome:1:…") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pasted(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun PairName(code: Crypto.PairingCode, done: (String) -> Unit) {
    var name by remember { mutableStateOf(if (code.kind == "laptop") "Work laptop" else "Phone") }
    Panel {
        Text("Name this ${code.kind}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted("Everyone in the household sees this name.", small = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(60) }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { done(name) }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}
