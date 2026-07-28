import AVFoundation
import OpenEOSCore
import SwiftUI
import UIKit

struct LiveViewSurface: View {
    @EnvironmentObject private var camera: CameraAppState

    var body: some View {
        GeometryReader { proxy in
            let image = camera.liveViewData.flatMap(UIImage.init(data:))
            let contentSize = image?.size ?? (camera.activeLiveViewSource == .ccapiRTP ? camera.nativeLiveViewSize : nil)
            let imageRect = contentSize.map { aspectFitRect(contentSize: $0, containerSize: proxy.size) }
                ?? CGRect(origin: .zero, size: proxy.size)

            ZStack {
                Color.black

                if camera.activeLiveViewSource == .ccapiRTP {
                    IOSCcapiRTPVideoSurface(controller: camera.rtpController)
                    if camera.lastFrameAt == nil { offlineSurface }
                } else if let image {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.medium)
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    offlineSurface
                }

                if camera.showGrid {
                    CompositionGrid()
                        .frame(width: imageRect.width, height: imageRect.height)
                        .position(x: imageRect.midX, y: imageRect.midY)
                        .allowsHitTesting(false)
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

private struct FocusMarkerView: View {
    let accepted: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .stroke(accepted ? Color.cameraAccent : Color.cameraWarning, lineWidth: 2)
            .frame(width: 52, height: 52)
            .shadow(color: .black.opacity(0.65), radius: 1)
    }
}
