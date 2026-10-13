import SwiftUI
import UIKit

/// Touch cancellation and leaving the control release the original press immediately.
/// Dragging back in never creates a second press without a new touch-down.
@MainActor
struct HeldAutofocusButton: UIViewRepresentable {
    @ObservedObject var camera: CameraAppState

    func makeCoordinator() -> Coordinator { Coordinator(camera: camera) }

    func makeUIView(context: Context) -> AccessibleFocusButton {
        let button = AccessibleFocusButton(type: .system)
        button.setTitle("AF-ON", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 12, weight: .bold)
        button.accessibilityIdentifier = "held-autofocus-button"
        button.addTarget(context.coordinator, action: #selector(Coordinator.down), for: .touchDown)
        button.addTarget(context.coordinator, action: #selector(Coordinator.release), for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
        button.activation = { [weak coordinator = context.coordinator] in coordinator?.activate() }
        return button
    }

    func updateUIView(_ button: AccessibleFocusButton, context: Context) {
        if let owner = context.coordinator.owner, !camera.ownsHeldAutofocus(owner) {
            context.coordinator.owner = nil
        }
        button.isEnabled = camera.canStartHeldAutofocus || camera.heldAutofocusPressed || context.coordinator.owner != nil
        button.isSelected = camera.heldAutofocusPressed
        button.tintColor = camera.heldAutofocusPressed ? .systemYellow : .white
        button.accessibilityLabel = NSLocalizedString("held_autofocus", comment: "")
        button.accessibilityValue = NSLocalizedString(camera.heldAutofocusPressed ? "held_autofocus_active" : "held_autofocus_released", comment: "")
        button.accessibilityHint = NSLocalizedString("held_autofocus_hint", comment: "")
    }

    static func dismantleUIView(_ uiView: AccessibleFocusButton, coordinator: Coordinator) { coordinator.release() }

    @MainActor final class Coordinator: NSObject {
        let camera: CameraAppState
        var owner: UUID?
        init(camera: CameraAppState) { self.camera = camera }
        @objc func down() {
            guard owner == nil else { return }
            owner = camera.beginHeldAutofocus()
        }
        @objc func release() {
            guard let owner else { return }
            self.owner = nil
            camera.releaseHeldAutofocus(owner: owner)
        }
        func activate() {
            if owner != nil { release() } else { down() }
        }
    }
}

final class AccessibleFocusButton: UIButton {
    var activation: (() -> Void)?
    override func accessibilityActivate() -> Bool {
        guard isEnabled else { return false }
        activation?()
        return true
    }
}
