(function (root, factory) {
  const mediaLibrary = factory();
  if (typeof module === "object" && module.exports) module.exports = mediaLibrary;
  root.OpenEOSMediaLibrary = mediaLibrary;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const VIDEO_EXTENSIONS = new Set(["mp4", "mov", "m4v", "avi", "mkv"]);
  const PHOTO_EXTENSIONS = new Set(["jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "avif", "cr2", "cr3", "crw", "dng"]);
  const AUDIO_EXTENSIONS = new Set(["wav", "mp3", "aac", "m4a", "flac", "ogg", "opus", "aif", "aiff", "wma"]);
  const MIME_TYPE = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/;
  const CALENDAR_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
  const ISO_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?$/;
  const PTP_DATE_TIME = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$/;
  const FOLDER_CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/;

  function asciiLower(value) { return value.replace(/[A-Z]/g, (letter) => letter.toLowerCase()); }

  function mediaExtension(item) {
    const name = typeof item?.name === "string" ? item.name : "";
    return name.includes(".") ? asciiLower(name.split(".").pop()) : "";
  }

  function normalizedMediaMime(value) {
    if (value === null || value === undefined) return "";
    if (typeof value !== "string" || FOLDER_CONTROL_CHARACTERS.test(value)) return null;
    const mime = asciiLower(value.split(";", 1)[0].trim());
    return !mime || MIME_TYPE.test(mime) ? mime : null;
  }

  function classifyMedia(item) {
    if (typeof item?.name !== "string" || !item.name.trim()) return "unknown";
    const mime = normalizedMediaMime(item.contentType);
    if (mime === null) return "unknown";
    const kind = typeof item.kind === "string" ? asciiLower(item.kind.trim()) : "";
    const families = new Set();
    if (["image", "photo", "jpeg", "jpg", "raw"].includes(kind)) families.add("photo");
    else if (["video", "movie"].includes(kind)) families.add("video");
    else if (kind === "audio") families.add("audio");
    else if (["text", "document"].includes(kind)) families.add("other");
    if (mime && mime !== "application/octet-stream") {
      families.add(mime.startsWith("image/") ? "photo" : mime.startsWith("video/") ? "video" :
        mime.startsWith("audio/") ? "audio" : "other");
    }
    const extension = mediaExtension(item);
    if (PHOTO_EXTENSIONS.has(extension)) families.add("photo");
    else if (VIDEO_EXTENSIONS.has(extension)) families.add("video");
    else if (AUDIO_EXTENSIONS.has(extension)) families.add("audio");
    return families.size > 1 ? "conflict" : families.values().next().value || "unknown";
  }

  function isVideo(item) { return classifyMedia(item) === "video"; }
  function isPhoto(item) { return classifyMedia(item) === "photo"; }
  function canRequestThumbnail(item) { return isPhoto(item) || isVideo(item); }

  function automaticJpegEligible(item) {
    if (typeof item?.id !== "string" || !item.id.trim() || item.kind !== "image" || !isPhoto(item) ||
        (/[\\/]/.test(item.name) || item.name.lastIndexOf(".") <= 0 || !["jpg", "jpeg"].includes(mediaExtension(item)))) return false;
    // Deliberately stricter than display normalization: don't enlarge any
    // platform's former admission with aliases, blank declarations or guesses.
    return item.contentType === null || item.contentType === undefined || item.contentType === "" ||
      normalizedMediaMime(item.contentType) === "image/jpeg";
  }

  function freshAutomaticJpegMetadata(original, returned, fresh) {
    return fresh === true && automaticJpegEligible(original) && automaticJpegEligible(returned) &&
      original.id === returned.id && original.name === returned.name;
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

  const zoneFormatters = new Map();

  function currentDisplayZone() {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  }

  function zonedCalendar(date, displayZone) {
    try {
      let formatter = zoneFormatters.get(displayZone);
      if (!formatter) {
        formatter = new Intl.DateTimeFormat("en-GB-u-ca-gregory-nu-latn", {
          timeZone: displayZone, year: "numeric", month: "2-digit", day: "2-digit",
          hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23", era: "short",
        });
        if (zoneFormatters.size >= 16) zoneFormatters.delete(zoneFormatters.keys().next().value);
        zoneFormatters.set(displayZone, formatter);
      }
      const parts = Object.fromEntries(formatter.formatToParts(date).map(({ type, value }) => [type, value]));
      const values = [parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second].map(Number);
      if (parts.era !== "AD" || !validCalendarDate(...values.slice(0, 3))) return null;
      return values;
    } catch (_) { return null; }
  }

  function parseMediaDate(value, displayZone = currentDisplayZone()) {
    if (typeof value !== "string") return null;
    const raw = value.trim();
    if (raw.length > 128) return null;
    const named = /^(.*)\[([A-Za-z0-9_+./-]{1,64})\]$/.exec(raw);
    const source = named ? named[1] : raw;
    const calendar = CALENDAR_DATE.exec(source);
    if (calendar) {
      if (named) return null;
      const [, year, month, day] = calendar.map(Number);
      if (!validCalendarDate(year, month, day)) return null;
      // This Date is a UTC formatting carrier only. It is never an instant key.
      return { kind: "date-only", date: utcDate(year, month, day), timestamp: null,
        dateKey: source, timeKey: null, timeOrder: null, hasTime: false, formatZone: "UTC" };
    }
    const parts = ISO_DATE_TIME.exec(source) || PTP_DATE_TIME.exec(source);
    if (!parts) return null;
    const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction, zone] = parts;
    const [year, month, day, hour, minute, second] = [yearText, monthText, dayText, hourText, minuteText, secondText || "0"].map(Number);
    if (!validCalendarDate(year, month, day) || hour > 23 || minute > 59 || second > 59 || (named && !zone)) return null;
    const millisecond = Number((fraction || "").padEnd(3, "0").slice(0, 3));
    let components = [year, month, day, hour, minute, second];
    let date = utcDate(...components, millisecond);
    let timestamp = null;
    if (zone) {
      let offsetMinutes = 0;
      if (zone !== "Z") {
        const offset = zone.slice(1).replace(":", "");
        const offsetHour = Number(offset.slice(0, 2));
        const offsetMinute = Number(offset.slice(2));
        if (offsetHour > 18 || offsetMinute > 59 || (offsetHour === 18 && offsetMinute !== 0)) return null;
        offsetMinutes = (offsetHour * 60 + offsetMinute) * (zone[0] === "-" ? -1 : 1);
      }
      date = new Date(date.getTime() - offsetMinutes * 60_000);
      if (date.getUTCFullYear() < 1 || date.getUTCFullYear() > 9999) return null;
      timestamp = date.getTime();
      if (named) {
        const namedCalendar = zonedCalendar(date, named[2]);
        if (!namedCalendar || utcDate(...namedCalendar, millisecond).getTime() - timestamp !== offsetMinutes * 60_000) return null;
      }
      components = zonedCalendar(date, displayZone);
      if (!components) return null;
    }
    const [displayYear, displayMonth, displayDay, displayHour, displayMinute, displaySecond] = components;
    const pad = (number, length = 2) => String(number).padStart(length, "0");
    return { kind: zone ? "instant" : "floating", date, timestamp,
      dateKey: `${pad(displayYear, 4)}-${pad(displayMonth)}-${pad(displayDay)}`,
      timeKey: `${pad(displayHour)}:${pad(displayMinute)}:${pad(displaySecond)}.${pad(millisecond, 3)}`,
      timeOrder: timestamp ?? ((hour * 60 + minute) * 60 + second) * 1000 + millisecond,
      hasTime: true, formatZone: zone ? displayZone : "UTC" };
  }

  function mediaDateForItem(item, displayZone = currentDisplayZone()) {
    return parseMediaDate(item?.captureTime, displayZone);
  }

  function mediaTime(item) {
    // Only an explicitly offset source may become an absolute timestamp.
    return mediaDateForItem(item)?.timestamp ?? null;
  }

  function selectReviewItem(items, knownIds = new Set(), kind = "recent") {
    if (!["recent", "photo", "video"].includes(kind)) return null;
    return (Array.isArray(items) ? items : []).find((item) =>
      !knownIds.has(item.id) && (kind === "recent" || (kind === "photo" ? isPhoto(item) : isVideo(item))),
    ) || null;
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

  function countUnknownDates(items, displayZone = currentDisplayZone()) {
    return items.reduce((count, item) => count + (mediaDateForItem(item, displayZone) === null ? 1 : 0), 0);
  }

  function normalizeRating(value) {
    // Missing or malformed evidence is unknown, never an implicit zero rating.
    return Number.isInteger(value) && value >= 0 && value <= 5 ? value : null;
  }

  function ratingMatchesFilter(value, filter = "all") {
    const rating = normalizeRating(value);
    switch (filter) {
      case "all": return true;
      case "unrated": return rating === 0;
      case "1-plus": return rating !== null && rating >= 1;
      case "2-plus": return rating !== null && rating >= 2;
      case "3-plus": return rating !== null && rating >= 3;
      case "4-plus": return rating !== null && rating >= 4;
      case "5": return rating === 5;
      case "unknown": return rating === null;
      default: return false;
    }
  }

  function countUnknownRatings(items) {
    return items.reduce((count, item) => count + (normalizeRating(item?.rating) === null ? 1 : 0), 0);
  }

  function validFolderValue(value, maximumLength) {
    // String.length is the Bridge contract's UTF-16 code-unit length. Validate
    // without normalizing: an opaque ID's whitespace and encoding are identity.
    return typeof value === "string" && value.length <= maximumLength &&
      value.trim().length > 0 && !FOLDER_CONTROL_CHARACTERS.test(value);
  }

  function folderForItem(item) {
    const id = item?.folderId;
    const label = item?.folderLabel;
    // A partial pair is unknown. Filenames, media IDs and host paths are not
    // camera folder evidence and must never fill these optional fields.
    if (!validFolderValue(id, 4096) || !validFolderValue(label, 1024)) return null;
    return { id, label };
  }

  function folderOptions(items) {
    const folders = new Map();
    for (const item of items) {
      const folder = folderForItem(item);
      if (!folder) continue;
      const existing = folders.get(folder.id);
      if (!existing) {
        folders.set(folder.id, { ...folder, count: 1, labelConflict: false });
        continue;
      }
      existing.count += 1;
      if (existing.label !== folder.label) {
        existing.labelConflict = true;
        // Retain an observed label deterministically; conflicting names neither
        // change the authoritative ID nor establish any inferred folder path.
        if (folder.label < existing.label) existing.label = folder.label;
      }
    }
    const compare = (left, right) => left < right ? -1 : left > right ? 1 : 0;
    return [...folders.values()].sort((left, right) =>
      compare(left.label, right.label) || compare(left.id, right.id));
  }

  function countUnknownFolders(items) {
    return items.reduce((count, item) => count + (folderForItem(item) === null ? 1 : 0), 0);
  }

  function folderMatchesFilter(item, filter = { kind: "all" }) {
    if (!filter || typeof filter !== "object" || Array.isArray(filter)) return false;
    if (filter.kind === "all") return true;
    if (filter.kind === "unknown") return folderForItem(item) === null;
    if (filter.kind !== "folder" || !validFolderValue(filter.id, 4096)) return false;
    return folderForItem(item)?.id === filter.id;
  }

  function naturalName(left, right, locale) {
    return String(left?.name || "").localeCompare(String(right?.name || ""), locale, {
      numeric: true,
      sensitivity: "base",
    });
  }

  function itemsForDisplay(items, filter, sort, locale, range = null, ratingFilter = "all", folderFilter = { kind: "all" }, displayZone = currentDisplayZone()) {
    const activeRange = range === null ? null : validateDateRange(range);
    if (activeRange && !activeRange.valid) throw new RangeError(`Invalid media date range: ${activeRange.error}`);
    const needsDates = activeRange || sort === "newest" || sort === "oldest";
    const dates = new Map(needsDates ? items.map((item) => [item, mediaDateForItem(item, displayZone)]) : []);
    const filtered = items.filter((item) => {
      if (filter === "video" && !isVideo(item)) return false;
      if (filter === "photo" && !isPhoto(item)) return false;
      if (!ratingMatchesFilter(item?.rating, ratingFilter)) return false;
      if (!folderMatchesFilter(item, folderFilter)) return false;
      if (activeRange) {
        const date = dates.get(item);
        if (!date || date.dateKey < activeRange.range.start || date.dateKey > activeRange.range.end) return false;
      }
      return true;
    });
    if (sort === "camera") return filtered;
    return filtered.map((item, index) => ({ item, index })).sort((leftEntry, rightEntry) => {
      const left = leftEntry.item;
      const right = rightEntry.item;
      if (sort === "rating-highest" || sort === "rating-lowest") {
        const leftRating = normalizeRating(left?.rating);
        const rightRating = normalizeRating(right?.rating);
        if (leftRating === null && rightRating !== null) return 1;
        if (leftRating !== null && rightRating === null) return -1;
        if (leftRating !== null && rightRating !== null && leftRating !== rightRating) {
          return sort === "rating-lowest" ? leftRating - rightRating : rightRating - leftRating;
        }
        return leftEntry.index - rightEntry.index;
      }
      const nameOrder = naturalName(left, right, locale);
      if (sort === "name") {
        return nameOrder || String(left.id).localeCompare(String(right.id), locale, { numeric: true });
      }
      const leftDate = dates.get(left);
      const rightDate = dates.get(right);
      if (!leftDate && rightDate) return 1;
      if (leftDate && !rightDate) return -1;
      if (leftDate && rightDate) {
        const direction = sort === "oldest" ? 1 : -1;
        if (leftDate.dateKey !== rightDate.dateKey) {
          return direction * (leftDate.dateKey < rightDate.dateKey ? -1 : 1);
        }
        // A total tuple order: never compare a fabricated midnight with an instant.
        const precision = { instant: 0, floating: 1, "date-only": 2 };
        const precisionOrder = precision[leftDate.kind] - precision[rightDate.kind];
        if (precisionOrder) return precisionOrder;
        if (leftDate.timeOrder !== rightDate.timeOrder) return direction * (leftDate.timeOrder - rightDate.timeOrder);
      }
      // Equal precision/time and all unknown values retain camera response order.
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
    const extension = name.includes(".") ? asciiLower(name.split(".").pop()) : "";
    if (extension === "mp4") return "MP4";
    if (extension === "mov") return "QuickTime MOV";
    if (extension === "m4v") return "M4V";
    if (extension === "avi") return "AVI";
    if (extension === "mkv") return "Matroska MKV";
    return extension ? extension.toUpperCase() : "VIDEO";
  }

  return {
    classifyMedia,
    normalizedMediaMime,
    isVideo,
    isPhoto,
    canRequestThumbnail,
    automaticJpegEligible,
    freshAutomaticJpegMetadata,
    parseMediaDate,
    mediaDateForItem,
    mediaTime,
    selectReviewItem,
    validateDateRange,
    countUnknownDates,
    normalizeRating,
    ratingMatchesFilter,
    countUnknownRatings,
    folderForItem,
    folderOptions,
    countUnknownFolders,
    folderMatchesFilter,
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
