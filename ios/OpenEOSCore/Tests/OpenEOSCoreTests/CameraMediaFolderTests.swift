import Foundation
import XCTest

@testable import OpenEOSCore

final class CameraMediaFolderTests: XCTestCase {
    func testAcceptedFolderValuesRemainOpaqueAndUnmodified() throws {
        let id = " synthetic:gphoto:card%2Fone+ "
        let label = " /card 1/旅行+ "
        let folder = try XCTUnwrap(CameraMediaFolder(id: id, label: label))

        XCTAssertEqual(folder.id, id)
        XCTAssertEqual(folder.label, label)
        XCTAssertEqual(folder, CameraMediaFolder(id: id, label: label))
        XCTAssertNotEqual(folder, CameraMediaFolder(id: "other", label: label))
        let legacyItem = CameraMediaItem(id: "image", name: "IMG.JPG", kind: "image")
        XCTAssertNil(legacyItem.folder)
        XCTAssertNotEqual(
            legacyItem,
            CameraMediaItem(id: "image", name: "IMG.JPG", kind: "image", folder: folder)
        )
    }

    func testBoundsUseUTF16UnitsAndAcceptTheExactLimits() {
        let maximumID = String(repeating: "📷", count: 2_048)
        let maximumLabel = String(repeating: "📷", count: 512)
        XCTAssertNotNil(CameraMediaFolder(id: maximumID, label: maximumLabel))
        XCTAssertNil(CameraMediaFolder(id: maximumID + "a", label: "folder"))
        XCTAssertNil(CameraMediaFolder(id: "folder", label: maximumLabel + "a"))
        XCTAssertNotNil(CameraMediaFolder(id: String(repeating: "a", count: 4_096), label: "/"))
        XCTAssertNil(CameraMediaFolder(id: String(repeating: "a", count: 4_097), label: "/"))
    }

    func testCanonicallyEquivalentFolderIDsRemainDistinctAndKeepTheirOriginalBytes() throws {
        let composedID = "synthetic:caf\u{E9}"
        let decomposedID = "synthetic:cafe\u{301}"
        let composed = try XCTUnwrap(CameraMediaFolder(id: composedID, label: "/café"))
        let decomposed = try XCTUnwrap(CameraMediaFolder(id: decomposedID, label: "/café"))

        XCTAssertNotEqual(composed, decomposed)
        XCTAssertEqual(Array(composed.id.utf8), Array(composedID.utf8))
        XCTAssertEqual(Array(decomposed.id.utf8), Array(decomposedID.utf8))
        XCTAssertNotEqual(composed, CameraMediaFolder(id: composedID, label: "/cafe\u{301}"))
    }

    func testBlankAndEveryC0C1ControlAreRejectedInEitherField() {
        for value in ["", " ", "\u{2003}\u{3000}"] {
            XCTAssertNil(CameraMediaFolder(id: value, label: "/"))
            XCTAssertNil(CameraMediaFolder(id: "folder", label: value))
        }
        for codePoint in Array(0x00...0x1F) + Array(0x7F...0x9F) {
            let control = String(UnicodeScalar(codePoint)!)
            XCTAssertNil(CameraMediaFolder(id: "folder" + control, label: "/"), "ID: \(codePoint)")
            XCTAssertNil(CameraMediaFolder(id: "folder", label: "/" + control), "Label: \(codePoint)")
        }
    }
}
