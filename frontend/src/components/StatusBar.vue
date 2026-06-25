<script setup lang="ts">
import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();
</script>

<template>
  <section class="status">
    <span>{{ camera.status?.connected ? "Connected" : "Disconnected" }}</span>
    <span>Battery {{ camera.status?.battery.level ?? 0 }}%</span>
    <span>{{ camera.status?.media.available ? "Card OK" : "No card" }}</span>
    <span>{{ camera.status?.mode ?? "movie" }}</span>
  </section>
</template>

<style scoped>
.status {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
}

span {
  min-height: 44px;
  display: grid;
  place-items: center;
  padding: 8px;
  color: #dbeafe;
  background: rgba(16, 19, 26, 0.88);
  font-size: 0.88rem;
  text-align: center;
}

@media (max-width: 640px) {
  .status {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
