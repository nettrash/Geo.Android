package me.nettrash.geo.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.geo.R

/**
 * Slim banner shown at the top of the tab view when the user has
 * denied a sensor permission Geo needs. Tapping it deep-links to the
 * app's system settings screen so the user can fix it without
 * hunting through menus.
 *
 * Direct port of iOS `PermissionsBanner.swift`.
 */
@Composable
fun PermissionsBanner(monitor: PermissionsMonitor) {
    val locationDenied by monitor.locationDenied.collectAsState()
    val motionDenied by monitor.motionDenied.collectAsState()
    val show by monitor.hasAnyDenial.collectAsState()

    if (!show) return

    val context = LocalContext.current

    val headlineRes = when {
        locationDenied && motionDenied -> R.string.permissions_banner_headline_both
        locationDenied -> R.string.permissions_banner_headline_location
        motionDenied -> R.string.permissions_banner_headline_motion
        else -> null
    }
    val detailRes = when {
        locationDenied && motionDenied -> R.string.permissions_banner_detail_both
        locationDenied -> R.string.permissions_banner_detail_location
        motionDenied -> R.string.permissions_banner_detail_motion
        else -> null
    }
    if (headlineRes == null || detailRes == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFFD54F),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(headlineRes),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(detailRes),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.permissions_banner_open_settings),
            color = Color(0xFF80DEEA),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
