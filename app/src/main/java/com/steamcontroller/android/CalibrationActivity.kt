package com.steamcontroller.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.steamcontroller.android.input.GyroActivation
import com.steamcontroller.android.databinding.ActivityCalibrationBinding
import com.steamcontroller.android.databinding.DialogAutotuneBinding
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.service.ControllerService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.input.StickTuner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding

    private var lastLeftRawX = 0
    private var lastLeftRawY = 0
    private var lastRightRawX = 0
    private var lastRightRawY = 0

    private var leftCal = StickCalibration()
    private var rightCal = StickCalibration()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.steamcontroller.android.ui.TvFocus.install(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                resetAll()
                true
            } else false
        }

        leftCal = Prefs.getLeftCalibration(this)
        rightCal = Prefs.getRightCalibration(this)
        bindUiFromState()

        binding.btnTestRumble.setOnClickListener {
            if (ControllerService.modeFlow.value == ControllerService.InjectionMode.NONE) {
                Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, ControllerService::class.java).apply {
                action = ControllerService.ACTION_TEST_RUMBLE
            }
            startService(intent)
        }

        // Rumble intensity slider — applied live to all subsequent rumble events
        val savedIntensity = Prefs.getRumbleIntensity(this)
        binding.sliderRumbleIntensity.value = savedIntensity.toFloat()
        binding.tvRumbleIntensity.text = "$savedIntensity%"
        binding.sliderRumbleIntensity.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            val pct = value.toInt()
            binding.tvRumbleIntensity.text = "$pct%"
            Prefs.setRumbleIntensity(this, pct)
        })

        // Mouse sensitivity slider — only relevant in Desktop profile but always visible
        val savedSens = Prefs.getMouseSensitivity(this)
        binding.sliderMouseSensitivity.value = savedSens
        binding.tvMouseSensitivity.text = "%.1f×".format(savedSens)
        binding.sliderMouseSensitivity.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.tvMouseSensitivity.text = "%.1f×".format(value)
            Prefs.setMouseSensitivity(this, value)
        })

        // ── Auto-start ───────────────────────────────────────────────────────
        // These live here rather than on the main screen: they are set-once options, and on
        // the main screen the card pushed Start Service and the shortcut row off the bottom
        // of a 1080p TV, which has no scrollbar to hint that anything is below the fold.
        binding.switchAutoStartOnBoot.isChecked = Prefs.getAutoStartOnBoot(this)
        binding.switchAutoStartOnBoot.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoStartOnBoot(this, checked)
        }
        binding.switchAutoStartOnConnect.isChecked = Prefs.getAutoStartOnControllerConnect(this)
        binding.switchAutoStartOnConnect.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoStartOnControllerConnect(this, checked)
        }

        // ── Gyro aiming ──────────────────────────────────────────────────────
        // All of these are re-read by UInputGamepad on its 250ms pref refresh, so they take
        // effect without restarting the service — you can tune sensitivity while aiming.
        binding.switchGyroEnabled.isChecked = Prefs.getGyroEnabled(this)
        binding.switchGyroEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setGyroEnabled(this, checked)
        }

        val savedGyroSens = Prefs.getGyroSensitivity(this)
        binding.sliderGyroSensitivity.value = savedGyroSens
        binding.tvGyroSensitivity.text = "%.1f×".format(savedGyroSens)
        binding.sliderGyroSensitivity.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.tvGyroSensitivity.text = "%.1f×".format(value)
            Prefs.setGyroSensitivity(this, value)
        })

        binding.switchGyroInvertY.isChecked = Prefs.getGyroInvertY(this)
        binding.switchGyroInvertY.setOnCheckedChangeListener { _, checked ->
            Prefs.setGyroInvertY(this, checked)
        }

        // Activation is a cycling button rather than a dropdown: one DPAD click steps to the
        // next mode, with no popup to navigate on a TV.
        fun renderGyroActivation() {
            binding.btnGyroActivation.text =
                getString(R.string.calib_gyro_activation) + ": " + Prefs.getGyroActivation(this).displayName
        }
        renderGyroActivation()
        binding.btnGyroActivation.setOnClickListener {
            val all = GyroActivation.ALL
            val next = all[(Prefs.getGyroActivation(this).ordinal + 1) % all.size]
            Prefs.setGyroActivation(this, next)
            renderGyroActivation()
        }

        // Trackpads-as-mouse toggle (active alongside Xbox/PS profiles only).
        // UInputGamepad re-reads the pref at most every 250ms so flipping it is
        // effectively live without restarting the service.
        binding.switchTrackpadAsMouse.isChecked = Prefs.getTrackpadAsMouseInGamepad(this)
        binding.switchTrackpadAsMouse.setOnCheckedChangeListener { _, checked ->
            Prefs.setTrackpadAsMouseInGamepad(this, checked)
        }

        // Live preview from the running service
        lifecycleScope.launch {
            ControllerService.stateFlow.filterNotNull().collect { state ->
                lastLeftRawX  = state.leftJoyX.toInt()
                lastLeftRawY  = state.leftJoyY.toInt()
                lastRightRawX = state.rightJoyX.toInt()
                lastRightRawY = state.rightJoyY.toInt()

                val (lx, ly) = leftCal.apply(lastLeftRawX, lastLeftRawY)
                val (rx, ry) = rightCal.apply(lastRightRawX, lastRightRawY)

                binding.padLeft.setPosition(lx / 32767f, ly / 32767f)
                binding.padRight.setPosition(rx / 32767f, ry / 32767f)

                binding.tvLeftRaw.text  = "raw: %5d, %5d".format(lastLeftRawX, lastLeftRawY)
                binding.tvRightRaw.text = "raw: %5d, %5d".format(lastRightRawX, lastRightRawY)
            }
        }

        wireControls()
    }

    private fun bindUiFromState() {
        binding.sliderLeftDeadzone.value  = leftCal.deadzonePercent.toFloat()
        binding.sliderRightDeadzone.value = rightCal.deadzonePercent.toFloat()
        binding.tvLeftDeadzone.text  = "${leftCal.deadzonePercent}%"
        binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
        binding.switchLeftInvertY.isChecked  = leftCal.invertY
        binding.switchRightInvertY.isChecked = rightCal.invertY

        binding.padLeft.deadzoneFraction  = leftCal.deadzonePercent / 100f
        binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
    }

    private fun wireControls() {
        binding.sliderLeftDeadzone.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            leftCal = leftCal.copy(deadzonePercent = value.toInt())
            binding.tvLeftDeadzone.text = "${leftCal.deadzonePercent}%"
            binding.padLeft.deadzoneFraction = leftCal.deadzonePercent / 100f
            binding.padLeft.invalidate()
            saveLeft()
        })

        binding.sliderRightDeadzone.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            rightCal = rightCal.copy(deadzonePercent = value.toInt())
            binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
            binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
            binding.padRight.invalidate()
            saveRight()
        })

        binding.switchLeftInvertY.setOnCheckedChangeListener { _, checked ->
            leftCal = leftCal.copy(invertY = checked)
            saveLeft()
        }
        binding.switchRightInvertY.setOnCheckedChangeListener { _, checked ->
            rightCal = rightCal.copy(invertY = checked)
            saveRight()
        }

        // V1.2: every layout (phone, sw600dp, TV) exposes a single Calibrate All button.
        // The old per-stick Set center buttons were removed; their bindings would NPE.
        binding.btnCalibrateAll.setOnClickListener {
            leftCal  = leftCal.copy(centerX = lastLeftRawX, centerY = lastLeftRawY)
            rightCal = rightCal.copy(centerX = lastRightRawX, centerY = lastRightRawY)
            saveLeft()
            saveRight()
            android.widget.Toast.makeText(this, "Sticks calibrated", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnAutoTuneSticks.setOnClickListener { startAutoTune() }
    }

    // ── Auto-tune sticks ─────────────────────────────────────────────────────

    /**
     * Steps of the guided measurement. Only REST, SWEEP and RELEASE record samples; the
     * other two give the user time to move their thumbs so the recordings start clean.
     */
    private enum class TunePhase(val step: Int, val seconds: Double, val icon: Int, val text: Int) {
        GET_READY(1, 3.0, R.drawable.ic_tune_rest, R.string.calib_autotune_get_ready),
        REST(1, 3.0, R.drawable.ic_tune_rest, R.string.calib_autotune_rest),
        SWEEP(2, 10.0, R.drawable.ic_tune_sweep, R.string.calib_autotune_sweep),
        SETTLE(3, 1.5, R.drawable.ic_tune_release, R.string.calib_autotune_settle),
        RELEASE(3, 3.0, R.drawable.ic_tune_release, R.string.calib_autotune_release),
    }

    @Volatile private var tunePhase: TunePhase? = null
    private var tuneJob: Job? = null
    private val tuneSamples = HashMap<TunePhase, Pair<ArrayList<IntArray>, ArrayList<IntArray>>>()

    private fun samplesOf(phase: TunePhase) =
        tuneSamples.getOrPut(phase) { Pair(ArrayList(), ArrayList()) }

    /**
     * Guided measurement with a live picture of both sticks, then a report and Apply.
     * See StickTuner for what is measured and how it becomes a calibration.
     */
    private fun startAutoTune() {
        if (ControllerService.modeFlow.value == ControllerService.InjectionMode.NONE ||
            ControllerService.stateFlow.value == null
        ) {
            Toast.makeText(this, getString(R.string.calib_autotune_need_service), Toast.LENGTH_LONG).show()
            return
        }
        tuneSamples.clear()

        val view = DialogAutotuneBinding.inflate(layoutInflater)
        // NestedScrollView makes itself focusable in its constructor, whatever the layout
        // says; focused, it ate D-pad DOWN, so the dialog buttons could not be reached.
        view.root.isFocusable = false
        view.root.isFocusableInTouchMode = false
        // Not cancelable by Back: the controller's B button is Back, and the controller is
        // in the user's hands for the whole measurement.
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelAutoTune() }
            .show().also { com.steamcontroller.android.ui.TvFocus.install(it) }

        fun showPhase(p: TunePhase) {
            view.ivStepIcon.setImageResource(p.icon)
            view.tvStepLabel.text = getString(R.string.calib_autotune_step, p.step)
            view.tvStepText.text = getString(p.text)
            view.ivStepIcon.alpha = 0f
            view.ivStepIcon.scaleX = 0.7f; view.ivStepIcon.scaleY = 0.7f
            view.ivStepIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }

        val recorder = lifecycleScope.launch {
            ControllerService.stateFlow.filterNotNull().collect { s ->
                val lx = s.leftJoyX.toInt(); val ly = s.leftJoyY.toInt()
                val rx = s.rightJoyX.toInt(); val ry = s.rightJoyY.toInt()
                view.traceLeft.setLive(lx, ly)
                view.traceRight.setLive(rx, ry)
                val phase = tunePhase ?: return@collect
                if (phase == TunePhase.REST || phase == TunePhase.SWEEP || phase == TunePhase.RELEASE) {
                    val bucket = samplesOf(phase)
                    bucket.first.add(intArrayOf(lx, ly))
                    bucket.second.add(intArrayOf(rx, ry))
                }
                if (phase == TunePhase.SWEEP) {
                    view.traceLeft.addReach(lx, ly)
                    view.traceRight.addReach(rx, ry)
                }
            }
        }

        tuneJob = lifecycleScope.launch {
            try {
                for (phase in TunePhase.values()) {
                    tunePhase = phase
                    showPhase(phase)
                    if (phase == TunePhase.SWEEP) {
                        // Centre from the rest step, so the traced shape is drawn around it.
                        val rest = samplesOf(TunePhase.REST)
                        centreOf(rest.first)?.let { view.traceLeft.setCenter(it[0], it[1]) }
                        centreOf(rest.second)?.let { view.traceRight.setCenter(it[0], it[1]) }
                    }
                    val totalMs = (phase.seconds * 1000).toLong()
                    val start = android.os.SystemClock.uptimeMillis()
                    while (true) {
                        val elapsed = android.os.SystemClock.uptimeMillis() - start
                        if (elapsed >= totalMs) break
                        view.progressStep.setProgressCompat((elapsed * 1000 / totalMs).toInt(), false)
                        view.tvCountdown.text = ((totalMs - elapsed + 999) / 1000).toString()
                        delay(50)
                    }
                }
                tunePhase = null
                recorder.cancel()
                dialog.dismiss()
                showAutoTuneReport()
            } finally {
                tunePhase = null
                recorder.cancel()
            }
        }
    }

    private fun centreOf(samples: List<IntArray>): IntArray? {
        if (samples.isEmpty()) return null
        val tail = samples.drop(samples.size / 3)
        return intArrayOf(tail.map { it[0] }.sorted()[tail.size / 2], tail.map { it[1] }.sorted()[tail.size / 2])
    }

    private fun cancelAutoTune() {
        tuneJob?.cancel()
        tuneJob = null
        tunePhase = null
    }

    private fun showAutoTuneReport() {
        val rest = samplesOf(TunePhase.REST)
        val sweep = samplesOf(TunePhase.SWEEP)
        val release = samplesOf(TunePhase.RELEASE)
        val left = StickTuner.analyze(rest.first, sweep.first, release.first, leftCal)
        val right = StickTuner.analyze(rest.second, sweep.second, release.second, rightCal)

        val report = buildString {
            append(describe(getString(R.string.calib_left_stick_label), leftCal, left))
            append("\n\n")
            append(describe(getString(R.string.calib_right_stick_label), rightCal, right))
        }
        android.util.Log.i(
            "StickTune",
            "samples rest=${rest.first.size} sweep=${sweep.first.size} release=${release.first.size}\n" +
                "left ${StickTuner.diagnostics(rest.first, release.first)}\n" +
                "right ${StickTuner.diagnostics(rest.second, release.second)}\n$report",
        )

        // Same picture as during the measurement, now with the proposed deadzone and
        // full-output ring drawn on the traced shape.
        val view = DialogAutotuneBinding.inflate(layoutInflater)
        // NestedScrollView makes itself focusable in its constructor, whatever the layout
        // says; focused, it ate D-pad DOWN, so the dialog buttons could not be reached.
        view.root.isFocusable = false
        view.root.isFocusableInTouchMode = false
        view.ivStepIcon.setImageResource(R.drawable.ic_tune_done)
        view.tvStepLabel.text = getString(R.string.calib_autotune_step_results)
        view.tvStepText.text = getString(R.string.calib_autotune_report_title)
        view.progressStep.visibility = android.view.View.GONE
        view.tvCountdown.visibility = android.view.View.GONE
        view.tvReportLeft.visibility = android.view.View.VISIBLE
        view.tvReportLeft.text = describe(null, leftCal, left)
        view.tvReportRight.visibility = android.view.View.VISIBLE
        view.tvReportRight.text = describe(null, rightCal, right)
        fun paint(trace: com.steamcontroller.android.ui.StickTraceView, samples: List<IntArray>,
                  r: StickTuner.Result) {
            centreOf(rest.first.let { if (trace === view.traceLeft) it else rest.second })
                ?.let { trace.setCenter(it[0], it[1]); trace.setLive(it[0], it[1]) }
            samples.forEach { trace.addReach(it[0], it[1]) }
            if (r.problem == null) {
                trace.setZones(r.calibration.deadzonePercent / 100f, r.calibration.outerPercent / 100f)
            }
        }
        paint(view.traceLeft, sweep.first, left)
        paint(view.traceRight, sweep.second, right)

        val usable = left.problem == null || right.problem == null
        val builder = MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setNeutralButton(R.string.calib_autotune_retry) { _, _ -> startAutoTune() }
            .setNegativeButton(R.string.calib_autotune_discard, null)
        if (usable) {
            builder.setPositiveButton(R.string.calib_autotune_apply) { _, _ ->
                if (left.problem == null) leftCal = left.calibration
                if (right.problem == null) rightCal = right.calibration
                saveLeft(); saveRight()
                bindUiFromState()
                android.util.Log.i("StickTune", "Applied left=$leftCal right=$rightCal")
                Toast.makeText(this, getString(R.string.calib_autotune_applied), Toast.LENGTH_SHORT).show()
            }
        }
        com.steamcontroller.android.ui.TvFocus.install(builder.show())
    }

    /** Report lines for one stick; [name] prefixes them when shown outside its column. */
    private fun describe(name: String?, before: StickCalibration, r: StickTuner.Result): String {
        val head = if (name != null) "$name\n" else ""
        if (r.problem != null) return head + r.problem
        val after = r.calibration
        return head +
            "• Centre was off %.1f%% — fixed\n".format(r.centerOffsetPct) +
            "• Jitter %.1f%%, returns within %.1f%%\n".format(r.jitterPct, r.returnErrorPct) +
            "• Reach %.0f%% weakest, %.0f%% typical\n".format(r.reachWeakestPct, r.reachTypicalPct) +
            "• Deadzone ${before.deadzonePercent}% → ${after.deadzonePercent}%\n" +
            "• Full output at ${before.outerPercent}% → ${after.outerPercent}%"
    }

    private fun saveLeft()  = Prefs.setLeftCalibration(this, leftCal)
    private fun saveRight() = Prefs.setRightCalibration(this, rightCal)

    private fun resetAll() {
        leftCal = StickCalibration()
        rightCal = StickCalibration()
        saveLeft(); saveRight()
        bindUiFromState()
    }
}
