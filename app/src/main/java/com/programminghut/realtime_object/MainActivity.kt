package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.net.URI
import java.net.URISyntaxException
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    lateinit var labels: List<String>
    var colors = listOf(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )
    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap: Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model: SsdMobilenetV11Metadata1
    private var webSocketClient: WebSocketClient? = null

    // Tracking variables
    private var bottleDetected = false
    private var bottleCentered = false
    private var bottlePosition = ""
    private var personDetected = false
    private var timer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        get_permission()

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageView = findViewById(R.id.imageView)

        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
                sendWebSocketMessage("connection from app!")
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}
            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                bitmap = textureView.bitmap!!
                var image = TensorImage.fromBitmap(bitmap)
                image = imageProcessor.process(image)

                val outputs = model.process(image)
                val locations = outputs.locationsAsTensorBuffer.floatArray
                val classes = outputs.classesAsTensorBuffer.floatArray
                val scores = outputs.scoresAsTensorBuffer.floatArray

                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)

                val h = mutable.height
                val w = mutable.width
                paint.textSize = h / 15f
                paint.strokeWidth = h / 85f
                var x = 0
                bottleDetected = false
                bottleCentered = false
                personDetected = false

                for (index in scores.indices) {
                    val score = scores[index]
                    if (score > 0.5) {
                        val label = labels[classes[index].toInt()]
                        if (label == "bottle") {
                            bottleDetected = true
                            val centerX = (locations[index * 4 + 1] + locations[index * 4 + 3]) / 2 * w
                            if (centerX > w / 3 && centerX < 2 * w / 3) {
                                bottleCentered = true
                            } else if (centerX <= w / 3) {
                                bottlePosition = "left"
                            } else {
                                bottlePosition = "right"
                            }
                            paint.color = colors[index % colors.size]
                            paint.style = Paint.Style.STROKE
                            canvas.drawRect(
                                RectF(
                                    locations[index * 4 + 1] * w,
                                    locations[index * 4] * h,
                                    locations[index * 4 + 3] * w,
                                    locations[index * 4 + 2] * h
                                ),
                                paint
                            )
                            paint.style = Paint.Style.FILL
                            canvas.drawText(
                                "$label $score",
                                locations[index * 4 + 1] * w,
                                locations[index * 4] * h,
                                paint
                            )
                        } else if (label == "person") {
                            personDetected = true
                        }
                    }
                }

                imageView.setImageBitmap(mutable)

                // Start timer to check bottle position every 3 seconds
                startTimer()
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Initialize WebSocket
        connectWebSocket("192.168.4.1")
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
        webSocketClient?.close()
        stopTimer()
    }

    @SuppressLint("MissingPermission")
    fun open_camera() {
        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(), null, null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {}
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {}
            override fun onError(p0: CameraDevice, p1: Int) {}
        }, handler)
    }

    fun get_permission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_permission()
        }
    }

    private fun connectWebSocket(ip: String) {
        val uri: URI
        try {
            uri = URI("ws://$ip/CarInput")
        } catch (e: URISyntaxException) {
            e.printStackTrace()
            return
        }

        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake) {
                Log.d("WebSocket", "Connected")
                sendWebSocketMessage("connection from app!")
            }

            override fun onMessage(message: String) {
                Log.d("WebSocket", "Message received: $message")
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.d("WebSocket", "Disconnected: $reason")
            }

            override fun onError(ex: Exception) {
                ex.printStackTrace()
                Log.e("WebSocket", "Error: ${ex.message}")
            }
        }
        webSocketClient?.connect()
    }

    private fun sendWebSocketMessage(message: String) {
        if (webSocketClient != null && webSocketClient!!.isOpen) {
            webSocketClient!!.send(message)
            Log.d("WebSocket", "Message sent: $message")
        } else {
            Log.e("WebSocket", "WebSocket is not connected")
        }
    }

    private fun startTimer() {
        if (timer == null) {
            timer = Timer()
            timer?.schedule(object : TimerTask() {
                override fun run() {
                    handleBottlePosition()
                }
            }, 0, 3000) // Check every 3 seconds
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun handleBottlePosition() {
        if (bottleDetected) {
            if (bottleCentered) {
                sendCommandToESP32("MoveCar,1") // Move forward
            } else {
                when (bottlePosition) {
                    "left" -> sendCommandToESP32("MoveCar,3") // Move left
                    "right" -> sendCommandToESP32("MoveCar,4") // Move right
                }
            }
        } else if (personDetected) {
            sendCommandToESP32("MoveCar,0") // Stop
        }
    }

    private fun sendCommandToESP32(command: String) {
        if (webSocketClient != null && webSocketClient!!.isOpen) {
            webSocketClient!!.send(command)
            Log.d("WebSocket", "Command sent to ESP32: $command")
        } else {
            Log.e("WebSocket", "WebSocket is not connected")
        }
    }
}
