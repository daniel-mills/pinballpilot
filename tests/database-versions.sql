-- Runs after the existing security and connector tests in the disposable database.
do $$ begin
  assert (select snapshot->>'body' from public.claim_revisions where claim_id='upgrade-claim' and revision=4)='Previously reviewed wording','Existing claim was not backfilled';
  assert (select count(*) from public.claim_revisions where claim_id='upgrade-claim')=1,'Migration invented unavailable old revisions';
  assert (select status='approved' and revision=4 from public.claims where id='upgrade-claim'),'Migration changed existing approval';
end $$;
do $$ declare original text; before_revision integer; rejected boolean:=false; begin
  select snapshot->>'body' into original from public.claim_revisions where claim_id='fact' and revision=1;
  assert original='Original wording','Original claim text was lost';
  assert (select snapshot->>'url' from public.source_revisions where source_id='source' and revision=1)='https://example.com/one','Original source URL was lost';
  assert (select source_revision from public.claim_revision_evidence where claim_id='fact' and claim_revision=1)=1,'Citation was retargeted';
  select revision into before_revision from public.claims where id='fact';
  update public.claims set kind='recommendation' where id='fact';
  assert (select revision=before_revision+1 and status='pending' from public.claims where id='fact'),'Changing claim kind retained approval';
  begin update public.claim_revisions set snapshot='{}' where claim_id='fact'; exception when others then rejected:=true; end;
  assert rejected,'Claim history was mutable';
  rejected:=false;
  begin delete from public.source_revisions where source_id='source'; exception when others then rejected:=true; end;
  assert rejected,'Source history could be removed';
  select revision into before_revision from public.claims where id='fact';
  update public.knowledge_sources set notes='New software limitation' where id='source';
  assert (select revision=before_revision+1 from public.claims where id='fact'),'Evidence notes did not invalidate approval';
end $$;
insert into public.machine_variants(id,machine_id,edition) values('test-premium','test','Premium');
insert into public.software_releases(id,variant_id,version) values('release-test','test-pro','01.75'),('release-other','test-premium','1.00');
update public.claims set applicability='{"status":"releases","releaseIds":["release-test"],"settings":"Factory defaults"}' where id='fact';
do $$ declare rejected boolean:=false; begin
  assert exists(select 1 from public.claim_revision_releases where claim_id='fact' and release_id='release-test'),'Release applicability was not captured';
  begin update public.claims set applicability='{"status":"releases","releaseIds":["release-other"],"settings":""}' where id='fact'; exception when foreign_key_violation then rejected:=true; end;
  assert rejected,'Cross-edition software was accepted';
end $$;
set role authenticated;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000001';
do $$ begin
  assert (select count(*) from public.claim_revisions)=0,'Player could read editorial history';
end $$;
select public.sync_game('{"id":"10000000-0000-4000-8000-000000000004","variantId":"workshop-demo","packVersion":1,"startedAt":1000,"updatedAt":2000,"score":null,"ended":false,"completed":[],"state":{"locks":{"value":2,"origin":"player","observedAt":2000}}}');
-- Old clients cannot erase typed state by omitting the new field.
select public.sync_game('{"id":"10000000-0000-4000-8000-000000000004","variantId":"workshop-demo","startedAt":1000,"updatedAt":3000,"score":null,"ended":false,"completed":[]}');
do $$ declare rejected boolean:=false; begin
  assert (select state->'locks'->>'value' from public.player_games where id='10000000-0000-4000-8000-000000000004')='2','Legacy sync erased state';
  assert (select pack_version from public.player_games where id='10000000-0000-4000-8000-000000000004')=1,'Legacy sync erased pack pin';
  begin perform public.sync_game('{"id":"10000000-0000-4000-8000-000000000004","variantId":"workshop-demo","packVersion":2,"startedAt":1000,"updatedAt":4000,"score":null,"ended":false,"completed":[]}'); exception when others then rejected:=true; end;
  assert rejected,'A game changed its pinned pack';
end $$;
set request.jwt.claim.sub='00000000-0000-4000-8000-000000000002';
do $$ begin assert not exists(select 1 from public.player_games where id='10000000-0000-4000-8000-000000000004'),'Other player can read state'; end $$;
reset role;
-- A version 2 publish pins the reviewed applicability, source revision and behaviour.
select public.review_claim('fact',(select revision from public.claims where id='fact'),'approved','Verify versioned publication','00000000-0000-4000-8000-000000000003');
update public.pack_drafts set payload=(
  select jsonb_build_object('schemaVersion',2,'version',2,'releases',jsonb_build_array(jsonb_build_object('id','release-test','variantId','test-pro','version','01.75','releasedAt',null)),'sources',jsonb_build_array(jsonb_build_object(
    'id',s.id,'revision',s.revision,'contentHash',s.content_hash,'title',s.title,'url',s.url,'kind',s.kind,'locator',s.locator,'accessedAt',s.accessed_at,'notes',s.notes)),
    'claims',jsonb_build_array(jsonb_build_object('id',c.id,'revision',c.revision,'body',c.body,'title',c.title,'kind',c.kind,'sourceIds',to_jsonb(c.source_ids),'applicability',c.applicability,'ruleSpec',c.rule_spec,
    'citations',(select jsonb_agg(jsonb_build_object('sourceId',source_id,'sourceRevision',source_revision,'locator',locator,'relation',relation)) from public.claim_revision_evidence where claim_id=c.id and claim_revision=c.revision))))
  from public.claims c join public.knowledge_sources s on s.id=any(c.source_ids) where c.id='fact'
) where variant_id='test-pro';
do $$ declare expected integer; rejected boolean:=false; original jsonb; begin
  select payload into original from public.pack_drafts where variant_id='test-pro';
  update public.pack_drafts set payload=jsonb_set(payload,'{claims,0,citations,0,sourceRevision}','999') where variant_id='test-pro';
  select revision into expected from public.pack_drafts where variant_id='test-pro';
  begin perform public.publish_pack('test-pro',2,expected,'test','00000000-0000-4000-8000-000000000003'); exception when others then rejected:=true; end;
  assert rejected,'Forged evidence revision was published';
  update public.pack_drafts set payload=jsonb_set(original,'{releases,0,version}','"different firmware"') where variant_id='test-pro';
  select revision into expected from public.pack_drafts where variant_id='test-pro';
  rejected:=false;
  begin perform public.publish_pack('test-pro',2,expected,'test','00000000-0000-4000-8000-000000000003'); exception when others then rejected:=true; end;
  assert rejected,'Forged release label was published';
  update public.pack_drafts set payload=original where variant_id='test-pro';
  select revision into expected from public.pack_drafts where variant_id='test-pro';
  perform public.publish_pack('test-pro',2,expected,'test','00000000-0000-4000-8000-000000000003');
  assert (select payload->'claims'->0->'citations'->0->>'sourceRevision' from public.machine_pack_versions where variant_id='test-pro' and version=2)='3','Published source pin changed';
end $$;
select 'PASS: immutable knowledge, precise citations, software applicability, pinned sessions and private state' as result;
