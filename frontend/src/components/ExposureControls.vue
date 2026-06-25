<script setup lang="ts">
import { useCameraStore } from "../stores/cameraStore";

const camera = useCameraStore();
</script>

<template>
  <section class="panel">
    <label>
      <span>ISO</span>
      <select
        :value="camera.status?.exposure.iso"
        :disabled="!camera.capabilities"
        @change="camera.setExposure('iso', ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="value in camera.capabilities?.iso ?? []" :key="value" :value="value">
          {{ value }}
        </option>
      </select>
    </label>

    <label>
      <span>Shutter</span>
      <select
        :value="camera.status?.exposure.shutter"
        :disabled="!camera.capabilities"
        @change="camera.setExposure('shutter', ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="value in camera.capabilities?.shutter ?? []" :key="value" :value="value">
          {{ value }}
        </option>
      </select>
    </label>

    <label>
      <span>Aperture</span>
      <select
        :value="camera.status?.exposure.aperture"
        :disabled="!camera.capabilities"
        @change="camera.setExposure('aperture', ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="value in camera.capabilities?.aperture ?? []" :key="value" :value="value">
          F{{ value }}
        </option>
      </select>
    </label>

    <label>
      <span>White balance</span>
      <select
        :value="camera.status?.exposure.white_balance"
        :disabled="!camera.capabilities"
        @change="camera.setWhiteBalance(($event.target as HTMLSelectElement).value)"
      >
        <option v-for="value in camera.capabilities?.white_balance ?? []" :key="value" :value="value">
          {{ value }}
        </option>
      </select>
    </label>
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

select {
  width: 100%;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  padding: 10px 11px;
  color: #f8fafc;
  background: #111827;
}
</style>
