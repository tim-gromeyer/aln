/*
 * AirPods like Normal (ALN) - Bringing Apple-only features to Linux and Android for seamless AirPods functionality!
 *
 * Copyright (C) 2024 Kavish Devar
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


package me.kavishdevar.aln.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import me.kavishdevar.aln.R
import me.kavishdevar.aln.services.ServiceManager

class NoiseControlWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = RemoteViews(context.packageName, R.layout.noise_control_widget)

        val offIntent = Intent(context, NoiseControlWidget::class.java).apply {
            action = "ACTION_SET_ANC_MODE"
            putExtra("ANC_MODE", 1)
        }
        val transparencyIntent = Intent(context, NoiseControlWidget::class.java).apply {
            action = "ACTION_SET_ANC_MODE"
            putExtra("ANC_MODE", 3)
        }
        val adaptiveIntent = Intent(context, NoiseControlWidget::class.java).apply {
            action = "ACTION_SET_ANC_MODE"
            putExtra("ANC_MODE", 4)
        }
        val ancIntent = Intent(context, NoiseControlWidget::class.java).apply {
            action = "ACTION_SET_ANC_MODE"
            putExtra("ANC_MODE", 2)
        }

        views.setOnClickPendingIntent(
            R.id.widget_off_button,
            PendingIntent.getBroadcast(context, 0, offIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        views.setOnClickPendingIntent(
            R.id.widget_transparency_button,
            PendingIntent.getBroadcast(context, 1, transparencyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        views.setOnClickPendingIntent(
            R.id.widget_adaptive_button,
            PendingIntent.getBroadcast(context, 2, adaptiveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        views.setOnClickPendingIntent(
            R.id.widget_anc_button,
            PendingIntent.getBroadcast(context, 3, ancIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        ServiceManager.getService()?.updateNoiseControlWidget()
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "ACTION_SET_ANC_MODE") {
            val mode = intent.getIntExtra("ANC_MODE", 1)
            ServiceManager.getService()?.setANCMode(mode)
        }
    }
}
