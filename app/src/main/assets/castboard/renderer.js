// ====配置与状态========================
// 集中保存渲染参数、DOM 引用和跨帧状态。DOM 查询只在加载时做一次，后续函数只读这些引用。

const OPACITY_TABLE = [1, 0.58, 0.28, 0.13, 0.04];
const BLUR_TABLE = [0, 2.7, 3.9, 5.1, 6.3];
const VISIBLE_BEFORE = 3;
const VISIBLE_AFTER = 5;
const LYRIC_TRANSITION_MS = 1260;
const LYRIC_TRANSITION =
  "transform 1180ms var(--ease), opacity 980ms var(--ease)";
const DETAIL_LAYER_VISIBLE_KEY = "lyrics2screen.detailLayerVisible";

const stage = document.querySelector(".stage");
const trackInfo = document.querySelector(".track-info");
const trackInfoTitle = document.querySelector(".track-info-title");
const trackInfoAuthor = document.querySelector(".track-info-author");
const detailLayer = document.querySelector(".detail-layer");
const detailArt = document.querySelector(".detail-art");
const detailCover = document.querySelector(".detail-cover");
const detailCopy = document.querySelector(".detail-copy");
const detailTitle = document.querySelector(".detail-title");
const detailAuthor = document.querySelector(".detail-author");
const detailAlbum = document.querySelector(".detail-album");
const detailProgressFill = document.querySelector(".detail-progress-fill");
const detailProgressElapsed = document.querySelector(".detail-progress-elapsed");
const detailProgressDuration = document.querySelector(".detail-progress-duration");
const cursorInverter = document.querySelector(".cursor-inverter");
const cursorCoverClip = document.querySelector(".cursor-cover-clip");
const cursorCoverFill = document.querySelector(".cursor-cover-fill");

const lineElements = new Map();

let cachedGap = 36;
let cachedActiveY = window.innerHeight * 0.35;
let currentIndex = 0;
let lastRenderedIndex = null;
let lyricsAnimationTimer = 0;
let detailRafId = 0;
let detailHideTimer = 0;
let cursorRafId = 0;
let cursorX = 0;
let cursorY = 0;
let cursorVisible = false;
let coverCursorTargetVisible = false;
let coverCursorResizeObserver = null;
let resizeTimer = 0;

// ====通用工具========================
// 处理 CSS 长度、时间格式、比例裁剪和调度清理。这里保持无业务状态，供歌词和详情渲染复用。

function parseCssLength(raw, fallback) {
  const value = parseFloat(raw);
  if (!Number.isFinite(value)) return fallback;
  if (raw.endsWith("vh") || raw.endsWith("%")) return (window.innerHeight * value) / 100;
  return value;
}

function cacheLayoutMetrics() {
  const root = getComputedStyle(document.documentElement);
  cachedGap = parseCssLength(root.getPropertyValue("--line-gap"), 36);
  cachedActiveY = parseCssLength(root.getPropertyValue("--active-y"), window.innerHeight * 0.35);
}

function formatSeconds(value) {
  if (!Number.isFinite(value) || value <= 0) return "0:00";

  const total = Math.floor(value);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function clampUnit(value) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

function readCssNumber(element, name, fallback) {
  const value = parseFloat(getComputedStyle(element).getPropertyValue(name));
  return Number.isFinite(value) ? value : fallback;
}

function cancelRaf(id) {
  if (id) cancelAnimationFrame(id);
  return 0;
}

function clearTimer(id) {
  if (id) window.clearTimeout(id);
  return 0;
}

function readStoredDetailLayerVisible() {
  try {
    return window.localStorage.getItem(DETAIL_LAYER_VISIBLE_KEY) === "1";
  } catch {
    return false;
  }
}

function storeDetailLayerVisible(isVisible) {
  try {
    window.localStorage.setItem(DETAIL_LAYER_VISIBLE_KEY, isVisible ? "1" : "0");
  } catch {
    // 存储不可用时只影响刷新后恢复，不影响当前交互。
  }
}

// ====歌词节点管理========================
// 只保留当前行附近的少量 DOM 节点。新增节点先禁用过渡，完成定位后下一帧再释放动画。

function resetLyricsDom() {
  stage.replaceChildren();
  lineElements.clear();
  lastRenderedIndex = null;
}

function createLineElement(index) {
  const line = document.createElement("p");
  line.className = "line";
  line.textContent = LYRICS[index].text;
  line.dataset.index = String(index);
  line.style.opacity = "0";
  line.style.pointerEvents = "none";
  line.style.transition = "none";
  stage.appendChild(line);
  lineElements.set(index, line);
  return line;
}

function visibleRangeFor(activeIndex) {
  return [
    Math.max(0, activeIndex - VISIBLE_BEFORE),
    Math.min(LYRICS.length - 1, activeIndex + VISIBLE_AFTER),
  ];
}

function syncVisibleLineElements(start, end) {
  const createdLines = new Set();

  for (const [index, line] of lineElements) {
    if (index < start || index > end) {
      line.remove();
      lineElements.delete(index);
    }
  }

  for (let index = start; index <= end; index += 1) {
    if (!lineElements.has(index)) {
      createdLines.add(createLineElement(index));
    }
  }

  return createdLines;
}

function getLineHeights() {
  const heights = new Map();
  for (const [index, line] of lineElements) {
    heights.set(index, line.offsetHeight);
  }
  return heights;
}

function offsetsForVisibleRange(start, end, activeIndex, heights) {
  const offsets = new Map([[activeIndex, 0]]);
  const gap = cachedGap;

  let offset = 0;
  for (let i = activeIndex + 1; i <= end; i += 1) {
    offset += (heights.get(i - 1) ?? 0) / 2 + gap + (heights.get(i) ?? 0) / 2;
    offsets.set(i, offset);
  }

  offset = 0;
  for (let i = activeIndex - 1; i >= start; i -= 1) {
    offset -= (heights.get(i + 1) ?? 0) / 2 + gap + (heights.get(i) ?? 0) / 2;
    offsets.set(i, offset);
  }

  return offsets;
}

function disableLyricsTransitions() {
  for (const line of lineElements.values()) {
    line.style.transition = "none";
  }
}

function scheduleLyricsTransitionDisable() {
  lyricsAnimationTimer = clearTimer(lyricsAnimationTimer);
  lyricsAnimationTimer = window.setTimeout(() => {
    lyricsAnimationTimer = 0;
    disableLyricsTransitions();
  }, LYRIC_TRANSITION_MS);
}

// ====歌词渲染流程========================
// 负责计算当前行、垂直偏移、缩放、透明度和模糊。默认禁用过渡，只在相邻歌词切换时临时启用。

function renderLyrics() {
  if (LYRICS.length === 0) {
    resetLyricsDom();
    lyricsAnimationTimer = clearTimer(lyricsAnimationTimer);
    stage.classList.remove("is-ready");
    return;
  }

  const active = currentIndex;
  const shouldAnimate = lastRenderedIndex !== null && Math.abs(active - lastRenderedIndex) === 1;
  const activeY = cachedActiveY;
  const [start, end] = visibleRangeFor(active);
  const createdLines = syncVisibleLineElements(start, end);
  const heights = getLineHeights();
  const offsets = offsetsForVisibleRange(start, end, active, heights);

  for (const [index, line] of lineElements) {
    const distance = index - active;
    const absDistance = Math.abs(distance);
    const offset = offsets.get(index) ?? 0;
    const height = heights.get(index) ?? 0;
    const translateY = activeY - height / 2 + offset;
    const scale = Math.max(0.84, 1.08 - absDistance * 0.075);
    const opacity = OPACITY_TABLE[absDistance] ?? 0;
    const blur = BLUR_TABLE[absDistance] ?? 0;

    line.style.transition = shouldAnimate && !createdLines.has(line) ? LYRIC_TRANSITION : "none";
    line.classList.toggle("is-current", distance === 0);
    line.style.transform = `translateY(${translateY}px) scale(${scale})`;
    line.style.opacity = String(opacity);
    line.style.pointerEvents = distance === 0 ? "auto" : "none";
    line.style.filter = blur > 0 ? `blur(${blur}px)` : "none";
    line.style.zIndex = String(20 - absDistance);
  }

  if (shouldAnimate) {
    scheduleLyricsTransitionDisable();
  } else {
    lyricsAnimationTimer = clearTimer(lyricsAnimationTimer);
  }

  lastRenderedIndex = active;
}

function layoutLyrics() {
  stage.classList.remove("is-ready");
  lyricsAnimationTimer = clearTimer(lyricsAnimationTimer);

  currentIndex = activeIndex;
  resetLyricsDom();
  renderLyrics();

  if (lineElements.size > 0) {
    stage.classList.add("is-ready");
  }
}

// ====播放信息渲染========================
// 渲染右下角摘要和详情层文字。进度高频刷新时只更新进度条，避免重复写标题、歌手等静态节点。

function renderTrackSummary() {
  const title = TRACK_INFO.title;
  const author = TRACK_INFO.author;
  const hasInfo = title !== "" || author !== "";

  trackInfo.hidden = !hasInfo;
  trackInfoTitle.hidden = title === "";
  trackInfoAuthor.hidden = author === "";
  trackInfoTitle.textContent = title;
  trackInfoAuthor.textContent = author;
}

function titleVisualLength(value) {
  let length = 0;
  for (const char of value.trim()) {
    const code = char.codePointAt(0) ?? 0;
    if (/\s/.test(char)) {
      length += 0.25;
    } else if (code <= 0x7f) {
      length += 0.55;
    } else if (code <= 0xff) {
      length += 0.65;
    } else {
      length += 1;
    }
  }
  return length;
}

function titleSizeByLength(title, minSize, maxSize) {
  const length = titleVisualLength(title);
  const ratio = clampUnit((length - 12) / 24);
  const easedRatio = ratio * ratio * (3 - 2 * ratio);
  return maxSize - (maxSize - minSize) * easedRatio;
}

function setDetailTitleSize(size) {
  detailCopy.style.setProperty("--detail-title-size", `${size.toFixed(1)}px`);
}

function updateDetailTitleSize() {
  const minSize = readCssNumber(detailCopy, "--detail-title-min", 46);
  const maxSize = readCssNumber(detailCopy, "--detail-title-max", 86);
  const size = titleSizeByLength(detailTitle.textContent, minSize, maxSize);
  setDetailTitleSize(size);
}

function renderDetailMetadata() {
  const title = TRACK_INFO.title || "未知歌曲";
  const author = TRACK_INFO.author || "未知歌手";
  const album = TRACK_INFO.album || "";
  const cover = TRACK_INFO.cover || "";

  detailTitle.textContent = title;
  detailAuthor.textContent = author;
  if (album === "") {
    detailAlbum.textContent = "\u00a0";
    detailAlbum.setAttribute("aria-hidden", "true");
  } else {
    detailAlbum.textContent = album;
    detailAlbum.removeAttribute("aria-hidden");
  }
  if (cover === "") {
    detailCover.hidden = true;
    detailCover.removeAttribute("src");
  } else if (detailCover.getAttribute("src") !== cover) {
    detailCover.hidden = true;
    detailCover.src = cover;
  }
  detailProgressDuration.textContent = TRACK_INFO.durationHuman || formatSeconds(duration);
  updateDetailTitleSize();
}

function renderDetailProgress() {
  const elapsed = Math.max(0, progressPosition);
  const durationSeconds = Number.isFinite(duration) && duration > 0 ? duration : 0;
  const progress = durationSeconds > 0 ? clampUnit(elapsed / durationSeconds) : 0;

  detailProgressFill.style.width = `${(progress * 100).toFixed(2)}%`;
  detailProgressElapsed.textContent = formatSeconds(elapsed);
}

function renderDetailPanel() {
  renderDetailMetadata();
  renderDetailProgress();
}

function showDetailLayer() {
  detailHideTimer = clearTimer(detailHideTimer);
  storeDetailLayerVisible(true);
  renderDetailPanel();
  detailLayer.hidden = false;
  updateDetailTitleSize();
  detailLayer.getBoundingClientRect();

  detailRafId = cancelRaf(detailRafId);
  detailRafId = requestAnimationFrame(() => {
    detailRafId = 0;
    detailLayer.classList.add("is-open");
    syncCoverCursorTarget();
  });
}

function hideDetailLayer() {
  detailHideTimer = clearTimer(detailHideTimer);
  detailRafId = cancelRaf(detailRafId);
  storeDetailLayerVisible(false);
  detailLayer.classList.remove("is-open");
  syncCoverCursorTarget();
  detailHideTimer = window.setTimeout(() => {
    detailHideTimer = 0;
    if (!detailLayer.classList.contains("is-open")) {
      detailLayer.hidden = true;
      syncCoverCursorTarget();
    }
  }, 240);
}

function toggleDetailLayer() {
  if (detailLayer.hidden || !detailLayer.classList.contains("is-open")) {
    showDetailLayer();
  } else {
    hideDetailLayer();
  }
}

// ====反色光标========================
// 白色圆形遮罩使用 difference 混合模式反色；封面区域叠加纯白圆形裁剪层。

function isMousePointer(event) {
  return event.pointerType === "mouse";
}

function isCoverCursorTargetReady() {
  return (
    !detailLayer.hidden &&
    detailLayer.classList.contains("is-open") &&
    !detailCover.hidden &&
    detailCover.getAttribute("src") !== null
  );
}

function syncCoverCursorVisibility() {
  cursorCoverClip.classList.toggle("is-visible", cursorVisible && coverCursorTargetVisible);
}

function setCoverCursorTargetVisible(isVisible) {
  coverCursorTargetVisible = isVisible;
  syncCoverCursorVisibility();
}

function syncCoverCursorTarget() {
  if (!isCoverCursorTargetReady()) {
    setCoverCursorTargetVisible(false);
    return;
  }

  const rect = detailArt.getBoundingClientRect();
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const left = Math.min(viewportWidth, Math.max(0, rect.left));
  const top = Math.min(viewportHeight, Math.max(0, rect.top));
  const right = Math.min(viewportWidth, Math.max(0, rect.right));
  const bottom = Math.min(viewportHeight, Math.max(0, rect.bottom));

  if (right <= left || bottom <= top) {
    setCoverCursorTargetVisible(false);
    return;
  }

  cursorCoverClip.style.setProperty("--cursor-cover-top", `${top.toFixed(2)}px`);
  cursorCoverClip.style.setProperty("--cursor-cover-right", `${(viewportWidth - right).toFixed(2)}px`);
  cursorCoverClip.style.setProperty("--cursor-cover-bottom", `${(viewportHeight - bottom).toFixed(2)}px`);
  cursorCoverClip.style.setProperty("--cursor-cover-left", `${left.toFixed(2)}px`);
  setCoverCursorTargetVisible(true);
}

function setCursorVisible(isVisible) {
  cursorVisible = isVisible;
  cursorInverter.classList.toggle("is-visible", isVisible);
  syncCoverCursorVisibility();
}

function renderCursorInverter() {
  cursorRafId = 0;
  const transform = `translate3d(${cursorX}px, ${cursorY}px, 0) translate(-50%, -50%)`;
  cursorInverter.style.transform = transform;
  cursorCoverFill.style.transform = transform;
}

function scheduleCursorRender() {
  if (cursorRafId) return;
  cursorRafId = requestAnimationFrame(renderCursorInverter);
}

function onPointerMove(event) {
  if (!isMousePointer(event)) {
    setCursorVisible(false);
    return;
  }

  cursorX = event.clientX;
  cursorY = event.clientY;
  setCursorVisible(true);
  scheduleCursorRender();
}

function onPointerLeave(event) {
  if (isMousePointer(event)) {
    setCursorVisible(false);
  }
}

function onPointerOut(event) {
  if (!event.relatedTarget && isMousePointer(event)) {
    setCursorVisible(false);
  }
}

function hideCursorInverter() {
  setCursorVisible(false);
}

// ====事件入口========================
// 对接 data.js 派发的播放事件和浏览器事件。每个入口只做必要的局部刷新。

function onTrackChange() {
  renderTrackSummary();
  renderDetailPanel();
}

function onProgressChange() {
  renderDetailProgress();

  const nextIndex = activeIndex;
  if (nextIndex !== currentIndex) {
    currentIndex = nextIndex;
    renderLyrics();
  }
}

function onResize() {
  resizeTimer = clearTimer(resizeTimer);
  resizeTimer = window.setTimeout(() => {
    resizeTimer = 0;
    cacheLayoutMetrics();
    renderLyrics();
    updateDetailTitleSize();
    syncCoverCursorTarget();
  }, 80);
}

function preventContextMenu(event) {
  event.preventDefault();
}

function onCoverError() {
  detailCover.hidden = true;
  detailCover.removeAttribute("src");
  syncCoverCursorTarget();
}

function onCoverLoad() {
  detailCover.hidden = false;
  syncCoverCursorTarget();
}

function onDetailLayerTransitionEnd(event) {
  if (event.target === detailLayer && event.propertyName === "transform") {
    syncCoverCursorTarget();
  }
}

function callBackend(name) {
  const api = window.pywebview?.api;
  const method = api?.[name];
  if (typeof method !== "function") return;

  try {
    const result = method.call(api);
    if (result && typeof result.catch === "function") {
      result.catch(() => {});
    }
  } catch {
    // 后端桥接未就绪时忽略，不能影响触摸打开详情。
  }
}

function preserveCursorAfterTouch() {
  callBackend("preserveCursorAfterTouch");
}

function onTouchPointer(event) {
  if (event.pointerType === "touch") {
    preserveCursorAfterTouch();
  }
}

function bindTouchCursorPreservation() {
  if ("PointerEvent" in window) {
    window.addEventListener("pointerdown", onTouchPointer, { passive: true });
    window.addEventListener("pointerup", onTouchPointer, { passive: true });
    return;
  }

  window.addEventListener("touchstart", preserveCursorAfterTouch, { passive: true });
  window.addEventListener("touchend", preserveCursorAfterTouch, { passive: true });
}

function cleanupScheduledWork() {
  lyricsAnimationTimer = clearTimer(lyricsAnimationTimer);
  detailRafId = cancelRaf(detailRafId);
  detailHideTimer = clearTimer(detailHideTimer);
  cursorRafId = cancelRaf(cursorRafId);
  resizeTimer = clearTimer(resizeTimer);
  if (coverCursorResizeObserver !== null) {
    coverCursorResizeObserver.disconnect();
    coverCursorResizeObserver = null;
  }
}

function bindCoverCursorResizeObserver() {
  if (!("ResizeObserver" in window)) return;

  coverCursorResizeObserver = new ResizeObserver(syncCoverCursorTarget);
  coverCursorResizeObserver.observe(detailArt);
}

function bindEvents() {
  window.addEventListener("resize", onResize);
  window.addEventListener("lyrics-track-change", onTrackChange);
  window.addEventListener("lyrics-progress-change", onProgressChange);
  window.addEventListener("lyrics-lines-change", layoutLyrics);
  window.addEventListener("click", toggleDetailLayer);
  window.addEventListener("pointermove", onPointerMove, { passive: true });
  window.addEventListener("pointerleave", onPointerLeave, { passive: true });
  window.addEventListener("pointerout", onPointerOut, { passive: true });
  window.addEventListener("blur", hideCursorInverter);
  detailCover.addEventListener("load", onCoverLoad);
  detailCover.addEventListener("error", onCoverError);
  detailLayer.addEventListener("transitionend", onDetailLayerTransitionEnd);
  bindCoverCursorResizeObserver();
  bindTouchCursorPreservation();
  window.addEventListener("contextmenu", preventContextMenu);
  window.addEventListener("beforeunload", cleanupScheduledWork);
}

// ====启动流程========================
// 先固定静态界面状态并绑定事件。字体加载完成后再首次布局，避免歌词高度在首帧后跳动。

function boot() {
  cacheLayoutMetrics();

  const ready = document.fonts?.ready ?? Promise.resolve();
  ready.then(() => {
    renderTrackSummary();
    renderDetailPanel();
    layoutLyrics();
    if (readStoredDetailLayerVisible()) {
      showDetailLayer();
    }
  });
}

bindEvents();

if (document.readyState === "loading") {
  window.addEventListener("DOMContentLoaded", boot, { once: true });
} else {
  boot();
}
