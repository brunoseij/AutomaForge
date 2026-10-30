package com.automaforge.android.presentation.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.automaforge.android.BuildConfig
import com.automaforge.android.CameraActivity
import com.automaforge.android.domain.model.*
import com.automaforge.android.presentation.AutomaViewModelFactory
import com.automaforge.android.presentation.settings.*
import com.automaforge.android.presentation.web.GameWebView

@Composable
fun MainScreen(vm: MainViewModel, factory: AutomaViewModelFactory) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var pendingCamera by remember { mutableStateOf<CameraRequest?>(null) }
    fun openCamera(request: CameraRequest) {
        context.startActivity(
            Intent(context, CameraActivity::class.java)
                .putExtra("host", request.host)
                .putExtra("room", request.room)
                .putExtra("role", request.role)
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            pendingCamera?.let {
                if (allowed) openCamera(it)
                else vm.report("Permissão de câmera necessária para fotografar.")
            }
            pendingCamera = null
        }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            state.error?.let { error ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error)
                        TextButton(onClick = vm::clearError) { Text("Fechar aviso") }
                    }
                }
            }
            val connection = state.connection
            if (connection == null) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text("AutomaForge", style = MaterialTheme.typography.headlineLarge)
                        Text("Controlador e câmeras na rede local")
                    }
                    item {
                        Button(
                            onClick = { vm.startController() },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Iniciar controlador + servidor")
                        }
                    }
                    item {
                        OutlinedTextField(
                            address,
                            { address = it },
                            label = { Text("IP do controlador") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = { vm.connect(address) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Conectar a servidor existente")
                        }
                    }
                    item {
                        Text(
                            "Use o mesmo APK como câmera do tripé ou leitor privado. O navegador LAN continua disponível."
                        )
                    }
                    if (state.loading || state.busy)
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.importWarnings > 0)
                        item {
                            Text(
                                "${state.importWarnings} arquivos antigos precisam de recuperação. Os originais foram preservados.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    if (state.games.isNotEmpty())
                        item {
                            Text("Partidas salvas", style = MaterialTheme.typography.titleLarge)
                        }
                    items(state.games, key = { it.code }) { game ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(game.name, style = MaterialTheme.typography.titleMedium)
                                Text("Sala ${game.code} · Turno ${game.turn} · ${game.phase}")
                                if (game.pendingProposal) Text("Proposta pendente de revisão")
                                TextButton(
                                    onClick = { vm.startController(game.code) },
                                    enabled = !state.busy,
                                ) {
                                    Text("Retomar partida")
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "${BuildConfig.VERSION_NAME} · Kotlin · MVVM",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (connection.target.controller)
                            "LAN: ${connection.publicAddress}:${NetworkPolicy.PORT}"
                        else "Cliente: ${connection.target.host}",
                        modifier = Modifier.weight(1f),
                    )
                    if (connection.target.controller)
                        TextButton(onClick = vm::showSettings) { Text("IA") }
                    TextButton(onClick = vm::leave) { Text("Sair") }
                }
                GameWebView(
                    connection,
                    state.currentUrl,
                    state.reloadEpoch,
                    Modifier.fillMaxSize(),
                    vm::showSettings,
                    { request ->
                        if (
                            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                        )
                            openCamera(request)
                        else {
                            pendingCamera = request
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    vm::rememberLocation,
                    vm::leave,
                    vm::report,
                )
            }
        }
    }
    if (state.settingsOpen) {
        val settings: AiSettingsViewModel = viewModel(factory = factory)
        AiSettingsDialog(settings, vm::closeSettings, vm::settingsSaved)
    }
}
