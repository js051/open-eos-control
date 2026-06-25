export interface CameraInfo {
  connected: boolean;
  model: string;
  serial: string;
  api: string;
}

export interface ExposureState {
  iso: string;
  shutter: string;
  aperture: string;
  white_balance: string;
}

export interface CameraStatus {
  connected: boolean;
  battery: {
    level: number;
    status: string;
  };
  recording: boolean;
  mode: string;
  media: {
    available: boolean;
    remaining_minutes: number;
  };
  exposure: ExposureState;
}

export interface CameraCapabilities {
  iso: string[];
  shutter: string[];
  aperture: string[];
  white_balance: string[];
}

export interface ApiError {
  code: string;
  message: string;
  recoverable: boolean;
}
