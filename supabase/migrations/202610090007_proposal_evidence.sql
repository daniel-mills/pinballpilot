create or replace function public.review_research_proposal(p_id uuid,p_status text,p_note text,p_actor uuid) returns void
language plpgsql security definer set search_path='' as $$
declare p public.research_proposals; c public.claims; source jsonb; source_id text; v_source_ids text[]:='{}'; v_claim_id text; relations jsonb:='{}'; v_applicability jsonb;
begin
  if p_status not in ('approved','rejected') or not exists(select 1 from public.memberships where user_id=p_actor and role in ('editor','owner')) then raise exception 'Not authorised' using errcode='42501'; end if;
  select * into p from public.research_proposals where id=p_id for update;
  if p.id is null or p.status<>'pending' then raise exception 'Proposal already reviewed or missing' using errcode='P0002'; end if;
  if p_status='approved' then
    v_applicability:=coalesce(p.payload->'applicability','{"status":"unknown","releaseIds":[],"settings":""}'::jsonb);
    if p.target_claim_id is not null then
      select * into c from public.claims where id=p.target_claim_id for update;
      if c.id is null or c.revision<>p.base_revision or c.variant_id<>p.variant_id then raise exception 'Claim changed; request a fresh proposal' using errcode='P0002'; end if;
    end if;
    for source in select value from jsonb_array_elements(p.payload->'sources') loop
      source_id:='mcp-source-'||gen_random_uuid()::text;
      insert into public.knowledge_sources(id,title,url,kind,locator,accessed_at,notes,content_hash) values(source_id,source->>'title',source->>'url',source->>'kind',source->>'locator',(source->>'accessedAt')::date,source->>'notes',source->>'contentHash');
      relations:=relations||jsonb_build_object(source_id,coalesce(source->>'relation','supports'));
      v_source_ids:=array_append(v_source_ids,source_id);
    end loop;
    v_claim_id:=coalesce(p.target_claim_id,'mcp-claim-'||gen_random_uuid()::text);
    if p.target_claim_id is null then
      insert into public.claims(id,variant_id,kind,title,body,source_ids,software,applicability,source_relations,rule_spec) values(v_claim_id,p.variant_id,p.payload->>'kind',p.payload->>'title',p.payload->>'body',v_source_ids,p.payload->>'software',v_applicability,relations,nullif(p.payload->'ruleSpec','null'::jsonb));
    else
      update public.claims set kind=p.payload->>'kind',title=p.payload->>'title',body=p.payload->>'body',source_ids=v_source_ids,software=p.payload->>'software',applicability=v_applicability,source_relations=relations,rule_spec=nullif(p.payload->'ruleSpec','null'::jsonb) where id=v_claim_id;
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
