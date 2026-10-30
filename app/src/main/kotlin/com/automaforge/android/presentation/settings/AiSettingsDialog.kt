package com.automaforge.android.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = options.find { it.first == value }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun AiSettingsDialog(vm: AiSettingsViewModel, onClose: () -> Unit, onSaved: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var key by remember {
        mutableStateOf("")
    } // Never saved into saved-state, Room or the web bridge.
    LaunchedEffect(vm) {
        vm.reload()
        vm.savedEvents.collect {
            key = ""
            onSaved()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onClose() },
        title = { Text("Configurar IA") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("As escolhas valem para manual, câmera e jogadas.")
                val cfg = state.configuration
                if (cfg == null) CircularProgressIndicator()
                else {
                    Choice(
                        "Modelo",
                        state.model,
                        cfg.models.map { it.id to it.label },
                        !state.saving,
                        vm::chooseModel,
                    )
                    val levels = cfg.models.find { it.id == state.model }?.reasoning.orEmpty()
                    Choice(
                        "Nível de raciocínio",
                        state.effort,
                        levels.map { it.value to it.label },
                        !state.saving && levels.size > 1,
                        vm::chooseEffort,
                    )
                    Text(
                        if (cfg.keyConfigured) "Chave configurada. Deixe vazio para manter a atual."
                        else "Configure a chave OpenAI no controlador."
                    )
                    OutlinedTextField(
                        key,
                        { key = it },
                        label = { Text("Chave OpenAI") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !state.saving,
                    )
                    Text(
                        "A disponibilidade depende da conta. Níveis maiores podem aumentar o tempo e o consumo."
                    )
                    TextButton(
                        onClick = {
                            key = ""
                            vm.clearKey()
                        },
                        enabled = !state.saving,
                    ) {
                        Text("Apagar chave")
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.save(key) },
                enabled = state.configuration != null && !state.saving,
            ) {
                Text(if (state.saving) "Salvando…" else "Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !state.saving) { Text("Cancelar") }
        },
    )
}
