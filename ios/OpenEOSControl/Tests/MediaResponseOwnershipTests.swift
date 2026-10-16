import Foundation
import OpenEOSCore
import XCTest
import UIKit
import UniformTypeIdentifiers

@testable import OpenEOSControl

/// Every held response deliberately ignores cancellation. Assertions follow the
/// exact owner task's completion, rather than treating a delay as evidence.
@MainActor
final class MediaResponseOwnershipTests: XCTestCase {
    func testInvalidLegacyBridgeRefreshPreservesRowsAndFailsWithoutFallback() async throws {
        let valid = "{\"id\":\"replacement\",\"name\":\"NEW.JPG\",\"kind\":\"image\"}"
        for body in ["{\"items\":[\(valid),null]}", "{\"items\":[\(valid),\(valid)]}"] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let previous = state.mediaItems
            let reads = await peer.count(.listing)
            await peer.enqueue(.listing, body: Data(body.utf8))
            state.startMediaLibraryLoad()
            try await waitUntil { !state.mediaLibraryLoading && state.mediaLibraryLoadStatus == .failed }
            XCTAssertEqual(state.mediaItems, previous)
            XCTAssertEqual(state.mediaLibraryLoadStatus, .failed)
            XCTAssertTrue(state.mediaListingDefersThumbnails)
            XCTAssertNotNil(state.lastError)
            let after = await peer.count(.listing)
            XCTAssertEqual(after, reads + 1, "Malformed legacy data must not start a fallback traversal")
        }
    }

    func testBridgeThumbnailsWaitForCompletionAndExplicitPreviewRetiresListing() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        let item = try XCTUnwrap(state.mediaItems.first)
        await peer.enqueue(.listing, gate: "listing")
        state.startMediaLibraryLoad()
        try await waitForGate("listing", peer: peer)
        let before = await peer.count(.thumbnail)
        XCTAssertTrue(state.mediaListingDefersThumbnails)
        await state.loadMediaThumbnail(item)
        let during = await peer.count(.thumbnail)
        XCTAssertEqual(during, before)
        await state.openMediaPreview(item)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .cancelled)
        XCTAssertTrue(state.mediaListingDefersThumbnails)
        XCTAssertNotNil(state.mediaPreviewData)
        await peer.release("listing")
        // A late cancelled listing must not enable automatic thumbnail demand.
        state.closeMediaPreview()
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete && !state.mediaLibraryLoading }
        XCTAssertFalse(state.mediaListingDefersThumbnails)
        await state.loadMediaThumbnail(item)
        let after = await peer.count(.thumbnail)
        XCTAssertEqual(after, before + 1)
    }

    func testBridgeCancelConsumesQueuedContentsHintButAllowsANewEvent() async throws {
        let peer = MediaOwnershipPeer(events: true)
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.screen = .media
        try await waitUntil { await peer.eventWaiting() }
        await peer.enqueue(.listing, gate: "manual-listing")
        state.startMediaLibraryLoad()
        try await waitForGate("manual-listing", peer: peer)
        let polls = await peer.eventPolls()
        let before = await peer.count(.listing)
        await peer.emitContents()
        state.cancelMediaLibraryLoad()
        await peer.release("manual-listing")
        try await waitUntil { await peer.eventPolls() > polls }
        let afterCancel = await peer.count(.listing)
        XCTAssertEqual(afterCancel, before, "Old hint must not restart latest or gallery listing")
        XCTAssertEqual(state.mediaLibraryLoadStatus, .cancelled)
        await peer.emitContents()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete && !state.mediaLibraryLoading }
        let afterNewEvent = await peer.count(.listing)
        XCTAssertEqual(afterNewEvent, before + 1, "A new event has one gallery traversal")
    }

    func testBridgeFailedEventListingIsConsumedWithoutRetryingTraversal() async throws {
        let peer = MediaOwnershipPeer(events: true)
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.screen = .media
        try await waitUntil { await peer.eventWaiting() }
        let before = await peer.count(.listing)
        let polls = await peer.eventPolls()
        await peer.enqueue(.listing, error: .networkConnectionLost)
        await peer.emitContents()
        try await waitUntil { await peer.eventPolls() > polls }
        let after = await peer.count(.listing)
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .failed)
        XCTAssertTrue(state.mediaListingDefersThumbnails)
    }

    func testDateFilterAndClearPreserveAnActuallySelectedPreview() async throws {
        for bridge in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let item = try XCTUnwrap(state.latestMediaItem)
            await state.openMediaPreview(item)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewItem).id, item.id)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewData), MediaOwnershipPeer.imageBytes)
            let owner = state.mediaLibrarySessionID
            let media = state.mediaItems
            let loadStatus = state.mediaLibraryLoadStatus
            let listingCount = await peer.count(.listing)
            let previewCount = await peer.count(.preview)
            let date = Date(timeIntervalSince1970: 1_786_752_000)
            let range = try XCTUnwrap(MediaDateRange(startDate: date, endDate: date,
                                                   timeZone: TimeZone(secondsFromGMT: 0)!))
            XCTAssertTrue(state.setMediaDateRange(range, owner: owner))
            // The selected fixture has an unknown capture date and is excluded from the list.
            XCTAssertTrue(mediaItemsForDisplay(media, filter: .all, sort: .camera, dateRange: range).isEmpty)
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            XCTAssertEqual(state.mediaItems, media)
            XCTAssertEqual(state.mediaLibraryLoadStatus, loadStatus)
            XCTAssertTrue(state.setMediaDateRange(nil, owner: owner))
            XCTAssertEqual(mediaItemsForDisplay(state.mediaItems, filter: .all, sort: .camera), media)
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            let afterListingCount = await peer.count(.listing)
            let afterPreviewCount = await peer.count(.preview)
            XCTAssertEqual(afterListingCount, listingCount)
            XCTAssertEqual(afterPreviewCount, previewCount)
        }
    }

    func testRatingFilterAndClearPreserveAnActuallySelectedPreviewWithoutRequests() async throws {
        for bridge in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let item = try XCTUnwrap(state.latestMediaItem)
            await state.openMediaPreview(item)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewItem).id, item.id)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewData), MediaOwnershipPeer.imageBytes)
            let items = state.mediaItems
            let status = state.mediaLibraryLoadStatus
            let owner = state.mediaLibrarySessionID
            let listingCount = await peer.count(.listing)
            let previewCount = await peer.count(.preview)
            let infoCount = await peer.count(.info)
            let mutations = await peer.mutationOperations()
            // Choose a valid filter that excludes the selected item, regardless of fixture rating.
            let excluded: MediaRatingFilter = item.rating == 5 ? .unrated : .five
            XCTAssertTrue(state.setMediaRatingFilter(excluded, owner: owner))
            XCTAssertFalse(mediaItemsForDisplay(items, filter: .all, sort: .ratingHigh,
                ratingFilter: excluded).contains { $0.id == item.id })
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            XCTAssertEqual(state.mediaItems, items)
            XCTAssertEqual(state.mediaLibraryLoadStatus, status)
            XCTAssertTrue(state.setMediaRatingFilter(.all, owner: owner))
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            let afterListing = await peer.count(.listing)
            let afterPreview = await peer.count(.preview)
            XCTAssertEqual(afterListing, listingCount)
            XCTAssertEqual(afterPreview, previewCount)
            let afterInfo = await peer.count(.info)
            let afterMutations = await peer.mutationOperations()
            XCTAssertEqual(afterInfo, infoCount)
            XCTAssertEqual(afterMutations, mutations)
        }
    }

    func testFolderFilterAndClearPreserveSelectedPreviewAndDownloadWithoutRequests() async throws {
        for bridge in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let item = try XCTUnwrap(state.latestMediaItem)
            await state.openMediaPreview(item)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewItem).id, item.id)
            XCTAssertEqual(try XCTUnwrap(state.mediaPreviewData), MediaOwnershipPeer.imageBytes)
            state.startMediaDownload(item)
            let download = try XCTUnwrap(state.mediaDownloadTask)
            await download.value
            let downloadedFile = try XCTUnwrap(state.downloadedFile(for: item))
            XCTAssertTrue(FileManager.default.fileExists(atPath: downloadedFile.path))
            XCTAssertEqual(try Data(contentsOf: downloadedFile), MediaOwnershipPeer.imageBytes)
            let owner = state.mediaLibrarySessionID
            let items = state.mediaItems
            XCTAssertTrue(items.contains { $0.id == item.id })
            let scope = state.mediaLibraryScope
            let status = state.mediaLibraryLoadStatus
            let hasMore = state.mediaLibraryHasMore
            let requests = await peer.requestOperations()
            let limits = await peer.listLimits()
            let excluded = try XCTUnwrap(CameraMediaFolder(id: "synthetic:not-loaded", label: "Not loaded"))
            XCTAssertFalse(items.contains { $0.folder.map { mediaFolderIdentity($0) } == mediaFolderIdentity(excluded) })
            XCTAssertTrue(state.setMediaFolderFilter(.folder(excluded), owner: owner))
            XCTAssertTrue(mediaItemsForDisplay(items, filter: .all, sort: .camera,
                folderFilter: state.mediaFolderFilter).isEmpty)
            XCTAssertEqual(state.mediaFolderFilter, .folder(excluded))
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            XCTAssertEqual(state.downloadedFile(for: item), downloadedFile)
            XCTAssertTrue(state.setMediaFolderFilter(.all, owner: owner))
            XCTAssertEqual(mediaItemsForDisplay(state.mediaItems, filter: .all, sort: .camera,
                folderFilter: state.mediaFolderFilter), items)
            XCTAssertEqual(state.mediaPreviewItem, item)
            XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes)
            XCTAssertEqual(state.downloadedFile(for: item), downloadedFile)
            XCTAssertTrue(FileManager.default.fileExists(atPath: downloadedFile.path))
            XCTAssertEqual(state.mediaItems, items)
            XCTAssertEqual(state.mediaLibraryScope, scope)
            XCTAssertEqual(state.mediaLibraryLoadStatus, status)
            XCTAssertEqual(state.mediaLibraryHasMore, hasMore)
            let afterRequests = await peer.requestOperations()
            let afterLimits = await peer.listLimits()
            XCTAssertEqual(afterRequests, requests, "Folder selection cannot list, preview, download, or mutate media")
            XCTAssertEqual(afterLimits, limits, "Folder selection cannot request full-card enumeration")
        }
    }

    func testRetiredInfoAndEveryMetadataResponseCannotOwnReplacementSession() async throws {
        for bridge in [false, true] {
            for operation in OwnershipOperation.metadataAndInfo {
                for fails in [false, true] {
                    try await assertRetiredOperation(operation, bridge: bridge, fails: fails)
                }
            }
        }
    }

    func testRetiredDeletePreservesSameIDViewerDownloadAndReplacementMediaOwner() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                try await assertRetiredOperation(.delete, bridge: bridge, fails: fails)
            }
        }
    }

    func testCurrentOwnerInfoAndAllMetadataActionsStillPublishReadback() async throws {
        for bridge in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let item = try XCTUnwrap(state.latestMediaItem)
            await state.openMediaPreview(item)
            for operation in OwnershipOperation.metadataAndInfo {
                await perform(operation, state: state, item: item)
                let updated = await peer.currentItem()
                XCTAssertEqual(state.mediaItems, [updated])
                XCTAssertEqual(state.latestMediaItem, updated)
                XCTAssertEqual(state.mediaPreviewItem, updated)
                XCTAssertNil(state.lastError)
                XCTAssertFalse(state.isBusy(.media))
            }
            let mutations = await peer.mutationOperations()
            XCTAssertEqual(mutations, [.protection, .rating, .rotation, .archive])
        }
    }

    func testRealPickerCoordinatorRetainsOpeningTicketAndDeliversOnlyOnce() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        let file = try uploadFile("COORDINATOR.JPG")
        defer { state.requestDisconnect(); removeUpload(file) }
        try await connectAndLoad(state)
        let first = try XCTUnwrap(state.beginMediaUploadSelection())
        var completions = 0, acquisitions = 0, releases = 0
        let access = MediaUploadFileAccess(acquire: { _ in acquisitions += 1; return true }, release: { _ in releases += 1 })
        let oldCoordinator = MediaUploadPicker.Coordinator(selection: first) { opening, urls in
            completions += 1
            if let url = urls?.first { state.startMediaUpload(url, selection: opening, access: access) }
            else { state.cancelMediaUploadSelection(opening) }
        }
        state.cancelMediaUploadSelection(first)
        let second = try XCTUnwrap(state.beginMediaUploadSelection())
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.data])
        oldCoordinator.documentPicker(controller, didPickDocumentsAt: [file])
        oldCoordinator.documentPickerWasCancelled(controller)
        oldCoordinator.documentPicker(controller, didPickDocumentsAt: [file])
        XCTAssertEqual(completions, 1)
        XCTAssertEqual(acquisitions, 0)
        XCTAssertTrue(state.startMediaUpload(file, selection: second, access: access))
        await state.mediaUploadTask?.value
        XCTAssertEqual(acquisitions, 1); XCTAssertEqual(releases, 1)
        let uploads = await peer.count(.upload)
        XCTAssertEqual(uploads, 1)
    }

    func testRejectedProviderGrantNeverDispatchesAndDoesNotReleaseAnUnacquiredGrant() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        let file = try uploadFile("DENIED-GRANT.JPG")
        defer { state.requestDisconnect(); removeUpload(file) }
        try await connectAndLoad(state)
        var acquisitions = 0, releases = 0
        let opening = try XCTUnwrap(state.beginMediaUploadSelection())
        let access = MediaUploadFileAccess(acquire: { _ in acquisitions += 1; return false }, release: { _ in releases += 1 })
        XCTAssertFalse(state.startMediaUpload(file, selection: opening, access: access))
        XCTAssertFalse(state.startMediaUpload(file, selection: opening, access: access))
        XCTAssertEqual(acquisitions, 1); XCTAssertEqual(releases, 0)
        XCTAssertEqual(state.mediaUploadResult?.outcome, .notSent)
        XCTAssertNotNil(state.mediaUploadError)
        XCTAssertFalse(state.isBusy(.media))
        let uploads = await peer.count(.upload)
        XCTAssertEqual(uploads, 0)
    }

    func testPickerOpeningOwnerRejectsLateCallbackBeforeAcquiringTheFile() async throws {
        let oldPeer = MediaOwnershipPeer(), newPeer = MediaOwnershipPeer()
        let state = makeState([oldPeer, newPeer])
        let file = try uploadFile("PICKER.JPG")
        defer { state.requestDisconnect(); removeUpload(file) }
        try await connectAndLoad(state)
        let opening = try XCTUnwrap(state.beginMediaUploadSelection())
        await state.disconnect()
        try await connectAndLoad(state)
        var acquisitions = 0, releases = 0
        let access = MediaUploadFileAccess(acquire: { _ in acquisitions += 1; return true }, release: { _ in releases += 1 })
        XCTAssertFalse(state.startMediaUpload(file, selection: opening, access: access))
        XCTAssertEqual(acquisitions, 0)
        XCTAssertEqual(releases, 0)
        let oldUploads = await oldPeer.count(.upload), newUploads = await newPeer.count(.upload)
        XCTAssertEqual(oldUploads + newUploads, 0)
        XCTAssertNil(state.mediaUploadResult)
    }

    func testCancelledOldPickerCannotConsumeNewPickerAndDuplicateCallbackCannotResend() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        let file = try uploadFile("SINGLE-USE.JPG")
        defer { state.requestDisconnect(); removeUpload(file) }
        try await connectAndLoad(state)
        let first = try XCTUnwrap(state.beginMediaUploadSelection())
        state.cancelMediaUploadSelection(first)
        let second = try XCTUnwrap(state.beginMediaUploadSelection())
        var acquisitions = 0, releases = 0
        let access = MediaUploadFileAccess(acquire: { _ in acquisitions += 1; return true }, release: { _ in releases += 1 })
        XCTAssertFalse(state.startMediaUpload(file, selection: first, access: access))
        state.cancelMediaUploadSelection(first)
        XCTAssertTrue(state.startMediaUpload(file, selection: second, access: access))
        let task = try XCTUnwrap(state.mediaUploadTask)
        XCTAssertFalse(state.startMediaUpload(file, selection: second, access: access))
        await task.value
        XCTAssertEqual(acquisitions, 1); XCTAssertEqual(releases, 1)
        XCTAssertEqual(state.mediaUploadResult?.outcome, .verified)
        let uploads = await peer.count(.upload)
        XCTAssertEqual(uploads, 1)
    }

    func testGrantedSourceReleasedExactlyOnceBeforeEntryOnCancelRetireAndStagingFailure() async throws {
        for mode in ["cancel", "retire", "missing-source"] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            let file = try uploadFile("GRANT.JPG")
            defer { state.requestDisconnect(); removeUpload(file) }
            try await connectAndLoad(state)
            let opening = try XCTUnwrap(state.beginMediaUploadSelection())
            var acquisitions = 0, releases = 0
            let access = MediaUploadFileAccess(acquire: { _ in acquisitions += 1; return true }, release: { _ in releases += 1 })
            let listings = await peer.count(.listing)
            XCTAssertTrue(state.startMediaUpload(file, selection: opening, access: access))
            let task = try XCTUnwrap(state.mediaUploadTask)
            if mode == "cancel" { state.cancelMediaUpload() }
            else if mode == "retire" { state.requestDisconnect() }
            else { try FileManager.default.removeItem(at: file) }
            await task.value
            XCTAssertEqual(acquisitions, 1, mode); XCTAssertEqual(releases, 1, mode)
            XCTAssertEqual(state.mediaUploadResult?.outcome, .notSent, mode)
            let uploads = await peer.count(.upload), afterListings = await peer.count(.listing)
            XCTAssertEqual(uploads, 0, mode); XCTAssertEqual(afterListings, listings, mode)
            XCTAssertFalse(state.isBusy(.media))
        }
    }

    func testNoReceiptNeverConfirmsPreexistingMatchingOrAbsentMedia() async throws {
        for (name, size, folder) in [
            ("COLLISION.JPG", Optional(Int64(MediaOwnershipPeer.imageBytes.count)), "first-folder"),
            ("collision.jpg", Optional(Int64(MediaOwnershipPeer.imageBytes.count)), "another-folder"),
            ("COLLISION.JPG", nil, "another-folder"),
            ("ABSENT.JPG", Optional(Int64(MediaOwnershipPeer.imageBytes.count)), "first-folder"),
        ] {
            for failure in [URLError.Code.cancelled, .networkConnectionLost] {
                let peer = MediaOwnershipPeer(acceptUpload: false, listedSize: size, folder: folder)
                let state = makeState([peer])
                let file = try uploadFile(name)
                defer { state.requestDisconnect(); removeUpload(file) }
                try await connectAndLoad(state)
                let before = await peer.count(.listing)
                await peer.enqueue(.upload, error: failure)
                XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
                await state.mediaUploadTask?.value
                XCTAssertNil(state.uploadedMediaName)
                XCTAssertEqual(state.mediaUploadResult?.outcome, .uncertain)
                XCTAssertNil(state.mediaUploadResult?.receipt)
                let uploads = await peer.count(.upload), listings = await peer.count(.listing)
                XCTAssertEqual(uploads, 1); XCTAssertEqual(listings, before, "Uncertain uploads wait for explicit review")
                XCTAssertEqual(state.mediaItems.count, 1, "Existing observation remains independent from the attempted upload")
                XCTAssertFalse(state.isBusy(.media))
            }
        }
    }

    func testUnreadableStagingWithExistingSameNameCannotClaimSuccessOrStartHTTP() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        let file = try uploadFile("COLLISION.JPG")
        defer { state.requestDisconnect(); removeUpload(file) }
        try await connectAndLoad(state)
        let before = await peer.count(.listing)
        try FileManager.default.removeItem(at: file)
        XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
        await state.mediaUploadTask?.value
        XCTAssertEqual(state.mediaUploadResult?.outcome, .notSent)
        XCTAssertNil(state.uploadedMediaName)
        let uploads = await peer.count(.upload), listings = await peer.count(.listing)
        XCTAssertEqual(uploads, 0); XCTAssertEqual(listings, before)
    }

    func testVerifiedReceiptIdentitySurvivesFailedGalleryRefresh() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer])
        let file = try uploadFile("COLLISION.JPG")
        defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
        try await connectAndLoad(state)
        await peer.enqueue(.upload, gate: "receipt")
        await peer.enqueue(.listing, error: .networkConnectionLost)
        XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
        let task = try XCTUnwrap(state.mediaUploadTask)
        try await waitForGate("receipt", peer: peer)
        await peer.release("receipt")
        await task.value
        XCTAssertEqual(state.mediaUploadResult?.outcome, .verified)
        XCTAssertEqual(state.mediaUploadResult?.receipt?.id, "uploaded-COLLISION.JPG")
        XCTAssertNotEqual(state.mediaUploadResult?.receipt?.id, state.mediaItems.first?.id)
        XCTAssertEqual(state.uploadedMediaName, "COLLISION.JPG")
        XCTAssertEqual(state.mediaUploadResult?.refreshFailed, true)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .failed)
        let uploads = await peer.count(.upload)
        XCTAssertEqual(uploads, 1)
    }

    func testDirectCCAPIPickerIsUnavailableAndCannotTransmitLocalFile() async throws {
        let peer = MediaOwnershipPeer()
        let state = makeState([peer], bridge: false)
        defer { state.requestDisconnect() }
        try await connectAndLoad(state)
        XCTAssertNil(state.beginMediaUploadSelection())
        let uploads = await peer.count(.upload)
        XCTAssertEqual(uploads, 0)
    }

    func testRetiredUploadResponseCannotReconcileOrFinishReplacementUpload() async throws {
        for failure in [nil, URLError.Code.networkConnectionLost, .cancelled] {
            let oldPeer = MediaOwnershipPeer()
            let newPeer = MediaOwnershipPeer(rating: 2)
            let state = makeState([oldPeer, newPeer])
            let file = try uploadFile("OLD-UPLOAD.JPG")
            let replacementFile = try uploadFile("NEW-UPLOAD.JPG")
            defer {
                state.requestDisconnect()
                Task { await oldPeer.releaseAll(); await newPeer.releaseAll() }
                removeUpload(file); removeUpload(replacementFile)
            }
            try await connectAndLoad(state)
            await oldPeer.enqueue(.upload, gate: "old-upload", error: failure)
            XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
            let old = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("old-upload", peer: oldPeer)
            let oldListings = await oldPeer.count(.listing)
            await state.disconnect()
            try await connectAndLoad(state)
            let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
            await newPeer.enqueue(.upload, gate: "new-upload")
            XCTAssertTrue(state.startMediaUpload(replacementFile, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
            let replacement = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("new-upload", peer: newPeer)
            try await waitUntil { state.mediaUploadProgress?.bytesTransferred == 1 }

            await oldPeer.release("old-upload")
            await old.value

            assertPreserved(preserved, state: state)
            XCTAssertEqual(state.activeMediaUploadName, replacementFile.lastPathComponent)
            XCTAssertEqual(state.mediaUploadProgress?.bytesTransferred, 1)
            XCTAssertNil(state.uploadedMediaName)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertFalse(replacement.isCancelled)
            let after = await oldPeer.count(.listing)
            XCTAssertEqual(after, oldListings, "A retired session must never begin cancellation reconciliation")
            let uploads = await oldPeer.count(.upload)
            XCTAssertEqual(uploads, 1)
            await newPeer.release("new-upload")
            await replacement.value
            XCTAssertEqual(state.uploadedMediaName, replacementFile.lastPathComponent)
        }
    }

    func testRetiredPostUploadAndCancellationListingsCannotPublishIntoReplacement() async throws {
        for cancelled in [false, true] {
            for fails in [false, true] {
                let oldPeer = MediaOwnershipPeer()
                let newPeer = MediaOwnershipPeer(rating: 2)
                let state = makeState([oldPeer, newPeer])
                let file = try uploadFile("OLD-LIST.JPG")
                let replacementFile = try uploadFile("NEW-LIST.JPG")
                defer {
                    state.requestDisconnect()
                    Task { await oldPeer.releaseAll(); await newPeer.releaseAll() }
                    removeUpload(file); removeUpload(replacementFile)
                }
                try await connectAndLoad(state)
                await oldPeer.enqueue(.upload, gate: "upload")
                await oldPeer.enqueue(.listing, gate: "old-list", error: fails ? .networkConnectionLost : nil)
                XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
                let old = try XCTUnwrap(state.mediaUploadTask)
                try await waitForGate("upload", peer: oldPeer)
                await oldPeer.release("upload")
                try await waitForGate("old-list", peer: oldPeer)
                if cancelled { state.cancelMediaUpload() }
                await state.disconnect()
                try await connectAndLoad(state)
                let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
                await newPeer.enqueue(.upload, gate: "new-upload")
                XCTAssertTrue(state.startMediaUpload(replacementFile, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
                let replacement = try XCTUnwrap(state.mediaUploadTask)
                try await waitForGate("new-upload", peer: newPeer)

                await oldPeer.release("old-list")
                await old.value

                assertPreserved(preserved, state: state)
                XCTAssertEqual(state.activeMediaUploadName, replacementFile.lastPathComponent)
                XCTAssertNil(state.uploadedMediaName)
                XCTAssertNil(state.mediaUploadError)
                XCTAssertFalse(replacement.isCancelled)
                let oldUploads = await oldPeer.count(.upload)
                XCTAssertEqual(oldUploads, 1, "Reconciliation must never retransmit the file")
                await newPeer.release("new-upload")
                await replacement.value
                XCTAssertEqual(state.uploadedMediaName, replacementFile.lastPathComponent)
            }
        }
    }

    func testSameOwnerCancellationPreservesOnlyVerifiedReceiptWithoutRetransmission() async throws {
        for failure in [nil, URLError.Code.cancelled] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            let file = try uploadFile("CANCELLED-ACK.JPG")
            defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
            try await connectAndLoad(state)
            let before = await peer.count(.listing)
            await peer.enqueue(.upload, gate: "upload", error: failure)
            XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
            let upload = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("upload", peer: peer)
            state.cancelMediaUpload()
            await peer.release("upload")
            await upload.value

            XCTAssertEqual(state.uploadedMediaName, failure == nil ? file.lastPathComponent : nil)
            XCTAssertEqual(state.mediaUploadResult?.outcome, failure == nil ? .verified : .uncertain)
            XCTAssertFalse(state.mediaItems.contains { $0.name == file.lastPathComponent }, "Cancellation does not start a fresh list")
            XCTAssertEqual(state.mediaLibraryLoadStatus, .complete)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertNil(state.lastError)
            XCTAssertNil(state.mediaUploadTask)
            XCTAssertFalse(state.isBusy(.media))
            let uploads = await peer.count(.upload)
            let listings = await peer.count(.listing)
            XCTAssertEqual(uploads, 1)
            XCTAssertEqual(listings, before, "Cancellation must not start a new read or delay the result")
        }
    }

    func testUploadCancelledOrRetiredBeforeEntrySendsNeitherPostNorReconciliation() async throws {
        for retire in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            let file = try uploadFile("QUEUED.JPG")
            defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
            try await connectAndLoad(state)
            let before = await peer.count(.listing)
            XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
            let queued = try XCTUnwrap(state.mediaUploadTask)
            // No suspension: invalidate the MainActor task before its first entry.
            if retire { state.requestDisconnect() }
            else { state.cancelMediaUpload() }
            await queued.value
            let uploads = await peer.count(.upload)
            let listings = await peer.count(.listing)
            XCTAssertEqual(uploads, 0)
            XCTAssertEqual(listings, before)
            XCTAssertNil(state.uploadedMediaName)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertNil(state.mediaUploadTask)
            XCTAssertNil(state.activeMediaUploadName)
            XCTAssertFalse(state.isBusy(.media))
        }
    }

    func testObsoleteUploadListingCannotPublishAfterScopeChangeOrRoundTrip() async throws {
        for cancelled in [false, true] {
            for roundTrip in [false, true] {
                for fails in [false, true] {
                    let peer = MediaOwnershipPeer()
                    let state = makeState([peer])
                    let file = try uploadFile("SCOPE.JPG")
                    defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
                    try await connectAndLoad(state)
                    let originalItems = state.mediaItems
                    await peer.enqueue(.upload, gate: "upload")
                    await peer.enqueue(.listing, gate: "old-scope", error: fails ? .networkConnectionLost : nil)
                    XCTAssertTrue(state.startMediaUpload(file, selection: try XCTUnwrap(state.beginMediaUploadSelection())))
                    let upload = try XCTUnwrap(state.mediaUploadTask)
                    try await waitForGate("upload", peer: peer)
                    await peer.release("upload")
                    try await waitForGate("old-scope", peer: peer)
                    if cancelled { state.cancelMediaUpload() }
                    state.setMediaLibraryScope(.all)
                    if roundTrip { state.setMediaLibraryScope(.recent) }
                    XCTAssertEqual(state.mediaLibraryLoadStatus, .notLoaded)
                    await peer.release("old-scope")
                    await upload.value

                    XCTAssertEqual(state.mediaLibraryScope, roundTrip ? .recent : .all)
                    XCTAssertEqual(state.mediaItems, originalItems)
                    XCTAssertEqual(state.mediaLibraryLoadStatus, .notLoaded)
                    XCTAssertFalse(state.mediaLibraryHasMore)
                    XCTAssertNil(state.mediaUploadError)
                    XCTAssertNil(state.lastError)
                    XCTAssertFalse(state.isBusy(.media))
                    if !fails || !cancelled {
                        XCTAssertEqual(state.uploadedMediaName, file.lastPathComponent)
                    }
                    state.startMediaLibraryLoad()
                    try await waitUntil { state.mediaLibraryLoadStatus == .complete }
                    XCTAssertTrue(state.mediaItems.contains { $0.name == file.lastPathComponent })
                    let limits = await peer.listLimits()
                    XCTAssertEqual(limits.suffix(2).map { $0 ?? "all" }, ["61", roundTrip ? "61" : "all"])
                    let uploads = await peer.count(.upload)
                    XCTAssertEqual(uploads, 1)
                }
            }
        }
    }

    private func assertRetiredOperation(_ operation: OwnershipOperation, bridge: Bool, fails: Bool) async throws {
        let oldPeer = MediaOwnershipPeer()
        let newPeer = MediaOwnershipPeer(rating: 2)
        let state = makeState([oldPeer, newPeer], bridge: bridge)
        defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        let oldItem = try XCTUnwrap(state.latestMediaItem)
        await oldPeer.enqueue(operation, gate: "retired", error: fails ? .networkConnectionLost : nil)
        let old = Task { await perform(operation, state: state, item: oldItem) }
        try await waitForGate("retired", peer: oldPeer)
        await state.disconnect()
        try await connectAndLoad(state)
        let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
        XCTAssertEqual(preserved.item.id, oldItem.id, "The fixture must exercise an ID collision")
        XCTAssertNotEqual(preserved.item, oldItem)
        await newPeer.enqueue(.info, gate: "replacement")
        let replacement = Task { await state.loadMediaInfo(preserved.item) }
        try await waitForGate("replacement", peer: newPeer)
        await oldPeer.release("retired")
        await old.value

        assertPreserved(preserved, state: state)
        XCTAssertNil(state.deletedMediaName)
        let replacementHeld = await newPeer.waiting("replacement")
        XCTAssertTrue(replacementHeld)
        let mutations = await oldPeer.mutationOperations()
        XCTAssertEqual(mutations, operation == .info ? [] : [operation])
        await newPeer.release("replacement")
        await replacement.value
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertNil(state.lastError)
    }

    private struct PreservedMedia {
        let item: CameraMediaItem
        let items: [CameraMediaItem]
        let file: URL
        let warning: String
        let snapshot: CameraSnapshot?
    }

    private func prepareViewerAndWarning(_ state: CameraAppState, peer: MediaOwnershipPeer) async throws -> PreservedMedia {
        let item = try XCTUnwrap(state.latestMediaItem)
        await state.openMediaPreview(item)
        state.startMediaDownload(item)
        let download = try XCTUnwrap(state.mediaDownloadTask)
        await download.value
        let file = try XCTUnwrap(state.downloadedFile(for: item))
        await peer.enqueue(.info, error: .cannotFindHost)
        await state.loadMediaInfo(item)
        return PreservedMedia(item: item, items: state.mediaItems, file: file,
            warning: try XCTUnwrap(state.lastError), snapshot: state.snapshot)
    }

    private func assertPreserved(_ saved: PreservedMedia, state: CameraAppState,
                                 file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(state.mediaItems, saved.items, file: file, line: line)
        XCTAssertEqual(state.latestMediaItem, saved.item, file: file, line: line)
        XCTAssertEqual(state.mediaPreviewItem, saved.item, file: file, line: line)
        XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes, file: file, line: line)
        XCTAssertEqual(state.downloadedFile(for: saved.item), saved.file, file: file, line: line)
        XCTAssertTrue(FileManager.default.fileExists(atPath: saved.file.path), file: file, line: line)
        XCTAssertEqual(state.lastError, saved.warning, file: file, line: line)
        XCTAssertEqual(state.snapshot, saved.snapshot, file: file, line: line)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .complete, file: file, line: line)
        XCTAssertTrue(state.isBusy(.media), file: file, line: line)
    }

    private func perform(_ operation: OwnershipOperation, state: CameraAppState, item: CameraMediaItem) async {
        switch operation {
        case .info: await state.loadMediaInfo(item)
        case .protection: await state.setMediaProtection(item, enabled: true, owner: state.mediaLibrarySessionID)
        case .rating: await state.setMediaRating(item, rating: 5, owner: state.mediaLibrarySessionID)
        case .rotation: await state.setMediaRotation(item, degrees: 270, owner: state.mediaLibrarySessionID)
        case .archive: await state.setMediaArchive(item, enabled: true, owner: state.mediaLibrarySessionID)
        case .delete: await state.deleteMedia(item)
        default: XCTFail("Unexpected app operation")
        }
    }

    private func makeState(_ peers: [MediaOwnershipPeer], bridge: Bool = true) -> CameraAppState {
        var remaining = peers
        let defaults = UserDefaults(suiteName: "MediaResponseOwnershipTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = remaining.removeFirst()
            return bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        })
        state.autoRefresh = false
        return state
    }

    private func connectAndLoad(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        await state.latestMediaTask?.value
        _ = try XCTUnwrap(state.latestMediaItem)
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
    }

    private func waitForGate(_ gate: String, peer: MediaOwnershipPeer) async throws {
        try await waitUntil { await peer.waiting(gate) }
    }

    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await condition() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected fixture gate or production state did not arrive")
        throw URLError(.timedOut)
    }

    private func uploadFile(_ name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(name)
        try MediaOwnershipPeer.imageBytes.write(to: file)
        return file
    }

    private func removeUpload(_ file: URL) { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
}

private enum OwnershipOperation: String, Sendable {
    case info, protection, rating, rotation, archive, delete, listing, upload, preview, thumbnail, download
    static let metadataAndInfo: [Self] = [.info, .protection, .rating, .rotation, .archive]
}

/// An in-memory peer for both direct CCAPI simulator and Bridge wire contracts.
/// Only synthetic files are changed. Events are cancelled immediately so no
/// unrelated polling or live-view work can race the controlled media requests.
private actor MediaOwnershipPeer: CameraHTTPTransport {
    static let imageBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 3, 4, 5, 0xFF, 0xD9])
    private struct Plan {
        let gate: String?
        let error: URLError.Code?
        let body: Data?
    }
    private var plans: [OwnershipOperation: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var operations: [OwnershipOperation] = []
    private var limits: [String?] = []
    private var item: CameraMediaItem
    private var uploaded: [CameraMediaItem] = []
    private let events: Bool
    private let acceptUpload: Bool
    private var eventContinuation: CheckedContinuation<CameraHTTPResponse, Never>?
    private var eventPollCount = 0

    init(rating: Int = 0, events: Bool = false, acceptUpload: Bool = true,
         listedSize: Int64? = Int64(MediaOwnershipPeer.imageBytes.count), folder: String = "original-folder") {
        self.events = events
        self.acceptUpload = acceptUpload
        item = CameraMediaItem(id: "collision", name: "COLLISION.JPG", kind: "image",
            sizeBytes: listedSize, previewAvailable: true,
            protected: false, rating: rating, rotationDegrees: 0, archived: false,
            contentType: "image/jpeg", folder: CameraMediaFolder(id: folder, label: folder))
    }

    func enqueue(_ operation: OwnershipOperation, gate: String? = nil, error: URLError.Code? = nil, body: Data? = nil) {
        plans[operation, default: []].append(Plan(gate: gate, error: error, body: body))
    }
    func currentItem() -> CameraMediaItem { item }
    func requestOperations() -> [OwnershipOperation] { operations }
    func count(_ operation: OwnershipOperation) -> Int { operations.filter { $0 == operation }.count }
    func listLimits() -> [String?] { limits }
    func mutationOperations() -> [OwnershipOperation] {
        operations.filter { [.protection, .rating, .rotation, .archive, .delete, .upload].contains($0) }
    }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func eventWaiting() -> Bool { eventContinuation != nil }
    func eventPolls() -> Int { eventPollCount }
    func emitContents() {
        let pending = eventContinuation
        eventContinuation = nil
        pending?.resume(returning: CameraHTTPResponse(statusCode: 200,
            body: Data("{\"changedKeys\":[\"contents\"]}".utf8)))
    }
    func releaseAll() {
        eventContinuation?.resume(returning: CameraHTTPResponse(statusCode: 200,
            body: Data("{\"changedKeys\":[]}".utf8)))
        eventContinuation = nil
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }
    private func take(_ operation: OwnershipOperation) -> Plan {
        operations.append(operation)
        return plans[operation]?.isEmpty == false
            ? plans[operation]!.removeFirst() : Plan(gate: nil, error: nil, body: nil)
    }
    private func finish(_ plan: Plan) async throws {
        if let gate = plan.gate { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let kind = query.first { $0.name == "kind" }?.value
        let simulator = path.hasPrefix("/ccapi")
        var operation: OwnershipOperation?
        var object: [String: Any] = [:]
        if path.hasSuffix("/events") {
            guard events else { throw CancellationError() }
            if method == "DELETE" {
                eventContinuation?.resume(returning: CameraHTTPResponse(statusCode: 200,
                    body: Data("{\"changedKeys\":[]}".utf8)))
                eventContinuation = nil
                return CameraHTTPResponse(statusCode: 200)
            }
            eventPollCount += 1
            return await withCheckedContinuation { eventContinuation = $0 }
        }
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "synthetic-owner", "engine": "ccapi"]
        } else if path.hasSuffix("/media") {
            operation = .listing
            limits.append(query.first { $0.name == "limit" }?.value)
            object = ["items": ([item] + uploaded).map { mediaJSON($0, simulator: simulator) }]
        } else if path.contains("/media/") {
            if method == "DELETE" { operation = .delete }
            else if method == "PUT" {
                let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                let action = simulator ? payload["action"] as! String : path.components(separatedBy: "/").last!
                switch action {
                case "protect", "protection": operation = .protection
                case "rating": operation = .rating
                case "rotate", "rotation": operation = .rotation
                case "archive": operation = .archive
                default: throw URLError(.unsupportedURL)
                }
                item = CameraMediaItem(id: item.id, name: item.name, kind: item.kind,
                    sizeBytes: item.sizeBytes, previewAvailable: true,
                    protected: operation == .protection ? true : item.protected,
                    rating: operation == .rating ? 5 : item.rating,
                    rotationDegrees: operation == .rotation ? 270 : item.rotationDegrees,
                    archived: operation == .archive ? true : item.archived, contentType: item.contentType)
                object = simulator ? [:] : mediaJSON(item, simulator: false)
            } else if kind == "thumbnail" || path.hasSuffix("/thumbnail") { operation = .thumbnail }
            else if kind == "display" || path.hasSuffix("/preview") { operation = .preview }
            else {
                operation = .info
                object = simulator
                    ? ["filesize": item.sizeBytes!, "protect": item.protected == true ? "enable" : "disable",
                       "rating": item.rating == 0 ? "off" : String(item.rating!), "rotate": String(item.rotationDegrees!),
                       "archive": item.archived == true ? "enable" : "disable", "contenttype": "image/jpeg"]
                    : mediaJSON(item, simulator: false)
            }
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic media owner", "serial": "SYNTHETIC-OWNER", "api": "fixture"]
        } else if path.hasSuffix("/capabilities") {
            object = simulator ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_PREVIEW",
                "MEDIA_DOWNLOAD", "MEDIA_UPLOAD", "MEDIA_DELETE", "MEDIA_PROTECT", "MEDIA_RATING", "MEDIA_ROTATE", "MEDIA_ARCHIVE"] + (events ? ["EVENT_POLLING"] : [])]
        } else if path.hasSuffix("/status") {
            object = ["connected": true, "recording": false, "mode": "Manual",
                "battery": [String: Any](), "media": [String: Any](), "exposure": [String: Any]()]
        }
        if let operation {
            let plan = take(operation)
            try await finish(plan)
            if let body = plan.body { return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"], body: body) }
            if operation == .delete { return CameraHTTPResponse(statusCode: 204) }
            if operation == .thumbnail || operation == .preview {
                return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Self.imageBytes)
            }
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        let plan = take(.download)
        try await finish(plan)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Self.imageBytes.write(to: file)
        return CameraHTTPDownloadResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], temporaryFileURL: file)
    }

    func upload(_ request: URLRequest, from fileURL: URL,
                progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPUploadResponse {
        let plan = take(.upload)
        let result = CameraMediaItem(id: "uploaded-\(fileURL.lastPathComponent)", name: fileURL.lastPathComponent,
            kind: "image", sizeBytes: Int64(Self.imageBytes.count), previewAvailable: true)
        if acceptUpload { uploaded.append(result) } // Independent of whether the response arrives.
        progress(CameraMediaTransferProgress(bytesTransferred: 1, totalBytes: result.sizeBytes))
        try await finish(plan)
        progress(CameraMediaTransferProgress(bytesTransferred: result.sizeBytes!, totalBytes: result.sizeBytes))
        return CameraHTTPUploadResponse(statusCode: 201,
            body: try JSONSerialization.data(withJSONObject: mediaJSON(result, simulator: false)))
    }

    private func mediaJSON(_ item: CameraMediaItem, simulator: Bool) -> [String: Any] {
        var json: [String: Any] = ["id": item.id, "name": item.name, "kind": item.kind,
            "previewAvailable": item.previewAvailable]
        json[simulator ? "size_bytes" : "sizeBytes"] = item.sizeBytes
        json[simulator ? "protect" : "protected"] = item.protected
        json["folderId"] = item.folder?.id
        json["folderLabel"] = item.folder?.label
        json["rating"] = item.rating
        json[simulator ? "rotate" : "rotationDegrees"] = item.rotationDegrees
        json[simulator ? "archive" : "archived"] = item.archived
        json[simulator ? "content_type" : "contentType"] = item.contentType
        return json
    }
}
