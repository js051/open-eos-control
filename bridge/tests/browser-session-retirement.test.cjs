"use strict";
const assert = require("node:assert/strict");
const http = require("node:http");
const vm = require("node:vm");
const { test } = require("node:test");
const { retireFixtureSession } = require("./browser-session-retirement.cjs");

for (const [maintenance, status, body] of [[false, 204, ""], [true, 204, ""], [false, 200, "unexpected"], [true, 403, ""]]) {
  test(`native Fetch retirement: maintenance=${maintenance}, status=${status}`, async () => {
    const requests = [];
    const server = http.createServer((request, response) => {
      requests.push({ method: request.method, path: request.url });
      response.writeHead(status); response.end(body);
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    try {
      const origin = `http://127.0.0.1:${server.address().port}`;
      const route = `/v1/session/synthetic${maintenance ? "/maintenance/control/session" : ""}`;
      const window = { fetch };
      const sandbox = vm.createContext({ window, location: { href: origin, origin }, URL, Request });
      let productBody, closed = false;
      const page = {
        evaluate: (callback, argument) => vm.runInContext(`(${callback.toString()})(${JSON.stringify(argument) ?? "undefined"})`, sandbox),
        async click(selector) {
          assert.equal(selector, "#disconnect-button");
          const response = await window.fetch(origin + route, { method: "DELETE" });
          assert.equal(response.bodyUsed, false, "observer must not consume the product response");
          productBody = await response.text();
        },
        async waitForFunction(callback) { assert.equal(await this.evaluate(callback), true); },
        async waitForSelector(selector) { assert.equal(selector, "#connection-view:not([hidden])"); closed = true; },
        locator(selector) { assert.equal(selector, "#connection-error"); return { isHidden: async () => true }; },
      };
      if (status === 204) await retireFixtureSession({ page }, { maintenance });
      else await assert.rejects(retireFixtureSession({ page }, { maintenance }), assert.AssertionError);
      assert.equal(closed, status === 204);
      assert.equal(productBody, body);
      assert.equal(window.fetch, fetch, "restore the exact original Fetch");
      assert.deepEqual(requests, [{ method: "DELETE", path: route }]);
    } finally { await new Promise(resolve => server.close(resolve)); }
  });
}
