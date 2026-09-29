package com.example.minapp

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlay = OverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            text = "Carrom Vision — tap Predict"
        }
        val btn = Button(this).apply {
            text = "AI Suggest Shot (offline algorithm)"
            setOnClickListener { runPrediction() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(btn)
            addView(overlay)
        }
        setContentView(root)
        runPrediction()
    }

    private fun runPrediction() {
        val (striker, coins) = Predictor.demoState()
        val pred = Predictor.fullPrediction(striker, coins)
        overlay.update(striker, coins, pred)
        status.text = pred.best?.let {
            "BEST: ${it.reason} angle=${"%.1f".format(it.angleDeg)} (0=up,90=right) score=${"%.3f".format(it.score)}"
        } ?: "No clean pot found"
    }
}
