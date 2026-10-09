alter table public.player_games add column pack_version integer check(pack_version>0);
alter table public.player_games add column state jsonb not null default '{}' check(jsonb_typeof(state)='object');
alter table public.player_activity add column payload jsonb not null default '{}' check(jsonb_typeof(payload)='object');

create or replace function public.sync_game(p_game jsonb) returns void language plpgsql security invoker set search_path='' as $$
declare existing public.player_games; requested_version integer:=(p_game->>'packVersion')::integer;
begin
  select * into existing from public.player_games where id=(p_game->>'id')::uuid for update;
  if existing.id is not null and (existing.variant_id<>p_game->>'variantId' or
     existing.pack_version is not null and requested_version is not null and existing.pack_version<>requested_version) then
    raise exception 'Game machine and pack version cannot change';
  end if;
  if requested_version is not null and not exists(select 1 from public.machine_pack_versions where variant_id=p_game->>'variantId' and version=requested_version)
     and not (p_game->>'variantId'='workshop-demo' and requested_version=1) then raise exception 'Unknown published pack'; end if;
  insert into public.player_games(id,owner_id,variant_id,started_at,updated_at,score,ended,completed,pack_version,state)
  values((p_game->>'id')::uuid,auth.uid(),p_game->>'variantId',to_timestamp((p_game->>'startedAt')::numeric/1000),to_timestamp((p_game->>'updatedAt')::numeric/1000),(p_game->>'score')::bigint,(p_game->>'ended')::boolean,array(select jsonb_array_elements_text(p_game->'completed')),requested_version,coalesce(p_game->'state','{}'))
  on conflict(id) do update set updated_at=excluded.updated_at,score=excluded.score,ended=excluded.ended,completed=excluded.completed,
    pack_version=coalesce(public.player_games.pack_version,excluded.pack_version),
    state=case when p_game ? 'state' then excluded.state else public.player_games.state end
    where public.player_games.owner_id=auth.uid() and public.player_games.updated_at<excluded.updated_at;
end $$;
-- Events form an append-only audit. Players may delete their own private history.
create function public.immutable_activity() returns trigger language plpgsql set search_path='' as $$
begin raise exception 'Append a correction event instead of editing activity'; end $$;
create trigger immutable_activity before update on public.player_activity for each row execute function public.immutable_activity();
