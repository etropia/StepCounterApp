package com.exact.stepcounter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StepForegroundService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "step_tracking_channel"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, StepForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepForegroundService::class.java))
        }
    }

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(StepRepository.getLastSteps(this)))
        } catch (e: Exception) {
            stopSelf()
            return
        }
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val cumulativeSinceBoot = event.values[0].toInt()
        StepRepository.setLastCumulative(this, cumulativeSinceBoot)

        val stepsToday = StepRepository.computeStepsToday(this, cumulativeSinceBoot)
        val calories = StepRepository.calculateCalories(this, stepsToday)

        updateNotification(stepsToday)
        StepWidgetProvider.updateAllWidgets(this)

        sendBroadcast(
            Intent(StepRepository.ACTION_STEPS_UPDATED)
                .setPackage(packageName)
                .putExtra(StepRepository.EXTRA_STEPS, stepsToday)
                .putExtra(StepRepository.EXTRA_CALORIES, calories)
        )

        val session = StepRepository.updateSessionOnSensorEvent(this, cumulativeSinceBoot)
        if (session != null) {
            sendBroadcast(
                Intent(StepRepository.ACTION_SESSION_UPDATED)
                    .setPackage(packageName)
                    .putExtra(StepRepository.EXTRA_SESSION_STEPS, session.steps)
                    .putExtra(StepRepository.EXTRA_SESSION_CALORIES, session.calories)
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step tracking",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps counting your steps in the background for the widget"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(steps: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StepTrue is tracking your steps")
            .setContentText("$steps steps today")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(steps: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(steps))
    }
}
