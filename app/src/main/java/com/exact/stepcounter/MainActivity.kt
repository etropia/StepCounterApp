package com.exact.stepcounter

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvSteps: TextView
    private lateinit var tvCalories: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var tvSessionSteps: TextView
    private lateinit var tvSessionCalories: TextView
    private lateinit var tvSessionHint: TextView
    private lateinit var tvViewHistory: TextView
    private lateinit var btnSession: Button

    private var hasStepSensor = true

    companion object {
        const val REQ_ACTIVITY_RECOGNITION = 100
        const val REQ_NOTIFICATIONS = 101
    }

    private val stepsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val steps = intent.getIntExtra(StepRepository.EXTRA_STEPS, 0)
            val calories = intent.getFloatExtra(StepRepository.EXTRA_CALORIES, -1f)
            tvSteps.text = steps.toString()
            tvCalories.text = StepRepository.formatCalories(calories)
            tvStatus.text = "Live \u00b7 hardware step sensor"
            pop(tvSteps)
        }
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val steps = intent.getIntExtra(StepRepository.EXTRA_SESSION_STEPS, 0)
            val calories = intent.getFloatExtra(StepRepository.EXTRA_SESSION_CALORIES, -1f)
            tvSessionSteps.text = steps.toString()
            tvSessionCalories.text = StepRepository.formatCalories(calories)
            pop(tvSessionSteps)
        }
    }

    private fun pop(view: android.view.View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(90)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            .start()
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (StepRepository.isSessionActive(this@MainActivity)) {
                val elapsed = System.currentTimeMillis() - StepRepository.getSessionStartTime(this@MainActivity)
                tvSessionTimer.text = StepRepository.formatDuration(elapsed)
                timerHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSteps = findViewById(R.id.tvSteps)
        tvCalories = findViewById(R.id.tvCalories)
        tvStatus = findViewById(R.id.tvStatus)
        tvSessionTimer = findViewById(R.id.tvSessionTimer)
        tvSessionSteps = findViewById(R.id.tvSessionSteps)
        tvSessionCalories = findViewById(R.id.tvSessionCalories)
        tvSessionHint = findViewById(R.id.tvSessionHint)
        tvViewHistory = findViewById(R.id.tvViewHistory)
        btnSession = findViewById(R.id.btnSession)

        findViewById<android.widget.ImageButton>(R.id.btnEditProfile).setOnClickListener {
            showProfileDialog(forceShow = true)
        }
        btnSession.setOnClickListener { onSessionButtonClicked() }
        tvViewHistory.setOnClickListener { showSessionHistory() }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hasStepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        if (!hasStepSensor) {
            tvStatus.text = "This device has no hardware step-counter sensor. " +
                "Step counting cannot be exact without it."
            btnSession.isEnabled = false
            tvSessionHint.text = "Sessions need a step-counter sensor, which this device doesn't have."
        }

        requestPermissionsIfNeeded()
        StepForegroundService.start(this)

        if (!StepRepository.isOnboarded(this)) {
            showProfileDialog(forceShow = true)
        }

        showLastKnownValues()
        refreshSessionUi()
    }

    private fun showLastKnownValues() {
        tvSteps.text = StepRepository.getLastSteps(this).toString()
        tvCalories.text = StepRepository.formatCalories(StepRepository.getLastCalories(this))
    }

    private fun onSessionButtonClicked() {
        if (StepRepository.isSessionActive(this)) {
            val summary = StepRepository.stopSession(this)
            refreshSessionUi()
            showSessionSummary(summary)
        } else {
            val started = StepRepository.startSession(this)
            if (!started) {
                Toast.makeText(
                    this,
                    "No step reading yet. Go to Settings > Apps > StepTrue and make sure " +
                        "'Physical activity' permission is Allowed and background activity " +
                        "isn't restricted, then reopen the app and try again.",
                    Toast.LENGTH_LONG
                ).show()
                StepForegroundService.start(this)
                return
            }
            refreshSessionUi()
        }
    }

    private fun refreshSessionUi() {
        val active = StepRepository.isSessionActive(this)
        btnSession.text = if (active) "Stop Session" else "Start Session"
        btnSession.setBackgroundResource(
            if (active) R.drawable.bg_pill_button_stop else R.drawable.bg_pill_button_start
        )
        tvSessionSteps.text = StepRepository.getSessionLiveSteps(this).toString()
        tvSessionCalories.text = StepRepository.formatCalories(StepRepository.getSessionLiveCalories(this))

        timerHandler.removeCallbacks(timerTick)
        if (active) {
            val elapsed = System.currentTimeMillis() - StepRepository.getSessionStartTime(this)
            tvSessionTimer.text = StepRepository.formatDuration(elapsed)
            timerHandler.post(timerTick)
            if (hasStepSensor) tvSessionHint.text = "Session in progress\u2026"
        } else {
            tvSessionTimer.text = "00:00:00"
            if (hasStepSensor) tvSessionHint.text = ""
        }
    }

    private fun showSessionHistory() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_session_history, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.llHistoryContainer)
        val emptyText = view.findViewById<TextView>(R.id.tvHistoryEmpty)

        val history = StepRepository.getSessionHistory(this)
        if (history.isEmpty()) {
            emptyText.visibility = android.view.View.VISIBLE
        } else {
            val inflater = LayoutInflater.from(this)
            for (session in history) {
                val row = inflater.inflate(R.layout.item_history_row, container, false)
                row.findViewById<TextView>(R.id.tvRowDate).text =
                    StepRepository.formatDateTime(session.startTimeMs)
                row.findViewById<TextView>(R.id.tvRowSteps).text = session.steps.toString()
                row.findViewById<TextView>(R.id.tvRowDuration).text =
                    StepRepository.formatDuration(session.durationMs)
                row.findViewById<TextView>(R.id.tvRowCalories).text =
                    StepRepository.formatCalories(session.calories)
                container.addView(row)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Session history")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSessionSummary(summary: StepRepository.SessionSummary) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_session_summary, null)
        view.findViewById<TextView>(R.id.tvSummarySteps).text = summary.steps.toString()
        view.findViewById<TextView>(R.id.tvSummaryDuration).text =
            StepRepository.formatDuration(summary.durationMs)
        view.findViewById<TextView>(R.id.tvSummaryCalories).text =
            StepRepository.formatCalories(summary.calories)

        val distanceText = if (summary.distanceKm < 0) "-- km"
            else String.format(Locale.US, "%.2f km", summary.distanceKm)
        view.findViewById<TextView>(R.id.tvSummaryDistance).text = distanceText

        val paceText = if (summary.distanceKm <= 0 || summary.durationMs <= 0) "--"
            else {
                val minutes = summary.durationMs / 60000.0
                val paceMinPerKm = minutes / summary.distanceKm
                String.format(Locale.US, "%.1f min/km", paceMinPerKm)
            }
        view.findViewById<TextView>(R.id.tvSummaryPace).text = paceText

        AlertDialog.Builder(this)
            .setTitle("Walk complete \u2713 saved to history")
            .setView(view)
            .setPositiveButton("OK", null)
            .setCancelable(true)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ACTIVITY_RECOGNITION &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            StepForegroundService.start(this)
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                    REQ_ACTIVITY_RECOGNITION
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS
                )
            }
        }
    }

    private fun showProfileDialog(forceShow: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val etWeight = view.findViewById<EditText>(R.id.etWeight)
        val etHeight = view.findViewById<EditText>(R.id.etHeight)

        val currentWeight = StepRepository.getWeight(this)
        val currentHeight = StepRepository.getHeight(this)
        if (currentWeight > 0) etWeight.setText(currentWeight.toString())
        if (currentHeight > 0) etHeight.setText(currentHeight.toString())

        AlertDialog.Builder(this)
            .setTitle("Your profile")
            .setView(view)
            .setCancelable(!forceShow || currentWeight > 0)
            .setPositiveButton("Save") { _, _ ->
                val weight = etWeight.text.toString().toFloatOrNull()
                val height = etHeight.text.toString().toFloatOrNull()
                if (weight == null || weight <= 0 || height == null || height <= 0) {
                    Toast.makeText(this, "Enter valid weight and height", Toast.LENGTH_SHORT).show()
                    showProfileDialog(forceShow = forceShow)
                    return@setPositiveButton
                }
                StepRepository.setProfile(this, weight, height)
                val steps = StepRepository.getLastSteps(this)
                val calories = StepRepository.calculateCalories(this, steps)
                tvCalories.text = StepRepository.formatCalories(calories)
                StepWidgetProvider.updateAllWidgets(this)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(StepRepository.ACTION_STEPS_UPDATED)
        val sessionFilter = IntentFilter(StepRepository.ACTION_SESSION_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stepsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(sessionReceiver, sessionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stepsReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionReceiver, sessionFilter)
        }
        showLastKnownValues()
        refreshSessionUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stepsReceiver)
        unregisterReceiver(sessionReceiver)
        timerHandler.removeCallbacks(timerTick)
    }
}
