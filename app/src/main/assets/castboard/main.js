// ==== 全局配置与公共状态 ====
const DETAIL_LAYER_VISIBLE_KEY = "lyrics2screen.detailLayerVisible";
window.pages = {};
window.currentPageName = null;
window.cachedGap = 36;
window.cachedActiveY = window.innerHeight * 0.35;
window.resizeTimer = 0;

// ==== 通用工具函数 ====
function parseCssLength(raw, fallback) {
  const value = resolveCssLength(String(raw ?? "").trim());
  return Number.isFinite(value) ? value : fallback;
}

function resolveCssLength(raw) {
  if (raw === "") return NaN;

  const clampArgs = cssFunctionArgs(raw, "clamp");
  if (clampArgs?.length === 3) {
    const min = resolveCssLength(clampArgs[0]);
    const preferred = resolveCssLength(clampArgs[1]);
    const max = resolveCssLength(clampArgs[2]);
    if ([min, preferred, max].every(Number.isFinite)) {
      return Math.min(max, Math.max(min, preferred));
    }
  }

  const value = parseFloat(raw);
  if (!Number.isFinite(value)) return NaN;
  if (raw.endsWith("rem")) {
    return value * readRootFontSize();
  }
  if (raw.endsWith("vh") || raw.endsWith("%")) {
    return (window.innerHeight * value) / 100;
  }
  if (raw.endsWith("vw")) {
    return (window.innerWidth * value) / 100;
  }
  return value;
}

function cssFunctionArgs(raw, name) {
  const prefix = `${name}(`;
  if (!raw.startsWith(prefix) || !raw.endsWith(")")) return null;

  const body = raw.slice(prefix.length, -1);
  const args = [];
  let depth = 0;
  let start = 0;

  for (let i = 0; i < body.length; i += 1) {
    const char = body[i];
    if (char === "(") depth += 1;
    if (char === ")") depth -= 1;
    if (char === "," && depth === 0) {
      args.push(body.slice(start, i).trim());
      start = i + 1;
    }
  }

  args.push(body.slice(start).trim());
  return args;
}

function readRootFontSize() {
  const value = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(value) ? value : 100;
}

function cacheLayoutMetrics() {
  const root = getComputedStyle(document.documentElement);
  cachedGap = parseCssLength(root.getPropertyValue("--line-gap"), 36);
  cachedActiveY = parseCssLength(root.getPropertyValue("--active-y"), window.innerHeight * 0.35);

  const clamped = window.innerWidth * 0.31;
  const maxByHeight = window.innerHeight * 0.88;
  const artSize = Math.min(clamped, maxByHeight);
  document.documentElement.style.setProperty("--detail-art-size", artSize + "px");
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
  const value = parseCssLength(getComputedStyle(element).getPropertyValue(name), NaN);
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

// ==== 页面导航与路由管理 ====
function navigateTo(newPageName) {
  if (currentPageName === newPageName) return;

  const root = document.getElementById("app-root");
  const oldPageDom = root.querySelector(".page");
  const oldPageName = currentPageName;

  const config = pages[newPageName];
  if (!config) return;

  const div = document.createElement("div");
  div.innerHTML = config.template;
  const newPageDom = div.firstElementChild;

  newPageDom.classList.add("page-enter");
  root.appendChild(newPageDom);

  currentPageName = newPageName;
  config.mount(newPageDom);

  if (newPageName === "detail") {
    storeDetailLayerVisible(true);
  } else if (newPageName === "lyrics") {
    storeDetailLayerVisible(false);
  }

  requestAnimationFrame(() => {
    newPageDom.getBoundingClientRect();
    if (oldPageDom) {
      oldPageDom.classList.remove("page-active");
      oldPageDom.classList.add("page-leave");
    }
    newPageDom.classList.remove("page-enter");
    newPageDom.classList.add("page-active");
  });

  if (oldPageDom && oldPageName) {
    setTimeout(() => {
      pages[oldPageName].unmount();
      oldPageDom.remove();
    }, 220);
  }
}

// ==== 全局事件分发 ====
function onTrackChange() {
  if (pages[currentPageName] && typeof pages[currentPageName].onTrackChange === "function") {
    pages[currentPageName].onTrackChange();
  }
}

function onProgressChange() {
  if (pages[currentPageName] && typeof pages[currentPageName].onProgressChange === "function") {
    pages[currentPageName].onProgressChange();
  }
}

function onResize() {
  resizeTimer = clearTimer(resizeTimer);
  resizeTimer = window.setTimeout(() => {
    resizeTimer = 0;
    cacheLayoutMetrics();
    if (pages[currentPageName] && typeof pages[currentPageName].onResize === "function") {
      pages[currentPageName].onResize();
    }
  }, 80);
}

function onPageClick() {
  if (currentPageName === "lyrics") {
    navigateTo("detail");
  } else if (currentPageName === "detail") {
    navigateTo("lyrics");
  }
}

function preventContextMenu(event) {
  event.preventDefault();
}

function preventWheelZoom(event) {
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault();
  }
}

function cleanupScheduledWork() {
  resizeTimer = clearTimer(resizeTimer);
  Object.values(pages).forEach(page => {
    if (typeof page.cleanup === "function") {
      page.cleanup();
    }
  });
}

function bindEvents() {
  window.addEventListener("resize", onResize);
  window.addEventListener("lyrics-track-change", onTrackChange);
  window.addEventListener("lyrics-progress-change", onProgressChange);
  window.addEventListener("lyrics-lines-change", () => {
    if (pages.lyrics && typeof pages.lyrics.layoutLyrics === "function") {
      pages.lyrics.layoutLyrics();
    }
  });
  window.addEventListener("click", onPageClick);
  window.addEventListener("contextmenu", preventContextMenu);
  window.addEventListener("wheel", preventWheelZoom, { passive: false });
  window.addEventListener("beforeunload", cleanupScheduledWork);
}

const loadedPages = new Set();
const totalPages = ["lyrics", "detail"];
let bootCalled = false;

window.onIframeLoad = function(pageName) {
  loadedPages.add(pageName);
  if (totalPages.every(p => loadedPages.has(p))) {
    if (!bootCalled) {
      bootCalled = true;
      boot();
    }
  }
};

// ==== 启动流程 ====
function boot() {
  bindEvents();
  cacheLayoutMetrics();

  const ready = document.fonts?.ready ?? Promise.resolve();
  ready.then(() => {
    const initialPage = readStoredDetailLayerVisible() ? "detail" : "lyrics";
    navigateTo(initialPage);
  });
}
