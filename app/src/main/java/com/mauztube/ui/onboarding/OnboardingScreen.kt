package com.mauztube.ui.onboarding

import com.mauztube.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mauztube.ui.theme.BrandRed

/** Primo avvio: collegare l'account Google o usare l'app solo in locale. */
@Composable
fun OnboardingScreen(onGoogle: () -> Unit, onLocal: () -> Unit) {
    // Surface: imposta il colore del testo del tema (fuori da una Surface sarebbe nero).
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    // Qualsiasi schermo: il contenuto è centrato se ci sta, altrimenti scorre. Sugli schermi
    // bassi (o con caratteri grandi) gli spazi si riducono; sui tablet la larghezza è limitata.
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
    val compact = maxHeight < 700.dp
    val gap = if (compact) 16.dp else 28.dp
    Column(
        Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).widthIn(max = 520.dp)
            .padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LanguageSelector(Modifier.padding(bottom = gap))
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.mauztube.R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 56.dp else 80.dp),
        )
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        Text(tr("Welcome to MauzTube", "Benvenuto in MauzTube"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            tr("No ads, no Shorts, with SponsorBlock. How do you want to use it?", "Senza pubblicità, senza Shorts, con SponsorBlock. Come vuoi usarlo?"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(gap))
        Choice(
            icon = Icons.Default.AccountCircle,
            title = tr("Connect your Google account", "Collega l'account Google"),
            text = tr("Your recommendations, subscriptions, history and playlists, synced with YouTube.", "Consigliati del tuo profilo, iscrizioni, cronologia e playlist sincronizzati con YouTube."),
        )
        Spacer(Modifier.height(12.dp))
        Choice(
            icon = Icons.Default.Lock,
            title = tr("Stay local", "Resta in locale"),
            text = tr("No account: subscriptions, history and recommendations stay on this phone only.", "Nessun account: iscrizioni, cronologia e consigli restano solo su questo telefono."),
        )
        Spacer(Modifier.height(gap))
        Button(onClick = onGoogle, modifier = Modifier.fillMaxWidth()) { Text(tr("Sign in with Google", "Accedi con Google")) }
        OutlinedButton(onClick = onLocal, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tr("Continue without an account", "Continua senza account")) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val linksEnabled by androidx.compose.runtime.produceState<Boolean?>(true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mauztube.util.OpenLinks.isEnabled(ctx) }
        }
        if (linksEnabled != true) {
            androidx.compose.material3.TextButton(
                onClick = { com.mauztube.util.OpenLinks.openSettings(ctx) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Open YouTube links with MauzTube", "Apri i link YouTube con MauzTube")) }
        }
        Text(
            tr("You can change this at any time in Settings.", "Potrai cambiare scelta in qualsiasi momento dalle Impostazioni."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
    }
}
}

@Composable
private fun Choice(icon: ImageVector, title: String, text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Scelta lingua (inglese/italiano): applicata subito ricreando l'activity. */
@Composable
fun LanguageSelector(modifier: Modifier = Modifier) {
    val activity = androidx.activity.compose.LocalActivity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(com.mauztube.ui.theme.AppIcons.Language, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Language / Lingua", style = MaterialTheme.typography.labelLarge)
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
            com.mauztube.util.AppLanguage.entries.forEachIndexed { i, lang ->
                SegmentedButton(
                    selected = com.mauztube.util.I18n.language == lang,
                    onClick = {
                        if (com.mauztube.util.I18n.language != lang) {
                            com.mauztube.util.I18n.set(context, lang)
                            activity?.recreate()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, com.mauztube.util.AppLanguage.entries.size),
                ) { Text(lang.label) }
            }
        }
    }
}
