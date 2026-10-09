import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir:'tests/browser',
  workers:1,
  use:{baseURL:'http://127.0.0.1:3000',headless:true,viewport:{width:1440,height:1000}},
  reporter:'list',
  outputDir:'artifacts/browser',
  webServer:[
    {command:'node admin/.output/server/index.mjs',url:'http://127.0.0.1:3000',reuseExistingServer:true,timeout:60000},
    {command:'node admin/.output/server/index.mjs',url:'http://127.0.0.1:3001',reuseExistingServer:false,timeout:60000,env:{HOST:'127.0.0.1',PORT:'3001',NUXT_PUBLIC_SUPABASE_URL:'http://127.0.0.1:54399',NUXT_PUBLIC_SUPABASE_ANON_KEY:'public-browser-test-key'}},
  ],
})
