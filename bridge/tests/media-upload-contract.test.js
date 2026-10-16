"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const upload = require("../open_eos_bridge/static/media-upload.js");
const contract = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/media-upload/v1/upload-outcome-vectors.json"), "utf8"));
const file = { name: "IMG_0001.JPG", size: 1234 };
const receipt = JSON.stringify({ id: "media-upload-7", name: file.name, kind: "photo", sizeBytes: file.size });
function admitted() {
  const session = { id: "synthetic-session" }, owner = upload.createOwner(session);
  assert.equal(upload.admit(owner, file, { session, currentOwner: owner }), true);
  return owner;
}
for (const row of contract.outcomeCases) {
  test(`shared outcome: ${row.id}`, () => {
    const owner = admitted();
    if (row.dispatchStarted) upload.dispatch(owner);
    if (row.verifiedReceiptForAttempt) upload.observeReceipt(owner, { status: 201, text: receipt });
    if (row.cancellationRequested) upload.cancel(owner);
    // Listing observations are deliberately not receipt inputs. The app test
    // separately exercises loaded collision rows through cancellation callbacks.
    owner.listingObservation = row.listingObservation;
    assert.equal(upload.outcome(owner), row.expectedOutcome);
    assert.equal(upload.dispatch(owner), !row.dispatchStarted && !row.cancellationRequested);
    assert.equal(upload.dispatch(owner), false, "an attempt cannot dispatch twice");
  });
}
for (const row of contract.admissionCases) {
  test(`shared admission: ${row.id}`, () => {
    const session = { id: "synthetic-reusable-id" }, owner = upload.createOwner(session);
    owner.consumed = !row.singleUseAvailable;
    const currentSession = row.currentOwner === row.openingOwner ? session : row.currentOwner ? { id: session.id } : null;
    const currentOwner = row.currentToken === row.openingToken ? owner : row.currentToken ? upload.createOwner(currentSession) : null;
    assert.equal(upload.admit(owner, row.sourceSelected ? file : null,
      { session: currentSession, currentOwner, supported: row.featureSupported, busy: row.mediaBusy }), row.expectedAdmit);
  });
}
for (const row of contract.publicationCases) {
  test(`shared publication: ${row.id}`, () => {
    const session = { id: "synthetic-reusable-id" }, owner = upload.createOwner(session);
    const currentSession = row.currentOwner === row.attemptOwner ? session : row.currentOwner ? { id: session.id } : null;
    const currentOwner = row.currentToken === row.attemptToken ? owner : row.currentToken ? upload.createOwner(currentSession) : null;
    assert.equal(upload.canPublish(owner, currentOwner, currentSession), row.expectedPublish);
  });
}
for (const row of contract.receiptCases) {
  test(`shared raw receipt: ${row.id}`, () => {
    const session = {}, owner = upload.createOwner(session);
    upload.admit(owner, { name: row.expectedFilename, size: row.expectedSizeBytes }, { session, currentOwner: owner });
    upload.dispatch(owner);
    assert.equal(upload.observeReceipt(owner, { status: row.statusCode, text: row.rawResponseJSON, sameRequest: row.boundToAttempt }), row.expectedVerified);
    assert.equal(upload.outcome(owner), row.expectedVerified ? "confirmed" : "uncertain");
    if (row.expectedVerified) assert.equal(owner.receipt.id, JSON.parse(row.rawResponseJSON).id, "opaque identity stays exact");
  });
}
for (const row of contract.journeyCases) {
  test(`shared production owner journey: ${row.id}`, () => {
    let owner, currentOwner, session, currentSession, dispatches = 0;
    for (const event of row.events) {
      if (event === "open") { session = currentSession = {}; owner = currentOwner = upload.createOwner(session); }
      if (event === "picker-result" || event === "repeated-picker-result") upload.admit(owner, file, { session: currentSession, currentOwner });
      if (event === "dispatch" && upload.dispatch(owner)) dispatches += 1;
      if (["cancel", "picker-cancel", "stage-failure"].includes(event)) upload.cancel(owner);
      if (event === "reconnect") { upload.cancel(owner, { retire: true }); currentSession = {}; currentOwner = null; }
      if (event === "verified-receipt") upload.observeReceipt(owner, { status: 201, text: receipt });
      if (event === "invalid-receipt") upload.observeReceipt(owner, { status: 201, text: "{}" });
      // Stage/list/refresh/network events do not claim a receipt or replay IO.
    }
    assert.equal(dispatches, row.expectedDispatches);
    assert.equal(upload.outcome(owner), row.expectedOutcome);
    assert.equal(upload.outcome(owner) === "confirmed" && upload.canPublish(owner, currentOwner, currentSession), row.mayPublishConfirmationForCurrentOwner);
    assert.equal(upload.dispatch(owner), false, "no implicit replay after a terminal journey");
    assert.equal(row.automaticReplayCount, 0);
  });
}
