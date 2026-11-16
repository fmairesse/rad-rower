package io.radrower

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class HealthConnectRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.health_connect_permission_rationale)
                textSize = 18f
                setPadding(32, 32, 32, 32)
            },
        )
    }
}
