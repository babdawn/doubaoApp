package com.example.doubaoapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import androidx.compose.foundation.combinedClickable
import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.doubaoapp.R // Import the R class for resources
import android.net.Uri // Import Uri for image selection
import androidx.activity.compose.rememberLauncherForActivityResult // Import for activity result launcher
import androidx.compose.material3.TextButton // Import TextButton for the new button
import coil.compose.rememberAsyncImagePainter // Import coil for image loading
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentVideoFile: File? = null
    private var isRecordingInProgress = false
    private var tts: TextToSpeech? = null

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
        setContent {
            AppContent(this)
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            } else {
                 Log.d("TTS", "TTS Initialized successfully")
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    private fun speak(text: String) {
        Log.d("TTS", "Attempting to speak: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppContent(activity: MainActivity) {
        var isRecording by remember { mutableStateOf(false) }
        var description by remember { mutableStateOf("描述将显示在这里...") }
        var permissionState by remember { mutableStateOf(activity.allPermissionsGranted()) }
        var isProcessing by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            permissionState = permissions.all { it.value } &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!permissionState) {
                description = "权限请求失败，请授予摄像头、麦克风和存储权限"
            }
        }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val size = inputStream?.available() ?: 0
                    inputStream?.close()
                    
                    // 限制图片大小为 5MB
                    if (size > 5 * 1024 * 1024) {
                        description = "图片大小不能超过5MB"
                    } else {
                        selectedImageUri = it
                    }
                } catch (e: Exception) {
                    description = "图片处理失败: ${e.message}"
                }
            }
        }

        LaunchedEffect(Unit) {
            val neededPermissions = mutableListOf(*activity.permissions)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (!activity.allPermissionsGranted()) {
                permissionLauncher.launch(neededPermissions.toTypedArray())
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Section 1: Top bar with Logo Button and Camera Preview
            Box(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.15f) // Adjust height as needed
                .align(Alignment.TopCenter)
                .padding(16.dp)
            ) {
                 Button(
                    onClick = {
                        imagePickerLauncher.launch("image/*")
                    },
                    modifier = Modifier.align(Alignment.CenterStart), // Align to the start of the top box
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5722)
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 8.dp
                    )
                ) {
                    Text(
                        "替换logo",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Camera Preview
                CameraPreview(
                    modifier = Modifier
                        .size(120.dp, 160.dp) // Keep camera preview small
                        .align(Alignment.CenterEnd), // Align to the end of the top box
                    onCameraError = { error ->
                        description = "摄像头错误: $error"
                    }
                )
            }

            // Section 2: Main Logo Image (Red Boxed Area)
            Box(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f) // Adjust height to fit between top and bottom sections
                .align(Alignment.Center) // Center the large image box
            ) {
                val painter = rememberAsyncImagePainter(
                    model = selectedImageUri ?: R.drawable.logo
                )

                Image(
                    painter = painter,
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .fillMaxSize() // Make the image fill this middle box
                        .align(Alignment.Center), // Center the image within the box
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit // Or .Crop, experiment to see which fits better
                )
            }

            // Section 3: Bottom content (Description Card and Button)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.25f)
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Description Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 4.dp
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                color = Color(0xFFFF5722),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Text(
                            text = description,
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Record Button
                Button(
                    onClick = { /* 空实现，使用pointerInteropFilter处理点击事件 */ },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color.Red else Color(0xFFFF5722)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    if (permissionState && !isRecordingInProgress && !isProcessing) {
                                        isRecording = true
                                        isRecordingInProgress = true
                                        description = "正在录制..."
                                        try {
                                            activity.startRecording()
                                        } catch (e: Exception) {
                                            description = "录制失败: ${e.message}"
                                            isRecording = false
                                            isRecordingInProgress = false
                                        }
                                    } else if (!permissionState) {
                                        description = "请授予摄像头和麦克风权限"
                                        val neededPermissions = mutableListOf(*activity.permissions)
                                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                        permissionLauncher.launch(neededPermissions.toTypedArray())
                                    }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    if (isRecording) {
                                        isRecording = false
                                        isProcessing = true
                                        activity.stopRecording { newDescription ->
                                            description = newDescription
                                        }
                                        scope.launch {
                                            delay(5000)
                                            activity.getDescription { newDescription ->
                                                description = newDescription
                                                activity.speak(newDescription)
                                                isProcessing = false
                                                isRecordingInProgress = false
                                            }
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                ) {
                    Text(text = if (isRecording) "正在录制..." else "按住说话")
                }
            }
        }
    }

    @Composable
    fun CameraPreview(modifier: Modifier = Modifier, onCameraError: (String) -> Unit) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            factory = { ctx ->
                val previewView = androidx.camera.view.PreviewView(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val recorder = Recorder.Builder()
                            .setExecutor(cameraExecutor)
                            .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                    } catch (e: Exception) {
                        onCameraError("无法初始化摄像头: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(context))
                previewView
            },
            modifier = modifier,
            update = { previewView ->
                previewView.invalidate()
            }
        )
    }

    private fun allPermissionsGranted(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startRecording() {
        if (videoCapture == null) {
            throw IllegalStateException("VideoCapture is not initialized")
        }

        if (!allPermissionsGranted()) {
            throw SecurityException("Required permissions not granted")
        }

        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Failed to get movies directory")
        
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }

        currentVideoFile = File(moviesDir, "recorded_video.mp4")
        val outputOptions = FileOutputOptions.Builder(currentVideoFile!!).build()

        try {
            recording = videoCapture!!.output
                .prepareRecording(this@MainActivity, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this@MainActivity)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d("Recording", "Recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e("Recording", "Recording failed: ${event.error}")
                                isRecordingInProgress = false
                            } else {
                                Log.d("Recording", "Recording completed: ${currentVideoFile?.absolutePath}")
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            throw SecurityException("Camera permission not granted")
        }
    }

    private fun stopRecording(onDescriptionUpdated: (String) -> Unit) {
        try {
            recording?.stop()
            recording = null

            currentVideoFile?.let { file ->
                if (file.exists()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(500)
                         uploadVideo(file, onDescriptionUpdated)
                    }
                } else {
                    onDescriptionUpdated("录制文件未找到")
                }
            } ?: run {
                onDescriptionUpdated("未找到录制文件")
            }
        } catch (e: Exception) {
            onDescriptionUpdated("停止录制失败: ${e.message}")
        }
    }

    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun uploadVideo(file: File, onDescriptionUpdated: (String) -> Unit) {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://t7i58u00a3nz.ngrok.xiaomiqiu123.top/")
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ApiService::class.java)

        val requestFile = file.asRequestBody("video/mp4".toMediaTypeOrNull())
        val videoPart = MultipartBody.Part.createFormData("video", file.name, requestFile)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = api.submitVideo(videoPart).execute()
                if (response.isSuccessful) {
                    onDescriptionUpdated("视频上传成功，正在处理中...")
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    onDescriptionUpdated("上传失败: ${response.code()} - $errorBody")
                }
            } catch (e: Exception) {
                onDescriptionUpdated("上传错误: ${e.message}")
            }
        }
    }

    private fun getDescription(onDescriptionUpdated: (String) -> Unit) {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://t7i58u00a3nz.ngrok.xiaomiqiu123.top/")
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ApiService::class.java)

        api.getVideoDescription().enqueue(object : Callback<ResponseData> {
            override fun onResponse(call: Call<ResponseData>, response: Response<ResponseData>) {
                if (response.isSuccessful && response.body() != null) {
                    val description = response.body()!!.description ?: "无描述"
                    onDescriptionUpdated(description)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    onDescriptionUpdated("获取描述失败: ${response.code()} - $errorBody")
                }
            }

            override fun onFailure(call: Call<ResponseData>, t: Throwable) {
                onDescriptionUpdated("描述错误: ${t.message}")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
    }
}

interface ApiService {
    @Multipart
    @POST("submit_video")
    fun submitVideo(@Part video: MultipartBody.Part): Call<ResponseData>

    @GET("get_video_description")
    fun getVideoDescription(): Call<ResponseData>
}

data class ResponseData(
    val status: String,
    val message: String? = null,
    val description: String? = null
)