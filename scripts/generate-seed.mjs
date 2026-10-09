import { readFileSync, writeFileSync } from 'node:fs'
const demo=JSON.parse(readFileSync('content/demo-pack.json','utf8'))
const maiden=JSON.parse(readFileSync('content/iron-maiden-research.json','utf8'))
const q=value=>`'${String(value).replaceAll("'","''")}'`
const j=value=>`${q(JSON.stringify(value))}::jsonb`
let sql=`-- Generated from content/*.json. Authentic findings remain pending.\n`
sql+=`insert into public.machines(id,name,manufacturer,year) values ('iron-maiden','Iron Maiden: Legacy of the Beast','Stern',2018),('workshop','Workshop table','Pinball Pilot',2026) on conflict do nothing;\n`
sql+=`insert into public.machine_variants(id,machine_id,edition) values ('iron-maiden-pro','iron-maiden','Pro'),('workshop-demo','workshop','Fictional demonstration') on conflict do nothing;\n`
for(const p of [demo,maiden]) {
  for(const s of p.sources) sql+=`insert into public.knowledge_sources(id,title,url,kind,locator,accessed_at,notes) values (${[s.id,s.title,s.url,s.kind,s.locator,s.accessedAt,s.notes].map(q).join(',')}) on conflict do nothing;\n`
  for(const c of p.claims) sql+=`insert into public.claims(id,variant_id,kind,title,body,source_ids,status,revision,reviewed_revision,software,review_note) values (${[c.id,c.variantId,c.kind,c.title,c.body].map(q).join(',')},array[${c.sourceIds.map(q).join(',')}],${q(c.status)},${c.revision},${c.reviewedRevision??'null'},${q(c.software)},${q(c.reviewNote)}) on conflict do nothing;\n`
  for(const c of p.claims) for(const id of c.sourceIds) sql+=`insert into public.claim_sources values(${q(c.id)},${q(id)}) on conflict do nothing;\n`
}
sql+=`insert into public.pack_drafts(variant_id,payload) values (${q(demo.variantId)},${j(demo)}) on conflict do nothing;\n`
const maidenDraft=JSON.parse(readFileSync('content/iron-maiden-draft.json','utf8'))
sql+=`insert into public.pack_drafts(variant_id,payload) values (${q(maidenDraft.variantId)},${j(maidenDraft)}) on conflict do nothing;\n`
writeFileSync('supabase/seed.sql',sql)
console.log('Generated non-destructive database seed; no authentic content approved.')
