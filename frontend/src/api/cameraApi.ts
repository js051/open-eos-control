import type { CameraCapabilities, CameraInfo, CameraStatus } from "../types/camera";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8000";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    ...init
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error?.message ?? `Request failed: ${response.status}`);
  }

  return response.json() as Promise<T>;
}

export const cameraApi = {
  connect(baseUrl: string, useFake: boolean): Promise<CameraInfo> {
    return request<CameraInfo>("/api/camera/connect", {
      method: "POST",
      body: JSON.stringify({ base_url: baseUrl, use_fake: useFake })
    });
  },
  status(): Promise<CameraStatus> {
    return request<CameraStatus>("/api/camera/status");
  },
  capabilities(): Promise<CameraCapabilities> {
    return request<CameraCapabilities>("/api/camera/capabilities");
  },
  setExposure(payload: Partial<Pick<CameraStatus["exposure"], "iso" | "shutter" | "aperture">>) {
    return request<CameraStatus>("/api/camera/exposure", {
      method: "PATCH",
      body: JSON.stringify(payload)
    });
  },
  setWhiteBalance(whiteBalance: string) {
    return request<CameraStatus>("/api/camera/white-balance", {
      method: "PATCH",
      body: JSON.stringify({ white_balance: whiteBalance })
    });
  },
  startRecording() {
    return request<{ ok: boolean; recording: boolean }>("/api/camera/record/start", { method: "POST" });
  },
  stopRecording() {
    return request<{ ok: boolean; recording: boolean }>("/api/camera/record/stop", { method: "POST" });
  },
  tapFocus(x: number, y: number) {
    return request<{ ok: boolean; x: number; y: number }>("/api/camera/focus/tap", {
      method: "POST",
      body: JSON.stringify({ x, y })
    });
  },
  liveviewFrameUrl(): string {
    return `${API_BASE_URL}/api/liveview/frame?t=${Date.now()}`;
  }
};
