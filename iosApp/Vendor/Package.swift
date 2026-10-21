// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PhononRuntime",
    platforms: [.iOS("18.0"), .macOS("15.0")],
    products: [.library(name: "PhononRuntime", targets: ["PhononRuntime"])],
    targets: [
        .binaryTarget(name: "PhononCore", path: "PhononCore.xcframework"),
        .target(name: "PhononRuntime", dependencies: ["PhononCore"], path: "Phonon/Sources/PhononRuntime", linkerSettings: [.linkedFramework("CoreML"), .linkedFramework("Accelerate"), .linkedFramework("Foundation"), .linkedLibrary("c++")])
    ]
)
