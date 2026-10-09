import { test,expect } from '@playwright/test'
import demo from '../../content/demo-pack.json' with {type:'json'}
import research from '../../content/iron-maiden-research.json' with {type:'json'}
import maiden from '../../content/iron-maiden-draft.json' with {type:'json'}
test.use({baseURL:'http://127.0.0.1:3001'})
test('editor creates and revokes a token, reviews a sourced correction, and loads evidence into a draft',async({page})=>{
  const secret='pp_mcp_'+'a'.repeat(64)
  const p={id:'50000000-0000-4000-8000-000000000001',variant_id:'iron-maiden-pro',target_claim_id:research.claims[0]!.id,base_revision:1,status:'pending',created_at:new Date().toISOString(),review_note:'',applied_claim_id:null,payload:{title:'Clarify the qualification sequence',body:'Revised instruction for browser testing only.',kind:'recommendation',software:'Unverified',rationale:'Clarify the next shot.',uncertainty:'Verify the installed software.',sources:[{title:'Manufacturer reference',url:'https://www.sternpinball.com/game/iron-maiden/pro/',kind:'manufacturer',locator:'Feature description',accessedAt:'2026-10-09',notes:'Test fixture'}]},previous_claim:{body:'Previous wording for comparison'}}
  const tokens:{id:string;label:string;expires_at:string;revoked_at:string|null}[]=[]
  const calls:string[]=[]
  await page.addInitScript(()=>{
    const access_token=btoa(JSON.stringify({alg:'HS256',typ:'JWT'}))+'.'+btoa(JSON.stringify({sub:'00000000-0000-4000-8000-000000000003',exp:Math.floor(Date.now()/1000)+3600}))+'.test-signature'
    localStorage.setItem('sb-127-auth-token',JSON.stringify({access_token,refresh_token:'browser-test-refresh',expires_at:Math.floor(Date.now()/1000)+3600,token_type:'bearer',user:{id:'00000000-0000-4000-8000-000000000003',email:'editor@example.test'}}))
  })
  await page.route('http://127.0.0.1:54399/**',async route=>{
    const request=route.request();const url=new URL(request.url());const path=url.pathname.split('/pilot/')[1];calls.push(`${request.method()} ${path}`)
    let data:unknown={};let status=200
    if(request.method()==='OPTIONS') return route.fulfill({status:204,headers:{'Access-Control-Allow-Origin':'http://127.0.0.1:3001','Access-Control-Allow-Headers':'authorization,apikey,content-type,x-client-info','Access-Control-Allow-Methods':'GET,POST,OPTIONS'}})
    if(path==='admin/workspace') data={claims:research.claims,sources:research.sources,jobs:[],drafts:[demo,maiden],pack:demo,budget:{limitMicroAud:60000000}}
    else if(path==='admin/connector-tokens'&&request.method()==='POST') {tokens.push({id:'40000000-0000-4000-8000-000000000001',label:request.postDataJSON().label,expires_at:'2026-12-01T00:00:00Z',revoked_at:null});data={token:secret,record:tokens[0]}}
    else if(path==='admin/connector-tokens') data=tokens
    else if(path==='admin/connector-tokens/revoke') {tokens[0]!.revoked_at=new Date().toISOString();data={ok:true}}
    else if(path==='admin/proposals') data={items:[p],nextOffset:null}
    else if(path==='admin/proposals/review') {const b=request.postDataJSON();p.status=b.status;p.review_note=b.note;data={ok:true}}
    else if(path==='research/knowledge/workshop-demo') data={claims:[],sources:[],nextOffset:null}
    else {status=404;data={error:'Unexpected browser test request'}}
    await route.fulfill({status,json:data,headers:{'Access-Control-Allow-Origin':'http://127.0.0.1:3001'}})
  })
  await page.goto('/')
  await expect(page.getByText('Connected workspace',{exact:true})).toBeVisible()
  await page.getByText('Settings',{exact:true}).click()
  await page.getByLabel('Connector name').fill('Claude research')
  await page.getByRole('button',{name:'Create connector token'}).click()
  await expect(page.getByLabel('New connector token')).toHaveValue(secret)
  expect(await page.evaluate(()=>JSON.stringify(localStorage))).not.toContain(secret)
  await page.getByRole('button',{name:'Hide token'}).click()
  await expect(page.getByLabel('New connector token')).toHaveCount(0)
  await page.getByRole('button',{name:'Revoke',exact:true}).click()
  await expect(page.getByText('Connector revoked.',{exact:true})).toBeVisible()
  await page.getByText('Research & review',{exact:true}).click()
  await expect(page.getByText('Previous wording for comparison')).toBeVisible()
  await expect(page.getByRole('link',{name:'Manufacturer reference'})).toHaveAttribute('href',p.payload.sources[0]!.url)
  await expect(page.getByRole('button',{name:'Approve proposal'})).toBeDisabled()
  await page.getByLabel('Proposal review note').fill('Checked the manufacturer source and edition.')
  await page.screenshot({path:'artifacts/admin-mcp-review.png',fullPage:true,animations:'disabled'})
  await page.getByRole('button',{name:'Approve proposal'}).click()
  await expect(page.getByText(/Evidence approved. Load it into/)).toBeVisible()
  expect(calls).not.toContain('POST admin/publish')
  await page.getByText('Rules & guides',{exact:true}).click()
  await page.getByRole('button',{name:'Load latest evidence into draft'}).click()
  await expect(page.getByText(/Latest evidence loaded into this draft/)).toBeVisible()
})
