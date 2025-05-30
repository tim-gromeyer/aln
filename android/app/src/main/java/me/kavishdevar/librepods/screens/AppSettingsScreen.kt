/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
 *
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.kavishdevar.librepods.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledSwitch
import me.kavishdevar.librepods.utils.RadareOffsetFinder
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun AppSettingsScreen(navController: NavController) {
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val name = remember { mutableStateOf(sharedPreferences.getString("name", "") ?: "") }
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hazeState = remember { HazeState() }

    var showResetDialog by remember { mutableStateOf(false) }

    var showPhoneBatteryInWidget by remember {
        mutableStateOf(sharedPreferences.getBoolean("show_phone_battery_in_widget", true))
    }
    var conversationalAwarenessPauseMusicEnabled by remember {
        mutableStateOf(sharedPreferences.getBoolean("conversational_awareness_pause_music", false))
    }
    var relativeConversationalAwarenessVolumeEnabled by remember {
        mutableStateOf(sharedPreferences.getBoolean("relative_conversational_awareness_volume", true))
    }
    var openDialogForControlling by remember {
        mutableStateOf(sharedPreferences.getString("qs_click_behavior", "dialog") == "dialog")
    }
    var disconnectWhenNotWearing by remember {
        mutableStateOf(sharedPreferences.getBoolean("disconnect_when_not_wearing", false))
    }
    var mDensity by remember { mutableFloatStateOf(0f) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.thick(),
                    block = fun HazeEffectScope.() {
                        alpha =
                            if (scrollState.value > 60.dp.value * mDensity) 1f else 0f
                    })
                    .drawBehind {
                        mDensity = density
                        val strokeWidth = 0.7.dp.value * density
                        val y = size.height - strokeWidth / 2
                        if (scrollState.value > 60.dp.value * density) {
                            drawLine(
                                if (isDarkTheme) Color.DarkGray else Color.LightGray,
                                Offset(0f, y),
                                Offset(size.width, y),
                                strokeWidth
                            )
                        }
                    },
                title = {
                    Text(
                        text = stringResource(R.string.app_settings),
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            navController.popBackStack()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = if (isDarkTheme)  Color(0xFF007AFF) else Color(0xFF3C6DF5),
                            modifier = Modifier.scale(1.5f)
                        )
                        Text(
                            text = name.value,
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = if (isSystemInDarkTheme()) Color(0xFF000000)
        else Color(0xFFF2F2F7),
    ) { paddingValues ->
        Column (
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
                .hazeSource(state = hazeState)
        ) {
            val isDarkTheme = isSystemInDarkTheme()
            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            val textColor = if (isDarkTheme) Color.White else Color.Black

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Widget".uppercase(),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(8.dp, bottom = 2.dp, top = 8.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            showPhoneBatteryInWidget = !showPhoneBatteryInWidget
                            sharedPreferences.edit().putBoolean("show_phone_battery_in_widget", showPhoneBatteryInWidget).apply()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = "Show phone battery in widget",
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Display your phone's battery level in the widget alongside AirPods battery",
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }

                    StyledSwitch(
                        checked = showPhoneBatteryInWidget,
                        onCheckedChange = {
                            showPhoneBatteryInWidget = it
                            sharedPreferences.edit().putBoolean("show_phone_battery_in_widget", it).apply()
                        }
                    )
                }
            }

            Text(
                text = "Conversational Awareness".uppercase(),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(8.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val sliderValue = remember { mutableFloatStateOf(0f) }
                LaunchedEffect(sliderValue) {
                    if (sharedPreferences.contains("conversational_awareness_volume")) {
                        sliderValue.floatValue = sharedPreferences.getInt("conversational_awareness_volume", 43).toFloat()
                    }
                }

                fun updateConversationalAwarenessPauseMusic(enabled: Boolean) {
                    conversationalAwarenessPauseMusicEnabled = enabled
                    sharedPreferences.edit().putBoolean("conversational_awareness_pause_music", enabled).apply()
                }

                fun updateRelativeConversationalAwarenessVolume(enabled: Boolean) {
                    relativeConversationalAwarenessVolumeEnabled = enabled
                    sharedPreferences.edit().putBoolean("relative_conversational_awareness_volume", enabled).apply()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            updateConversationalAwarenessPauseMusic(!conversationalAwarenessPauseMusicEnabled)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.conversational_awareness_pause_music),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.conversational_awareness_pause_music_description),
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }

                    StyledSwitch(
                        checked = conversationalAwarenessPauseMusicEnabled,
                        onCheckedChange = {
                            updateConversationalAwarenessPauseMusic(it)
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            updateRelativeConversationalAwarenessVolume(!relativeConversationalAwarenessVolumeEnabled)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.relative_conversational_awareness_volume),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.relative_conversational_awareness_volume_description),
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }

                    StyledSwitch(
                        checked = relativeConversationalAwarenessVolumeEnabled,
                        onCheckedChange = {
                            updateRelativeConversationalAwarenessVolume(it)
                        }
                    )
                }

                Text(
                    text = "Conversational Awareness Volume",
                    fontSize = 16.sp,
                    color = textColor,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                val trackColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFFD9D9D9)
                val activeTrackColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
                val thumbColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)

                Slider(
                    value = sliderValue.floatValue,
                    onValueChange = {
                        sliderValue.floatValue = it
                        sharedPreferences.edit().putInt("conversational_awareness_volume", it.toInt()).apply()
                    },
                    valueRange = 10f..85f,
                    onValueChangeFinished = {
                        sliderValue.floatValue = sliderValue.floatValue.roundToInt().toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(vertical = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = thumbColor,
                        activeTrackColor = activeTrackColor,
                        inactiveTrackColor = trackColor,
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .shadow(4.dp, CircleShape)
                                .background(thumbColor, CircleShape)
                        )
                    },
                    track = {
                        Box (
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp),
                            contentAlignment = Alignment.CenterStart
                        )
                        {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(trackColor, RoundedCornerShape(4.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(((sliderValue.floatValue - 10) * 100) /7500)
                                    .height(4.dp)
                                    .background(if (conversationalAwarenessPauseMusicEnabled) trackColor else activeTrackColor, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "10%",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                            color = textColor.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = "85%",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                            color = textColor.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            Text(
                text = "Quick Settings Tile".uppercase(),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(8.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                fun updateQsClickBehavior(enabled: Boolean) {
                    openDialogForControlling = enabled
                    sharedPreferences.edit().putString("qs_click_behavior", if (enabled) "dialog" else "cycle").apply()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            updateQsClickBehavior(!openDialogForControlling)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = "Open dialog for controlling",
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (openDialogForControlling)
                                   "If disabled, clicking on the QS will cycle through modes"
                                   else "If enabled, it will show a dialog for controlling noise control mode and conversational awareness",
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }

                    StyledSwitch(
                        checked = openDialogForControlling,
                        onCheckedChange = {
                            updateQsClickBehavior(it)
                        }
                    )
                }
            }

            Text(
                text = "Ear Detection".uppercase(),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(8.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                fun updateDisconnectWhenNotWearing(enabled: Boolean) {
                    disconnectWhenNotWearing = enabled
                    sharedPreferences.edit().putBoolean("disconnect_when_not_wearing", enabled).apply()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            updateDisconnectWhenNotWearing(!disconnectWhenNotWearing)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = "Disconnect AirPods when not wearing",
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "You will still be able to control them with the app - this just disconnects the audio.",
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }

                    StyledSwitch(
                        checked = disconnectWhenNotWearing,
                        onCheckedChange = {
                            updateDisconnectWhenNotWearing(it)
                        }
                    )
                }
            }

            Text(
                text = "Advanced Options".uppercase(),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(8.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigate("troubleshooting")
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.troubleshooting),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.troubleshooting_description),
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset Hook Offset",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = {
                        Text(
                            "Reset Hook Offset",
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    text = {
                        Text(
                            "This will clear the current hook offset and require you to go through the setup process again. Are you sure you want to continue?",
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (RadareOffsetFinder.clearHookOffsets()) {
                                    Toast.makeText(
                                        context,
                                        "Hook offset has been reset. Redirecting to setup...",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    navController.navigate("onboarding") {
                                        popUpTo("settings") { inclusive = true }
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Failed to reset hook offset",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                showResetDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                "Reset",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showResetDialog = false }
                        ) {
                            Text(
                                "Cancel",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                )
            }
        }
    }
}
