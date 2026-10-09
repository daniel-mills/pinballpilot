-- Immutable evidence history. Current rows remain the editorial working copies.
create table public.software_releases (
  id text primary key, variant_id text not null references public.machine_variants(id),
  version text not null check(length(version) between 1 and 100), released_at date,
  unique(variant_id,version), unique(id,variant_id)
);
alter table public.knowledge_sources add column revision integer not null default 1 check(revision>0);
alter table public.knowledge_sources add column content_hash text check(content_hash ~ '^[a-f0-9]{64}$');
alter table public.claims add column applicability jsonb not null default '{"status":"unknown","releaseIds":[],"settings":""}';
alter table public.claims add column evidence_epoch integer not null default 0;
alter table public.claims add column source_relations jsonb not null default '{}';
alter table public.claims add column rule_spec jsonb;
create table public.source_revisions (
  source_id text not null references public.knowledge_sources(id), revision integer not null,
  snapshot jsonb not null, recorded_at timestamptz not null default now(), primary key(source_id,revision)
);
create table public.claim_revisions (
  claim_id text not null references public.claims(id), revision integer not null,
  variant_id text not null references public.machine_variants(id), snapshot jsonb not null,
  recorded_at timestamptz not null default now(), primary key(claim_id,revision), unique(claim_id,revision,variant_id)
);
create table public.claim_revision_evidence (
  claim_id text not null, claim_revision integer not null, source_id text not null, source_revision integer not null,
  locator text not null, relation text not null default 'supports' check(relation in ('supports','contradicts')),
  primary key(claim_id,claim_revision,source_id),
  foreign key(claim_id,claim_revision) references public.claim_revisions(claim_id,revision),
  foreign key(source_id,source_revision) references public.source_revisions(source_id,revision)
);
create table public.claim_revision_releases (
  claim_id text not null, claim_revision integer not null, variant_id text not null, release_id text not null,
  primary key(claim_id,claim_revision,release_id),
  foreign key(claim_id,claim_revision,variant_id) references public.claim_revisions(claim_id,revision,variant_id),
  foreign key(release_id,variant_id) references public.software_releases(id,variant_id)
);
insert into public.source_revisions select id,revision,to_jsonb(s),now() from public.knowledge_sources s;
insert into public.claim_revisions select id,revision,variant_id,to_jsonb(c),now() from public.claims c;
insert into public.claim_revision_evidence
select c.id,c.revision,s.id,s.revision,s.locator,'supports' from public.claims c join public.knowledge_sources s on s.id=any(c.source_ids);

create function public.immutable_knowledge() returns trigger language plpgsql set search_path='' as $$
begin raise exception 'Knowledge history is immutable; create a new revision'; end $$;
do $$ declare t text; begin
  foreach t in array array['source_revisions','claim_revisions','claim_revision_evidence','claim_revision_releases','software_releases','review_events'] loop
    execute format('create trigger immutable_history before update or delete on public.%I for each row execute function public.immutable_knowledge()',t);
  end loop;
  foreach t in array array['source_revisions','claim_revisions','claim_revision_evidence','claim_revision_releases','software_releases'] loop
    execute format('alter table public.%I enable row level security',t);
    execute format('create policy editor_history on public.%I for select to authenticated using(public.is_editor())',t);
    execute format('grant select on public.%I to authenticated',t);
    execute format('grant all on public.%I to service_role',t);
  end loop;
end $$;

create function public.version_source() returns trigger language plpgsql set search_path='' as $$
begin
  if TG_OP='UPDATE' then
    new.revision:=old.revision;
    if (new.title,new.url,new.kind,new.locator,new.accessed_at,new.notes,new.content_hash) is distinct from
       (old.title,old.url,old.kind,old.locator,old.accessed_at,old.notes,old.content_hash) then new.revision:=old.revision+1; end if;
  else new.revision:=1; end if;
  return new;
end $$;
create trigger version_source before insert or update on public.knowledge_sources for each row execute function public.version_source();
create function public.capture_source() returns trigger language plpgsql security definer set search_path='' as $$
begin
  if TG_OP='INSERT' or new.revision<>old.revision then
    insert into public.source_revisions(source_id,revision,snapshot) values(new.id,new.revision,to_jsonb(new));
  end if;
  return new;
end $$;
create trigger a_capture_source after insert or update on public.knowledge_sources for each row execute function public.capture_source();
create or replace function public.invalidate_source_claims() returns trigger language plpgsql security definer set search_path='' as $$
begin
  if new.revision<>old.revision then
    update public.claims set evidence_epoch=evidence_epoch+1 where old.id=any(source_ids);
  end if;
  return new;
end $$;
create or replace function public.protect_claim_revision() returns trigger language plpgsql set search_path='' as $$
begin
  if (new.kind,new.title,new.body,new.source_ids,new.software,new.variant_id,new.applicability,new.evidence_epoch,new.source_relations,new.rule_spec) is distinct from
     (old.kind,old.title,old.body,old.source_ids,old.software,old.variant_id,old.applicability,old.evidence_epoch,old.source_relations,old.rule_spec) then
    new.revision:=old.revision+1; new.status:='pending'; new.reviewed_revision:=null; new.reviewed_by:=null; new.review_note:='';
  else new.revision:=old.revision; end if;
  return new;
end $$;
create function public.capture_claim() returns trigger language plpgsql security definer set search_path='' as $$
declare a jsonb:=new.applicability;
begin
  if jsonb_typeof(a) is distinct from 'object' or a->>'status' is null or a->>'status' not in ('unknown','releases','not_applicable')
     or jsonb_typeof(a->'releaseIds') is distinct from 'array' or jsonb_typeof(a->'settings') is distinct from 'string' then raise exception 'Invalid applicability'; end if;
  if (a->>'status'='releases') <> (jsonb_array_length(a->'releaseIds')>0) then raise exception 'Release applicability must list releases'; end if;
  if TG_OP='INSERT' or new.revision<>old.revision then
    insert into public.claim_revisions(claim_id,revision,variant_id,snapshot) values(new.id,new.revision,new.variant_id,to_jsonb(new));
    insert into public.claim_revision_evidence(claim_id,claim_revision,source_id,source_revision,locator,relation)
      select new.id,new.revision,s.id,s.revision,s.locator,coalesce(new.source_relations->>s.id,'supports') from public.knowledge_sources s where s.id=any(new.source_ids);
    if (select count(*) from public.claim_revision_evidence where claim_id=new.id and claim_revision=new.revision)<>cardinality(new.source_ids) then raise exception 'Missing or duplicate source'; end if;
    insert into public.claim_revision_releases select new.id,new.revision,new.variant_id,jsonb_array_elements_text(a->'releaseIds');
    -- Compatibility array is the current-row input; keep its relational projection consistent.
    delete from public.claim_sources where claim_id=new.id;
    insert into public.claim_sources select new.id,unnest(new.source_ids);
  end if;
  return new;
end $$;
create trigger capture_claim after insert or update on public.claims for each row execute function public.capture_claim();

-- Restrict history mutation to these triggers/RPCs; readers may inspect history only.
revoke insert,update,delete on public.claim_sources from authenticated;

-- Add precise revision checks to the existing transaction and its draft/claim locks.
alter function public.publish_pack(text,integer,integer,text,uuid) rename to publish_pack_v1;
create function public.publish_pack(p_variant text,p_version integer,p_draft_revision integer,p_hash text,p_actor uuid) returns void
language plpgsql security definer set search_path='' as $$
declare d public.pack_drafts; item jsonb; source jsonb; s public.knowledge_sources; c public.claims; expected jsonb; release public.software_releases;
begin
  if not exists(select 1 from public.memberships where user_id=p_actor and role in ('editor','owner')) then raise exception 'Not authorised'; end if;
  select * into d from public.pack_drafts where variant_id=p_variant for update;
  for item in select value from jsonb_array_elements(coalesce(d.payload->'releases','[]')) loop
    select * into release from public.software_releases where id=item->>'id';
    if release.id is null or release.variant_id<>p_variant or item->>'variantId' is distinct from release.variant_id or
       item->>'version' is distinct from release.version or item->>'releasedAt' is distinct from release.released_at::text then raise exception 'Software release does not match the registered edition and version'; end if;
  end loop;
  for source in select value from jsonb_array_elements(coalesce(d.payload->'sources','[]')) loop
    select * into s from public.knowledge_sources where id=source->>'id' for share;
    if s.id is null or (source->>'title',source->>'url',source->>'kind',source->>'locator',source->>'accessedAt',source->>'notes') is distinct from
       (s.title,s.url,s.kind,s.locator,s.accessed_at::text,s.notes) then raise exception 'Source changed; refresh evidence'; end if;
    if d.payload->>'schemaVersion'='2' and ((source->>'revision')::integer is distinct from s.revision or source->>'contentHash' is distinct from s.content_hash) then raise exception 'Source revision changed'; end if;
  end loop;
  for item in select value from jsonb_array_elements(coalesce(d.payload->'claims','[]')) loop
    select * into c from public.claims where id=item->>'id' for share;
    if item ? 'software' and item->>'software' is distinct from c.software then raise exception 'Software evidence changed'; end if;
    if d.payload->>'schemaVersion'='2' then
      select jsonb_agg(jsonb_build_object('sourceId',e.source_id,'sourceRevision',e.source_revision,'locator',e.locator,'relation',e.relation) order by e.source_id)
        into expected from public.claim_revision_evidence e where e.claim_id=c.id and e.claim_revision=c.revision;
      if item->'applicability' is distinct from c.applicability or nullif(item->'ruleSpec','null'::jsonb) is distinct from c.rule_spec or item->'citations' is null or
        not ((item->'citations') @> expected and expected @> (item->'citations')) then raise exception 'Evidence references changed'; end if;
    end if;
  end loop;
  perform public.publish_pack_v1(p_variant,p_version,p_draft_revision,p_hash,p_actor);
end $$;
revoke all on function public.publish_pack(text,integer,integer,text,uuid) from public,anon,authenticated;
grant execute on function public.publish_pack(text,integer,integer,text,uuid) to service_role;
revoke all on function public.publish_pack_v1(text,integer,integer,text,uuid) from public,anon,authenticated,service_role;

create view public.current_claim_evidence with(security_invoker=true) as
select e.* from public.claim_revision_evidence e join public.claims c on c.id=e.claim_id and c.revision=e.claim_revision;
grant select on public.current_claim_evidence to authenticated,service_role;
