(function (root, factory) {
  const mediaLibrary = factory();
  if (typeof module === "object" && module.exports) module.exports = mediaLibrary;
  root.OpenEOSMediaLibrary = mediaLibrary;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const VIDEO_EXTENSIONS = new Set(["mp4", "mov", "m4v", "avi", "mkv"]);

  function isVideo(item) {
    if (String(item?.kind || "").toLowerCase() === "video") return true;
    if (String(item?.contentType || "").toLowerCase().startsWith("video/")) return true;
    const name = String(item?.name || "");
    const extension = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
    return VIDEO_EXTENSIONS.has(extension);
  }

  function mediaTime(item) {
    const raw = String(item?.captureTime || "").trim();
    if (!raw) return null;
    let normalized = raw;
    const compact = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$/.exec(raw);
    if (compact) {
      normalized = `${compact[1]}-${compact[2]}-${compact[3]}T${compact[4]}:${compact[5]}:${compact[6]}`;
    } else if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(raw)) {
      normalized = raw.replace(" ", "T");
    }
    const value = Date.parse(normalized);
    return Number.isFinite(value) ? value : null;
  }

  function naturalName(left, right, locale) {
    return String(left?.name || "").localeCompare(String(right?.name || ""), locale, {
      numeric: true,
      sensitivity: "base",
    });
  }

  function itemsForDisplay(items, filter, sort, locale) {
    const filtered = items.filter((item) => {
      if (filter === "video") return isVideo(item);
      if (filter === "photo") return !isVideo(item);
      return true;
    });
    if (sort === "camera") return filtered;
    return filtered.map((item, index) => ({ item, index })).sort((leftEntry, rightEntry) => {
      const left = leftEntry.item;
      const right = rightEntry.item;
      const nameOrder = naturalName(left, right, locale);
      if (sort === "name") {
        return nameOrder || String(left.id).localeCompare(String(right.id), locale, { numeric: true });
      }
      const leftTime = mediaTime(left);
      const rightTime = mediaTime(right);
      if (leftTime === null && rightTime !== null) return 1;
      if (leftTime !== null && rightTime === null) return -1;
      if (leftTime !== null && rightTime !== null && leftTime !== rightTime) {
        return sort === "oldest" ? leftTime - rightTime : rightTime - leftTime;
      }
      return leftEntry.index - rightEntry.index;
    }).map(({ item }) => item);
  }

  function page(items, index, size) {
    if (!Number.isSafeInteger(size) || size <= 0) throw new RangeError("Page size must be positive");
    const pageCount = Math.max(1, Math.ceil(items.length / size));
    const pageIndex = Math.min(Math.max(Number.isSafeInteger(index) ? index : 0, 0), pageCount - 1);
    const start = pageIndex * size;
    return {
      items: items.slice(start, start + size),
      pageIndex,
      pageCount,
      start: items.length ? start + 1 : 0,
      end: Math.min(start + size, items.length),
      total: items.length,
    };
  }

  function setBounded(map, key, value, capacity) {
    if (!(map instanceof Map)) throw new TypeError("A Map is required");
    if (!Number.isSafeInteger(capacity) || capacity <= 0) throw new RangeError("Capacity must be positive");
    const evicted = [];
    if (map.has(key)) evicted.push([key, map.get(key)]);
    map.delete(key);
    map.set(key, value);
    while (map.size > capacity) {
      const oldestKey = map.keys().next().value;
      evicted.push([oldestKey, map.get(oldestKey)]);
      map.delete(oldestKey);
    }
    return evicted;
  }

  function touch(map, key) {
    if (!(map instanceof Map)) throw new TypeError("A Map is required");
    if (!map.has(key)) return false;
    const value = map.get(key);
    map.delete(key);
    map.set(key, value);
    return true;
  }

  return { isVideo, mediaTime, naturalName, itemsForDisplay, page, setBounded, touch };
});
