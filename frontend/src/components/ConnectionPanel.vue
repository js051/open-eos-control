<script setup lang="ts">
import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();
</script>

<template>
  <section class="panel">
    <label>
      <span>Camera URL</span>
      <input v-model="camera.baseUrl" type="url" />
    </label>

    <label class="toggle">
      <input v-model="camera.useFake" type="checkbox" />
      <span>Fake camera</span>
    </label>

    <button class="primary" :disabled="camera.loading" @click="camera.connect">
      {{ camera.loading ? "Connecting" : "Connect" }}
    </button>

    <div v-if="camera.info" class="connection-state">
      <strong>{{ camera.info.model }}</strong>
      <span>{{ camera.info.api }} / {{ camera.info.serial }}</span>
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.055);
}

label {
  display: grid;
  gap: 6px;
  color: #cdd6e5;
  font-size: 0.86rem;
}

input[type="url"] {
  width: 100%;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  padding: 10px 11px;
  color: #f8fafc;
  background: rgba(15, 18, 24, 0.9);
}

.toggle {
  grid-template-columns: auto 1fr;
  align-items: center;
}

.primary {
  border: 0;
  border-radius: 6px;
  padding: 11px 12px;
  color: #111318;
  background: #facc15;
  font-weight: 700;
}

.primary:disabled {
  cursor: default;
  opacity: 0.65;
}

.connection-state {
  display: grid;
  gap: 4px;
  color: #e2e8f0;
  font-size: 0.9rem;
}

.connection-state span {
  color: #94a3b8;
}
</style>
