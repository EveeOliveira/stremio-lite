package app.stremiolite.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.stremiolite.R
import app.stremiolite.remote.RemoteServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Mostra o QR code e o codigo para parear o celular. */
class RemoteActivity : ComponentActivity() {
    private lateinit var toggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        toggle = findViewById(R.id.btnToggle)
        RemoteServer.enabled = true
        toggle.setOnClickListener { RemoteServer.enabled = !RemoteServer.enabled; refresh() }
        findViewById<TextView>(R.id.btnNewCode).setOnClickListener { RemoteServer.newCode(); refresh() }
        toggle.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = RemoteServer.enabled
        val address = if (on) RemoteServer.address() else null
        val code = RemoteServer.code
        val qr = findViewById<ImageView>(R.id.qr)
        val info = findViewById<TextView>(R.id.address)
        val codeView = findViewById<TextView>(R.id.code)
        toggle.text = if (on) "Desativar" else "Ativar"
        findViewById<View>(R.id.btnNewCode).visibility = if (on) View.VISIBLE else View.GONE
        if (address == null) {
            qr.visibility = View.INVISIBLE
            codeView.text = ""
            info.text = if (on) "Sem conexão com a rede local." else "Controle desativado."
            return
        }
        qr.visibility = View.VISIBLE
        qr.setImageBitmap(qrBitmap("$address/#c=$code", dp(220)))
        codeView.text = "${code.take(3)} ${code.drop(3)}"
        info.text = "ou abra $address"
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
