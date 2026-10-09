alter table public.player_games add column completed text[] not null default '{}';
create policy published_playfield_read on storage.objects for select to authenticated using (
  bucket_id='playfields' and public.is_tester() and exists(select 1 from public.machine_pack_versions v where v.payload->'image'->>'path'=name)
);
create function public.sync_game(p_game jsonb) returns void language plpgsql security invoker set search_path='' as $$
begin
  insert into public.player_games(id,owner_id,variant_id,started_at,updated_at,score,ended,completed)
  values((p_game->>'id')::uuid,auth.uid(),p_game->>'variantId',to_timestamp((p_game->>'startedAt')::numeric/1000),to_timestamp((p_game->>'updatedAt')::numeric/1000),(p_game->>'score')::bigint,(p_game->>'ended')::boolean,array(select jsonb_array_elements_text(p_game->'completed')))
  on conflict(id) do update set updated_at=excluded.updated_at,score=excluded.score,ended=excluded.ended,completed=excluded.completed
    where public.player_games.owner_id=auth.uid() and public.player_games.updated_at<excluded.updated_at;
end $$;
revoke all on function public.sync_game(jsonb) from public,anon;
grant execute on function public.sync_game(jsonb) to authenticated;
