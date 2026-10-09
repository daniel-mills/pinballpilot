<script setup lang="ts">
import { publicationErrors, packSchema } from '../../shared/contracts'
import type { Claim } from '../../shared/contracts'
const w = useWorkshop()
const { pack, claims, notice, failure, busy, connected, published, limit } = w
const section = ref('Machines')
const drawer = ref<boolean | null>(null)
const filter = ref('pending')
const query = ref('')
const email = ref('')
const notes = reactive<Record<string,string>>({})
const researchQuery = ref('Verify the first Trooper Multiball qualification sequence for Iron Maiden Pro, including software and operator-setting differences.')
const budgetInput = ref(60)
const search = ref('')
const draftJson = ref('')
const showJson = ref(false)
const referenceFile = ref<File|null>(null)
const visibleClaims = computed(() => claims.value.filter(c => (filter.value==='all'||c.status===filter.value) && `${c.title} ${c.body}`.toLowerCase().includes(query.value.toLowerCase())))
const pending = computed(() => claims.value.filter(c => c.status==='pending').length)
const errors = computed(() => publicationErrors(pack.value))
const navigation = [{ title:'Machines',icon:'mdi-pinball' },{title:'Research & review',icon:'mdi-book-search-outline'},{title:'Playfield editor',icon:'mdi-vector-polygon'},{title:'Rules & guides',icon:'mdi-sign-direction'},{title:'Publish packs',icon:'mdi-package-variant-closed'},{title:'Settings',icon:'mdi-tune'}]
onMounted(async () => { await w.initialise(); budgetInput.value = limit.value })
function sourcesFor(c: Claim) { return w.sources.value.filter(s => c.sourceIds.includes(s.id)) }
function download(value: unknown, name: string) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}))
  const a = document.createElement('a'); a.href=url; a.download=name; a.click(); URL.revokeObjectURL(url)
}
function saveJson() {
  try { pack.value=packSchema.parse(JSON.parse(draftJson.value)); showJson.value=false; void w.savePack() }
  catch (e) { failure.value=`Draft could not be read: ${e instanceof Error?e.message:'invalid JSON'}` }
}
</script>
<template>
  <v-app>
    <v-navigation-drawer v-model="drawer" width="252" color="surface">
      <div class="brand"><span class="brand-ball"/><div>Pinball Pilot<small>Content workshop</small></div></div>
      <v-list nav class="px-3" aria-label="Workshop navigation">
        <v-list-item v-for="item in navigation" :key="item.title" :title="item.title" :prepend-icon="item.icon" :active="section===item.title" color="primary" @click="section=item.title">
          <template v-if="item.title==='Research & review' && pending" #append><v-badge :content="pending" color="primary" inline/></template>
        </v-list-item>
      </v-list>
      <template #append><div class="navigation-note"><v-icon size="18" icon="mdi-shield-lock-outline" class="mr-1"/>Player photos and conversations are private. They never appear in this workshop.</div></template>
    </v-navigation-drawer>
    <v-app-bar flat color="background" height="64"><v-app-bar-nav-icon aria-label="Toggle navigation" @click="drawer=!drawer"/><v-app-bar-title class="text-body-1">Workshop / {{ section }}</v-app-bar-title><v-chip :color="connected?'success':'secondary'" variant="tonal" class="mr-5">{{ connected?'Connected workspace':'Local sandbox' }}</v-chip></v-app-bar>
    <v-main><main class="workshop-shell">
      <v-alert v-if="!w.configured" type="info" variant="tonal" density="compact" class="mb-5">Local sandbox. Your edits stay in this browser. Live research, email accounts and publishing need a connected backend.</v-alert>
      <v-alert v-if="failure" type="error" closable class="mb-5" @click:close="failure=''">{{ failure }}</v-alert>
      <v-alert v-if="notice" type="success" variant="tonal" closable class="mb-5" @click:close="notice=''">{{ notice }}</v-alert>
      <v-select v-if="['Playfield editor','Rules & guides','Publish packs'].includes(section)" :model-value="pack.variantId" :items="Object.values(w.drafts.value).map(p=>({title:`${p.name} · ${p.edition}`,value:p.variantId}))" label="Machine draft" @update:model-value="w.choosePack"/>

      <template v-if="section==='Machines'">
        <header class="page-heading"><div><h1>A good game starts<br>with a clear plan.</h1><p>Build guides players can trust. Map the shots, check the evidence and turn a complicated machine into a few useful next steps.</p></div><v-btn color="primary" prepend-icon="mdi-book-search-outline" @click="section='Research & review'">Review research</v-btn></header>
        <v-text-field v-model="search" label="Find a machine" prepend-inner-icon="mdi-magnify" clearable class="mb-3" @click:clear="search=''"/>
        <v-card v-if="!search || 'iron maiden legacy of the beast pro'.includes(search.toLowerCase())" class="machine-card mb-6">
          <div class="machine-art"><svg viewBox="0 0 160 220" aria-label="Machine awaiting a licensed playfield image" role="img"><rect x="25" y="10" width="110" height="70" rx="6" fill="#151d2d" stroke="#93c7db" stroke-width="3"/><path d="M40 90 H120 L145 190 H15Z" fill="#151d2d" stroke="#93c7db" stroke-width="3"/><path d="M25 192 V215 M135 192 V215" stroke="#b8c6d7" stroke-width="6"/><circle cx="80" cy="45" r="19" fill="none" stroke="#ffc46b" stroke-width="3"/><path d="M80 32 V49 M80 56 V59" stroke="#ffc46b" stroke-width="4"/><path d="M55 174 L72 180 M105 174 L88 180" stroke="#ffc46b" stroke-width="7"/></svg></div>
          <div class="machine-details"><v-chip color="primary" variant="tonal" size="small" class="mb-4">Awaiting your review</v-chip><h2>Iron Maiden:<br>Legacy of the Beast</h2><div class="muted">Stern · Pro edition</div><p class="muted">The first real machine. {{ pending }} findings await review. A licensed playfield image and verified shot map are still needed before players receive a guide.</p><v-btn variant="tonal" color="primary" @click="section='Research & review'">Review {{ claims.length }} findings</v-btn></div>
        </v-card>
        <v-card v-if="!search || 'workshop demonstration'.includes(search.toLowerCase())" class="machine-card">
          <div class="machine-art"><img src="/workshop.svg" alt="Original fictional playfield schematic" style="height:240px;max-width:100%"></div>
          <div class="machine-details"><v-chip color="secondary" variant="tonal" size="small" class="mb-4">Fictional demonstration</v-chip><h2>Workshop table</h2><p class="muted">Explore the complete editing flow with twelve shots, simple and advanced scoring plans, and a route to multiball. These rules do not describe a real machine.</p><v-btn color="secondary" variant="tonal" @click="section='Playfield editor'">Open playfield editor</v-btn></div>
        </v-card>
      </template>

      <template v-else-if="section==='Research & review'">
        <header class="page-heading"><div><h1>Evidence before advice.</h1><p>Iron Maiden Pro. Approve each fact or recommendation against its sources. Manufacturer material carries the most weight; uncertainty stays visible.</p></div><v-chip color="primary">{{ pending }} pending</v-chip></header>
        <ResearchConnector mode="proposals"/>
        <v-expansion-panels class="mb-6"><v-expansion-panel title="Request new research"><v-expansion-panel-text><v-textarea v-model="researchQuery" label="Research question" variant="outlined" rows="3"/><v-btn :loading="busy" color="primary" @click="w.queueResearch(researchQuery)">Queue research</v-btn><p v-if="w.researchRequest.value" class="muted mt-4">Last request: {{ w.researchRequest.value }}</p></v-expansion-panel-text></v-expansion-panel></v-expansion-panels>
        <div class="d-flex ga-4 flex-wrap"><v-btn-toggle v-model="filter" mandatory color="primary" variant="outlined" divided><v-btn value="pending">Pending</v-btn><v-btn value="approved">Approved</v-btn><v-btn value="rejected">Rejected</v-btn><v-btn value="all">All</v-btn></v-btn-toggle><v-text-field v-model="query" label="Search findings" prepend-inner-icon="mdi-magnify" hide-details/></div>
        <div v-if="!visibleClaims.length" class="empty-panel mt-6"><h2>No findings in this view.</h2><p class="muted">Choose another status or clear your search.</p></div>
        <article v-for="claim in visibleClaims" :key="claim.id" class="claim-row">
          <div class="claim-header"><div><span class="muted text-caption">{{ claim.kind==='fact'?'Machine fact':'Strategic recommendation' }} · Revision {{ claim.revision }}</span><h2 class="mt-1">{{ claim.title }}</h2></div><v-chip :color="claim.status==='approved'?'success':claim.status==='rejected'?'error':'primary'" size="small" variant="tonal">{{ claim.status }}</v-chip></div>
          <p class="claim-body">{{ claim.body }}</p>
          <div v-for="source in sourcesFor(claim)" :key="source.id" class="source-line"><v-icon size="17" :icon="source.kind==='manufacturer'?'mdi-factory':'mdi-book-open-outline'" class="mr-2"/><a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }}</a><span class="muted"> · {{ source.locator }}</span></div>
          <p class="muted text-caption mt-3">Software: {{ claim.software }}</p>
          <div class="review-controls"><v-text-field v-model="notes[claim.id]" label="Review note" placeholder="What did you verify?" hide-details/><v-btn color="success" variant="tonal" :disabled="busy || claim.status==='approved'" @click="w.review(claim,'approved',notes[claim.id] || '')">Approve</v-btn><v-btn color="error" variant="text" :disabled="busy || claim.status==='rejected'" @click="w.review(claim,'rejected',notes[claim.id] || '')">Reject</v-btn></div>
          <p v-if="claim.reviewNote" class="muted mt-3">Review: {{ claim.reviewNote }}</p>
        </article>
      </template>

      <template v-else-if="section==='Playfield editor'">
        <header class="page-heading"><div><h1>Every shot has a place.</h1><p>{{ pack.name }} · {{ pack.edition }}. Position markers independently of image resolution. Zoom and scroll to reach small targets.</p></div><v-btn color="primary" :loading="busy" @click="w.savePack">Save draft</v-btn></header>
        <v-expansion-panels class="mb-6"><v-expansion-panel title="Machine details and reference image"><v-expansion-panel-text>
          <v-text-field v-model="pack.name" label="Machine name"/><div class="d-flex ga-4"><v-text-field v-model="pack.edition" label="Edition"/><v-text-field v-model="pack.manufacturer" label="Manufacturer"/></div>
          <v-file-input v-model="referenceFile" label="Reference playfield image" accept="image/jpeg,image/png,image/webp" variant="outlined"/>
          <v-btn :disabled="!referenceFile" :loading="busy" class="mb-5" variant="tonal" @click="referenceFile && w.uploadImage(referenceFile)">Upload reference image</v-btn>
          <v-text-field v-model="pack.image.license" label="Image licence or permission record"/>
          <v-checkbox v-model="pack.image.rightsConfirmed" label="I have verified permission to distribute this reference image" color="primary"/>
        </v-expansion-panel-text></v-expansion-panel></v-expansion-panels>
        <PlayfieldEditor :key="pack.variantId" :pack="pack" :image-url="w.imagePreview.value" @change="w.persist"/>
      </template>

      <template v-else-if="section==='Rules & guides'">
        <v-btn v-if="connected" variant="tonal" :loading="busy" @click="w.loadReviewedEvidence">Load latest evidence into draft</v-btn>
        <header class="page-heading"><div><h1>Turn shots into a plan.</h1><p>{{ pack.name }} · {{ pack.edition }}. Physical shots, rule prerequisites and recommendations stay separate.</p></div><v-btn variant="tonal" @click="draftJson=JSON.stringify(pack,null,2);showJson=true">Edit pack JSON</v-btn></header>
        <v-card v-for="guide in pack.strategies" :key="guide.id" class="pa-6 mb-5"><v-chip size="small" variant="tonal" color="secondary" class="mb-3">{{ guide.level }} {{ guide.objective }}</v-chip><h2>{{ guide.title }}</h2><v-list bg-color="transparent"><v-list-item v-for="(step,i) in guide.steps" :key="step" class="px-0 py-3"><template #prepend><v-avatar size="30" color="primary" variant="tonal" class="mr-4">{{ i+1 }}</v-avatar></template><v-list-item-title class="text-wrap">{{ pack.rules.find(r=>r.id===step)?.instruction }}</v-list-item-title><p class="muted text-body-2 mt-1">{{ pack.rules.find(r=>r.id===step)?.why }}</p></v-list-item></v-list></v-card>
      </template>

      <template v-else-if="section==='Publish packs'">
        <header class="page-heading"><div><h1>Ready for the next game.</h1><p>Check a complete snapshot before it reaches players. Published versions remain unchanged when you edit the next draft.</p></div></header>
        <div class="publish-layout"><v-card class="pa-6"><h2>{{ pack.name }}</h2><p class="muted mb-6">Version {{ pack.version }} · {{ pack.shots.length }} shots · {{ pack.strategies.length }} guides</p><v-alert v-if="errors.length" type="warning" variant="tonal"><p v-for="error in errors" :key="error">{{ error }}</p></v-alert><v-alert v-else type="success" variant="tonal">The fictional demonstration passes the publication checks.</v-alert><v-btn color="primary" class="mt-6" :disabled="errors.length>0" :loading="busy" @click="w.publish">{{ w.configured?'Publish version':'Create sandbox snapshot' }}</v-btn></v-card><v-card class="pa-6"><h2>Iron Maiden Pro</h2><p class="muted">Real-machine publishing remains blocked until the reviewed facts, strategies, licensed image and verified annotations form a complete pack.</p><v-btn variant="text" color="primary" class="mt-4" @click="section='Research & review'">Continue review</v-btn></v-card></div>
        <div class="section-line"><h2>Published snapshots</h2><v-btn variant="text" @click="download(pack,'workshop-draft.json')">Export current draft</v-btn></div>
        <v-list v-if="published.length" bg-color="surface" rounded="lg"><v-list-item v-for="snapshot in published" :key="snapshot.version" :title="`${snapshot.name} — version ${snapshot.version}`" subtitle="Immutable JSON snapshot"><template #append><v-btn variant="tonal" @click="download(snapshot,`workshop-v${snapshot.version}.json`)">Download</v-btn></template></v-list-item></v-list><p v-else class="muted">No snapshots published in this workspace yet.</p>
      </template>

      <template v-else-if="section==='Settings'">
        <header class="page-heading"><div><h1>A small, private pilot.</h1><p>Five testers. A monthly spending ceiling you control. No access to players’ private photos or conversations.</p></div></header>
        <ResearchConnector mode="settings"/>
        <div class="settings-grid"><v-card class="pa-6"><h2>Monthly spending cap</h2><div class="budget-amount">A${{ limit.toFixed(0) }}</div><p class="muted mb-5">AI pauses when the available budget is exhausted. Downloaded guides keep working. Hosting allowance and provider pricing must be configured before enabling paid services.</p><v-text-field v-model.number="budgetInput" type="number" label="Monthly limit (AUD)" min="1" max="10000"/><v-btn color="primary" :loading="busy" @click="w.saveBudget(Number(budgetInput))">Save spending cap</v-btn></v-card><v-card class="pa-6"><h2>Editor sign-in</h2><p class="muted mb-6">Use the email associated with your editor membership. Player accounts do not grant workshop access.</p><v-text-field v-model="email" type="email" label="Email address"/><v-btn variant="tonal" color="secondary" :loading="busy" :disabled="!email.includes('@')" @click="w.signIn(email)">Send sign-in link</v-btn><v-divider class="my-6"/><p class="muted">{{ w.configured ? 'Backend configured. Sign in to load your shared workspace.' : 'No backend credentials configured. This sandbox does not send email or incur AI costs.' }}</p></v-card></div>
      </template>
      <v-dialog v-model="showJson" max-width="1000"><v-card class="pa-6"><h2>Edit structured machine pack</h2><p class="muted mb-4">Changes to authentic content must be reviewed again before publication.</p><v-textarea v-model="draftJson" label="Machine pack JSON" variant="outlined" rows="22" style="font-family:monospace"/><v-card-actions><v-btn @click="showJson=false">Cancel</v-btn><v-btn color="primary" @click="saveJson">Validate and save draft</v-btn></v-card-actions></v-card></v-dialog>
    </main></v-main>
  </v-app>
</template>
