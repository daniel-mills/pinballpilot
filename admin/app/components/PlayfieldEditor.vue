<script setup lang="ts">
import type { MachinePack, Shot } from '../../../shared/contracts'
const props = defineProps<{ pack: MachinePack; imageUrl:string }>()
const emit = defineEmits<{ change: [] }>()
const selected = ref(props.pack.shots[0]?.id ?? '')
const mode = ref<'select' | 'point' | 'polygon'>('select')
const zoom = ref(1)
const polygon = ref<{x:number;y:number}[]>([])
const shot = computed(() => props.pack.shots.find(s => s.id === selected.value))
const boardHeight=computed(()=>600*props.pack.image.height/props.pack.image.width)
function addShot() {const id=`shot-${crypto.randomUUID().slice(0,8)}`;props.pack.shots.push({id,name:'New shot',type:'target',geometry:{point:{x:.5,y:.5}},description:'Describe this shot and verify its position.',difficulty:.5,risk:.5});selected.value=id;emit('change')}
function mark(event: MouseEvent) {
  if (mode.value === 'select' || !shot.value) return
  const rect = (event.currentTarget as SVGSVGElement).getBoundingClientRect()
  const point = { x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)), y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)) }
  if (mode.value === 'polygon') polygon.value.push(point)
  else { shot.value.geometry.point = point; emit('change'); mode.value = 'select' }
}
function finishPolygon() {
  if (shot.value && polygon.value.length >= 3) { shot.value.geometry.polygon = [...polygon.value]; polygon.value = []; mode.value = 'select'; emit('change') }
}
function points(s: Shot) { return s.geometry.polygon?.map(p => `${p.x * 600},${p.y * boardHeight.value}`).join(' ') }
</script>
<template>
  <div class="editor-grid">
    <section>
      <div class="board-toolbar"><v-btn icon="mdi-minus" aria-label="Zoom out" variant="text" @click="zoom = Math.max(1, zoom - .25)"/><span>{{ Math.round(zoom * 100) }}%</span><v-btn icon="mdi-plus" aria-label="Zoom in" variant="text" @click="zoom = Math.min(3, zoom + .25)"/><v-btn variant="text" @click="zoom = 1">Fit playfield</v-btn></div>
      <div class="board-viewport">
        <svg :style="{ width: `${zoom * 100}%` }" :viewBox="`0 0 600 ${boardHeight}`" role="img" :aria-label="`${pack.name} playfield with editable shot markers`" @click="mark">
          <image :href="imageUrl" width="600" :height="boardHeight" preserveAspectRatio="none"/>
          <g v-for="s in pack.shots" :key="s.id">
            <polygon v-if="s.geometry.polygon" :points="points(s)" fill="#ffc46b33" stroke="#ffc46b" stroke-width="3"/>
            <g class="shot-marker" role="button" tabindex="0" :aria-label="s.name" :transform="`translate(${s.geometry.point.x*600},${s.geometry.point.y*boardHeight})`" @click.stop="selected=s.id" @keydown.enter="selected=s.id" @keydown.space.prevent="selected=s.id">
              <circle :r="selected===s.id ? 24 : 17" :fill="selected===s.id ? '#ffc46b' : '#202c41'" stroke="#f4f0e8" stroke-width="3"/>
              <circle r="4" :fill="selected===s.id ? '#202c41' : '#93c7db'"/>
            </g>
          </g>
          <polyline v-if="polygon.length" :points="polygon.map(p=>`${p.x*600},${p.y*boardHeight}`).join(' ')" fill="none" stroke="#ffc46b" stroke-width="4"/>
        </svg>
      </div>
    </section>
    <section class="annotation-panel">
      <h2>Shot geometry</h2><p class="muted mb-6">Select a marker, then place its centre or draw its touch area.</p>
      <v-btn class="mb-5" variant="tonal" color="secondary" @click="addShot">Add shot</v-btn>
      <v-select v-model="selected" :items="pack.shots" item-title="name" item-value="id" label="Shot"/>
      <template v-if="shot">
        <v-text-field v-model="shot.name" label="Shot name" @update:model-value="emit('change')"/>
        <v-select v-model="shot.type" label="Shot type" :items="['ramp','orbit','target','scoop','lock','lane','bumper']" @update:model-value="emit('change')"/>
        <v-textarea v-model="shot.description" label="Description" variant="outlined" rows="3" @update:model-value="emit('change')"/>
        <p class="mb-4">Position: {{ shot.geometry.point.x.toFixed(3) }}, {{ shot.geometry.point.y.toFixed(3) }}</p>
        <div class="d-flex ga-2 flex-wrap"><v-btn :variant="mode==='point'?'flat':'tonal'" color="primary" @click="mode='point'">Place centre</v-btn><v-btn variant="tonal" @click="mode='polygon';polygon=[]">Draw area</v-btn><v-btn v-if="mode==='polygon'" :disabled="polygon.length<3" @click="finishPolygon">Finish area</v-btn></div>
        <p v-if="mode!=='select'" class="mt-4" role="status">{{ mode==='point'?'Click the playfield to place the shot.':'Click at least three vertices, then finish the area.' }}</p>
      </template>
    </section>
  </div>
</template>
