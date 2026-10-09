-- Existing production-shaped data before installing knowledge history.
insert into public.machines values('upgrade-fixture','Upgrade fixture','Original',2026);
insert into public.machine_variants(id,machine_id,edition) values('upgrade-fixture-pro','upgrade-fixture','Pro');
insert into public.knowledge_sources(id,title,url,kind,locator,accessed_at,notes)
values('upgrade-source','Existing source','https://example.com/original','original','Section one','2026-10-09','Existing metadata');
insert into public.claims(id,variant_id,kind,title,body,source_ids,status,revision,reviewed_revision)
values('upgrade-claim','upgrade-fixture-pro','fact','Existing approved fact','Previously reviewed wording',array['upgrade-source'],'approved',4,4);
