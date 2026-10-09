-- Content is editable only by explicitly provisioned editors. Player data is owner-only.
create table public.memberships (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('tester','editor','owner'))
);
alter table public.memberships enable row level security;
create policy own_membership on public.memberships for select to authenticated using (user_id = auth.uid());
create function public.is_editor() returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.memberships where user_id = auth.uid() and role in ('editor','owner'));
$$;
create function public.is_tester() returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.memberships where user_id = auth.uid());
$$;
create function public.is_owner() returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.memberships where user_id = auth.uid() and role = 'owner');
$$;

create table public.machines (id text primary key, name text not null, manufacturer text not null, year integer);
create table public.machine_variants (id text primary key, machine_id text not null references public.machines(id), edition text not null, software text not null default 'unverified', unique(machine_id,edition));
create table public.playfields (
  id text primary key, variant_id text not null references public.machine_variants(id), image_path text not null,
  width integer not null check(width > 0), height integer not null check(height > 0), license text not null,
  rights_confirmed boolean not null default false, unique(id,variant_id)
);
create table public.shots (
  id text not null, variant_id text not null references public.machine_variants(id), playfield_id text not null,
  name text not null, type text not null, geometry jsonb not null,
  difficulty double precision not null check(difficulty between 0 and 1), risk double precision not null check(risk between 0 and 1),
  primary key(variant_id,id), foreign key(playfield_id,variant_id) references public.playfields(id,variant_id),
  check(jsonb_typeof(geometry->'point') = 'object'),
  check((geometry->'point'->>'x')::numeric between 0 and 1 and (geometry->'point'->>'y')::numeric between 0 and 1)
);
create table public.knowledge_sources (
  id text primary key, title text not null, url text not null check(url ~ '^https://'),
  kind text not null check(kind in ('manufacturer','rulesheet','community','video','original')),
  locator text not null default '', accessed_at date not null, notes text not null default ''
);
create table public.claims (
  id text primary key, variant_id text not null references public.machine_variants(id), kind text not null check(kind in ('fact','recommendation')),
  title text not null, body text not null, source_ids text[] not null check(cardinality(source_ids)>0),
  revision integer not null default 1 check(revision>0), reviewed_revision integer,
  status text not null default 'pending' check(status in ('pending','approved','rejected')),
  software text not null default 'unverified', review_note text not null default '', reviewed_by uuid references auth.users(id),
  check(status <> 'approved' or (reviewed_revision is not null and reviewed_revision = revision)), unique(variant_id,id)
);
create table public.claim_sources (claim_id text references public.claims(id) on delete cascade, source_id text references public.knowledge_sources(id), primary key(claim_id,source_id));
create function public.protect_claim_revision() returns trigger language plpgsql set search_path = '' as $$
begin
  if TG_OP = 'UPDATE' and (new.title,new.body,new.source_ids,new.software,new.variant_id) is distinct from (old.title,old.body,old.source_ids,old.software,old.variant_id) then
    new.revision := old.revision + 1; new.status := 'pending'; new.reviewed_revision := null; new.reviewed_by := null; new.review_note := '';
  end if;
  return new;
end $$;
create trigger reset_edited_claim before update on public.claims for each row execute function public.protect_claim_revision();
create table public.review_events (id bigint generated always as identity primary key, claim_id text not null references public.claims(id), revision integer not null, status text not null, note text not null, reviewer uuid not null references auth.users(id), created_at timestamptz not null default now());
create table public.rules (id text not null, variant_id text not null references public.machine_variants(id), shot_id text not null, instruction text not null, why text not null, value double precision not null check(value between 0 and 1), progression double precision not null check(progression between 0 and 1), primary key(variant_id,id), foreign key(variant_id,shot_id) references public.shots(variant_id,id));
create table public.rule_claims (variant_id text not null, rule_id text not null, claim_id text not null, primary key(variant_id,rule_id,claim_id), foreign key(variant_id,rule_id) references public.rules(variant_id,id), foreign key(variant_id,claim_id) references public.claims(variant_id,id));
create table public.rule_outcomes (id text not null, variant_id text not null, rule_id text not null, primary key(variant_id,id), foreign key(variant_id,rule_id) references public.rules(variant_id,id));
create table public.rule_prerequisites (variant_id text not null, rule_id text not null, outcome_id text not null, primary key(variant_id,rule_id,outcome_id), foreign key(variant_id,rule_id) references public.rules(variant_id,id), foreign key(variant_id,outcome_id) references public.rule_outcomes(variant_id,id));
create table public.strategies (id text not null, variant_id text not null references public.machine_variants(id), title text not null, objective text not null check(objective in ('scoring','multiball')), level text not null check(level in ('simple','advanced')), primary key(variant_id,id));
create table public.strategy_steps (variant_id text not null, strategy_id text not null, position integer not null check(position>=0), rule_id text not null, primary key(variant_id,strategy_id,position), foreign key(variant_id,strategy_id) references public.strategies(variant_id,id), foreign key(variant_id,rule_id) references public.rules(variant_id,id));
create table public.pack_drafts (variant_id text primary key references public.machine_variants(id), revision integer not null default 1, payload jsonb not null, updated_at timestamptz not null default now());
create table public.machine_pack_versions (variant_id text not null references public.machine_variants(id), version integer not null check(version>0), payload jsonb not null, sha256 text not null, published_by uuid not null references auth.users(id), published_at timestamptz not null default now(), primary key(variant_id,version));
create table public.research_jobs (id uuid primary key default gen_random_uuid(), variant_id text not null references public.machine_variants(id), query text not null check(length(query)<=2000), state text not null default 'queued' check(state in ('queued','running','review','failed')), requested_by uuid not null references auth.users(id), created_at timestamptz not null default now(), error text);

do $$ declare t text; begin
  foreach t in array array['machines','machine_variants','playfields','shots','knowledge_sources','claims','claim_sources','rules','rule_claims','rule_outcomes','rule_prerequisites','strategies','strategy_steps','pack_drafts','research_jobs'] loop
    execute format('alter table public.%I enable row level security',t);
    execute format('create policy editors on public.%I for all to authenticated using (public.is_editor()) with check (public.is_editor())',t);
  end loop;
end $$;
-- Claims and publication are only mutated through server review/publish transactions.
revoke insert, update, delete on public.claims from authenticated;
alter table public.review_events enable row level security;
create policy editor_review_history on public.review_events for select to authenticated using(public.is_editor());
alter table public.machine_pack_versions enable row level security;
create policy published_packs on public.machine_pack_versions for select to authenticated using(public.is_tester());

create table public.player_games (id uuid primary key, owner_id uuid not null references auth.users(id) on delete cascade, variant_id text not null, started_at timestamptz not null, updated_at timestamptz not null default now(), score bigint check(score>=0), ended boolean not null default false, unique(id,owner_id));
create table public.player_activity (id uuid primary key, owner_id uuid not null references auth.users(id) on delete cascade, game_id uuid not null, timestamp timestamptz not null, kind text not null, text text not null, foreign key(game_id,owner_id) references public.player_games(id,owner_id) on delete cascade);
create table public.player_preferences (owner_id uuid primary key references auth.users(id) on delete cascade, favourites text[] not null default '{}', learning jsonb not null default '{}', settings jsonb not null default '{}', updated_at timestamptz not null default now());
create table public.player_photos (id uuid primary key default gen_random_uuid(), owner_id uuid not null references auth.users(id) on delete cascade, game_id uuid, storage_path text not null, created_at timestamptz not null default now(), foreign key(game_id,owner_id) references public.player_games(id,owner_id));
do $$ declare t text; begin
  foreach t in array array['player_games','player_activity','player_preferences','player_photos'] loop
    execute format('alter table public.%I enable row level security',t);
    execute format('create policy only_owner on public.%I for all to authenticated using (owner_id=auth.uid() and public.is_tester()) with check (owner_id=auth.uid() and public.is_tester())',t);
  end loop;
end $$;

create table public.budget_config (id boolean primary key default true check(id), limit_micro_aud bigint not null default 60000000 check(limit_micro_aud>0), infrastructure_micro_aud bigint not null default 15000000 check(infrastructure_micro_aud>=0), online_enabled boolean not null default false);
insert into public.budget_config(id) values(true);
create table public.usage_reservations (request_id uuid primary key, period text not null, kind text not null, reserved_micro_aud bigint not null check(reserved_micro_aud>0), actual_micro_aud bigint check(actual_micro_aud>=0), state text not null default 'reserved' check(state in ('reserved','settled')), created_at timestamptz not null default now());
alter table public.budget_config enable row level security;
create policy read_budget on public.budget_config for select to authenticated using(public.is_editor());
create policy owner_budget on public.budget_config for update to authenticated using(public.is_owner()) with check(public.is_owner());
alter table public.usage_reservations enable row level security;
create policy cost_only on public.usage_reservations for select to authenticated using(public.is_owner());
create function public.reserve_budget(p_request_id uuid, p_amount bigint, p_kind text) returns boolean language plpgsql security definer set search_path = '' as $$
declare cfg public.budget_config; spent bigint; period_name text;
begin
  if p_amount<=0 then raise exception 'Invalid cost bound'; end if;
  select * into cfg from public.budget_config where id=true for update;
  if not cfg.online_enabled then return false; end if;
  -- Reject duplicate requests; never launch a second paid call for the same reservation.
  if exists(select 1 from public.usage_reservations where request_id=p_request_id) then return false; end if;
  period_name := to_char(now() at time zone 'Australia/Hobart','YYYY-MM');
  select coalesce(sum(coalesce(actual_micro_aud,reserved_micro_aud)),0) into spent from public.usage_reservations where period=period_name;
  if spent + cfg.infrastructure_micro_aud + p_amount > cfg.limit_micro_aud then return false; end if;
  insert into public.usage_reservations(request_id,period,kind,reserved_micro_aud) values(p_request_id,period_name,p_kind,p_amount);
  return true;
end $$;
revoke all on function public.reserve_budget(uuid,bigint,text) from public,anon,authenticated;
grant execute on function public.reserve_budget(uuid,bigint,text) to service_role;

-- Only the server may record a review, atomically with its audit event.
create function public.review_claim(p_id text,p_revision integer,p_status text,p_note text,p_reviewer uuid) returns void language plpgsql security definer set search_path='' as $$
declare c public.claims;
begin
  if p_status not in ('approved','rejected') or not exists(select 1 from public.memberships where user_id=p_reviewer and role in ('editor','owner')) then raise exception 'Not authorised'; end if;
  select * into c from public.claims where id=p_id for update;
  if c.id is null or c.revision<>p_revision then raise exception 'Claim changed; reload before reviewing'; end if;
  if exists(select 1 from unnest(c.source_ids) s where not exists(select 1 from public.knowledge_sources where id=s)) then raise exception 'Missing evidence'; end if;
  update public.claims set status=p_status,reviewed_revision=case when p_status='approved' then revision else null end,review_note=p_note,reviewed_by=p_reviewer where id=p_id;
  insert into public.review_events(claim_id,revision,status,note,reviewer) values(p_id,p_revision,p_status,p_note,p_reviewer);
end $$;
revoke all on function public.review_claim(text,integer,text,text,uuid) from public,anon,authenticated;
grant execute on function public.review_claim(text,integer,text,text,uuid) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('player-photos','player-photos',false,5242880,array['image/jpeg','image/png','image/webp']);
create policy private_photo_read on storage.objects for select to authenticated using(bucket_id='player-photos' and (storage.foldername(name))[1]=auth.uid()::text and public.is_tester());
create policy private_photo_write on storage.objects for insert to authenticated with check(bucket_id='player-photos' and (storage.foldername(name))[1]=auth.uid()::text and public.is_tester());
create policy private_photo_delete on storage.objects for delete to authenticated using(bucket_id='player-photos' and (storage.foldername(name))[1]=auth.uid()::text and public.is_tester());
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('playfields','playfields',false,20000000,array['image/jpeg','image/png','image/webp']);
create policy editor_playfield_write on storage.objects for all to authenticated using(bucket_id='playfields' and public.is_editor()) with check(bucket_id='playfields' and public.is_editor());

-- Supabase default privileges vary across versions: make intentional grants explicit.
grant select on public.memberships,public.machine_pack_versions,public.review_events,public.usage_reservations to authenticated;
grant select,insert,update,delete on public.machines,public.machine_variants,public.playfields,public.shots,public.knowledge_sources,public.claim_sources,public.rules,public.rule_claims,public.rule_outcomes,public.rule_prerequisites,public.strategies,public.strategy_steps,public.pack_drafts,public.research_jobs,public.player_games,public.player_activity,public.player_preferences,public.player_photos to authenticated;
grant select on public.claims to authenticated;
grant select,update on public.budget_config to authenticated;
grant all on all tables in schema public to service_role;
grant usage,select on all sequences in schema public to service_role;
