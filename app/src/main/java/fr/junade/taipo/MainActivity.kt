package fr.junade.taipo

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View

class MainActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        findViewById<View>(R.id.button_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.button_select).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS))
        }
        findViewById<View>(R.id.button_keyboard_settings).setOnClickListener {
            startActivity(Intent(this, KeyboardSettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_model_settings).setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_system_prompts).setOnClickListener {
            startActivity(Intent(this, SystemPromptsActivity::class.java))
        }
        findViewById<View>(R.id.button_voice_model_settings).setOnClickListener {
            startActivity(Intent(this, VoiceModelSettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_personal_dictionary).setOnClickListener {
            startActivity(Intent(this, PersonalDictionaryActivity::class.java))
        }
    }
}