insert into auth.users(id) values('00000000-0000-4000-8000-000000000001'),('00000000-0000-4000-8000-000000000002'),('00000000-0000-4000-8000-000000000003');
insert into public.memberships values('00000000-0000-4000-8000-000000000001','tester'),('00000000-0000-4000-8000-000000000002','tester'),('00000000-0000-4000-8000-000000000003','editor');
set role authenticated;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000001';
insert into public.player_games(id,owner_id,variant_id,started_at) values('10000000-0000-4000-8000-000000000001',auth.uid(),'workshop-demo',now());
insert into public.player_activity(id,owner_id,game_id,timestamp,kind,text) values('20000000-0000-4000-8000-000000000001',auth.uid(),'10000000-0000-4000-8000-000000000001',now(),'question','Private question');
insert into storage.objects(bucket_id,name) values('player-photos',auth.uid()::text||'/photo.jpg');
do $$ begin
  assert (select count(*) from public.player_activity)=1,'Owner cannot read own activity';
  assert (select count(*) from storage.objects)=1,'Owner cannot read own photo';
end $$;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000002';
do $$ begin
  assert (select count(*) from public.player_activity)=0,'Another tester can read private activity';
  assert (select count(*) from storage.objects)=0,'Another tester can read private photos';
  begin
    insert into public.player_games(id,owner_id,variant_id,started_at) values('10000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001','workshop-demo',now());
    raise exception 'Cross-owner insert was accepted';
  exception when insufficient_privilege then null;
  end;
end $$;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000003';
do $$ begin
  assert (select count(*) from public.player_activity)=0,'Editor can read private player conversations';
  assert (select count(*) from storage.objects)=0,'Editor can read private player photos';
  begin perform public.reserve_budget(gen_random_uuid(),1,'forbidden'); raise exception 'Editor could reserve money directly'; exception when insufficient_privilege then null; end;
end $$;
reset role;
update public.budget_config set online_enabled=true,limit_micro_aud=60000000,infrastructure_micro_aud=15000000;
do $$ begin
  assert public.reserve_budget('30000000-0000-4000-8000-000000000001',45000000,'test'),'Exact remaining budget rejected';
  assert not public.reserve_budget('30000000-0000-4000-8000-000000000001',45000000,'test'),'Duplicate request accepted';
  assert not public.reserve_budget('30000000-0000-4000-8000-000000000002',1,'test'),'Budget cap exceeded';
end $$;
update public.budget_config set online_enabled=false;
do $$ begin assert not public.reserve_budget(gen_random_uuid(),1,'test'),'Disabled provider accepted'; end $$;
insert into public.machines values('test','Test','Test',2026);
insert into public.machine_variants(id,machine_id,edition) values('test-pro','test','Pro');
insert into public.knowledge_sources(id,title,url,kind,accessed_at) values('source','Reference','https://example.com/one','original','2026-10-09');
insert into public.claims(id,variant_id,kind,title,body,source_ids) values('fact','test-pro','fact','Fact','Original wording',array['source']);
select public.review_claim('fact',1,'approved','Verified','00000000-0000-4000-8000-000000000003');
update public.claims set body='Changed wording' where id='fact';
do $$ begin assert (select status='pending' and revision=2 and reviewed_revision is null from public.claims where id='fact'),'Editing did not revoke approval'; end $$;
select public.review_claim('fact',2,'approved','Verified again','00000000-0000-4000-8000-000000000003');
update public.knowledge_sources set url='https://example.com/two' where id='source';
do $$ begin assert (select status='pending' and revision=3 from public.claims where id='fact'),'Changing evidence did not revoke approval'; end $$;
set role authenticated;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000001';
select public.sync_game('{"id":"10000000-0000-4000-8000-000000000003","variantId":"test-pro","startedAt":1000,"updatedAt":2000,"score":123,"ended":true,"completed":["goal"]}'::jsonb);
select public.sync_game('{"id":"10000000-0000-4000-8000-000000000003","variantId":"test-pro","startedAt":1000,"updatedAt":1500,"score":1,"ended":false,"completed":[]}'::jsonb);
do $$ begin assert (select score=123 from public.player_games where id='10000000-0000-4000-8000-000000000003'),'Stale device overwrote newer history'; end $$;
reset role;
select public.review_claim('fact',3,'approved','Publication test','00000000-0000-4000-8000-000000000003');
insert into public.pack_drafts(variant_id,payload) select 'test-pro',jsonb_build_object('version',1,'claims',jsonb_build_array(jsonb_build_object('id',id,'revision',revision,'body',body,'title',title,'kind',kind,'sourceIds',to_jsonb(source_ids)))) from public.claims where id='fact';
do $$ declare rejected boolean:=false; begin
  begin perform public.publish_pack('test-pro',1,999,'test','00000000-0000-4000-8000-000000000003'); exception when others then rejected:=true; end;
  assert rejected,'Stale draft revision accepted';
end $$;
select public.publish_pack('test-pro',1,1,'test','00000000-0000-4000-8000-000000000003');
do $$ declare rejected boolean:=false; begin
  begin update public.machine_pack_versions set payload='{}' where variant_id='test-pro'; exception when others then rejected:=true; end;
  assert rejected,'Published snapshot could be changed';
end $$;
update public.pack_drafts set payload=jsonb_set(payload,'{version}','2') where variant_id='test-pro';
update public.claims set body='Changed after validation' where id='fact';
do $$ declare rejected boolean:=false; begin
  begin perform public.publish_pack('test-pro',2,2,'test','00000000-0000-4000-8000-000000000003'); exception when others then rejected:=true; end;
  assert rejected,'Publication accepted evidence changed after validation';
end $$;
select 'PASS: migrations, owner privacy, evidence changes, publication races, immutable snapshots, private sync and budget checks' as result;
