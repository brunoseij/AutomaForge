package com.automaforge.android.data.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import javax.inject.Inject

class CameraControllerFactory @Inject constructor(private val context: Context) {
    fun create(
        preview: TextureView,
        automatic: Boolean,
        onImage: (ByteArray) -> Unit,
        onMessage: (String) -> Unit,
    ) = CameraController(context, preview, automatic, onImage, onMessage)
}

/**
 * Hardware adapter; owns surfaces only while the Compose preview is resumed. No image touches disk.
 */
class CameraController(
    private val context: Context,
    private val preview: TextureView,
    private val automatic: Boolean,
    private val onImage: (ByteArray) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    @Volatile private var active = false
    @Volatile private var opening = false
    private var generation = 0

    init {
        preview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = open()

                override fun onSurfaceTextureSizeChanged(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {}

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    pause()
                    return true
                }
            }
    }

    fun resume() {
        if (active) return
        thread = HandlerThread("automa-camera").apply { start() }
        handler = Handler(thread!!.looper)
        active = true
        if (preview.isAvailable) open()
    }

    fun pause() {
        active = false
        opening = false
        generation++
        handler?.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        surface?.release()
        surface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun open() {
        if (!active || opening || camera != null || handler == null) return
        if (
            context.checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            onMessage("Câmera sem permissão.")
            return
        }
        val token = generation
        opening = true
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id =
                manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: error("Nenhuma câmera traseira encontrada.")
            val map =
                manager
                    .getCameraCharacteristics(id)[
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?: error("Câmera sem configuração JPEG.")
            val choices = map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList()
            val size =
                choices
                    .filter { it.width.toLong() * it.height <= 6500000 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: choices.minByOrNull { it.width.toLong() * it.height }
                    ?: error("A câmera não suporta fotos JPEG.")
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        opening = false
                        if (!active || token != generation) {
                            device.close()
                            return
                        }
                        camera = device
                        configure(device, size.width, size.height, token)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (token == generation) {
                            camera = null
                            opening = false
                            onMessage("Câmera desconectada.")
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (token == generation) {
                            camera = null
                            opening = false
                            onMessage("Falha na câmera: $error")
                        }
                    }
                },
                handler,
            )
        } catch (failure: Exception) {
            opening = false
            onMessage(failure.message ?: "Não foi possível abrir a câmera.")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(device: CameraDevice, width: Int, height: Int, token: Int) {
        try {
            val texture = preview.surfaceTexture ?: return
            texture.setDefaultBufferSize(1280, 720)
            val target = Surface(texture)
            surface = target
            val images = ImageReader.newInstance(width, height, ImageFormat.JPEG, 3)
            reader = images
            images.setOnImageAvailableListener(
                { source ->
                    try {
                        source.acquireLatestImage()?.use { image ->
                            if (active && token == generation) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                onImage(bytes)
                            }
                        }
                    } catch (failure: Exception) {
                        if (active) onMessage("Falha ao capturar a imagem.")
                    }
                },
                handler,
            )
            device.createCaptureSession(
                listOf(target, images.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        if (!active || token != generation || camera !== device) {
                            created.close()
                            return
                        }
                        session = created
                        try {
                            val request =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(target)
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    )
                                }
                            created.setRepeatingRequest(request.build(), null, handler)
                            onMessage(
                                if (automatic) "Câmera ativa. Capturas a cada 5 segundos."
                                else "Aguardando carta na caixa de leitura."
                            )
                            if (automatic)
                                handler?.postDelayed(
                                    object : Runnable {
                                        override fun run() {
                                            if (active && token == generation && session != null) {
                                                capture()
                                                handler?.postDelayed(this, 5000)
                                            }
                                        }
                                    },
                                    1200,
                                )
                        } catch (failure: Exception) {
                            onMessage("Erro ao iniciar a prévia da câmera.")
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        if (active) onMessage("Configuração da câmera falhou.")
                    }
                },
                handler,
            )
        } catch (failure: Exception) {
            onMessage("Falha ao configurar a câmera.")
        }
    }

    fun capture() {
        handler?.post {
            val device = camera
            val current = session
            val images = reader
            if (!active || device == null || current == null || images == null) {
                onMessage("A câmera ainda está iniciando.")
                return@post
            }
            try {
                val shot =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(images.surface)
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                    }
                current.capture(shot.build(), null, handler)
            } catch (failure: Exception) {
                onMessage("Captura falhou.")
            }
        }
    }
}
