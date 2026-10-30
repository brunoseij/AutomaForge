package com.automaforge.android.presentation.camera

import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.automaforge.android.data.camera.CameraControllerFactory
import com.automaforge.android.domain.model.CameraRequest

@Composable
fun CameraScreen(
    request: CameraRequest,
    vm: CameraViewModel,
    factory: CameraControllerFactory,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { TextureView(context) }
    val camera =
        remember(preview, request) {
            factory.create(preview, request.role == "board", { vm.image(request, it) }, vm::message)
        }
    DisposableEffect(camera, owner) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = camera.resume()

                override fun onPause(owner: LifecycleOwner) = camera.pause()
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            camera.pause()
        }
    }
    Surface {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (request.role == "board") "Câmera do tabuleiro" else "Leitor privado de cartas",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Sala ${request.room}" +
                    if (request.role == "board") " · Captura automática a cada 5 segundos"
                    else " · Capture só com a carta na caixa"
            )
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().weight(1f))
            Text(state.message)
            if (state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = camera::capture, enabled = !state.uploading) {
                    Text(if (request.role == "board") "Enviar foto agora" else "Ler carta privada")
                }
                TextButton(onClick = onBack) { Text("Voltar") }
            }
        }
    }
}
