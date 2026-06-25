import { defineStore } from "pinia";

import { cameraApi } from "../api/cameraApi";
import type { CameraCapabilities, CameraInfo, CameraStatus } from "../types/camera";

interface CameraState {
  baseUrl: string;
  useFake: boolean;
  info: CameraInfo | null;
  status: CameraStatus | null;
  capabilities: CameraCapabilities | null;
  frameUrl: string;
  error: string;
  loading: boolean;
}

export const useCameraStore = defineStore("camera", {
  state: (): CameraState => ({
    baseUrl: "http://localhost:18080",
    useFake: true,
    info: null,
    status: null,
    capabilities: null,
    frameUrl: cameraApi.liveviewFrameUrl(),
    error: "",
    loading: false
  }),
  actions: {
    async run(action: () => Promise<void>) {
      this.loading = true;
      this.error = "";
      try {
        await action();
      } catch (error) {
        this.error = error instanceof Error ? error.message : "Unknown camera error";
      } finally {
        this.loading = false;
      }
    },
    async connect() {
      await this.run(async () => {
        this.info = await cameraApi.connect(this.baseUrl, this.useFake);
        this.status = await cameraApi.status();
        this.capabilities = await cameraApi.capabilities();
        this.refreshFrame();
      });
    },
    async refreshStatus() {
      await this.run(async () => {
        this.status = await cameraApi.status();
      });
    },
    async setExposure(key: "iso" | "shutter" | "aperture", value: string) {
      await this.run(async () => {
        this.status = await cameraApi.setExposure({ [key]: value });
        this.refreshFrame();
      });
    },
    async setWhiteBalance(value: string) {
      await this.run(async () => {
        this.status = await cameraApi.setWhiteBalance(value);
        this.refreshFrame();
      });
    },
    async toggleRecording() {
      await this.run(async () => {
        if (this.status?.recording) {
          await cameraApi.stopRecording();
        } else {
          await cameraApi.startRecording();
        }
        this.status = await cameraApi.status();
        this.refreshFrame();
      });
    },
    async tapFocus(x: number, y: number) {
      await this.run(async () => {
        await cameraApi.tapFocus(x, y);
        this.refreshFrame();
      });
    },
    refreshFrame() {
      this.frameUrl = cameraApi.liveviewFrameUrl();
    },
    clearError() {
      this.error = "";
    }
  }
});
