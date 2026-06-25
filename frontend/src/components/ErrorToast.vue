<script setup lang="ts">
import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();
</script>

<template>
  <button v-if="camera.error" class="toast" @click="camera.clearError">
    {{ camera.error }}
  </button>
</template>

<style scoped>
.toast {
  position: fixed;
  right: 20px;
  bottom: 20px;
  max-width: min(420px, calc(100vw - 40px));
  border: 1px solid rgba(251, 113, 133, 0.45);
  border-radius: 8px;
  padding: 12px 14px;
  color: #ffe4e6;
  background: rgba(127, 29, 29, 0.94);
  text-align: left;
}
</style>
