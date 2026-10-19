#if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
import SwiftUI
import UIKit

/// Passive, fixture-only observation. This recognizer never accepts a touch.
@MainActor
struct CaptureModeTouchProbe: UIViewRepresentable {
    let camera: CameraAppState

    static var enabled: Bool {
        let process = ProcessInfo.processInfo
        guard process.environment["OEC_CAPTURE_MODE_TRACE"] == "1",
              process.environment["OEC_HTTP_PRESET_URL"] == "http://127.0.0.1:18080",
              let index = process.arguments.firstIndex(of: "-app-language"),
              process.arguments.indices.contains(index + 1) else { return false }
        return ["english", "traditionalChinese"].contains(process.arguments[index + 1])
    }

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.observe = { [weak camera] mode in camera?.traceCaptureMode(.touchBegan, requested: mode) }
    }

    static func dismantleUIView(_ view: ProbeView, coordinator: ()) { view.detach() }

    final class ProbeView: UIView, UIGestureRecognizerDelegate {
        var observe: ((AppCaptureMode) -> Void)?
        private weak var observedWindow: UIWindow?
        private lazy var observer: UITapGestureRecognizer = {
            let recognizer = UITapGestureRecognizer(target: nil, action: nil)
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            return recognizer
        }()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            detach()
            guard CaptureModeTouchProbe.enabled, let window else { return }
            observedWindow = window
            window.addGestureRecognizer(observer)
        }

        func detach() {
            observedWindow?.removeGestureRecognizer(observer)
            observedWindow = nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard CaptureModeTouchProbe.enabled, let window = observedWindow,
                  self.window === window, !isHidden, bounds.width > 0, bounds.height > 0 else { return false }
            let frame = convert(bounds, to: window)
            let point = touch.location(in: window)
            guard frame.origin.x.isFinite, frame.origin.y.isFinite,
                  frame.width.isFinite, frame.height.isFinite,
                  point.x.isFinite, point.y.isFinite, frame.contains(point) else { return false }
            // Fixed left/right enum only; never save coordinates or another view's text.
            observe?(point.x < frame.midX ? .photo : .video)
            return false
        }
    }
}
#endif
