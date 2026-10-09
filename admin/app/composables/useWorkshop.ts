import { createClient, type SupabaseClient } from '@supabase/supabase-js'
import demo from '../data/demo-pack.json'
import research from '../data/iron-maiden-research.json'
import maidenDraft from '../data/iron-maiden-draft.json'
import { claimSchema, sourceSchema, packSchema, type Claim, type MachinePack, publicationErrors } from '../../../shared/contracts'

export function useWorkshop() {
  const config = useRuntimeConfig()
  const configured = Boolean(config.public.supabaseUrl && config.public.supabaseAnonKey)
  const pack = useState<MachinePack>('pack', () => packSchema.parse(demo))
  const drafts = useState<Record<string,MachinePack>>('drafts', () => ({[demo.variantId]:packSchema.parse(demo),[maidenDraft.variantId]:packSchema.parse(maidenDraft)}))
  const imagePreview = useState('imagePreview', () => '/workshop.svg')
  const claims = useState<Claim[]>('claims', () => structuredClone(research.claims) as Claim[])
  const notice = useState('notice', () => '')
  const failure = useState('failure', () => '')
  const busy = useState('busy', () => false)
  const connected = useState('connected', () => false)
  const limit = useState('limit', () => 60)
  const researchRequest = useState('researchRequest', () => '')
  const published = useState<MachinePack[]>('published', () => [])
  const sources = useState('sources', () => structuredClone(research.sources))
  const jobs = useState<{id:string;query:string;state:string;error:string|null}[]>('jobs', () => [])
  let client: SupabaseClient | undefined
  function db() { return client ??= createClient(String(config.public.supabaseUrl), String(config.public.supabaseAnonKey)) }
  async function api(path: string, body?: unknown) {
    const { data: { session } } = await db().auth.getSession()
    if (!session) throw new Error('Sign in using your editor email first.')
    const response = await fetch(`${config.public.supabaseUrl}/functions/v1/pilot/${path}`, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${session.access_token}`, 'Content-Type': 'application/json', apikey: String(config.public.supabaseAnonKey) }, ...(body ? { body: JSON.stringify(body) } : {}) })
    const data = await response.json()
    if (!response.ok) throw new Error(data.error || 'Request failed. Try again.')
    return data
  }
  function persist() {
    drafts.value[pack.value.variantId]=pack.value
    if (import.meta.client && !configured) { try { localStorage.setItem('pilot-workshop-v1', JSON.stringify({ pack: pack.value, drafts:drafts.value, claims: claims.value, limit: limit.value, published: published.value, researchRequest: researchRequest.value })) } catch {failure.value='Browser storage is full. Export your draft before closing this page.'} }
  }
  async function choosePack(id:string) {
    persist();pack.value=drafts.value[id]??pack.value
    if(configured&&!pack.value.image.path.startsWith('/')) {const {data,error}=await db().storage.from('playfields').createSignedUrl(pack.value.image.path,3600);imagePreview.value=error?'/awaiting-image.svg':data.signedUrl}
    else imagePreview.value=pack.value.image.path.startsWith('data:')?pack.value.image.path:pack.value.demo?'/workshop.svg':'/awaiting-image.svg'
  }
  async function uploadImage(file:File) {
    await run(async()=>{
      if(!['image/jpeg','image/png','image/webp'].includes(file.type)||file.size>(configured?20_000_000:2_000_000)) throw new Error('Choose a JPEG, PNG or WebP under '+(configured?'20 MB.':'2 MB for the local sandbox.'))
      const image=await createImageBitmap(file)
      let path:string
      if(configured) {path=`${pack.value.variantId}/${crypto.randomUUID()}.${file.type.split('/')[1]}`;const {error}=await db().storage.from('playfields').upload(path,file,{contentType:file.type});if(error)throw error;const {data}=await db().storage.from('playfields').createSignedUrl(path,3600);imagePreview.value=data?.signedUrl??'/awaiting-image.svg'}
      else {path=await new Promise<string>((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=reject;reader.readAsDataURL(file)});imagePreview.value=path}
      pack.value.image={path,width:image.width,height:image.height,license:'Rights not yet recorded',rightsConfirmed:false};image.close();persist();notice.value='Reference image uploaded. Record its licence and verify marker locations before publishing.'
    })
  }
  async function run(task: () => Promise<void>) {
    busy.value = true; failure.value = ''; notice.value = ''
    try { await task() } catch (e) { failure.value = e instanceof Error ? e.message : 'Unable to complete this action.' } finally { busy.value = false }
  }
  async function initialise() {
    if (configured) {
      await run(async () => {
        const { data: { session } } = await db().auth.getSession()
        if (!session) return
        const data = await api('admin/workspace')
        claims.value = data.claims; connected.value = true
        sources.value = data.sources; jobs.value = data.jobs
        if (data.pack) pack.value = packSchema.parse(data.pack)
        if(data.drafts) drafts.value=Object.fromEntries(data.drafts.map((p:unknown)=>{const parsed=packSchema.parse(p);return [parsed.variantId,parsed]}))
        limit.value = data.budget.limitMicroAud / 1_000_000
      })
    } else if (import.meta.client) {
      try {
        const raw = localStorage.getItem('pilot-workshop-v1')
        if (raw) { const data = JSON.parse(raw); pack.value = packSchema.parse(data.pack); if(data.drafts) drafts.value=data.drafts;claims.value = data.claims; limit.value = data.limit ?? 60; published.value = data.published ?? []; researchRequest.value = data.researchRequest ?? '' }
      } catch { failure.value = 'Saved sandbox data could not be loaded. Export your work before clearing browser storage.' }
    }
    if (!configured || connected.value) await choosePack(pack.value.variantId)
  }
  async function signIn(email: string) {
    await run(async () => {
      if (!configured) throw new Error('Set the Supabase URL and public key to enable email sign-in. The sandbox works without an account.')
      const { error } = await db().auth.signInWithOtp({ email, options: { emailRedirectTo: window.location.origin } })
      if (error) throw error
      notice.value = 'Sign-in link sent. Open it in this browser.'
    })
  }
  async function review(claim: Claim, status: 'approved' | 'rejected', reviewNote: string) {
    await run(async () => {
      if (configured) await api('admin/review', { id: claim.id, revision: claim.revision, status, reviewNote })
      const i = claims.value.findIndex(c => c.id === claim.id)
      claims.value[i] = { ...claim, status, reviewedRevision: status === 'approved' ? claim.revision : null, reviewNote }
      for(const draft of Object.values(drafts.value)) draft.claims=draft.claims.map(c=>c.id===claim.id?claims.value[i]!:c)
      pack.value.claims=pack.value.claims.map(c=>c.id===claim.id?claims.value[i]!:c)
      persist(); notice.value = `${status === 'approved' ? 'Approved' : 'Rejected'}: ${claim.title}${configured ? '' : ' (sandbox only)'}`
    })
  }
  async function savePack() {
    await run(async () => { if (configured) await api('admin/draft', { pack: pack.value }); persist(); notice.value = 'Draft saved. Changes require review before publication.' })
  }
  async function publish() {
    await run(async () => {
      const errors = publicationErrors(pack.value)
      if (errors.length) throw new Error(errors.join('. '))
      const snapshot = packSchema.parse(JSON.parse(JSON.stringify(pack.value)))
      if (configured) await api('admin/publish', { variantId: snapshot.variantId, version: snapshot.version })
      published.value.unshift(snapshot); pack.value.version++; persist()
      notice.value = configured ? 'Pack published. Players can download the new version.' : 'Sandbox snapshot created. Download the pack to inspect it; no live content was published.'
    })
  }
  async function loadReviewedEvidence() {
    await run(async()=>{
      if(!connected.value) throw new Error('Connect and sign in to load reviewed evidence.')
      const target=pack.value
      const evidence:Claim[]=[]
      const references=new Map(target.sources.map(s=>[s.id,s]))
      let offset:number|null=0
      while(offset!==null) {
        const data=await api(`research/knowledge/${encodeURIComponent(target.variantId)}?offset=${offset}`)
        for(const c of data.claims) evidence.push(claimSchema.parse({id:c.id,variantId:c.variant_id,kind:c.kind,title:c.title,body:c.body,sourceIds:c.source_ids,status:c.status,revision:c.revision,reviewedRevision:c.reviewed_revision,software:c.software,reviewNote:c.review_note,applicability:c.applicability,ruleSpec:c.rule_spec,citations:(data.evidence??[]).filter((e:any)=>e.claim_id===c.id&&e.claim_revision===c.revision).map((e:any)=>({sourceId:e.source_id,sourceRevision:e.source_revision,locator:e.locator,relation:e.relation}))}))
        for(const s of data.sources) references.set(s.id,sourceSchema.parse({id:s.id,title:s.title,url:s.url,kind:s.kind,locator:s.locator,accessedAt:s.accessed_at,notes:s.notes,revision:s.revision,contentHash:s.content_hash}))
        target.releases=(data.releases??[]).map((r:any)=>({id:r.id,variantId:r.variant_id,version:r.version,releasedAt:r.released_at}))
        offset=data.nextOffset
      }
      const merged=new Map(target.claims.map(c=>[c.id,c]));for(const c of evidence) if(c.status==='approved'||merged.has(c.id)) merged.set(c.id,c)
      target.claims=[...merged.values()];target.sources=[...references.values()]
      persist();notice.value='Latest evidence loaded into this draft. Check guide wording and any remaining review requirements, then save before publishing.'
    })
  }
  async function upgradePack() {
    if(configured) { await loadReviewedEvidence(); if(failure.value) return }
    pack.value=packSchema.parse({...pack.value,schemaVersion:2,engineVersion:1,stateVariables:pack.value.stateVariables??[],releases:pack.value.releases??[],applicability:pack.value.applicability??{status:'unknown',releaseIds:[],settings:''}})
    if(!configured) {
      pack.value.sources=pack.value.sources.map(s=>({...s,revision:s.revision??1,contentHash:s.contentHash??null}))
      pack.value.claims=pack.value.claims.map(c=>({...c,applicability:c.applicability??{status:'unknown',releaseIds:[],settings:''},citations:c.citations??c.sourceIds.map(id=>{const source=pack.value.sources.find(s=>s.id===id);return {sourceId:id,sourceRevision:source?.revision??1,locator:source?.locator??'',relation:'supports' as const}})}))
    }
    persist();notice.value='Draft upgraded to version 2. Software applicability remains unknown until researched. Any new state behaviour requires individual review.'
  }
  async function queueResearch(query: string) {
    await run(async () => {
      if (!query.trim()) throw new Error('Describe what you want to research.')
      if (configured) await api('admin/research', { variantId: research.variantId, query })
      researchRequest.value = query; persist()
      notice.value = configured ? 'Research queued for the worker. Findings will require individual review.' : 'Research request saved locally. Connect the backend and research worker to run it.'
    })
  }
  async function saveBudget(value: number) {
    await run(async () => {
      if (!Number.isFinite(value) || value < 1 || value > 10000) throw new Error('Enter a monthly limit from A$1 to A$10,000.')
      if (configured) await api('admin/budget', { limitMicroAud: Math.round(value * 1_000_000) })
      limit.value = value; persist(); notice.value = configured ? 'Monthly cap updated.' : 'Sandbox cap saved. Live billing is not connected.'
    })
  }
  return { api, upgradePack, loadReviewedEvidence, configured, connected, pack, drafts,imagePreview,choosePack,uploadImage, claims, sources, jobs, research, limit, notice, failure, busy, published, researchRequest, initialise, signIn, review, savePack, publish, queueResearch, saveBudget, persist }
}
