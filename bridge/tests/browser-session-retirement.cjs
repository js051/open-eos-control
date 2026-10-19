"use strict";
const assert = require("node:assert/strict");

async function retireFixtureSession(f, { maintenance = false } = {}) {
  // A closed browser context does not release the real Bridge's camera owner.
  await f.page.evaluate((maintenance) => {
    const originalFetch = window.fetch;
    window.__syntheticSessionRetirementReceipt = null;
    window.fetch = function (input, init) {
      const url = new URL(input instanceof Request ? input.url : String(input), location.href);
      const method = init?.method || (input instanceof Request ? input.method : "GET");
      const response = originalFetch.call(this, input, init);
      if (method !== "DELETE" || url.origin !== location.origin ||
          !(maintenance ? /^\/v1\/session\/[^/]+\/maintenance\/control\/session$/ : /^\/v1\/session\/[^/]+$/).test(url.pathname)) return response;
      window.fetch = originalFetch;
      return response.then(async received => {
        // Fetch defines 204 as a null body; CDP may expose no resource body at all.
        // Read an independent clone through native Fetch, leaving the app's response intact.
        const bytes = (await received.clone().arrayBuffer()).byteLength;
        window.__syntheticSessionRetirementReceipt = { status: received.status, bytes };
        return received;
      });
    };
  }, maintenance);
  await f.page.click("#disconnect-button");
  await f.page.waitForFunction(() => window.__syntheticSessionRetirementReceipt !== null);
  const receipt = await f.page.evaluate(() => window.__syntheticSessionRetirementReceipt);
  assert.equal(receipt.status, 204);
  assert.equal(receipt.bytes, 0);
  await f.page.waitForSelector("#connection-view:not([hidden])");
  assert.equal(await f.page.locator("#connection-error").isHidden(), true);
}
module.exports = { retireFixtureSession };
