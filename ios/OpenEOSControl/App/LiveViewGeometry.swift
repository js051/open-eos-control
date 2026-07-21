import CoreGraphics

func aspectFitRect(contentSize: CGSize, containerSize: CGSize) -> CGRect {
    guard contentSize.width > 0, contentSize.height > 0, containerSize.width > 0, containerSize.height > 0 else {
        return CGRect(origin: .zero, size: containerSize)
    }
    let scale = min(containerSize.width / contentSize.width, containerSize.height / contentSize.height)
    let size = CGSize(width: contentSize.width * scale, height: contentSize.height * scale)
    return CGRect(
        x: (containerSize.width - size.width) / 2,
        y: (containerSize.height - size.height) / 2,
        width: size.width,
        height: size.height
    )
}
