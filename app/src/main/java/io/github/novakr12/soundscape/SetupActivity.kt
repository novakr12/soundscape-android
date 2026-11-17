package io.github.novakr12.soundscape

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SetupActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val root = findViewById<android.view.View>(R.id.setup_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
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
                worker.execute {
                    val problem = check(server)
                    runOnUiThread {
                        connect.isEnabled = true
                        if (problem == null) {
                            Settings(this).server = server
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

    private fun check(server: String): String? = try {
        val connection = URL(Server.infoAddress(server)).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (connection.responseCode == 200 && Server.looksLikeSoundscape(body)) {
                null
            } else {
                getString(R.string.setup_not_soundscape)
            }
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        getString(R.string.setup_unreachable, error.localizedMessage ?: error.javaClass.simpleName)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
