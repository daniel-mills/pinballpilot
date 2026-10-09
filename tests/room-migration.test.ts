import { DatabaseSync } from 'node:sqlite'
import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

it('Room 1→2 preserves saved data and matches the exported version 2 schema',()=>{
  const schema=(version:number)=>JSON.parse(readFileSync(`android/app/schemas/app.pinballpilot.data.PilotDatabase/${version}.json`,'utf8')).database
  const old=new DatabaseSync(':memory:'),expected=new DatabaseSync(':memory:')
  const create=(db:DatabaseSync,s:any)=>{for(const entity of s.entities) { db.exec(entity.createSql.replaceAll('${TABLE_NAME}',entity.tableName));for(const index of entity.indices??[]) db.exec(index.createSql.replaceAll('${TABLE_NAME}',entity.tableName)) }}
  try {
    create(old,schema(1));create(expected,schema(2))
    old.exec(`INSERT INTO packs VALUES ('demo',7,'{"version":7}');
      INSERT INTO games VALUES ('game','demo',1000,2000,'["lock-one"]',0,123);
      INSERT INTO events VALUES ('event','game',2000,'progress','First lock',0);
      INSERT INTO preferences VALUES ('autoNarrate','false');`)
    const kotlin=readFileSync('android/app/src/main/java/app/pinballpilot/data/PilotDatabase.kt','utf8')
    const statements=[...kotlin.matchAll(/db\.execSQL\("([^"\r\n]+)"\)/g)].map(m=>m[1]!)
    expect(statements.length).toBe(5)
    old.exec('BEGIN')
    for(const statement of statements) old.exec(statement)
    old.exec('COMMIT')
    for(const entity of schema(2).entities) {
      expect(old.prepare(`PRAGMA table_info(${entity.tableName})`).all()).toEqual(expected.prepare(`PRAGMA table_info(${entity.tableName})`).all())
      expect(old.prepare(`PRAGMA foreign_key_list(${entity.tableName})`).all()).toEqual(expected.prepare(`PRAGMA foreign_key_list(${entity.tableName})`).all())
    }
    expect(old.prepare('SELECT completed,score,packVersion,state FROM games').get()).toMatchObject({completed:'["lock-one"]',score:123,packVersion:null,state:'{}'})
    expect(old.prepare('SELECT version,json FROM pack_archive').get()).toMatchObject({version:7,json:'{"version":7}'})
    expect(old.prepare('SELECT text,payload,synced FROM events').get()).toMatchObject({text:'First lock',payload:'{}',synced:0})
    expect(old.prepare('SELECT value FROM preferences').get()).toMatchObject({value:'false'})
  } finally {old.close();expected.close()}
})
