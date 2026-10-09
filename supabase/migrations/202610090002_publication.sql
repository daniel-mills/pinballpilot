create function public.bump_draft() returns trigger language plpgsql set search_path='' as $$
begin new.revision := old.revision+1; new.updated_at := now(); return new; end $$;
create trigger draft_revision before update on public.pack_drafts for each row execute function public.bump_draft();

-- Edge validates geometry, guide graph and licensing. This transaction closes approval races.
create function public.publish_pack(p_variant text,p_version integer,p_draft_revision integer,p_hash text,p_actor uuid) returns void language plpgsql security definer set search_path='' as $$
declare d public.pack_drafts; item jsonb; c public.claims;
begin
  if not exists(select 1 from public.memberships where user_id=p_actor and role in ('editor','owner')) then raise exception 'Not authorised'; end if;
  select * into d from public.pack_drafts where variant_id=p_variant for update;
  if d.variant_id is null or d.revision<>p_draft_revision or (d.payload->>'version')::integer<>p_version then raise exception 'Draft changed; validate again'; end if;
  for item in select value from jsonb_array_elements(d.payload->'claims') loop
    select * into c from public.claims where id=item->>'id' for share;
    if c.id is null or c.variant_id<>p_variant or c.status<>'approved' or c.reviewed_revision<>c.revision or c.revision<>(item->>'revision')::integer or c.body<>item->>'body' or c.title<>item->>'title' or c.kind<>item->>'kind' or to_jsonb(c.source_ids)<>item->'sourceIds' then raise exception 'Evidence is missing, changed or unreviewed'; end if;
  end loop;
  insert into public.machine_pack_versions(variant_id,version,payload,sha256,published_by) values(p_variant,p_version,d.payload,p_hash,p_actor);
end $$;
revoke all on function public.publish_pack(text,integer,integer,text,uuid) from public,anon,authenticated;
grant execute on function public.publish_pack(text,integer,integer,text,uuid) to service_role;
create function public.immutable_pack() returns trigger language plpgsql set search_path='' as $$
begin raise exception 'Published packs are immutable; publish a new version'; end $$;
create trigger immutable_pack before update or delete on public.machine_pack_versions for each row execute function public.immutable_pack();
