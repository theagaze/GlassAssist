package com.glassassist

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.glassassist.core.Prefs
import com.glassassist.core.RootShellManager
import com.glassassist.service.FloatingBallService

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val rootIcon: ImageView by lazy { findViewById(R.id.statusRoot) }
    private val serviceStatus: TextView by lazy { findViewById(R.id.serviceStatus) }
    private val autoGrantStatus: TextView by lazy { findViewById(R.id.autoGrantStatus) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (prefs.rootProvisioned) {
                FloatingBallService.start(this)
                handler.postDelayed({ refreshStatus() }, 700L)
            } else {
                verifyAndProvision(showDialog = true, startAfter = true)
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingBallService::class.java))
            prefs.commitServiceEnabled(false)
            Toast.makeText(this, "Overlay parado", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refreshStatus() }, 300L)
        }
        findViewById<Button>(R.id.btnRoot).setOnClickListener {
            verifyAndProvision(showDialog = true, startAfter = false)
        }
        findViewById<Button>(R.id.btnApply).setOnClickListener {
            FloatingBallService.refreshSettings()
            Toast.makeText(this, "Preferências salvas e aplicadas", Toast.LENGTH_SHORT).show()
        }

        setupControls()

        if (!prefs.rootProvisioned) {
            // Só religa automaticamente se a última inicialização terminou bem-sucedida.
            // Após um crash o handler grava serviceEnabled=false e este auto-start não ocorre.
            verifyAndProvision(showDialog = true, startAfter = prefs.serviceEnabled)
        }
    }

    override fun onResume() {
        super.onResume()
        syncControls()
        refreshStatus()
        // Abrir as configurações não pode fazer a bolinha sumir.
        // O serviço verifica o keyguard antes de mostrar qualquer janela.
        if (FloatingBallService.isRunning) FloatingBallService.ensureOverlayVisible(this)
    }

    private fun verifyAndProvision(showDialog: Boolean, startAfter: Boolean) {
        val button = findViewById<Button>(R.id.btnRoot)
        button.isEnabled = false
        button.text = "Verificando…"
        autoGrantStatus.text = "Aguardando autorização do shell su (KernelSU Manager → Superuser)…"

        RootShellManager.checkRootAsync { ok, message ->
            if (!ok) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    button.text = "Verificar"
                    setStatus(rootIcon, false)
                    autoGrantStatus.text = message
                    if (showDialog) {
                        AlertDialog.Builder(this)
                            .setTitle("Root não autorizado")
                            .setMessage(
                                "$message\n\nAbra KernelSU Manager → Superuser e ative o toggle do " +
                                    "com.glassassist. O KernelSU não exibe pop-up automático."
                            )
                            .setPositiveButton("Entendi", null)
                            .show()
                    }
                }
                return@checkRootAsync
            }

            RootShellManager.provisionSelfAsync(this) { granted, grantMessage ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    button.text = "Verificar"
                    setStatus(rootIcon, granted)
                    prefs.commitRootProvisioned(granted)
                    autoGrantStatus.text = grantMessage
                    if (granted && startAfter && !FloatingBallService.isRunning) {
                        FloatingBallService.start(this)
                    }
                    if (showDialog) {
                        AlertDialog.Builder(this)
                            .setTitle(if (granted) "Root autorizado" else "Falha na concessão")
                            .setMessage(grantMessage)
                            .setPositiveButton("Entendi", null)
                            .show()
                    }
                    refreshStatus()
                }
            }
        }
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        try {
        setStatus(rootIcon, prefs.rootProvisioned)
        val shell = if (RootShellManager.connected) "shell conectado" else "shell aguardando"
        serviceStatus.text = if (FloatingBallService.isRunning) {
            "Serviço: ATIVO · overlay protegido · $shell"
        } else {
            "Serviço: parado · $shell"
        }
        autoGrantStatus.text = if (prefs.rootProvisioned) {
            "Permissões concedidas automaticamente pelo root: overlay, WRITE_SECURE_SETTINGS, " +
                "notificações, câmera e whitelist de bateria."
        } else {
            "Aguardando autorização root. Ative com.glassassist em KernelSU Manager → Superuser."
        }
        } catch (_: Throwable) {
            // Activity pode ter sido destruída enquanto o callback chegava.
        }
    }

    private fun setStatus(icon: ImageView, ok: Boolean) {
        icon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)
    }

    private fun setupControls() {
        findViewById<SeekBar>(R.id.seekSize).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.ballSizeDp = progress + 48
            findViewById<TextView>(R.id.lblSize).text = "Tamanho da bolinha: ${prefs.ballSizeDp} dp"
        })
        findViewById<SeekBar>(R.id.seekAlpha).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.inactiveAlpha = progress / 100f
            findViewById<TextView>(R.id.lblAlpha).text = "Opacidade inativa: $progress%"
        })
        findViewById<SeekBar>(R.id.seekBlur).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.blurRadius = progress
            findViewById<TextView>(R.id.lblBlur).text = "Desfoque do painel: $progress"
        })
        findViewById<RadioGroup>(R.id.rgBlurMode).setOnCheckedChangeListener { _, checkedId ->
            prefs.blurMode = when (checkedId) {
                R.id.rbBlurNative -> Prefs.BLUR_MODE_NATIVE
                R.id.rbBlurVisual -> Prefs.BLUR_MODE_VISUAL
                else -> Prefs.BLUR_MODE_AUTO
            }
        }
        findViewById<SeekBar>(R.id.seekRefraction).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.refractionStrength = progress / 100f
            findViewById<TextView>(R.id.lblRefraction).text =
                "Refração / dispersão: ${String.format("%.2f", prefs.refractionStrength)}"
        })
        findViewById<SeekBar>(R.id.seekSensitivity).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.volumeStepPx = progress
            findViewById<TextView>(R.id.lblSensitivity).text = "Sensibilidade do volume: $progress px/degrau"
        })
        findViewById<SeekBar>(R.id.seekPanelSize).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.panelSizeDp = progress + 188
            findViewById<TextView>(R.id.lblPanelSize).text =
                "Largura do painel: ${prefs.panelSizeDp} dp"
        })
        findViewById<SeekBar>(R.id.seekTileSize).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.tileSizeDp = progress + 44
            findViewById<TextView>(R.id.lblTileSize).text =
                "Botões do painel: ${prefs.tileSizeDp} dp"
        })
        findViewById<SeekBar>(R.id.seekCorner).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.panelCornerDp = progress + 16
            findViewById<TextView>(R.id.lblCorner).text =
                "Borda arredondada: ${prefs.panelCornerDp} dp"
        })
        findViewById<SeekBar>(R.id.seekEdge).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.edgeOpacity = progress + 30
            findViewById<TextView>(R.id.lblEdge).text =
                "Brilho da borda: ${prefs.edgeOpacity}%"
        })
        findViewById<SeekBar>(R.id.seekDarkness).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.panelDarkness = progress
            findViewById<TextView>(R.id.lblDarkness).text =
                "Escurecer fundo do painel: $progress%"
        })
        findViewById<SeekBar>(R.id.seekRepeat).setOnSeekBarChangeListener(simpleSeek { progress ->
            prefs.repeatDelayMs = progress + 60
            findViewById<TextView>(R.id.lblRepeat).text =
                "Repetição ao segurar: ${prefs.repeatDelayMs} ms"
        })
    }

    private fun syncControls() {
        findViewById<SeekBar>(R.id.seekSize).progress = prefs.ballSizeDp - 48
        findViewById<SeekBar>(R.id.seekAlpha).progress = (prefs.inactiveAlpha * 100).toInt()
        findViewById<SeekBar>(R.id.seekBlur).progress = prefs.blurRadius
        findViewById<RadioGroup>(R.id.rgBlurMode).check(
            when (prefs.blurMode) {
                Prefs.BLUR_MODE_NATIVE -> R.id.rbBlurNative
                Prefs.BLUR_MODE_VISUAL -> R.id.rbBlurVisual
                else -> R.id.rbBlurAuto
            }
        )
        findViewById<SeekBar>(R.id.seekRefraction).progress = (prefs.refractionStrength * 100).toInt()
        findViewById<SeekBar>(R.id.seekSensitivity).progress = prefs.volumeStepPx
        findViewById<TextView>(R.id.lblSize).text = "Tamanho da bolinha: ${prefs.ballSizeDp} dp"
        findViewById<TextView>(R.id.lblAlpha).text = "Opacidade inativa: ${(prefs.inactiveAlpha * 100).toInt()}%"
        findViewById<TextView>(R.id.lblBlur).text = "Desfoque do painel: ${prefs.blurRadius}"
        findViewById<TextView>(R.id.lblRefraction).text =
            "Refração / dispersão: ${String.format("%.2f", prefs.refractionStrength)}"
        findViewById<TextView>(R.id.lblSensitivity).text = "Sensibilidade do volume: ${prefs.volumeStepPx} px/degrau"
        findViewById<SeekBar>(R.id.seekPanelSize).progress = prefs.panelSizeDp - 188
        findViewById<TextView>(R.id.lblPanelSize).text = "Largura do painel: ${prefs.panelSizeDp} dp"
        findViewById<SeekBar>(R.id.seekTileSize).progress = prefs.tileSizeDp - 44
        findViewById<TextView>(R.id.lblTileSize).text = "Botões do painel: ${prefs.tileSizeDp} dp"
        findViewById<SeekBar>(R.id.seekCorner).progress = prefs.panelCornerDp - 16
        findViewById<TextView>(R.id.lblCorner).text = "Borda arredondada: ${prefs.panelCornerDp} dp"
        findViewById<SeekBar>(R.id.seekEdge).progress = prefs.edgeOpacity - 30
        findViewById<TextView>(R.id.lblEdge).text = "Brilho da borda: ${prefs.edgeOpacity}%"
        findViewById<SeekBar>(R.id.seekDarkness).progress = prefs.panelDarkness
        findViewById<TextView>(R.id.lblDarkness).text =
            "Escurecer fundo do painel: ${prefs.panelDarkness}%"
        findViewById<SeekBar>(R.id.seekRepeat).progress = prefs.repeatDelayMs - 60
        findViewById<TextView>(R.id.lblRepeat).text =
            "Repetição ao segurar: ${prefs.repeatDelayMs} ms"
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
