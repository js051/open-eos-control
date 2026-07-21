import SwiftUI

extension Color {
    static let cameraBackground = Color(red: 0.035, green: 0.04, blue: 0.045)
    static let cameraSurface = Color(red: 0.075, green: 0.085, blue: 0.095)
    static let cameraSurfaceRaised = Color(red: 0.12, green: 0.13, blue: 0.14)
    static let cameraBorder = Color.white.opacity(0.16)
    static let cameraText = Color.white.opacity(0.96)
    static let cameraSecondaryText = Color.white.opacity(0.67)
    static let cameraAccent = Color(red: 0.18, green: 0.82, blue: 0.9)
    static let cameraRecording = Color(red: 0.94, green: 0.12, blue: 0.16)
    static let cameraStatus = Color(red: 0.25, green: 0.82, blue: 0.48)
    static let cameraWarning = Color(red: 0.98, green: 0.76, blue: 0.22)
}

struct CameraIconButtonStyle: ButtonStyle {
    var selected = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: 48, height: 48)
            .foregroundStyle(selected ? Color.cameraBackground : Color.cameraText)
            .background(selected ? Color.cameraText : Color.cameraSurface.opacity(configuration.isPressed ? 0.95 : 0.72))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
    }
}

struct RotatingControl<Content: View>: View {
    let degrees: Double
    @ViewBuilder let content: Content

    var body: some View {
        content
            .rotationEffect(.degrees(degrees))
            .animation(.easeOut(duration: 0.18), value: degrees)
    }
}
