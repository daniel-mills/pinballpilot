package app.pinballpilot

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pinballpilot.ui.*
import app.pinballpilot.voice.CoachVoiceService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.*
import javax.inject.Inject

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    @Inject lateinit var session: CoachSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        intent.data?.let(session::callback)
        setContent { PilotTheme { PilotApp() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.data?.let(session::callback) }
}

@Composable fun PilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFFFC46B),onPrimary=Color(0xFF33250F),secondary=Color(0xFF93C7DB),background=Color(0xFF151D2D),surface=Color(0xFF202C41),onSurface=Color(0xFFF4F0E8),onBackground=Color(0xFFF4F0E8)),content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PilotApp(vm: CoachViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val coach = vm.session
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember { mutableStateOf("Machines") }
    var search by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var scoreFor by remember { mutableStateOf<app.pinballpilot.data.GameEntity?>(null) }
    var score by remember { mutableStateOf("") }
    val voiceStatus by CoachVoiceService.status.collectAsStateWithLifecycle()
    val narrationStatus by coach.narrator.status.collectAsStateWithLifecycle()
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if(allowed) showCamera=true else coach.message("Camera permission is needed for photos. Manual machine search still works.") }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if(permissions[Manifest.permission.RECORD_AUDIO]==true) context.startForegroundService(Intent(context,CoachVoiceService::class.java))
        else coach.message("Microphone permission is needed for hands-free mode.")
    }
    val talk = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(coach::voice) }
    fun tapToTalk() {
        coach.narrator.stop()
        context.stopService(Intent(context,CoachVoiceService::class.java))
        try { talk.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT,"Ask a question or say ‘done’")) }
        catch(_: Exception) { coach.message("Speech recognition is unavailable on this phone. Type your question below.") }
    }
    LaunchedEffect(page) { coach.active(page=="Guide") }
    Scaffold(
        topBar={TopAppBar(title={Text("Pinball Pilot",fontWeight=FontWeight.Bold)},actions={IconButton(onClick={cameraPermission.launch(Manifest.permission.CAMERA)}) {Icon(Icons.Default.PhotoCamera,"Identify or check progress")}})},
        bottomBar={NavigationBar { listOf("Machines" to Icons.Default.SportsEsports,"Guide" to Icons.AutoMirrored.Filled.MenuBook,"History" to Icons.Default.History,"Profile" to Icons.Default.Person).forEach { (label,icon) -> NavigationBarItem(selected=page==label,onClick={page=label},icon={Icon(icon,label)},label={Text(label)}) } }},
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(s.message.isNotEmpty()) item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF344154))) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { Text(s.message,Modifier.weight(1f)); IconButton(onClick={coach.message("")}) { Icon(Icons.Default.Close,"Dismiss") } } } }
            if(s.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when(page) {
                "Machines" -> {
                    item { Text("Walk up with a plan.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold); Text("Know your next shot. Learn why it matters.",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.secondary) }
                    item { OutlinedTextField(search,{search=it},label={Text("Find a machine")},leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true) }
                    if("iron maiden legacy of the beast pro".contains(search,ignoreCase=true)) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("Iron Maiden",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Legacy of the Beast · Pro");AssistChip(onClick={},label={Text("Guide awaiting review")},leadingIcon={Icon(Icons.Default.FactCheck,null)});Text("The first real machine guide is being verified. It will appear here when its rules, shots and reference image are approved.",style=MaterialTheme.typography.bodyMedium) } } }
                    items(s.packs.filter { "${it.name} ${it.edition}".contains(search,ignoreCase=true) }) { pack -> Card(Modifier.fillMaxWidth().clickable { coach.open(pack.variantId);page="Guide" }) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(pack.name,style=MaterialTheme.typography.headlineSmall);Text(if(pack.demo) "Fictional demonstration · ready offline" else pack.edition,color=MaterialTheme.colorScheme.secondary);Text("${pack.shots.size} shots · scoring & multiball guides");Button(onClick={coach.open(pack.variantId);page="Guide"}) {Text("Explore playfield")} } } }
                    item { Text("Demo rules belong only to the fictional Workshop table. They are not Iron Maiden advice.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary) }
                }
                "Guide" -> {
                    val pack=s.pack
                    if(pack==null) item { Text("Loading your downloaded guides…") }
                    else {
                        item { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) {Text(pack.name,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(pack.edition,color=MaterialTheme.colorScheme.secondary)};IconButton(onClick={coach.preference("favourite:${pack.variantId}",(!s.favourite).toString())}) {Icon(if(s.favourite) Icons.Default.Star else Icons.Default.StarOutline,"Toggle favourite")} } }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { FilterChip(selected=s.objective=="scoring",onClick={coach.objective("scoring")},label={Text("Scoring plan")});FilterChip(selected=s.objective=="multiball",onClick={coach.objective("multiball")},label={Text("Multiball route")}) } }
                        if(s.objective=="scoring") item { Row(verticalAlignment=Alignment.CenterVertically) {Text("Advanced strategy",Modifier.weight(1f));Switch(s.advanced,coach::advanced)} }
                        item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF3A342A))) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(if(s.next==null) "Guide complete" else "Your next shot",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                            Text(s.next?.rule?.instruction ?: "You’ve followed the whole route.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                            Text(s.next?.rule?.why ?: "Start a fresh game to practise again. Your learning history stays with you.")
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { if(s.next!=null) Button(onClick=coach::complete,enabled=!s.busy) {Text("Done — next step")};OutlinedButton(onClick=coach::speakNext) {Icon(Icons.Default.VolumeUp,null);Spacer(Modifier.width(6.dp));Text("Listen")} }
                        } } }
                        item { PlayfieldViewer(pack,s.selected,setOfNotNull(s.next?.shot?.id),coach::select) }
                        s.selected?.let { id -> pack.shots.find { it.id==id }?.let { shot -> item { Card {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(shot.name,style=MaterialTheme.typography.titleLarge);Text(shot.description);pack.rules.filter { it.shotId==id }.forEach {rule -> Text(rule.instruction,fontWeight=FontWeight.Bold);Text(rule.why); if(rule.prerequisites.isNotEmpty()) Text("Requires: ${rule.prerequisites.joinToString { outcome -> pack.rules.find { it.outcome==outcome }?.instruction ?: outcome }}",color=MaterialTheme.colorScheme.secondary) }} } } } }
                        item { Text("Explore the shots",style=MaterialTheme.typography.titleLarge) }
                        items(pack.shots) { shot -> ListItem(headlineContent={Text(shot.name)},supportingContent={Text(shot.type)},trailingContent={Icon(Icons.Default.TouchApp,null)},modifier=Modifier.clickable{coach.select(shot.id)},colors=ListItemDefaults.colors(containerColor=if(shot.id==s.selected) Color(0xFF344154) else Color.Transparent)) }
                        item { OutlinedTextField(question,{question=it},label={Text("Ask about this machine")},modifier=Modifier.fillMaxWidth()) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(onClick={coach.ask(question)},enabled=question.isNotBlank()&&!s.busy) {Text("Ask coach")};OutlinedButton(onClick=::tapToTalk) {Icon(Icons.Default.Mic,null);Text("Talk")} } }
                        if(s.answer.isNotBlank()) item { Card {Text(s.answer,Modifier.padding(18.dp))} }
                        item { TextButton(onClick=coach::newGame) {Text("Start a fresh game / correct grouping")} }
                    }
                }
                "History" -> {
                    item {Text("Every game teaches you.",style=MaterialTheme.typography.headlineMedium);Text("${s.learned} objectives learned on this machine.",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.secondary)}
                    if(s.games.isEmpty()) item {Text("Follow a guide or ask a question to begin your first game history. Activity is grouped automatically.")}
                    items(s.games,key={it.id}) {game -> Card {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(s.packs.find {it.variantId==game.variantId}?.name?:game.variantId,style=MaterialTheme.typography.titleLarge);Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(game.startedAt)));Text(game.score?.let{"Score: %,d".format(it)}?:"Score not recorded");Text(if(game.ended) "Finished" else "Automatically grouped activity");TextButton(onClick={scoreFor=game;score=game.score?.toString()?:""}) {Text("Record final score")} }} }
                    item { OutlinedButton(onClick=coach::sync,enabled=!s.busy) {Text("Sync private activity")} }
                }
                "Profile" -> {
                    item {Text("Your coach, your pace.",style=MaterialTheme.typography.headlineMedium)}
                    item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Speak new advice automatically",Modifier.weight(1f));Switch(s.autoNarrate,{coach.preference("autoNarrate",it.toString())})}}
                    item {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("Occasional progress prompts");Text("Quiet waiting is the default.",style=MaterialTheme.typography.bodySmall)};Switch(s.prompts,{coach.preference("prompts",it.toString())})}}
                    item {Card {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {Text("Hands-free coach",style=MaterialTheme.typography.titleLarge);Text("Enable while the app is open, then say ‘Hey Pinball’. The microphone notification stays visible while listening. Arcade noise and locked-screen behaviour still need device validation.");Text(voiceStatus,color=MaterialTheme.colorScheme.secondary);Button(onClick={val permissions=mutableListOf(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33) permissions+=Manifest.permission.POST_NOTIFICATIONS;if(Build.VERSION.SDK_INT>=31) permissions+=Manifest.permission.BLUETOOTH_CONNECT;microphone.launch(permissions.toTypedArray())}) {Text("Enable hands-free")};OutlinedButton(onClick={context.stopService(Intent(context,CoachVoiceService::class.java))}) {Text("Stop listening")};Text("Offline: spoken guides and touch controls remain available.",style=MaterialTheme.typography.bodySmall) }} }
                    if(narrationStatus.isNotEmpty()) item {Text(narrationStatus)}
                    item {OutlinedTextField(email,{email=it},label={Text("Email for sign-in link")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                    item {Button(onClick={coach.signIn(email)},enabled=email.contains('@')&&!s.busy) {Text("Send sign-in link")}}
                    item {Text("Your photos and conversations are private. Editors cannot browse them. A connected backend is needed for accounts, AI and cross-device sync.",style=MaterialTheme.typography.bodySmall)}
                    item {OutlinedButton(onClick=coach::updatePacks,enabled=!s.busy) {Text("Download guide updates")}}
                    item {Text("Version ${BuildConfig.VERSION_NAME} · Private development build",color=MaterialTheme.colorScheme.secondary)}
                }
            }
        }
    }
    if(showCamera) CameraCapture(onClose={showCamera=false},onPhotos={showCamera=false;coach.analyse(it)})
    if(scoreFor!=null) AlertDialog(onDismissRequest={scoreFor=null},title={Text("Final score")},text={OutlinedTextField(score,{score=it.filter(Char::isDigit)},label={Text("Score")})},confirmButton={TextButton(onClick={score.toLongOrNull()?.let{coach.saveScore(scoreFor!!,it);scoreFor=null}},enabled=score.toLongOrNull()!=null){Text("Save")}},dismissButton={TextButton(onClick={scoreFor=null}) {Text("Cancel")}})
    s.observation?.let { observation ->
        AlertDialog(onDismissRequest=coach::dismissObservation,title={Text("Check the photo findings")},text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                observation["candidates"]?.jsonArray?.forEach { candidate ->
                    val name=candidate.jsonObject["name"]?.jsonPrimitive?.content?:"Unknown machine"
                    Text("$name · ${candidate.jsonObject["edition"]?.jsonPrimitive?.content}",fontWeight=FontWeight.Bold)
                    OutlinedButton(onClick={coach.confirmMachine(name);page="Guide"}) {Text("Confirm this machine")}
                }
                observation["observations"]?.jsonArray?.forEach {Text(it.jsonPrimitive.content)}
                Text(observation["clarification"]?.jsonPrimitive?.content?:"")
                Text("These observations are unconfirmed. Update guide progress only when you recognise the result.",color=MaterialTheme.colorScheme.secondary)
            }
        },confirmButton={TextButton(onClick=coach::dismissObservation){Text("Back to my guide")}})
    }
}
