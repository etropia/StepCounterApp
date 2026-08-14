package com.exact.stepcounter

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget showing today's steps and estimated calories. The values it
 * displays are always the last ones written by StepForegroundService, so the
 * widget stays live even while the app itself is closed. The system also nudges
 * onUpdate periodically (see step_widget_info.xml) as a safety net.
 */
class StepWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StepWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val steps = StepRepository.getLastSteps(context)
            val calories = StepRepository.getLastCalories(context)

            val views = RemoteViews(context.packageName, R.layout.widget_step_counter)
            views.setTextViewText(R.id.widgetSteps, steps.toString())
            views.setTextViewText(R.id.widgetCalories, StepRepository.formatCalories(calories))

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Make sure the background tracker is running, then show whatever it last recorded.
        StepForegroundService.start(context)
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onEnabled(context: Context) {
        // First widget instance was just placed on a home screen.
        StepForegroundService.start(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget instance was removed - no more reason to keep tracking in
        // the background purely for the widget (the app can still restart it later).
        StepForegroundService.stop(context)
    }
}
