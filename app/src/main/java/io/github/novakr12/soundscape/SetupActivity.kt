package io.github.novakr12.soundscape

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val root = findViewById<android.view.View>(R.id.setup_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        matchBarsToTheme()
        val address = findViewById<EditText>(R.id.server_address)
        val status = findViewById<TextView>(R.id.setup_status)
        val connect = findViewById<Button>(R.id.connect)
        Settings(this).server?.let { address.setText(it) }
        val go = {
            val server = Server.normalize(address.text.toString())
            if (server == null) {
                status.text = getString(R.string.setup_bad_address)
            } else {
                connect.isEnabled = false
                status.text = getString(R.string.setup_checking)
                Pools.io.execute {
                    val problem = check(server)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        connect.isEnabled = true
                        if (problem == null) {
                            val settings = Settings(this)
                            if (settings.server != server) settings.forgetConnection()
                            settings.server = server
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        } else {
                            status.text = problem
                        }
                    }
                }
            }
        }
        connect.setOnClickListener { go() }
        address.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) go()
            action == EditorInfo.IME_ACTION_GO
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        matchBarsToTheme()
    }

    private fun check(server: String): String? = try {
        val answer = Net.get(
            address = Server.infoAddress(server),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
            accept = "application/json",
            maxBytes = MAX_INFO_BYTES,
        )
        if (answer.status == 200 && answer.body != null && Server.looksLikeSoundscape(answer.body)) {
            null
        } else {
            getString(R.string.setup_not_soundscape)
        }
    } catch (error: Exception) {
        getString(R.string.setup_unreachable, error.localizedMessage ?: error.javaClass.simpleName)
    }

    private companion object {
        const val MAX_INFO_BYTES = 64 * 1024
    }
}
