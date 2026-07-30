import XCTest
@testable import OpenEOSCore

final class CameraProfileTests: XCTestCase {
    func testProfileEncodesCanonicalWireValues() throws {
        let profile = CameraProfile(
            modelName: "Canon EOS R6 Mark III",
            family: .eosR,
            priority: .primary
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]

        let encoded = try encoder.encode(profile)

        XCTAssertEqual(
            String(decoding: encoded, as: UTF8.self),
            #"{"family":"EOS_R","modelName":"Canon EOS R6 Mark III","priority":"PRIMARY"}"#
        )
    }

    func testR6MarkThirdAliasesUseThePrimaryProfile() {
        let aliases = [
            "Canon EOS R6 Mark III",
            "EOS R6 Mark III",
            "R6 Mark III",
            "R6m3",
            "R63",
            "Canon EOS-R6 Mark III",
        ]

        for model in aliases {
            let profile = CameraProfile.from(modelName: model)
            XCTAssertEqual(profile.modelName, model)
            XCTAssertEqual(profile.family, .eosR, model)
            XCTAssertEqual(profile.priority, .primary, model)
        }
    }

    func testOtherFamiliesRetainTheirCompatibilityPriority() {
        XCTAssertEqual(
            CameraProfile.from(modelName: "Canon EOS R5"),
            CameraProfile(modelName: "Canon EOS R5", family: .eosR, priority: .supported)
        )
        XCTAssertEqual(
            CameraProfile.from(modelName: "Canon EOS M50"),
            CameraProfile(modelName: "Canon EOS M50", family: .eosM, priority: .supported)
        )
        XCTAssertEqual(
            CameraProfile.from(modelName: "Canon EOS 5D"),
            CameraProfile(modelName: "Canon EOS 5D", family: .eosDSLR, priority: .supported)
        )
        XCTAssertEqual(
            CameraProfile.from(modelName: "Canon PowerShot G7 X"),
            CameraProfile(modelName: "Canon PowerShot G7 X", family: .powerShot, priority: .research)
        )
    }
}
