package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.location.MockLocationSettings

/**
 * Simulated coordinates for virtual apps. The host device is never modified:
 * each container answers location requests from this configuration while the
 * switch is on.
 */
@Composable
internal fun LocationSimulationScreen() {
    val context = LocalContext.current
    val stored = remember { MockLocationSettings.load(context) }
    var enabled by remember { mutableStateOf(stored.enabled) }
    var latitude by remember {
        mutableStateOf(MockLocationSettings.format(stored.latitude))
    }
    var longitude by remember {
        mutableStateOf(MockLocationSettings.format(stored.longitude))
    }
    var message by remember { mutableStateOf("") }

    val parsedLatitude = MockLocationSettings.parseLatitude(latitude)
    val parsedLongitude = MockLocationSettings.parseLongitude(longitude)
    val latitudeInvalid = latitude.isNotBlank() && parsedLatitude == null
    val longitudeInvalid = longitude.isNotBlank() && parsedLongitude == null

    fun persist(nextEnabled: Boolean) {
        val lat = parsedLatitude
        val lon = parsedLongitude
        if (nextEnabled && (lat == null || lon == null)) {
            message = AppLanguage.tr(
                "请先填写有效的经纬度。",
                "Enter a valid latitude and longitude first."
            )
            return
        }
        val previous = MockLocationSettings.load(context)
        MockLocationSettings.save(
            context,
            MockLocationSettings.Config(
                enabled = nextEnabled,
                latitude = lat ?: previous.latitude,
                longitude = lon ?: previous.longitude
            )
        )
        enabled = nextEnabled
        if (lat != null) latitude = MockLocationSettings.format(lat)
        if (lon != null) longitude = MockLocationSettings.format(lon)
        message = if (nextEnabled) {
            AppLanguage.tr(
                "已开启，虚拟应用将使用模拟坐标。",
                "Enabled; virtual apps will use the simulated coordinates."
            )
        } else {
            AppLanguage.tr(
                "已关闭，虚拟应用恢复真实定位。",
                "Disabled; virtual apps use real location again."
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("坐标模拟", "Location simulation")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("为虚拟应用模拟坐标", "Simulate location for virtual apps"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            AppLanguage.tr(
                                "开启后，虚拟应用读取到的经纬度来自下方设置。",
                                "While on, virtual apps read the coordinates configured below."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = enabled, onCheckedChange = ::persist)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        AppLanguage.tr("坐标", "Coordinates"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = latitude,
                            onValueChange = { latitude = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = latitudeInvalid,
                            label = { Text(AppLanguage.tr("纬度", "Latitude")) },
                            placeholder = { Text("-90 ~ 90") },
                            supportingText = {
                                if (latitudeInvalid) {
                                    Text(AppLanguage.tr("范围 -90 到 90", "-90 to 90"))
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            )
                        )
                        OutlinedTextField(
                            value = longitude,
                            onValueChange = { longitude = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = longitudeInvalid,
                            label = { Text(AppLanguage.tr("经度", "Longitude")) },
                            placeholder = { Text("-180 ~ 180") },
                            supportingText = {
                                if (longitudeInvalid) {
                                    Text(AppLanguage.tr("范围 -180 到 180", "-180 to 180"))
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            )
                        )
                    }
                    Button(
                        onClick = { persist(enabled) },
                        enabled = parsedLatitude != null && parsedLongitude != null
                    ) {
                        Text(AppLanguage.tr("保存坐标", "Save coordinates"))
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "只对虚拟空间内的应用生效，不会修改真实设备的定位。应用需要已获得定位权限；" +
                        "使用自有定位服务的应用（例如部分地图或 Google Play 服务）可能仍返回真实位置。" +
                        "修改后，正在运行的应用可能需要重新打开才会使用新坐标。",
                    "Only affects apps inside the virtual space; the real device location is " +
                        "never changed. The app still needs location permission. Apps that use " +
                        "their own location service (some map SDKs or Google Play services) may " +
                        "still return the real position. Running apps may need to be reopened " +
                        "before they use the new coordinates."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
