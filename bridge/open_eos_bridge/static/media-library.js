(function (root, factory) {
  const mediaLibrary = factory();
  if (typeof module === "object" && module.exports) module.exports = mediaLibrary;
  root.OpenEOSMediaLibrary = mediaLibrary;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const VIDEO_EXTENSIONS = new Set(["mp4", "mov", "m4v", "avi", "mkv"]);
  const CALENDAR_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
  const ISO_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?$/;
  const PTP_DATE_TIME = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$/;

  function isVideo(item) {
    if (String(item?.kind || "").toLowerCase() === "video") return true;
    if (String(item?.contentType || "").toLowerCase().startsWith("video/")) return true;
    const name = String(item?.name || "");
    const extension = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
    return VIDEO_EXTENSIONS.has(extension);
  }

  function validCalendarDate(year, month, day) {
    if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1) return false;
    const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
    const monthDays = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    return day <= monthDays[month - 1];
  }

  function utcDate(year, month, day, hour = 0, minute = 0, second = 0, millisecond = 0) {
    // setUTCFullYear avoids Date.UTC's special interpretation of years 00–99.
    const date = new Date(0);
    date.setUTCFullYear(year, month - 1, day);
    date.setUTCHours(hour, minute, second, millisecond);
    return date;
  }

  function localDateKey(date) {
    return [date.getFullYear(), date.getMonth() + 1, date.getDate()]
      .map((part, index) => String(part).padStart(index === 0 ? 4 : 2, "0"))
      .join("-");
  }

  function parseMediaDate(value) {
    if (typeof value !== "string") return null;
    const raw = value.trim();
    const calendar = CALENDAR_DATE.exec(raw);
    if (calendar) {
      const [, year, month, day] = calendar.map(Number);
      if (!validCalendarDate(year, month, day)) return null;
      // A date-only value is a literal day, not a camera or browser timezone.
      // Format this carrier with timeZone: "UTC" and omit its invented midnight.
      const date = utcDate(year, month, day);
      return { date, timestamp: date.getTime(), dateKey: raw, hasTime: false };
    }
    const parts = ISO_DATE_TIME.exec(raw) || PTP_DATE_TIME.exec(raw);
    if (!parts) return null;
    const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction, zone] = parts;
    const [year, month, day, hour, minute, second] = [yearText, monthText, dayText, hourText, minuteText, secondText || "0"].map(Number);
    if (!validCalendarDate(year, month, day) || hour > 23 || minute > 59 || second > 59) return null;
    const millisecond = Number((fraction || "").padEnd(3, "0").slice(0, 3));
    let date;
    if (zone) {
      let offsetMinutes = 0;
      if (zone !== "Z") {
        const offset = zone.slice(1).replace(":", "");
        const offsetHour = Number(offset.slice(0, 2));
        const offsetMinute = Number(offset.slice(2));
        if (offsetHour > 23 || offsetMinute > 59) return null;
        offsetMinutes = (offsetHour * 60 + offsetMinute) * (zone[0] === "-" ? -1 : 1);
      }
      date = new Date(utcDate(year, month, day, hour, minute, second, millisecond).getTime() - offsetMinutes * 60_000);
    } else {
      // Floating camera values retain the browser's existing local-time and DST
      // interpretation; they do not establish the camera's actual timezone.
      date = new Date(0);
      date.setFullYear(year, month - 1, day);
      date.setHours(hour, minute, second, millisecond);
    }
    return { date, timestamp: date.getTime(), dateKey: localDateKey(date), hasTime: true };
  }

  function mediaDateForItem(item) {
    return parseMediaDate(item?.captureTime);
  }

  function mediaTime(item) {
    return mediaDateForItem(item)?.timestamp ?? null;
  }

  function validateDateRange(value) {
    const start = value?.start;
    const end = value?.end;
    if (start === undefined || start === null || start === "" || end === undefined || end === null || end === "") {
      return { valid: false, range: null, error: "required" };
    }
    if (typeof start !== "string" || typeof end !== "string" ||
        !CALENDAR_DATE.test(start) || !CALENDAR_DATE.test(end) || !parseMediaDate(start) || !parseMediaDate(end)) {
      return { valid: false, range: null, error: "invalid" };
    }
    if (start > end) return { valid: false, range: null, error: "reversed" };
    return { valid: true, range: { start, end }, error: null };
  }

  function countUnknownDates(items) {
    return items.reduce((count, item) => count + (mediaDateForItem(item) === null ? 1 : 0), 0);
  }

  function naturalName(left, right, locale) {
    return String(left?.name || "").localeCompare(String(right?.name || ""), locale, {
      numeric: true,
      sensitivity: "base",
    });
  }

  function itemsForDisplay(items, filter, sort, locale, range = null) {
    const activeRange = range === null ? null : validateDateRange(range);
    if (activeRange && !activeRange.valid) throw new RangeError(`Invalid media date range: ${activeRange.error}`);
    const filtered = items.filter((item) => {
      if (filter === "video" && !isVideo(item)) return false;
      if (filter === "photo" && isVideo(item)) return false;
      if (activeRange) {
        const date = mediaDateForItem(item);
        if (!date || date.dateKey < activeRange.range.start || date.dateKey > activeRange.range.end) return false;
      }
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
      const leftDate = mediaDateForItem(left);
      const rightDate = mediaDateForItem(right);
      const leftTime = leftDate?.timestamp ?? null;
      const rightTime = rightDate?.timestamp ?? null;
      if (leftTime === null && rightTime !== null) return 1;
      if (leftTime !== null && rightTime === null) return -1;
      // Date-only carriers use UTC but represent literal days. Group by the
      // displayed day before comparing instants so those groups cannot split.
      if (leftDate && rightDate && leftDate.dateKey !== rightDate.dateKey) {
        const dayOrder = leftDate.dateKey < rightDate.dateKey ? -1 : 1;
        return sort === "oldest" ? dayOrder : -dayOrder;
      }
      if (leftTime !== null && rightTime !== null && leftTime !== rightTime) {
        return sort === "oldest" ? leftTime - rightTime : rightTime - leftTime;
      }
      if (leftTime === null && rightTime === null) {
        const stableNameOrder = nameOrder || String(left.id).localeCompare(String(right.id), locale, { numeric: true });
        return sort === "oldest" ? stableNameOrder : -stableNameOrder;
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

  function imagePanBounds(scale, viewport, image) {
    const normalizedScale = Number(scale);
    const viewportWidth = Number(viewport?.width);
    const viewportHeight = Number(viewport?.height);
    const imageWidth = Number(image?.width);
    const imageHeight = Number(image?.height);
    if (
      normalizedScale <= 1 ||
      ![normalizedScale, viewportWidth, viewportHeight, imageWidth, imageHeight]
        .every((value) => Number.isFinite(value) && value > 0)
    ) return { x: 0, y: 0 };
    const fit = Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight);
    return {
      x: Math.max(0, (imageWidth * fit * normalizedScale - viewportWidth) / 2),
      y: Math.max(0, (imageHeight * fit * normalizedScale - viewportHeight) / 2),
    };
  }

  function clampImagePan(proposed, scale, viewport, image) {
    const bounds = imagePanBounds(scale, viewport, image);
    const x = Number.isFinite(Number(proposed?.x)) ? Number(proposed.x) : 0;
    const y = Number.isFinite(Number(proposed?.y)) ? Number(proposed.y) : 0;
    return {
      x: Math.min(Math.max(x, -bounds.x), bounds.x),
      y: Math.min(Math.max(y, -bounds.y), bounds.y),
    };
  }

  function videoPlaybackFailure(errorCode) {
    const code = Number(errorCode);
    if (code === 3 || code === 4) return "codec";
    return "transport";
  }

  function videoContainerLabel(filename) {
    const name = String(filename || "");
    const extension = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
    if (extension === "mp4") return "MP4";
    if (extension === "mov") return "QuickTime MOV";
    if (extension === "m4v") return "M4V";
    if (extension === "avi") return "AVI";
    if (extension === "mkv") return "Matroska MKV";
    return extension ? extension.toUpperCase() : "VIDEO";
  }

  return {
    isVideo,
    parseMediaDate,
    mediaDateForItem,
    mediaTime,
    validateDateRange,
    countUnknownDates,
    naturalName,
    itemsForDisplay,
    page,
    setBounded,
    touch,
    imagePanBounds,
    clampImagePan,
    videoPlaybackFailure,
    videoContainerLabel,
  };
});
