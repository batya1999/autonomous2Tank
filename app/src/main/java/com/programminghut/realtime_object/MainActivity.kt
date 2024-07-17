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

    // Variables to track commands and durations
    private val commandLog = mutableListOf<Pair<String, Long>>()
    private var commandStartTime: Long = 0
    private var lastLoggedCommand: String = ""
    private var accumulatedDuration: Long = 0

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
                paint.textSize = h / 30f // Adjust text size to be smaller
                paint.strokeWidth = h / 85f
                var x = 0
                bottleDetected = false
                bottleCentered = false
                personDetected = false

                for (index in scores.indices) {
                    val score = scores[index]
                    if (score > 0.5) {
                        val label = labels[classes[index].toInt()]
                        val left = locations[index * 4 + 1] * w
                        val top = locations[index * 4] * h
                        val right = locations[index * 4 + 3] * w
                        val bottom = locations[index * 4 + 2] * h

                        if (label == "bottle") {
                            bottleDetected = true
                            val centerX = (left + right) / 2
                            val imageCenterX = w / 2
                            val distanceFromCenter = centerX - imageCenterX
                            val angle = Math.toDegrees(Math.atan2(distanceFromCenter.toDouble(), h.toDouble()))

                            if (centerX > w / 3 && centerX < 2 * w / 3) {
                                bottleCentered = true
                                sendWebSocketMessage("go forward")
                            } else if (centerX <= w / 3) {
                                bottlePosition = "left"
                                sendWebSocketMessage("go left $angle degrees")
                            } else {
                                bottlePosition = "right"
                                sendWebSocketMessage("go right $angle degrees")
                            }

                            paint.color = colors[index % colors.size]
                            paint.style = Paint.Style.STROKE
                            canvas.drawRect(RectF(left, top, right, bottom), paint)
                            paint.style = Paint.Style.FILL
                            val distance = calculateDistance(left, top, right, bottom)
                            canvas.drawText(
                                "$label $score, Distance: %.2f m".format(distance),
                                left,
                                top - 10, // Positioning text above the bounding box
                                paint
                            )
                            // Print to serial monitor for the distance and angle
                            sendWebSocketMessage("Distance from camera $distance")

                            if (distance < 70.0) {
                                sendWebSocketMessage("MoveCar,5") // Search
                            }
                        } else if (label == "person") {
                            personDetected = true
                        }
                    }
                }

                imageView.setImageBitmap(mutable)
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
                commandStartTime = System.currentTimeMillis()
            }

            override fun onMessage(message: String) {
                Log.d("WebSocket", "Message received: $message")
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.d("WebSocket", "Disconnected: $reason")
                logCommandDuration("disconnect")
                printCommandLog()
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
            logCommandDuration(message)
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
            }, 0, 100) // Checks every 3 seconds
        }
    }


    // Function to log command duration
// Function to log command duration
    private fun logCommandDuration(command: String) {
        val directionCommands = setOf("MoveCar,1", "MoveCar,2", "MoveCar,3", "MoveCar,4", "MoveCar,0")
        if (directionCommands.contains(command)) {
            val currentTime = System.currentTimeMillis()
            if (lastLoggedCommand == command) {
                accumulatedDuration += (currentTime - commandStartTime)
            } else {
                if (lastLoggedCommand.isNotEmpty()) {
                    commandLog.add(Pair(lastLoggedCommand, accumulatedDuration))
                }
                lastLoggedCommand = command
                commandStartTime = currentTime
                accumulatedDuration = 0
            }
            commandStartTime = currentTime
        }
    }

    // Function to print the combined command log
    private fun printCommandLog() {
        if (lastLoggedCommand.isNotEmpty()) {
            commandLog.add(Pair(lastLoggedCommand, accumulatedDuration + (System.currentTimeMillis() - commandStartTime)))
        }
        val filteredCommandLog = commandLog.filter { it.first.startsWith("MoveCar,") && !it.first.equals("MoveCar,5") }

        // Combine consecutive commands of the same type
        val combinedCommandLog = mutableListOf<Pair<String, Long>>()
        var currentCommand = ""
        var currentDuration: Long = 0

        for (log in filteredCommandLog) {
            if (log.first == currentCommand) {
                currentDuration += log.second
            } else {
                if (currentCommand.isNotEmpty()) {
                    combinedCommandLog.add(Pair(currentCommand, currentDuration))
                }
                currentCommand = log.first
                currentDuration = log.second
            }
        }
        // Add the last command
        if (currentCommand.isNotEmpty()) {
            combinedCommandLog.add(Pair(currentCommand, currentDuration))
        }

        // Format the combined commands for sending
        val pathString = combinedCommandLog.joinToString(separator = "\n") { "${it.first}: ${it.second / 1000.0} sec" }
        sendWebSocketMessage("Path: \n$pathString")

        // Clear the command log for the next session
        commandLog.clear()
        lastLoggedCommand = ""
        accumulatedDuration = 0
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
            sendWebSocketMessage(command)
            Log.d("WebSocket", "Command sent to ESP32: $command")
        } else {
            Log.e("WebSocket", "WebSocket is not connected")
        }
    }

    private fun calculateDistance(left: Float, top: Float, right: Float, bottom: Float): Float {
        // Replace with your actual distance calculation logic based on object position in image
        val objectWidthPixels = right - left
        val focalLength = 500.0 // Example: Focal length of the camera in pixels
        val objectRealWidth = 0.1 // Example: Real width of the object in meters (adjust as per your object)
        val imageWidthPixels = bitmap.width.toFloat() // Width of the captured image in pixels

        // Calculate distance using simple perspective formula
        val distance = focalLength * objectRealWidth * imageWidthPixels / (objectWidthPixels * 2.0)

        return distance.toFloat()
    }
}
