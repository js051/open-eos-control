<script setup lang="ts">
import { computed } from "vue";

import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();
const recording = computed(() => camera.status?.recording ?? false);
</script>

<template>
  <button class="record" :class="{ active: recording }" :disabled="!camera.status" @click="camera.toggleRecording">
    <span class="dot"></span>
    {{ recording ? "Stop REC" : "Start REC" }}
  </button>
</template>

<style scoped>
.record {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-width: 128px;
  min-height: 44px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  color: #f8fafc;
  background: rgba(255, 255, 255, 0.08);
  font-weight: 700;
}

.record:disabled {
  cursor: default;
  opacity: 0.5;
}

.record.active {
  background: #be123c;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #f43f5e;
}

.active .dot {
  background: #fff1f2;
}
</style>
