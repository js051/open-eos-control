# Open EOS Control

Open EOS Control is an unofficial, open-source Canon EOS R6 Mark III-first control web app inspired by monitor/control tools such as ZineControl and Monitor+.

The MVP focuses on Wi-Fi control through Canon CCAPI-style HTTP endpoints, with a fake camera path that lets the app run without a physical camera. USB EDSDK, HDMI/UVC capture, LUTs, waveform, false color, zebra, and focus peaking are planned later phases.

## Stack

- Backend: Python 3.12+, FastAPI, httpx, Pydantic, pytest, ruff
- Frontend: Vue 3, Vite, TypeScript, Tailwind CSS, Pinia
- Simulator: FastAPI fake camera server
- Local orchestration: Docker Compose

## Layout

```text
open-eos-control/
  backend/       FastAPI app that exposes UI-friendly camera APIs
  frontend/      Vue 3 control surface
  simulator/     Fake Canon camera server for local development
  docker-compose.yml
```

## Quick Start

```bash
docker compose up --build
```

Services:

- Frontend: http://localhost:5173
- Backend API: http://localhost:8000
- Simulator: http://localhost:18080

Use `http://simulator:18080` as the camera base URL when running through Docker Compose. Use `http://localhost:18080` when running the backend directly on the host.

## Backend API

- `GET /api/health`
- `POST /api/camera/connect`
- `GET /api/camera/status`
- `GET /api/camera/capabilities`
- `PATCH /api/camera/exposure`
- `PATCH /api/camera/white-balance`
- `POST /api/camera/record/start`
- `POST /api/camera/record/stop`
- `POST /api/camera/focus/tap`
- `GET /api/liveview/frame`

## Development

Backend:

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8000
```

Simulator:

```bash
cd simulator
python -m venv .venv
.venv\Scripts\activate
pip install -e ".[dev]"
uvicorn main:app --reload --port 18080
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

## Camera Notes

`CcapiCameraClient` is intentionally a thin placeholder until the Canon CCAPI reference endpoints are verified against the target camera firmware. The UI and backend are written against the `CameraClient` interface so the fake client can be swapped for the real adapter without rewriting the app surface.

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
