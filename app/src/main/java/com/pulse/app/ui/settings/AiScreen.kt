package com.pulse.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pulse.app.PulseApp
import com.pulse.app.data.AiConfig
import com.pulse.app.data.EmailConfig
import com.pulse.app.data.GeminiClient
import com.pulse.app.data.LlmMessage
import com.pulse.app.data.LlmResult
import com.pulse.app.service.EmailDigest
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.labelled
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as PulseApp).graph
    val settings = graph.aiSettings
    val scope = rememberCoroutineScope()
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var emailStatus by remember { mutableStateOf<String?>(null) }
    AiContent(
        initialAi = remember { settings.load() },
        initialEmail = remember { settings.loadEmail() },
        aiStatus = aiStatus,
        emailStatus = emailStatus,
        onBack = onBack,
        onSaveAi = settings::save,
        onTestAi = { config ->
            aiStatus = "Testing…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    GeminiClient(config.apiKey).chat("Reply briefly.", listOf(LlmMessage("user", "Reply with the single word OK.")))
                }
                aiStatus = when (result) {
                    is LlmResult.Reply -> "Connected. Gemini answered."
                    is LlmResult.Failed -> result.reason
                }
            }
        },
        smartOn = remember { settings.isSmartReading() },
        onSmart = settings::setSmartReading,
        onSaveEmail = { config ->
            settings.saveEmail(config)
            EmailDigest.schedule(context.applicationContext, config.weekly && config.isSet)
        },
        onSendEmail = { config ->
            settings.saveEmail(config)
            emailStatus = "Sending…"
            scope.launch {
                val failure = withContext(Dispatchers.IO) { EmailDigest.send(graph) }
                emailStatus = failure ?: "Sent. Check your inbox."
            }
        },
    )
}

/** Plain data in, callbacks out, so tests can render it directly. */
@Composable
fun AiContent(
    initialAi: AiConfig,
    initialEmail: EmailConfig,
    aiStatus: String?,
    emailStatus: String?,
    onBack: () -> Unit,
    onSaveAi: (AiConfig) -> Unit,
    onTestAi: (AiConfig) -> Unit,
    onSaveEmail: (EmailConfig) -> Unit,
    onSendEmail: (EmailConfig) -> Unit,
    smartOn: Boolean = false,
    onSmart: (Boolean) -> Unit = {},
) {
    var key by remember { mutableStateOf(initialAi.apiKey) }
    var share by remember { mutableStateOf(initialAi.shareTotals) }
    var smart by remember { mutableStateOf(smartOn) }
    var resend by remember { mutableStateOf(initialEmail.resendKey) }
    var to by remember { mutableStateOf(initialEmail.toAddress) }
    var weekly by remember { mutableStateOf(initialEmail.weekly) }
    val ai = AiConfig(key, share)
    val email = EmailConfig(resend, to, weekly)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("AI and email", onBack)
        Surface(shape = MaterialTheme.shapes.large, color = Lime, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gemini", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text(
                    "Answers general questions you type. Get a free key at aistudio.google.com. Notifications and records " +
                        "stay on this phone, unless you turn on Smart reading below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.copy(alpha = 0.78f),
                )
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSaveAi(ai) }, modifier = Modifier.weight(1f)) { Text("Save") }
            TextButton(onClick = { onSaveAi(ai); onTestAi(ai) }, enabled = ai.isSet) { Text("Save and test") }
        }
        if (aiStatus != null) NoteCard(null, aiStatus)
        ToggleRow(
            "Share my monthly totals",
            "For advice about your money. Sends category totals only, never who you paid or any message.",
            share,
        ) {
            share = it
            onSaveAi(ai.copy(shareTotals = it))
        }
        ToggleRow(
            "Smart reading",
            if (ai.isSet) {
                "Gemini reads messages from apps you allow, to understand them better. They go to Google, and free-tier data " +
                    "may be used to improve Google's products, so private chats may be seen. Codes, passwords, bank alerts and " +
                    "muted people are never sent."
            } else {
                "Add your Gemini key first. Gemini can then read messages from apps you allow, to understand them better."
            },
            smart && ai.isSet,
            enabled = ai.isSet,
        ) {
            smart = it
            onSaveAi(ai)
            onSmart(it)
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            val ink = MaterialTheme.colorScheme.inverseOnSurface
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Email summary", style = MaterialTheme.typography.headlineMedium, color = ink)
                Text(
                    "Emails you your weekly totals through Resend. Get a free key at resend.com. Without your own domain, " +
                        "Resend only delivers to the email on your Resend account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.copy(alpha = 0.75f),
                )
            }
        }
        OutlinedTextField(
            value = resend,
            onValueChange = { resend = it },
            label = { Text("Resend API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = to,
            onValueChange = { to = it },
            label = { Text("Send to (your email)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow("Email me every week", "Runs in the background once a week, when the phone is online.", weekly) {
            weekly = it
            onSaveEmail(email.copy(weekly = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSaveEmail(email) }, modifier = Modifier.weight(1f)) { Text("Save") }
            TextButton(onClick = { onSendEmail(email) }, enabled = email.isSet) { Text("Send one now") }
        }
        if (emailStatus != null) NoteCard(null, emailStatus)
    }
}

@Composable
private fun ToggleRow(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.labelled(title))
        }
    }
}
