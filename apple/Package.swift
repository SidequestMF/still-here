// swift-tools-version:5.9
// Lets the shared decision logic be tested on its own with `swift test` (Mac or Linux).
// The watch and iPhone apps themselves are built in Xcode; see apple/README.md.
import PackageDescription

let package = Package(
    name: "StillHereCore",
    platforms: [.macOS(.v13), .iOS(.v17), .watchOS(.v10)],
    products: [.library(name: "StillHereCore", targets: ["StillHereCore"])],
    targets: [
        .target(name: "StillHereCore", path: "Shared"),
        .testTarget(name: "DetectionTests", dependencies: ["StillHereCore"], path: "Tests/DetectionTests"),
    ]
)
