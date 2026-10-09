create function public.invalidate_source_claims() returns trigger language plpgsql security definer set search_path='' as $$
begin
  if (new.url,new.title,new.locator,new.kind) is distinct from (old.url,old.title,old.locator,old.kind) then
    update public.claims set revision=revision+1,status='pending',reviewed_revision=null,reviewed_by=null,review_note='' where old.id=any(source_ids);
  end if;
  return new;
end $$;
create trigger source_changed after update on public.knowledge_sources for each row execute function public.invalidate_source_claims();
