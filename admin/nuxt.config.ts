export default defineNuxtConfig({
  compatibilityDate: '2025-12-01',
  modules: ['vuetify-nuxt-module'],
  css: ['@mdi/font/css/materialdesignicons.css', '~/assets/main.css'],
  runtimeConfig: { public: { supabaseUrl: '', supabaseAnonKey: '' } },
  devtools: { enabled: false },
  // Keep Nuxt's virtual renderer artifacts in the Windows production bundle.
  nitro: { externals: { inline: [/nuxt\/internal\//, /nuxt[\\/]dist[\\/]runtime[\\/]server/] } },
  app: { head: { title: 'Pinball Pilot · Workshop', meta: [{ name: 'description', content: 'Research, verify and publish machine-specific pinball guides.' }] } },
  vuetify: { moduleOptions: { prefixComposables: true }, vuetifyOptions: {
    icons: { defaultSet: 'mdi' },
    theme: { defaultTheme: 'workshop', themes: { workshop: { dark: true, colors: {
      background: '#151D2D', surface: '#202C41', primary: '#FFC46B', secondary: '#93C7DB',
      success: '#9CD2B0', warning: '#FFC46B', error: '#FF9C95', 'on-background': '#F4F0E8', 'on-surface': '#F4F0E8'
    } } } },
    defaults: { VBtn: { rounded: 'lg', style: 'text-transform:none;letter-spacing:0' }, VTextField: { variant: 'outlined', density: 'comfortable' }, VSelect: { variant: 'outlined', density: 'comfortable' }, VCard: { rounded: 'lg', elevation: 0 } }
  } }
})
