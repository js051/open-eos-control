<script setup lang="ts">
import ConnectionPanel from "./components/ConnectionPanel.vue";
import ErrorToast from "./components/ErrorToast.vue";
import ExposureControls from "./components/ExposureControls.vue";
import LiveViewPanel from "./components/LiveViewPanel.vue";
import RecordButton from "./components/RecordButton.vue";
import StatusBar from "./components/StatusBar.vue";
</script>

<template>
  <main class="app-shell">
    <section class="topbar">
      <div>
        <p class="eyebrow">Open EOS Control</p>
        <h1>R6 Mark III Control</h1>
      </div>
      <RecordButton />
    </section>

    <section class="workspace">
      <aside class="side-panel">
        <ConnectionPanel />
        <ExposureControls />
      </aside>
      <section class="monitor">
        <LiveViewPanel />
        <StatusBar />
      </section>
    </section>

    <ErrorToast />
  </main>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(64, 88, 118, 0.24), transparent 30rem),
    linear-gradient(135deg, #111318, #191d24 50%, #10131a);
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 auto 18px;
  max-width: 1280px;
}

.eyebrow {
  margin: 0 0 4px;
  color: #aeb7c8;
  font-size: 0.78rem;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: clamp(1.4rem, 2vw, 2.25rem);
  letter-spacing: 0;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  gap: 18px;
  max-width: 1280px;
  margin: 0 auto;
}

.side-panel,
.monitor {
  display: grid;
  gap: 14px;
  align-content: start;
}

@media (max-width: 840px) {
  .app-shell {
    padding: 16px;
  }

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .workspace {
    grid-template-columns: 1fr;
  }
}
</style>
