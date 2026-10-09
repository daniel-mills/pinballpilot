package app.pinballpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pinballpilot.domain.StateVariable
import app.pinballpilot.domain.StateEngine
import kotlinx.serialization.json.*

@Composable fun ProgressEditor(def: StateVariable, current: JsonPrimitive?, onConfirm: (JsonPrimitive?) -> Unit, onDismiss: () -> Unit) {
    var text by remember(def.id) { mutableStateOf(current?.takeIf { it != JsonNull }?.content ?: "") }
    val value = when (def.type) {
        "boolean" -> text.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        "counter" -> text.toLongOrNull()?.let(::JsonPrimitive)
        "enum" -> text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)
        else -> null
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(def.label)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Confirm what you can see on the machine. Leave uncertain progress unknown.")
        if (def.type == "counter") OutlinedTextField(text,{text=it},label={Text("Count")},singleLine=true)
        else for (option in if(def.type=="boolean") listOf("true","false") else def.values.orEmpty()) {
            FilterChip(selected=text==option,onClick={text=option},label={Text(if(option=="true") "Yes" else if(option=="false") "No" else option)})
        }
        TextButton(onClick={onConfirm(null);onDismiss()}) {Text("Mark unknown")}
    }},confirmButton={TextButton(enabled=value!=null&&StateEngine.valid(def,value),onClick={onConfirm(value);onDismiss()}) {Text("Confirm progress")}},dismissButton={TextButton(onClick=onDismiss) {Text("Cancel")}})
}
