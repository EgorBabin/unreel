package app.unreel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unreel.ui.UnreelColors
import app.unreel.ui.UnreelTheme

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            UnreelTheme {
                Surface(Modifier.fillMaxSize(), color = UnreelColors.Bg) {
                    HomeScreen(
                        prefs = prefs,
                        serviceEnabled = serviceEnabled,
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenAppInfo = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                        onOpenInstagram = {
                            packageManager.getLaunchIntentForPackage(ReelBlockerService.INSTAGRAM)?.let(::startActivity)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled(this)
    }

    companion object {
        fun isServiceEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, ReelBlockerService::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/* ─────────────────────────── screen ─────────────────────────── */

/** Immutable copy of the preferences, so Compose knows when to redraw. */
private data class PrefsState(
    val enabled: Boolean,
    val blockedCount: Int,
    val showToasts: Boolean,
    val manualUsername: String,
    val detectedUsername: String,
    val own: String?,
) {
    constructor(p: Prefs) : this(p.enabled, p.blockedCount, p.showToasts, p.manualUsername, p.detectedUsername, p.ownUsername)
}

@Composable
private fun HomeScreen(
    prefs: Prefs,
    serviceEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenInstagram: () -> Unit,
) {
    // Recompose whenever the service (or the UI) changes a preference.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.raw.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val state = remember(version) { PrefsState(prefs) }
    val enabled = state.enabled
    val own = state.own

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(UnreelColors.Violet.copy(alpha = 0.22f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 1100f,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            if (!serviceEnabled) {
                SetupCard(onOpenAccessibility, onOpenAppInfo)
            } else {
                StatusCard(enabled) { prefs.enabled = it }
            }

            StatRow(state.blockedCount)

            AccountCard(prefs, state)

            Card(title = "Behaviour") {
                ToggleRow(
                    title = "Show a message when a reel is blocked",
                    checked = state.showToasts,
                    onChange = { prefs.showToasts = it },
                )
            }

            HowItWorksCard()

            OutlinedButton(
                onClick = onOpenInstagram,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Open Instagram") }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = UnreelColors.Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "No internet permission · nothing leaves your phone · v${BuildConfig.VERSION_NAME}",
                    color = UnreelColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(UnreelColors.Gradient),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Unreel", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = UnreelColors.Text)
            Text("Block Reels. Keep yours.", fontSize = 14.sp, color = UnreelColors.Muted)
        }
    }
}

@Composable
private fun SetupCard(onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(UnreelColors.Warning.copy(alpha = 0.08f))
            .border(1.dp, UnreelColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = UnreelColors.Warning)
            Spacer(Modifier.width(10.dp))
            Text("One step left", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = UnreelColors.Text)
        }
        Text(
            "Turn on Unreel in Accessibility settings. This lets Unreel see when Instagram opens a reel " +
                "and press Back for you.",
            color = UnreelColors.Muted,
            fontSize = 14.sp,
        )
        Button(
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UnreelColors.Violet, contentColor = UnreelColors.Bg),
        ) { Text("Open Accessibility settings", fontWeight = FontWeight.Bold) }
        Text(
            "Switch greyed out? Android blocks this for apps installed from a file. Open App info → ⋮ menu → " +
                "“Allow restricted settings”, then try again.",
            color = UnreelColors.Muted,
            fontSize = 12.sp,
        )
        OutlinedButton(
            onClick = onOpenAppInfo,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Open App info") }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val accent = if (enabled) UnreelColors.Cyan else UnreelColors.Danger
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(UnreelColors.Violet.copy(alpha = 0.2f), UnreelColors.Cyan.copy(alpha = 0.12f)))
                } else {
                    Brush.linearGradient(listOf(UnreelColors.Danger.copy(alpha = 0.1f), UnreelColors.Danger.copy(alpha = 0.05f)))
                },
            )
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (enabled) "Protection is on" else "Protection is paused",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = UnreelColors.Text,
            )
            Text(
                if (enabled) "Other people's reels get closed" else "Reels are not being blocked",
                color = UnreelColors.Muted,
                fontSize = 13.sp,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle, colors = switchColors())
    }
}

@Composable
private fun StatRow(count: Int) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            "%,d".format(count),
            style = TextStyle(brush = UnreelColors.Gradient, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold),
        )
        Spacer(Modifier.width(10.dp))
        Text("reels blocked so far", color = UnreelColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun AccountCard(prefs: Prefs, state: PrefsState) {
    val own = state.own
    var text by remember { mutableStateOf(state.manualUsername) }
    val valid = text.isBlank() || Prefs.isValid(Prefs.normalize(text))

    Card(title = "Your Instagram account") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(UnreelColors.Gradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    own?.first()?.uppercase() ?: "?",
                    color = UnreelColors.Bg,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    own?.let { "@$it" } ?: "Not detected yet",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = UnreelColors.Text,
                )
                Text(
                    when {
                        own == null -> "Open your profile tab in Instagram once"
                        Prefs.isValid(state.manualUsername) -> "Set manually"
                        else -> "Detected from your profile"
                    },
                    color = UnreelColors.Muted,
                    fontSize = 13.sp,
                )
            }
            if (state.detectedUsername.isNotEmpty()) {
                IconButton(onClick = { prefs.detectedUsername = "" }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Detect again", tint = UnreelColors.Muted)
                }
            }
        }

        if (own == null) {
            Text(
                "Until Unreel knows your username, every reel is blocked — including yours.",
                color = UnreelColors.Warning,
                fontSize = 13.sp,
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                val v = Prefs.normalize(it)
                if (v.isEmpty() || Prefs.isValid(v)) prefs.manualUsername = v
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = !valid,
            label = { Text("Username override (optional)") },
            prefix = { Text("@", color = UnreelColors.Muted) },
            placeholder = { Text("auto-detect") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = UnreelColors.Violet,
                unfocusedBorderColor = UnreelColors.Border,
                cursorColor = UnreelColors.Cyan,
            ),
        )
        Text(
            "Your own reels stay fully watchable and editable.",
            color = UnreelColors.Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun HowItWorksCard() {
    Card(title = "How it works") {
        Step("1", "Open the Reels tab, or tap a reel anywhere — feed, Explore, DMs.")
        Step("2", "Unreel reads who posted it. If it isn't you, it closes the reel instantly.")
        Step("3", "Reels you posted (or co-posted) stay open: watch, edit, check insights.")
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(UnreelColors.Violet.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = UnreelColors.Violet, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Spacer(Modifier.width(12.dp))
        Text(text, color = UnreelColors.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/* ─────────────────────────── building blocks ─────────────────────────── */

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(UnreelColors.Surface)
            .border(1.dp, UnreelColors.Border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title.uppercase(),
            color = UnreelColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        content()
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = UnreelColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(0.dp).width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = UnreelColors.Violet,
    uncheckedThumbColor = UnreelColors.Muted,
    uncheckedTrackColor = Color(0x1FFFFFFF),
    uncheckedBorderColor = Color.Transparent,
)
