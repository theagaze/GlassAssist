package com.glassassist.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Lanterna via CameraManager, sem abrir a câmera. */
object TorchController {

    private var cameraId: String? = null
    private var enabled = false

    private fun findTorchCamera(context: Context): String? {
        cameraId?.let { return it }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        for (id in manager.cameraIdList) {
            val flash = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            if (flash == true) {
                cameraId = id
                return id
            }
        }
        return null
    }

    fun isAvailable(context: Context): Boolean = findTorchCamera(context) != null

    fun toggle(context: Context): Pair<Boolean, String> {
        val id = findTorchCamera(context)
            ?: return false to "Este dispositivo não possui lanterna compatível"
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            enabled = !enabled
            manager.setTorchMode(id, enabled)
            true to if (enabled) "Lanterna ligada" else "Lanterna desligada"
        } catch (e: Exception) {
            enabled = false
            false to "Falha na lanterna: ${e.message}"
        }
    }
}
