package com.ven.assists.simple

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 原「无障碍开启引导」全屏页；入口 logic 已在 [MainActivity] 注释。
 * 保留 Activity 仅占位 Manifest，避免外部仍传入 Intent 时白屏。
 */
class SettingGuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*
        android.graphics.Color (BarUtils)
        BarUtils.setStatusBarColor(this, android.graphics.Color.TRANSPARENT)
        CoroutineWrapper.launch {
            delay(500)
            withContext(Dispatchers.Main) {
                SettingGuideBinding.inflate(layoutInflater).apply {
                    setContentView(root)
                    ivClose.setOnClickListener {
                        finish()
                    }
                }
            }
        }
        */
        finish()
    }
}
