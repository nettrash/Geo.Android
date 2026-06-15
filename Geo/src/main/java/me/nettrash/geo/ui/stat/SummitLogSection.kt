package me.nettrash.geo.ui.stat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.geo.R
import me.nettrash.geo.ar.PanoramaCapture
import me.nettrash.geo.ar.SummitShareCard
import me.nettrash.geo.data.db.SummitLog
import me.nettrash.geo.ui.GeoViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Trophy case of logged ascents on the Stat tab. Mirrors iOS `SummitsSectionView`. */
@Composable
fun SummitLogSection(viewModel: GeoViewModel) {
    val summits by viewModel.summitLogs.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadSummitLogs() }
    var detail by remember { mutableStateOf<SummitLog?>(null) }

    Text(
        stringResource(R.string.section_summits),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(16.dp)
    )

    if (summits.isEmpty()) {
        Text(
            stringResource(R.string.summit_empty),
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    } else {
        summits.forEach { log -> SummitCard(log) { detail = log } }
    }

    detail?.let { log ->
        SummitDetailDialog(log = log, viewModel = viewModel, onDismiss = { detail = null })
    }
}

@Composable
private fun SummitCard(log: SummitLog, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Terrain, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(log.peakName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(formatStampLocal(log.loggedDate), color = Color.Gray, fontSize = 12.sp)
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            if (log.peakAltitude > 0) {
                Text("▲ ${log.peakAltitude} m", color = Color.White, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text(summitSetLabel(log.peakSet), color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SummitDetailDialog(log: SummitLog, viewModel: GeoViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(log.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(log.peakName, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                DetailRow(stringResource(R.string.summit_collection), summitSetLabel(log.peakSet))
                if (log.peakAltitude > 0) {
                    DetailRow(stringResource(R.string.summit_elevation), "${log.peakAltitude} m")
                }
                if (log.measuredAltitude > 0) {
                    DetailRow(stringResource(R.string.summit_your_altitude),
                        "${log.measuredAltitude.toInt()} m")
                }
                DetailRow(stringResource(R.string.summit_logged), formatStampLocal(log.loggedDate))
                DetailRow(stringResource(R.string.field_coordinates),
                    String.format(Locale.US, "%.5f, %.5f", log.latitude, log.longitude))
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.summit_note)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("geo:${log.latitude},${log.longitude}?q=${log.latitude},${log.longitude}"))
                            )
                        }
                    }) { Text(stringResource(R.string.action_directions)) }
                    TextButton(onClick = {
                        scope.launch {
                            val bmp = withContext(Dispatchers.Default) { SummitShareCard.render(context, log) }
                            PanoramaCapture.shareBitmap(context, bmp)
                        }
                    }) { Text(stringResource(R.string.action_share)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.updateSummitNote(log, note)
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.deleteSummitLog(log)
                onDismiss()
            }) { Text(stringResource(R.string.action_delete), color = Color(0xFFFF3B30)) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun summitSetLabel(set: String): String = stringResource(
    when (set) {
        "sevenPeaks" -> R.string.summit_set_seven
        "snowLeopardOfRussia" -> R.string.summit_set_snow_leopard
        "highest" -> R.string.summit_set_highest
        else -> R.string.summit_set_peak
    }
)

private val summitStampFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
private fun formatStampLocal(ms: Long): String = summitStampFormat.format(Date(ms))
