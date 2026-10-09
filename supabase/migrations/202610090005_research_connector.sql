-- Connector credentials grant content reads and proposal submission only.
create table public.research_tokens (
  id uuid primary key default gen_random_uuid(), owner_id uuid not null references auth.users(id),
  label text not null check(length(label) between 1 and 80), token_hash text unique not null check(length(token_hash)=64),
  created_at timestamptz not null default now(), expires_at timestamptz not null,
  revoked_at timestamptz, check(expires_at>created_at)
);
alter table public.research_tokens enable row level security;
revoke all on public.research_tokens from anon,authenticated;
grant all on public.research_tokens to service_role;

create table public.research_proposals (
  id uuid primary key default gen_random_uuid(), request_id uuid not null,
  token_id uuid not null references public.research_tokens(id), submitted_by uuid not null references auth.users(id),
  variant_id text not null references public.machine_variants(id), target_claim_id text references public.claims(id),
  base_revision integer, payload jsonb not null, previous_claim jsonb,
  status text not null default 'pending' check(status in ('pending','approved','rejected')),
  created_at timestamptz not null default now(), reviewed_at timestamptz, reviewed_by uuid references auth.users(id),
  review_note text not null default '', applied_claim_id text references public.claims(id),
  unique(token_id,request_id), check((target_claim_id is null)=(base_revision is null))
);
create index research_proposals_queue on public.research_proposals(status,created_at);
alter table public.research_proposals enable row level security;
revoke all on public.research_proposals from anon,authenticated;
grant select on public.research_proposals to authenticated;
create policy editor_proposals on public.research_proposals for select to authenticated using(public.is_editor());
grant all on public.research_proposals to service_role;

create function public.submit_research_proposal(p_token uuid,p_payload jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare t public.research_tokens; c public.claims; existing public.research_proposals; proposal_id uuid;
begin
  select * into t from public.research_tokens where id=p_token for share;
  if t.id is null or t.revoked_at is not null or t.expires_at<=now() or not exists(select 1 from public.memberships where user_id=t.owner_id and role in ('editor','owner')) then raise exception 'Connector expired or revoked' using errcode='42501'; end if;
  -- Serialize retries and bound the pending queue per connector.
  perform pg_advisory_xact_lock(hashtextextended(p_token::text,0));
  select * into existing from public.research_proposals where token_id=p_token and request_id=(p_payload->>'requestId')::uuid;
  if existing.id is not null then
    if existing.payload<>p_payload then raise exception 'Request ID already used for different content' using errcode='P0002'; end if;
    return jsonb_build_object('id',existing.id,'status',existing.status,'duplicate',true);
  end if;
  if (select count(*) from public.research_proposals where token_id=p_token and status='pending')>=200 then raise exception 'Review pending proposals before submitting more' using errcode='P0002'; end if;
  if not exists(select 1 from public.machine_variants where id=p_payload->>'variantId') then raise exception 'Unknown machine edition' using errcode='P0002'; end if;
  if p_payload->>'targetClaimId' is not null then
    select * into c from public.claims where id=p_payload->>'targetClaimId' for share;
    if c.id is null or c.variant_id<>p_payload->>'variantId' or c.revision<>(p_payload->>'baseRevision')::integer then raise exception 'Claim changed; read current knowledge and resubmit' using errcode='P0002'; end if;
  end if;
  insert into public.research_proposals(request_id,token_id,submitted_by,variant_id,target_claim_id,base_revision,payload,previous_claim)
    values((p_payload->>'requestId')::uuid,t.id,t.owner_id,p_payload->>'variantId',c.id,c.revision,p_payload,case when c.id is null then null else to_jsonb(c) end) returning id into proposal_id;
  return jsonb_build_object('id',proposal_id,'status','pending','duplicate',false);
end $$;

create function public.review_research_proposal(p_id uuid,p_status text,p_note text,p_actor uuid) returns void
language plpgsql security definer set search_path='' as $$
declare p public.research_proposals; c public.claims; source jsonb; source_id text; v_source_ids text[]:='{}'; v_claim_id text;
begin
  if p_status not in ('approved','rejected') or not exists(select 1 from public.memberships where user_id=p_actor and role in ('editor','owner')) then raise exception 'Not authorised' using errcode='42501'; end if;
  select * into p from public.research_proposals where id=p_id for update;
  if p.id is null or p.status<>'pending' then raise exception 'Proposal already reviewed or missing' using errcode='P0002'; end if;
  if p_status='approved' then
    if p.target_claim_id is not null then
      select * into c from public.claims where id=p.target_claim_id for update;
      if c.id is null or c.revision<>p.base_revision or c.variant_id<>p.variant_id then raise exception 'Claim changed; request a fresh proposal' using errcode='P0002'; end if;
    end if;
    for source in select value from jsonb_array_elements(p.payload->'sources') loop
      source_id:='mcp-source-'||gen_random_uuid()::text;
      insert into public.knowledge_sources(id,title,url,kind,locator,accessed_at,notes) values(source_id,source->>'title',source->>'url',source->>'kind',source->>'locator',(source->>'accessedAt')::date,source->>'notes');
      v_source_ids:=array_append(v_source_ids,source_id);
    end loop;
    v_claim_id:=coalesce(p.target_claim_id,'mcp-claim-'||gen_random_uuid()::text);
    if p.target_claim_id is null then
      insert into public.claims(id,variant_id,kind,title,body,source_ids,software) values(v_claim_id,p.variant_id,p.payload->>'kind',p.payload->>'title',p.payload->>'body',v_source_ids,p.payload->>'software');
    else
      update public.claims set kind=p.payload->>'kind',title=p.payload->>'title',body=p.payload->>'body',source_ids=v_source_ids,software=p.payload->>'software' where id=v_claim_id;
    end if;
    delete from public.claim_sources where claim_sources.claim_id=v_claim_id;
    insert into public.claim_sources(claim_id,source_id) select v_claim_id,unnest(v_source_ids);
    select * into c from public.claims where id=v_claim_id;
    perform public.review_claim(v_claim_id,c.revision,'approved',p_note,p_actor);
    -- Draft text is deliberately unchanged. The editor must incorporate the approved
    -- evidence and revalidate the guide before publishing a new snapshot.
  end if;
  update public.research_proposals set status=p_status,review_note=p_note,reviewed_by=p_actor,reviewed_at=now(),applied_claim_id=v_claim_id where id=p_id;
end $$;
revoke all on function public.submit_research_proposal(uuid,jsonb) from public,anon,authenticated;
revoke all on function public.review_research_proposal(uuid,text,text,uuid) from public,anon,authenticated;
grant execute on function public.submit_research_proposal(uuid,jsonb) to service_role;
grant execute on function public.review_research_proposal(uuid,text,text,uuid) to service_role;
