const LYRICS = [];
const TRACK_INFO = {
  title: "",
  author: "",
  album: "",
  cover: "",
  durationHuman: "",
};
let duration = 0;
let progressPosition = 0;
let activeIndex = 0;

const LYRICS_LINES_CHANGE_EVENT = "lyrics-lines-change";
const LYRICS_TRACK_CHANGE_EVENT = "lyrics-track-change";
const LYRICS_PROGRESS_CHANGE_EVENT = "lyrics-progress-change";

function notifyLyricsLinesChanged() {
  window.dispatchEvent(new Event(LYRICS_LINES_CHANGE_EVENT));
}

function notifyLyricsTrackChanged() {
  window.dispatchEvent(new Event(LYRICS_TRACK_CHANGE_EVENT));
}

function notifyLyricsProgressChanged() {
  window.dispatchEvent(new Event(LYRICS_PROGRESS_CHANGE_EVENT));
}

function normalizeTrackText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function hasTrackMetadata(data) {
  return !!data && (
    normalizeTrackText(data.title) !== "" ||
    normalizeTrackText(data.author) !== "" ||
    normalizeTrackText(data.album) !== "" ||
    normalizeTrackText(data.cover) !== ""
  );
}

function hasTrackFields(data) {
  return !!data && (
    typeof data.title === "string" ||
    typeof data.author === "string" ||
    typeof data.album === "string" ||
    typeof data.cover === "string" ||
    typeof data.durationHuman === "string"
  );
}

function isEmptyTrack(data) {
  return !!data &&
    !hasTrackMetadata(data) &&
    typeof data.duration === "number" &&
    data.duration <= 0;
}

function clearLyricsData() {
  if (LYRICS.length === 0) {
    return false;
  }
  LYRICS.length = 0;
  activeIndex = 0;
  return true;
}

function updateTrackInfo(data) {
  if (!data) return false;

  let changed = false;
  for (const key of ["title", "author", "album", "cover", "durationHuman"]) {
    if (!Object.prototype.hasOwnProperty.call(data, key)) continue;

    const nextValue = normalizeTrackText(data[key]);
    if (TRACK_INFO[key] !== nextValue) {
      TRACK_INFO[key] = nextValue;
      changed = true;
    }
  }

  return changed;
}

function toLyricLine(item) {
  if (!item || typeof item.time !== "number" || typeof item.text !== "string") {
    return null;
  }
  return {
    time: item.time,
    text: item.text,
  };
}

function mergeTranslatedLines(lines, translatedLines) {
  if (!lines.length || !translatedLines.length) {
    return lines;
  }

  const translations = new Map();
  for (const line of translatedLines) {
    translations.set(line.time, line.text);
  }

  return lines.map((line) => {
    const translated = translations.get(line.time);
    if (!translated) {
      return line;
    }
    return {
      time: line.time,
      text: `${line.text}\n${translated}`,
    };
  });
}

function onLyric(data) {
  if (!data) return;

  const lines = Array.isArray(data.lines)
    ? data.lines.map(toLyricLine).filter(Boolean)
    : [];
  const translatedLines = Array.isArray(data.translatedLines)
    ? data.translatedLines.map(toLyricLine).filter(Boolean)
    : [];

  if (lines.length === 0) {
    const cleared = clearLyricsData();
    if (cleared) {
      notifyLyricsLinesChanged();
    }
    return;
  }

  LYRICS.length = 0;
  for (const item of mergeTranslatedLines(lines, translatedLines)) {
    LYRICS.push(item);
  }
  activeIndex = _calcActiveIndex(progressPosition);

  notifyLyricsLinesChanged();
}

function onPlayerProgress(data) {
  if (!data || typeof data.progress !== "number") return;

  const nextPosition = data.progress / 1000;
  if (nextPosition === progressPosition) return;

  progressPosition = nextPosition;

  if (LYRICS.length > 0) {
    activeIndex = _calcActiveIndex(progressPosition);
  }

  notifyLyricsProgressChanged();
}

function onTrack(data) {
  if (!data) return;
  let trackChanged = hasTrackFields(data) ? updateTrackInfo(data) : false;
  let linesChanged = false;
  let progressChanged = false;

  if (typeof data.duration === "number" && data.duration !== duration) {
    duration = data.duration;
    trackChanged = true;
  }

  if (isEmptyTrack(data)) {
    linesChanged = clearLyricsData();
    if (progressPosition !== 0) {
      progressPosition = 0;
      progressChanged = true;
    }
    if (activeIndex !== 0) {
      activeIndex = 0;
      progressChanged = true;
    }
  }

  if (trackChanged) notifyLyricsTrackChanged();
  if (linesChanged) notifyLyricsLinesChanged();
  if (progressChanged) notifyLyricsProgressChanged();
}

window.handleMusicEvent = function (event, data) {
  handleEvent(event, data);
};

function requestCachedState() {
  if (window.pywebview && window.pywebview.api) {
    window.pywebview.api.onReady();
  }
}

window.addEventListener("pywebviewready", requestCachedState);

function handleEvent(event, data) {
  switch (event) {
    case "Lyric":
      onLyric(data);
      break;
    case "PlayerProgress":
      onPlayerProgress(data);
      break;
    case "Track":
      onTrack(data);
      break;
  }
}

function _calcActiveIndex(t) {
  if (LYRICS.length === 0 || !Number.isFinite(t)) return 0;
  let low = 0;
  let high = LYRICS.length - 1;
  let active = 0;

  while (low <= high) {
    const mid = (low + high) >> 1;
    if (t >= LYRICS[mid].time) {
      active = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  return active;
}
