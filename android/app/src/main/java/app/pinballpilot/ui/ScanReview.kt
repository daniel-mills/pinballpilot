package app.pinballpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pinballpilot.domain.*
import kotlinx.serialization.json.*

@Composable fun ScanReview(pack: MachinePack, result: ScanResult, expired: Boolean, busy: Boolean, onConfirm: (List<ScanReading>) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit, error: String = "") {
    var machineConfirmed by remember(result) { mutableStateOf(false) }
    val selected=remember(result) { mutableStateMapOf<String,Boolean>() }
    val values=remember(result) { mutableStateMapOf<String,String>().apply { result.readings.forEach { put(it.key,it.value.content) } } }
    fun edited(r: ScanReading): ScanReading? {
        val def=pack.stateVariables.find { it.id==r.variableId }
        val text=values[r.key].orEmpty()
        val value=when {
            r.target!="state" || def?.type=="counter" -> text.toLongOrNull()?.let(::JsonPrimitive)
            def?.type=="boolean" -> text.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            def?.type=="enum" -> JsonPrimitive(text)
            else -> null
        } ?: return null
        val reading=r.copy(value=value)
        return reading.takeIf { runCatching { ProgressScan.validate(listOf(it),pack.stateVariables) }.isSuccess }
    }
    val chosen=result.readings.filter { selected[it.key]==true }
    val valid=chosen.isNotEmpty()&&chosen.all { edited(it)!=null }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text("Check your display scan")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${pack.name} · ${pack.edition}",fontWeight=FontWeight.Bold)
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(result.note.isNotBlank()) Text(result.note)
            if(result.machineMismatch) Text("This appears to be a different machine. Open the correct guide before scanning.")
            else if(result.readings.isEmpty()) Text("No reliable readings were found. Try a clearer view, or update progress manually.")
            else {
                Text("Select only readings you recognise. Correct any values before saving.")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(machineConfirmed,{machineConfirmed=it},enabled=!busy)
                    Text("This is my current game on this machine.")
                }
                for(r in result.readings) {
                    val def=pack.stateVariables.find { it.id==r.variableId }
                    val label=when(r.target) { "score" -> "Current score"; "ball" -> "Displayed ball number"; else -> def?.label ?: "Progress" }
                    HorizontalDivider()
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(selected[r.key]==true,{selected[r.key]=it},enabled=!busy)
                        Text(label,fontWeight=FontWeight.SemiBold)
                    }
                    if(def?.type=="boolean") Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        for(option in listOf("true","false")) FilterChip(selected=values[r.key]==option,onClick={values[r.key]=option},enabled=!busy,label={Text(if(option=="true") "Yes" else "No")})
                    } else OutlinedTextField(values[r.key].orEmpty(),{values[r.key]=it},enabled=!busy,label={Text(label)},isError=edited(r)==null,singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(def?.type=="enum") Text("Values: ${def.values.orEmpty().joinToString()}",style=MaterialTheme.typography.bodySmall)
                    Text("Seen: ${r.evidence}",style=MaterialTheme.typography.bodySmall)
                    Text("AI confidence estimate: ${(r.confidence*100).toInt()}%",style=MaterialTheme.typography.bodySmall)
                    if(r.target=="ball") Text("Saves the displayed number. Use Ball ended separately to reset ball progress.",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(expired) Text("This scan is out of date. Scan again before saving.",color=MaterialTheme.colorScheme.error)
            TextButton(onClick=onRetry,enabled=!busy) {Text("Scan again")}
        }
    },confirmButton={TextButton(onClick={onConfirm(chosen.mapNotNull(::edited))},enabled=!busy&&!expired&&!result.machineMismatch&&machineConfirmed&&valid) {Text("Save selected readings")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy) {Text("Cancel")}})
}
