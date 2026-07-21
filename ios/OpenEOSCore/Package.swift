// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "OpenEOSCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "OpenEOSCore", targets: ["OpenEOSCore"]),
    ],
    targets: [
        .target(name: "OpenEOSCore"),
        .testTarget(name: "OpenEOSCoreTests", dependencies: ["OpenEOSCore"]),
    ]
)
