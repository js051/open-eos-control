import AVFoundation
import Foundation
import OpenEOSCore
import SwiftUI
import UIKit

struct LiveViewSurface: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var monitorAnalysis: LiveViewMonitorAnalysis?
    @State private var lutPreviewFrame: LiveViewLutPreviewFrame?
    @State private var lutPreviewWorker = LiveViewLutPreviewWorker()

    var body: some View {
        GeometryReader { proxy in
            let sourceImage = camera.liveViewData.flatMap(UIImage.init(data:))
            let image = activeLutPreviewFrame?.image ?? sourceImage
            let sourceSize = sourceImage?.size ?? (camera.activeLiveViewSource == .ccapiRTP ? camera.nativeLiveViewSize : nil)
            let contentSize = sourceSize.map {
                CGSize(
                    width: $0.width * camera.monitorSettings.desqueeze.horizontalScale,
                    height: $0.height
                )
            }
            let imageRect = contentSize.map { aspectFitRect(contentSize: $0, containerSize: proxy.size) }
                ?? CGRect(origin: .zero, size: proxy.size)
            let overlayImage = monitorAnalysis?.overlayImage()

            ZStack {
                Color.black

                if camera.activeLiveViewSource == .ccapiRTP {
                    IOSCcapiRTPVideoSurface(controller: camera.rtpController)
                        .frame(width: imageRect.width, height: imageRect.height)
                    if camera.lastFrameAt == nil { offlineSurface }
                } else if let image {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.medium)
                        .frame(width: imageRect.width, height: imageRect.height)
                } else {
                    offlineSurface
                }

                if let overlayImage {
                    Image(uiImage: overlayImage)
                        .resizable()
                        .interpolation(.none)
                        .frame(width: imageRect.width, height: imageRect.height)
                        .position(x: imageRect.midX, y: imageRect.midY)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                        .accessibilityIdentifier("monitor-pixel-overlay")
                }

                if camera.showGrid {
                    CompositionGrid()
                        .frame(width: imageRect.width, height: imageRect.height)
                        .position(x: imageRect.midX, y: imageRect.midY)
                        .allowsHitTesting(false)
                }

                if camera.monitorSettings.frameGuide != .off || camera.monitorSettings.safeAreaVisible {
                    MonitorGuides(
                        frameGuide: camera.monitorSettings.frameGuide,
                        safeAreaVisible: camera.monitorSettings.safeAreaVisible
                    )
                    .frame(width: imageRect.width, height: imageRect.height)
                    .position(x: imageRect.midX, y: imageRect.midY)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                    .accessibilityIdentifier("monitor-guides-overlay")
                }

                if camera.monitorSettings.histogramVisible, let monitorAnalysis {
                    let width = min(180, max(120, imageRect.width * 0.34))
                    HistogramView(histogram: monitorAnalysis.histogram)
                        .frame(width: width, height: 72)
                        .position(
                            x: imageRect.minX + width / 2 + 10,
                            y: imageRect.minY + 106
                        )
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                        .accessibilityIdentifier("live-view-histogram")
                }

                if camera.monitorSettings.waveformVisible, let waveform = monitorAnalysis?.waveform {
                    let width = min(180, max(120, imageRect.width * 0.34))
                    WaveformView(waveform: waveform)
                        .frame(width: width, height: 88)
                        .position(
                            x: imageRect.minX + width / 2 + 10,
                            y: imageRect.minY + 114
                        )
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                        .accessibilityIdentifier("live-view-waveform")
                }

                if let marker = camera.focusMarker {
                    FocusMarkerView(accepted: marker.accepted)
                        .position(
                            x: imageRect.minX + imageRect.width * marker.x,
                            y: imageRect.minY + imageRect.height * marker.y
                        )
                        .allowsHitTesting(false)
                }

                if camera.recording {
                    HStack(spacing: 7) {
                        Circle().fill(Color.cameraRecording).frame(width: 9, height: 9)
                        Text("recording").font(.caption.bold())
                    }
                    .foregroundStyle(Color.cameraText)
                    .padding(.horizontal, 10)
                    .frame(height: 30)
                    .background(Color.black.opacity(0.72))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .position(x: imageRect.midX, y: imageRect.minY + 26)
                }

                if camera.bulbExposureActive {
                    TimelineView(.periodic(from: .now, by: 0.25)) { context in
                        let elapsed = max(0, Int(context.date.timeIntervalSince(camera.bulbStartedAt ?? context.date)))
                        HStack(spacing: 7) {
                            Circle().fill(Color.cameraWarning).frame(width: 9, height: 9)
                            Text(String(format: NSLocalizedString("bulb_exposure_time", comment: ""), elapsed / 60, elapsed % 60))
                                .font(.caption.bold())
                        }
                        .foregroundStyle(Color.cameraText)
                        .padding(.horizontal, 10)
                        .frame(height: 30)
                        .background(Color.black.opacity(0.72))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                    .position(x: imageRect.midX, y: imageRect.minY + 26)
                }

                if camera.shutterFlash {
                    Color.white.ignoresSafeArea()
                }

                if camera.supports(.liveViewMagnification) {
                    let target: LiveViewMagnification = camera.liveViewMagnification == .x5 ? .x1 : .x5
                    Button {
                        Task { await camera.setLiveViewMagnification(target) }
                    } label: {
                        ZStack(alignment: .bottomTrailing) {
                            Image(systemName: target == .x5 ? "plus.magnifyingglass" : "minus.magnifyingglass")
                                .font(.system(size: 20, weight: .semibold))
                            Text("\(target.rawValue)\u{00D7}")
                                .font(.system(size: 10, weight: .bold, design: .rounded))
                                .offset(x: 4, y: 5)
                        }
                        .frame(width: 44, height: 44)
                        .foregroundStyle(Color.cameraAccent)
                        .background(Color.black.opacity(0.72))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                    .buttonStyle(.plain)
                    .disabled(camera.isBusy(.liveView))
                    .accessibilityLabel(
                        String(
                            format: NSLocalizedString("live_view_magnify_to", comment: ""),
                            target.rawValue
                        )
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(12)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                SpatialTapGesture().onEnded { value in
                    guard let action = camera.effectiveLiveViewTapAction,
                          imageRect.contains(value.location), imageRect.width > 0, imageRect.height > 0 else {
                        return
                    }
                    let x = (value.location.x - imageRect.minX) / imageRect.width
                    let y = (value.location.y - imageRect.minY) / imageRect.height
                    Task {
                        switch action {
                        case .focus: await camera.tapFocus(x: x, y: y)
                        case .whiteBalance: await camera.clickWhiteBalance(x: x, y: y)
                        }
                    }
                }
            )
        }
        .task(id: lutPreviewTaskID) {
            guard pixelMonitoringAvailable,
                  let data = camera.liveViewData,
                  let lut = camera.monitorSettings.cubeLut else {
                lutPreviewWorker.discardPending()
                lutPreviewFrame = nil
                return
            }
            let timestamp = camera.lastFrameAt
            let result = await lutPreviewWorker.renderLatest(data: data, lut: lut, frameTimestamp: timestamp)
            guard !Task.isCancelled else { return }
            switch result {
            case let .rendered(frame):
                guard frame.frameTimestamp == camera.lastFrameAt,
                      frame.lutID == camera.monitorSettings.cubeLut?.id else { return }
                lutPreviewFrame = frame
            case .failed:
                guard timestamp == camera.lastFrameAt,
                      lut.id == camera.monitorSettings.cubeLut?.id else { return }
                lutPreviewFrame = nil
                camera.reportCubeLutRenderFailure()
            case .superseded:
                break
            }
        }
        .task(id: monitorAnalysisTaskID) {
            guard pixelMonitoringAvailable,
                  camera.monitorSettings.needsPixelAnalysis,
                  let data = camera.liveViewData else {
                monitorAnalysis = nil
                return
            }
            let settings = camera.monitorSettings
            let preview = activeLutPreviewFrame
            let result = await Task.detached(priority: .utility) { () -> LiveViewMonitorAnalysis? in
                if let preview {
                    return analyzeLiveViewImage(preview.image, settings: settings)
                } else if settings.cubeLut == nil {
                    return analyzeLiveViewData(data, settings: settings)
                } else {
                    return nil
                }
            }.value
            guard !Task.isCancelled else { return }
            monitorAnalysis = result
        }
    }

    private var pixelMonitoringAvailable: Bool {
        !camera.isPreview
            && camera.activeLiveViewSource != nil
            && camera.activeLiveViewSource != .ccapiRTP
    }

    private var monitorAnalysisTaskID: LiveViewMonitorTaskID {
        LiveViewMonitorTaskID(
            frameTimestamp: camera.lastFrameAt,
            settings: camera.monitorSettings,
            pixelMonitoringAvailable: pixelMonitoringAvailable,
            lutPreviewID: activeLutPreviewFrame?.id
        )
    }

    private var lutPreviewTaskID: LiveViewLutTaskID {
        LiveViewLutTaskID(
            frameTimestamp: camera.lastFrameAt,
            lutID: camera.monitorSettings.cubeLut?.id,
            pixelMonitoringAvailable: pixelMonitoringAvailable
        )
    }

    private var activeLutPreviewFrame: LiveViewLutPreviewFrame? {
        guard let frame = lutPreviewFrame,
              frame.frameTimestamp == camera.lastFrameAt,
              frame.lutID == camera.monitorSettings.cubeLut?.id else { return nil }
        return frame
    }

    private var offlineSurface: some View {
        ZStack {
            Color(red: 0.025, green: 0.03, blue: 0.035)
            VStack(spacing: 12) {
                Image(systemName: camera.isPreview ? "camera.viewfinder" : "viewfinder")
                    .font(.system(size: 52, weight: .thin))
                    .foregroundStyle(camera.isPreview ? Color.cameraAccent : Color.cameraSecondaryText)
                Text(LocalizedStringKey(camera.isPreview ? "offline_preview" : "waiting_for_live_view"))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraSecondaryText)
            }
        }
    }
}

private struct IOSCcapiRTPVideoSurface: UIViewRepresentable {
    let controller: IOSCcapiRTPController

    func makeUIView(context: Context) -> IOSCcapiRTPVideoView {
        let view = IOSCcapiRTPVideoView()
        view.displayLayer.videoGravity = .resize
        controller.attach(view.displayLayer)
        return view
    }

    func updateUIView(_ uiView: IOSCcapiRTPVideoView, context: Context) {
        controller.attach(uiView.displayLayer)
    }

    static func dismantleUIView(_ uiView: IOSCcapiRTPVideoView, coordinator: Void) {
        // The controller is retained by CameraAppState; detaching is handled when another layer attaches.
        uiView.displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }
}

private struct LiveViewMonitorTaskID: Hashable {
    let frameTimestamp: Date?
    let settings: LiveViewMonitorSettings
    let pixelMonitoringAvailable: Bool
    let lutPreviewID: UUID?
}

private struct LiveViewLutTaskID: Hashable {
    let frameTimestamp: Date?
    let lutID: UUID?
    let pixelMonitoringAvailable: Bool
}

private struct LiveViewLutPreviewFrame: Identifiable, @unchecked Sendable {
    let id = UUID()
    let frameTimestamp: Date?
    let lutID: UUID
    let image: UIImage
}

private enum LiveViewLutWorkerResult: @unchecked Sendable {
    case rendered(LiveViewLutPreviewFrame)
    case failed
    case superseded
}

private final class LiveViewLutPreviewWorker: @unchecked Sendable {
    private struct Request: @unchecked Sendable {
        let data: Data
        let lut: CubeLut
        let frameTimestamp: Date?
        let continuation: CheckedContinuation<LiveViewLutWorkerResult, Never>
    }

    private let lock = NSLock()
    private let queue = DispatchQueue(label: "dev.openeos.control.live-view-lut", qos: .userInitiated)
    private var pending: Request?
    private var running = false

    func renderLatest(data: Data, lut: CubeLut, frameTimestamp: Date?) async -> LiveViewLutWorkerResult {
        await withCheckedContinuation { continuation in
            let request = Request(
                data: data,
                lut: lut,
                frameTimestamp: frameTimestamp,
                continuation: continuation
            )
            lock.lock()
            let superseded = pending
            pending = request
            let shouldStart = !running
            if shouldStart { running = true }
            lock.unlock()
            superseded?.continuation.resume(returning: .superseded)
            if shouldStart { queue.async { self.processRequests() } }
        }
    }

    func discardPending() {
        lock.lock()
        let discarded = pending
        pending = nil
        lock.unlock()
        discarded?.continuation.resume(returning: .superseded)
    }

    private func processRequests() {
        while true {
            lock.lock()
            guard let request = pending else {
                running = false
                lock.unlock()
                return
            }
            pending = nil
            lock.unlock()

            let image = autoreleasepool {
                renderCubeLutPreview(data: request.data, lut: request.lut)
            }
            if let image {
                request.continuation.resume(
                    returning: .rendered(
                        LiveViewLutPreviewFrame(
                            frameTimestamp: request.frameTimestamp,
                            lutID: request.lut.id,
                            image: image
                        )
                    )
                )
            } else {
                request.continuation.resume(returning: .failed)
            }
        }
    }
}

private final class IOSCcapiRTPVideoView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    var displayLayer: AVSampleBufferDisplayLayer {
        layer as! AVSampleBufferDisplayLayer
    }
}

private struct CompositionGrid: View {
    var body: some View {
        Canvas { context, size in
            var path = Path()
            for fraction in [1.0 / 3.0, 2.0 / 3.0] {
                path.move(to: CGPoint(x: size.width * fraction, y: 0))
                path.addLine(to: CGPoint(x: size.width * fraction, y: size.height))
                path.move(to: CGPoint(x: 0, y: size.height * fraction))
                path.addLine(to: CGPoint(x: size.width, y: size.height * fraction))
            }
            context.stroke(path, with: .color(.white.opacity(0.38)), lineWidth: 0.8)
        }
    }
}

private struct MonitorGuides: View {
    let frameGuide: LiveViewFrameGuide
    let safeAreaVisible: Bool

    var body: some View {
        Canvas { context, size in
            if let aspectRatio = frameGuide.aspectRatio {
                let rect = aspectFitRect(
                    contentSize: CGSize(width: aspectRatio, height: 1),
                    containerSize: size
                ).insetBy(dx: 1, dy: 1)
                context.stroke(
                    Path(roundedRect: rect, cornerRadius: 0),
                    with: .color(.white.opacity(0.85)),
                    style: StrokeStyle(lineWidth: 1.2, dash: [7, 5])
                )
            }
            if safeAreaVisible {
                let actionSafe = CGRect(origin: .zero, size: size).insetBy(
                    dx: size.width * 0.05,
                    dy: size.height * 0.05
                )
                let titleSafe = CGRect(origin: .zero, size: size).insetBy(
                    dx: size.width * 0.10,
                    dy: size.height * 0.10
                )
                context.stroke(
                    Path(roundedRect: actionSafe, cornerRadius: 0),
                    with: .color(Color.cameraAccent.opacity(0.72)),
                    style: StrokeStyle(lineWidth: 1, dash: [5, 4])
                )
                context.stroke(
                    Path(roundedRect: titleSafe, cornerRadius: 0),
                    with: .color(Color.cameraWarning.opacity(0.72)),
                    style: StrokeStyle(lineWidth: 1, dash: [3, 4])
                )
            }
        }
    }
}

private struct HistogramView: View {
    let histogram: [Int]

    var body: some View {
        Canvas { context, size in
            let peak = max(1, histogram.max() ?? 1)
            guard histogram.count > 1 else { return }
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height))
            for (index, count) in histogram.enumerated() {
                let x = size.width * CGFloat(index) / CGFloat(histogram.count - 1)
                let y = size.height * (1 - CGFloat(count) / CGFloat(peak))
                path.addLine(to: CGPoint(x: x, y: y))
            }
            path.addLine(to: CGPoint(x: size.width, y: size.height))
            path.closeSubpath()
            context.fill(path, with: .color(.white.opacity(0.36)))
            context.stroke(path, with: .color(.white.opacity(0.92)), lineWidth: 1)
        }
        .padding(7)
        .background(Color.black.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

private struct WaveformView: View {
    let waveform: LiveViewWaveform

    var body: some View {
        Canvas { context, size in
            for guide in 0...4 {
                let y = min(size.height - 1, size.height * CGFloat(guide) / 4)
                var path = Path()
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(path, with: .color(.white.opacity(0.16)), lineWidth: 0.5)
            }
            let peak = max(1, waveform.density.max() ?? 1)
            let cellWidth = size.width / CGFloat(waveform.width)
            let cellHeight = size.height / CGFloat(waveform.height)
            for (index, count) in waveform.density.enumerated() where count > 0 {
                let x = index % waveform.width
                let y = index / waveform.width
                let intensity = min(1, max(0.16, sqrt(Double(count) / Double(peak))))
                let rect = CGRect(
                    x: CGFloat(x) * cellWidth,
                    y: CGFloat(y) * cellHeight,
                    width: max(1, cellWidth),
                    height: max(1, cellHeight)
                )
                context.fill(Path(rect), with: .color(Color.cameraAccent.opacity(intensity)))
            }
        }
        .padding(7)
        .background(Color.black.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

private struct FocusMarkerView: View {
    let accepted: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .stroke(accepted ? Color.cameraAccent : Color.cameraWarning, lineWidth: 2)
            .frame(width: 52, height: 52)
            .shadow(color: .black.opacity(0.65), radius: 1)
    }
}
