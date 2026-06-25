<script setup lang="ts">
import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();

function focus(event: MouseEvent) {
  const target = event.currentTarget as HTMLElement;
  const rect = target.getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  camera.tapFocus(Number(x.toFixed(4)), Number(y.toFixed(4)));
}
</script>

<template>
  <section class="liveview" @click="focus">
    <img v-if="camera.status" :src="camera.frameUrl" alt="Live view frame" />
    <div v-else class="empty">
      <span>Connect fake camera</span>
    </div>
  </section>
</template>

<style scoped>
.liveview {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: #05070a;
}

img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.empty {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: #aeb7c8;
}
</style>
