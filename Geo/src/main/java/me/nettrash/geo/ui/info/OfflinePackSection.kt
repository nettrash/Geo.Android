package me.nettrash.geo.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.nettrash.geo.R
import me.nettrash.geo.offline.OfflinePack
import me.nettrash.geo.ui.GeoViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACCENT = Color(0xFFFF9800)

/**
 * Info-tab card for the offline expedition pack: a summary + a button that
 * opens the management dialog (download current area + saved-pack list).
 * Mirrors the iOS `OfflinePackCard`.
 */
@Composable
fun OfflineExpeditionCard(viewModel: GeoViewModel) {
    val packs by viewModel.offlinePacks.collectAsState()
    val downloading by viewModel.offlinePackDownloading.collectAsState()
    val progress by viewModel.offlinePackProgress.collectAsState()
    val status by viewModel.offlinePackStatus.collectAsState()
    val location by viewModel.locationManager.location.collectAsState()
    var showManager by remember { mutableStateOf(false) }

    InfoCard(watermark = "OFFLINE") {
        InfoRow(stringResource(R.string.offline_expedition_pack)) {
            MonoText(if (packs.isEmpty()) stringResource(R.string.offline_no_areas) else "${packs.size} saved")
        }
        if (downloading) {
            InfoRow(if (status.isEmpty()) stringResource(R.string.offline_downloading) else status) {
                MonoText("${(progress * 100).toInt()}%")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showManager = true },
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.offline_manage), fontSize = 12.sp, color = Color.White)
            }
        }
    }

    if (showManager) {
        OfflinePackManagerDialog(
            packs = packs,
            downloading = downloading,
            progress = progress,
            status = status,
            hasLocation = location != null,
            onDownload = { name, radius -> viewModel.downloadOfflinePack(name, radius) },
            onDelete = { viewModel.deleteOfflinePack(it) },
            onRename = { pack, newName -> viewModel.renameOfflinePack(pack, newName) },
            onDismiss = { showManager = false }
        )
    }
}

@Composable
private fun OfflinePackManagerDialog(
    packs: List<OfflinePack>,
    downloading: Boolean,
    progress: Float,
    status: String,
    hasLocation: Boolean,
    onDownload: (String, Double) -> Unit,
    onDelete: (OfflinePack) -> Unit,
    onRename: (OfflinePack, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var radiusKm by remember { mutableStateOf(10.0) }
    val radii = listOf(5.0, 10.0, 50.0, 100.0)
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var renamingPack by remember { mutableStateOf<OfflinePack?>(null) }
    var renameText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF222222)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.offline_packs_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                Text(stringResource(R.string.offline_section_download), color = ACCENT, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (!hasLocation) {
                    Text(stringResource(R.string.offline_waiting_gps), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.offline_name_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = ACCENT,
                            focusedBorderColor = ACCENT,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                            focusedLabelColor = ACCENT,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.offline_radius), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (r in radii) {
                            val selected = r == radiusKm
                            Button(
                                onClick = { radiusKm = r },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) ACCENT else Color.Gray.copy(alpha = 0.4f)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("${r.toInt()} km", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (downloading) {
                        Text(
                            if (status.isEmpty()) stringResource(R.string.offline_downloading) else status,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = ACCENT
                        )
                    } else {
                        Button(
                            onClick = { onDownload(name, radiusKm); name = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.offline_download_area), color = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.offline_section_saved), color = ACCENT, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (packs.isEmpty()) {
                    Text(stringResource(R.string.offline_none_yet), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    for (pack in packs) {
                        // Info stacked full-width, with the Rename/Delete actions on
                        // their own row beneath — so the "… · 100 km" detail line has
                        // the full width and doesn't wrap.
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(pack.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${pack.peakCount} peaks · ${pack.radiusKm.toInt()} km",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                            )
                            Text(
                                dateFormat.format(Date(pack.createdAt)),
                                color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { renameText = pack.name; renamingPack = pack }) {
                                    Text(stringResource(R.string.action_rename), color = ACCENT, fontSize = 12.sp)
                                }
                                TextButton(onClick = { onDelete(pack) }) {
                                    Text(stringResource(R.string.action_delete), color = Color(0xFFE57373), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.offline_footer),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = ACCENT) }
                }
            }
        }
    }

    renamingPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { renamingPack = null },
            containerColor = Color(0xFF222222),
            title = { Text(stringResource(R.string.offline_rename_title), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = ACCENT,
                        focusedBorderColor = ACCENT,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(pack, renameText); renamingPack = null }) {
                    Text(stringResource(R.string.action_save), color = ACCENT)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingPack = null }) {
                    Text(stringResource(R.string.action_cancel), color = Color.White)
                }
            }
        )
    }
}
