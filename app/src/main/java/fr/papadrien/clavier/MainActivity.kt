package fr.papadrien.clavier

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.button_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.button_select).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS))
        }
        findViewById<View>(R.id.button_model_settings).setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_voice_model_settings).setOnClickListener {
            startActivity(Intent(this, VoiceModelSettingsActivity::class.java))
        }
    }
}