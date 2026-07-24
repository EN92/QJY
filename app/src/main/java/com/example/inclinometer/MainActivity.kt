package com.example.inclinometer

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.ActivityNotFoundException
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.inclinometer.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var tts: TextToSpeech? = null

    // 滤波
    private val alphaAccel = 0.12f
    private val alphaAngle = 0.18f
    private val filteredAccel = FloatArray(3)
    private var filteredInit = false
    private var filteredPitch = 0f
    private var filteredRoll = 0f

    // 校准
    private var offsetPitch = 0f
    private var offsetRoll = 0f
    private var isCalibrated = false
    private val calibrationSamples = 50
    private val calibrationBufferPitch = FloatArray(calibrationSamples)
    private val calibrationBufferRoll = FloatArray(calibrationSamples)
    private var calibrationIndex = 0
    private var calibrationCount = 0
    private var isCalibrating = false

    // Delta
    private var deltaPitch = 0f
    private var deltaRoll = 0f
    private var hasDelta = false

    // Hold
    private var isHeld = false
    private var heldPitch = 0f
    private var heldRoll = 0f

    // 震动
    private var vibrator: Vibrator? = null
    private var lastVibrateTime = 0L
    private var lastEdgeVibrateTime = 0L
    private var lastLevelSpeakTime = 0L
    private val vibrateIntervalLevel = 800L
    private val vibrateIntervalEdge = 1200L
    private var vibrateEnabled = true
    private var ttsEnabled = false

    // 单位: 0=度, 1=弧度, 2=坡度百分比
    private var unitMode = 0

    // 夜间模式
    private var nightMode = false
    private var originalBrightness = 0.5f

    // 水平语音播报
    private var wasAtLevel = false

    // 屏幕方向锁定
    private var isOrientationLocked = false

    // 缓存
    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 角度吸附
    private var lastSnapAngle = -1
    private val snapAngles = intArrayOf(15, 30, 45, 60, 75, 90)
    private val snapThreshold = 1.5f

    private val prefs by lazy { getSharedPreferences("inclinometer", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalBrightness = window.attributes.screenBrightness

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        tts = TextToSpeech(this, this)

        if (accelerometer == null) {
            Toast.makeText(this, R.string.toast_no_sensor, Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.status_no_sensor)
            binding.layoutControls.visibility = View.GONE
            binding.layoutVibrateToggle.visibility = View.GONE
            return
        }

        loadPreferences()
        restoreInstanceState(savedInstanceState)

        // Centralized state application — runs once after both prefs and bundle
        if (isOrientationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        if (isHeld) {
            binding.inclinometerView.isHeld = true
        }
        syncAllButtons()
        applyNightMode(nightMode)
        if (hasDelta) {
            binding.btnDelta.text = getString(R.string.btn_clear_zero)
            binding.btnDelta.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF9800")
            )
            binding.tvDeltaLabel.visibility = View.VISIBLE
            binding.tvDelta.visibility = View.VISIBLE
            binding.tvDelta.text = getString(R.string.delta_prefix) + formatAngle(0f)
        }

        val hasSeenGuide = prefs.getBoolean("has_seen_guide", false)
        if (!hasSeenGuide) {
            showOrientationGuide()
            prefs.edit().putBoolean("has_seen_guide", true).apply()
        }

        setupListeners()
        binding.progressCalibration.visibility = View.GONE
    }

    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        isHeld = savedInstanceState.getBoolean("is_held", false)
        heldPitch = savedInstanceState.getFloat("held_pitch", 0f)
        heldRoll = savedInstanceState.getFloat("held_roll", 0f)
        hasDelta = savedInstanceState.getBoolean("has_delta", false)
        deltaPitch = savedInstanceState.getFloat("delta_pitch", 0f)
        deltaRoll = savedInstanceState.getFloat("delta_roll", 0f)
        unitMode = savedInstanceState.getInt("unit_mode", 0)
        nightMode = savedInstanceState.getBoolean("night_mode", false)
        vibrateEnabled = savedInstanceState.getBoolean("vibrate_enabled", true)
        ttsEnabled = savedInstanceState.getBoolean("tts_enabled", false)
        isOrientationLocked = savedInstanceState.getBoolean("orientation_locked", false)
        isCalibrated = savedInstanceState.getBoolean("is_calibrated", isCalibrated)
        offsetPitch = savedInstanceState.getFloat("offset_pitch", offsetPitch)
        offsetRoll = savedInstanceState.getFloat("offset_roll", offsetRoll)
        lastSnapAngle = savedInstanceState.getInt("last_snap_angle", -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_held", isHeld)
        outState.putFloat("held_pitch", heldPitch)
        outState.putFloat("held_roll", heldRoll)
        outState.putBoolean("has_delta", hasDelta)
        outState.putFloat("delta_pitch", deltaPitch)
        outState.putFloat("delta_roll", deltaRoll)
        outState.putInt("unit_mode", unitMode)
        outState.putBoolean("night_mode", nightMode)
        outState.putBoolean("vibrate_enabled", vibrateEnabled)
        outState.putBoolean("tts_enabled", ttsEnabled)
        outState.putBoolean("orientation_locked", isOrientationLocked)
        outState.putInt("last_snap_angle", lastSnapAngle)
        outState.putBoolean("is_calibrated", isCalibrated)
        outState.putFloat("offset_pitch", offsetPitch)
        outState.putFloat("offset_roll", offsetRoll)
    }

    private fun loadPreferences() {
        offsetPitch = prefs.getFloat("offset_pitch", 0f)
        offsetRoll = prefs.getFloat("offset_roll", 0f)
        isCalibrated = prefs.getBoolean("is_calibrated", false)
        vibrateEnabled = prefs.getBoolean("vibrate_enabled", true)
        ttsEnabled = prefs.getBoolean("tts_enabled", false)
        unitMode = prefs.getInt("unit_mode", 0)
        nightMode = prefs.getBoolean("night_mode", false)
        isOrientationLocked = prefs.getBoolean("orientation_locked", false)
    }

    private fun savePreferences() {
        prefs.edit()
            .putFloat("offset_pitch", offsetPitch)
            .putFloat("offset_roll", offsetRoll)
            .putBoolean("is_calibrated", isCalibrated)
            .putBoolean("vibrate_enabled", vibrateEnabled)
            .putBoolean("tts_enabled", ttsEnabled)
            .putInt("unit_mode", unitMode)
            .putBoolean("night_mode", nightMode)
            .putBoolean("orientation_locked", isOrientationLocked)
            .apply()
    }

    private fun setupListeners() {
        binding.btnCalibrate.setOnClickListener {
            if (isCalibrating) cancelCalibration() else startCalibration()
        }
        binding.btnReset.setOnClickListener { resetCalibration() }
        binding.btnDelta.setOnClickListener { if (hasDelta) clearDelta() else setDelta() }

        binding.btnHold.setOnClickListener {
            isHeld = !isHeld
            if (isHeld) {
                heldPitch = filteredPitch
                heldRoll = filteredRoll
                Toast.makeText(this, R.string.toast_hold, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_release, Toast.LENGTH_SHORT).show()
            }
            syncHoldButton()
            binding.inclinometerView.isHeld = isHeld
        }

        binding.btnUnit.setOnClickListener {
            unitMode = (unitMode + 1) % 3
            syncUnitButton()
            prefs.edit().putInt("unit_mode", unitMode).apply()
        }

        binding.btnVibrate.setOnClickListener {
            vibrateEnabled = !vibrateEnabled
            syncVibrateButton()
            savePreferences()
        }

        binding.btnNightMode.setOnClickListener {
            nightMode = !nightMode
            applyNightMode(nightMode)
            syncNightModeButton()
            savePreferences()
        }

        binding.btnVibrate.setOnLongClickListener {
            ttsEnabled = !ttsEnabled
            Toast.makeText(this,
                if (ttsEnabled) getString(R.string.tts_on) else getString(R.string.tts_off),
                Toast.LENGTH_SHORT).show()
            savePreferences()
            true
        }

        binding.btnShare.setOnClickListener { shareMeasurement() }

        binding.btnLockOrientation.setOnClickListener {
            isOrientationLocked = !isOrientationLocked
            requestedOrientation = if (isOrientationLocked) {
                ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            syncOrientationLockButton()
            savePreferences()
        }
    }

    private fun syncAllButtons() {
        syncHoldButton()
        syncUnitButton()
        syncVibrateButton()
        syncNightModeButton()
        syncOrientationLockButton()
    }

    private fun syncHoldButton() {
        if (isHeld) {
            binding.btnHold.text = getString(R.string.btn_release)
            binding.btnHold.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E91E63")
            )
        } else {
            binding.btnHold.text = getString(R.string.btn_hold)
            binding.btnHold.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#9C27B0")
            )
        }
    }

    private fun syncUnitButton() {
        val labels = arrayOf(getString(R.string.unit_degree), getString(R.string.unit_radian), getString(R.string.unit_grade))
        binding.btnUnit.text = labels[unitMode]
    }

    private fun syncVibrateButton() {
        if (vibrateEnabled) {
            binding.btnVibrate.text = getString(R.string.btn_vibrate_on)
            binding.btnVibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#4CAF50")
            )
        } else {
            binding.btnVibrate.text = getString(R.string.btn_vibrate_off)
            binding.btnVibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#666666")
            )
        }
    }

    private fun syncNightModeButton() {
        if (nightMode) {
            binding.btnNightMode.text = getString(R.string.btn_day_mode)
            binding.btnNightMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#333333")
            )
        } else {
            binding.btnNightMode.text = getString(R.string.btn_night_mode)
            binding.btnNightMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#555555")
            )
        }
    }

    private fun syncOrientationLockButton() {
        binding.btnLockOrientation.text = getString(
            if (isOrientationLocked) R.string.btn_unlock else R.string.btn_lock
        )
    }

    private fun applyNightMode(enabled: Boolean) {
        val params = window.attributes
        if (enabled) {
            params.screenBrightness = 0.08f
            binding.root.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
            binding.topBar.setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        } else {
            params.screenBrightness = if (originalBrightness >= 0f) originalBrightness else 0.5f
            binding.root.setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            binding.topBar.setBackgroundColor(android.graphics.Color.parseColor("#16213E"))
        }
        window.attributes = params
        binding.inclinometerView.isNightMode = enabled
    }

    // ========== 校准 ==========

    private fun showOrientationGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_guide_title)
            .setMessage(R.string.dialog_guide_message)
            .setPositiveButton(R.string.dialog_guide_ok) { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun startCalibration() {
        isCalibrating = true
        calibrationIndex = 0
        calibrationCount = 0
        binding.tvStatus.text = getString(R.string.status_calibrating)
        binding.btnCalibrate.text = getString(R.string.btn_cancel)
        binding.btnCalibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FF5252")
        )
        binding.progressCalibration.visibility = View.VISIBLE
        binding.progressCalibration.progress = 0
        binding.progressCalibration.max = calibrationSamples
        binding.tvCalibrationCount.visibility = View.VISIBLE
        binding.tvCalibrationCount.text = getString(R.string.calibration_format, 0, calibrationSamples)
    }

    private fun cancelCalibration() {
        isCalibrating = false
        binding.tvStatus.text = if (isCalibrated) getString(R.string.status_measuring) else getString(R.string.status_uncalibrated)
        binding.btnCalibrate.text = getString(R.string.btn_calibrate)
        binding.btnCalibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#4CAF50")
        )
        binding.progressCalibration.visibility = View.GONE
        binding.tvCalibrationCount.visibility = View.GONE
        Toast.makeText(this, R.string.toast_calibrate_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun finishCalibration() {
        val count = minOf(calibrationCount, calibrationSamples)
        val validPitch = calibrationBufferPitch.take(count)
        val validRoll = calibrationBufferRoll.take(count)

        if (count >= 10) {
            val stdP = calculateStd(validPitch)
            val stdR = calculateStd(validRoll)

            if (stdP > 2.5f || stdR > 2.5f) {
                isCalibrating = false
                binding.tvStatus.text = if (isCalibrated) getString(R.string.status_measuring) else getString(R.string.status_uncalibrated)
                binding.btnCalibrate.text = getString(R.string.btn_calibrate)
                binding.btnCalibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#4CAF50")
                )
                binding.progressCalibration.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_calibrate_fail_title)
                    .setMessage(getString(R.string.dialog_calibrate_fail_message, maxOf(stdP, stdR)))
                    .setPositiveButton(R.string.dialog_ok) { d, _ -> d.dismiss() }
                    .show()
                return
            }

            val meanP = validPitch.average().toFloat()
            val meanR = validRoll.average().toFloat()
            val filteredP = validPitch.filter { abs(it - meanP) <= 2f * stdP }
            val filteredR = validRoll.filter { abs(it - meanR) <= 2f * stdR }

            offsetPitch = if (filteredP.isNotEmpty()) filteredP.average().toFloat() else meanP
            offsetRoll = if (filteredR.isNotEmpty()) filteredR.average().toFloat() else meanR
        } else {
            offsetPitch = validPitch.average().toFloat()
            offsetRoll = validRoll.average().toFloat()
        }

        isCalibrated = true
        isCalibrating = false
        hasDelta = false
        savePreferences()

        binding.tvStatus.text = getString(R.string.status_calibrated)
        binding.btnCalibrate.text = getString(R.string.btn_calibrate)
        binding.btnCalibrate.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#4CAF50")
        )
        binding.progressCalibration.visibility = View.GONE
        binding.tvCalibrationCount.visibility = View.GONE
        clearDeltaUi()
        Toast.makeText(this, getString(R.string.toast_calibrate_done, offsetPitch, offsetRoll), Toast.LENGTH_LONG).show()
    }

    private fun calculateStd(values: List<Float>): Float {
        val mean = values.average().toFloat()
        return sqrt(values.map { (it - mean) * (it - mean) }.average().toDouble()).toFloat()
    }

    private fun resetCalibration() {
        offsetPitch = 0f
        offsetRoll = 0f
        isCalibrated = false
        hasDelta = false
        isHeld = false
        filteredInit = false
        prefs.edit()
            .remove("offset_pitch")
            .remove("offset_roll")
            .remove("is_calibrated")
            .apply()
        syncAllButtons()
        binding.tvStatus.text = getString(R.string.status_reset)
        binding.inclinometerView.isHeld = false
        clearDeltaUi()
        Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show()
    }

    // ========== Delta ==========

    private fun setDelta() {
        val (dp, dr) = if (isHeld) Pair(heldPitch, heldRoll) else Pair(filteredPitch, filteredRoll)
        deltaPitch = dp
        deltaRoll = dr
        hasDelta = true
        binding.btnDelta.text = getString(R.string.btn_clear_zero)
        binding.btnDelta.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FF9800")
        )
        binding.tvDeltaLabel.visibility = View.VISIBLE
        binding.tvDelta.visibility = View.VISIBLE
        binding.tvDelta.text = getString(R.string.delta_prefix) + "0.0°"
        Toast.makeText(this, R.string.toast_reference_set, Toast.LENGTH_SHORT).show()
    }

    private fun clearDelta() {
        hasDelta = false
        clearDeltaUi()
        Toast.makeText(this, R.string.toast_reference_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun clearDeltaUi() {
        binding.btnDelta.text = getString(R.string.btn_zero)
        binding.btnDelta.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#2196F3")
        )
        binding.tvDeltaLabel.visibility = View.GONE
        binding.tvDelta.visibility = View.GONE
    }

    // ========== 单位转换 ==========

    private fun formatAngle(degrees: Float): String {
        return when (unitMode) {
            1 -> String.format("%.3f rad", Math.toRadians(degrees.toDouble()))
            2 -> String.format("%.1f%%", kotlin.math.tan(Math.toRadians(degrees.toDouble())) * 100.0)
            else -> String.format("%.1f°", degrees)
        }
    }

    private fun formatSlope(degrees: Float): String {
        val gradePercent = kotlin.math.tan(Math.toRadians(degrees.toDouble())) * 100.0
        return if (degrees < 0.01f) {
            getString(R.string.slope_level)
        } else {
            val ratio = 100.0 / gradePercent
            getString(R.string.slope_prefix) + String.format("%.1f", gradePercent) + "%" +
                getString(R.string.slope_ratio, String.format("%.0f", ratio))
        }
    }

    // ========== 传感器 ==========

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (accelerometer != null) {
            binding.tvStatus.text = if (isCalibrated) getString(R.string.status_measuring) else getString(R.string.status_uncalibrated)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        binding.tvStatus.text = getString(R.string.status_paused)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        if (!filteredInit) {
            filteredAccel[0] = gx
            filteredAccel[1] = gy
            filteredAccel[2] = gz
            filteredInit = true
        } else {
            filteredAccel[0] += alphaAccel * (gx - filteredAccel[0])
            filteredAccel[1] += alphaAccel * (gy - filteredAccel[1])
            filteredAccel[2] += alphaAccel * (gz - filteredAccel[2])
        }

        val gNorm = sqrt(filteredAccel[0] * filteredAccel[0] + filteredAccel[1] * filteredAccel[1] + filteredAccel[2] * filteredAccel[2])
        if (gNorm < 0.01f) return

        val nx = filteredAccel[0] / gNorm
        val ny = filteredAccel[1] / gNorm
        val nz = filteredAccel[2] / gNorm

        val rawPitch = Math.toDegrees(atan2(nx.toDouble(), nz.toDouble())).toFloat()
        val rawRoll = Math.toDegrees(atan2(ny.toDouble(), nz.toDouble())).toFloat()

        if (isCalibrating) {
            calibrationBufferPitch[calibrationIndex] = rawPitch
            calibrationBufferRoll[calibrationIndex] = rawRoll
            calibrationIndex = (calibrationIndex + 1) % calibrationSamples
            calibrationCount++
            binding.progressCalibration.progress = minOf(calibrationCount, calibrationSamples)
            binding.tvCalibrationCount.text = getString(R.string.calibration_format, minOf(calibrationCount, calibrationSamples), calibrationSamples)
            if (calibrationCount >= calibrationSamples) finishCalibration()
            return
        }

        var calPitch = rawPitch - offsetPitch
        var calRoll = rawRoll - offsetRoll
        if (calPitch > 180f) calPitch -= 360f
        if (calPitch < -180f) calPitch += 360f
        if (calRoll > 180f) calRoll -= 360f
        if (calRoll < -180f) calRoll += 360f

        if (!isHeld) {
            filteredPitch += alphaAngle * (calPitch - filteredPitch)
            filteredRoll += alphaAngle * (calRoll - filteredRoll)
        }

        updateUI()
    }

    // ========== UI更新 ==========

    private fun updateUI() {
        val srcPitch = if (isHeld) heldPitch else filteredPitch
        val srcRoll = if (isHeld) heldRoll else filteredRoll

        val displayPitch = if (hasDelta) srcPitch - deltaPitch else srcPitch
        val displayRoll = if (hasDelta) srcRoll - deltaRoll else srcRoll
        val displayTilt = sqrt(displayPitch * displayPitch + displayRoll * displayRoll)

        binding.inclinometerView.pitchAngle = displayPitch
        binding.inclinometerView.rollAngle = displayRoll

        val pitchSign = if (displayPitch >= 0) "+" else ""
        val rollSign = if (displayRoll >= 0) "+" else ""
        binding.tvPitch.text = "$pitchSign${formatAngle(displayPitch)}"
        binding.tvRoll.text = "$rollSign${formatAngle(displayRoll)}"
        binding.tvCombined.text = formatAngle(displayTilt)

        // 坡度
        binding.tvSlope.text = formatSlope(displayTilt)

        // 方向
        binding.tvDirectionPitch.text = when {
            displayPitch > 1f -> getString(R.string.direction_pitch_up)
            displayPitch < -1f -> getString(R.string.direction_pitch_down)
            else -> getString(R.string.direction_level)
        }
        binding.tvDirectionRoll.text = when {
            displayRoll > 1f -> getString(R.string.direction_roll_right)
            displayRoll < -1f -> getString(R.string.direction_roll_left)
            else -> getString(R.string.direction_level)
        }

        // Delta
        if (hasDelta) {
            binding.tvDeltaLabel.visibility = View.VISIBLE
            binding.tvDelta.visibility = View.VISIBLE
            binding.tvDelta.text = getString(R.string.delta_prefix) + formatAngle(displayTilt)
        } else {
            binding.tvDeltaLabel.visibility = View.GONE
            binding.tvDelta.visibility = View.GONE
        }

        val absPitch = abs(displayPitch)
        val absRoll = abs(displayRoll)
        val maxAngle = maxOf(absPitch, absRoll)

        // 状态
        val statusBase = when {
            isCalibrating -> getString(R.string.status_calibrating)
            !isCalibrated -> getString(R.string.status_uncalibrated)
            maxAngle < 0.3f -> getString(R.string.status_level)
            maxAngle < 1.5f -> getString(R.string.status_micro_tilt)
            maxAngle < 8f -> getString(R.string.status_tilting)
            else -> getString(R.string.status_large_angle)
        }
        val statusText = buildString {
            append(statusBase)
            if (isHeld) append(getString(R.string.status_hold_suffix))
            if (hasDelta) append(getString(R.string.status_delta_suffix))
        }
        binding.tvStatus.text = statusText

        binding.inclinometerView.isAtLevel = maxAngle < 0.5f
        binding.inclinometerView.isAtEdge = maxAngle > 75f

        // 角度吸附提示
        updateSnapIndicator(displayTilt)

        // 震动 + 语音
        if (vibrateEnabled && !isHeld) {
            if (maxAngle < 0.3f) attemptLevelVibrate()
            if (maxAngle > 75f) attemptEdgeVibrate()
        }

        // 水平语音播报
        val isNowLevel = maxAngle < 0.5f
        if (ttsEnabled && !isHeld && isNowLevel && !wasAtLevel) {
            val now = System.currentTimeMillis()
            if (now - lastLevelSpeakTime > 2000L) {
                tts?.speak(getString(R.string.tts_level), TextToSpeech.QUEUE_FLUSH, null, "level")
                lastLevelSpeakTime = now
            }
        }
        wasAtLevel = isNowLevel
    }

    private fun updateSnapIndicator(tiltAngle: Float) {
        val mainAngle = abs(tiltAngle)
        var nearestSnap = -1
        for (angle in snapAngles) {
            if (abs(mainAngle - angle.toFloat()) <= snapThreshold) {
                nearestSnap = angle
                break
            }
        }

        if (nearestSnap != -1) {
            if (nearestSnap != lastSnapAngle) {
                binding.tvSnap.text = getString(R.string.snap_format, nearestSnap)
                if (vibrateEnabled) doVibrate(20)
            }
            binding.tvSnap.visibility = View.VISIBLE
        } else {
            binding.tvSnap.visibility = View.GONE
        }
        lastSnapAngle = nearestSnap
    }

    private fun attemptLevelVibrate() {
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime > vibrateIntervalLevel) {
            doVibrate(30)
            lastVibrateTime = now
        }
    }

    private fun attemptEdgeVibrate() {
        val now = System.currentTimeMillis()
        if (now - lastEdgeVibrateTime > vibrateIntervalEdge) {
            doVibrate(50)
            lastEdgeVibrateTime = now
        }
    }

    @Suppress("DEPRECATION")
    private fun doVibrate(durationMs: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(durationMs)
        }
    }

    // ========== 分享 ==========

    private fun shareMeasurement() {
        val dp = if (isHeld) heldPitch else filteredPitch
        val dr = if (isHeld) heldRoll else filteredRoll
        val dpDisplay = if (hasDelta) dp - deltaPitch else dp
        val drDisplay = if (hasDelta) dr - deltaRoll else dr
        val tilt = sqrt(dpDisplay * dpDisplay + drDisplay * drDisplay)
        val timestamp = dateFormat.format(java.util.Date())
        val body = getString(R.string.share_body,
            formatAngle(tilt),
            formatAngle(dpDisplay),
            formatAngle(drDisplay),
            formatSlope(tilt),
            timestamp)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }

    // ========== TTS ==========

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINESE
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (isCalibrating) return
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> binding.tvStatus.text = getString(R.string.status_reliable)
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> binding.tvStatus.text = getString(R.string.status_accuracy_low)
            else -> {
                if (!isHeld) {
                    binding.tvStatus.text = if (isCalibrated) getString(R.string.status_measuring) else getString(R.string.status_uncalibrated)
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
