// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "DecoEngine",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "DecoEngine", targets: ["DecoEngine"])
    ],
    targets: [
        .target(name: "DecoEngine"),
        .testTarget(name: "DecoEngineTests", dependencies: ["DecoEngine"])
    ]
)
