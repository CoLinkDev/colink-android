// Sync document language from URL query string on startup
(() => {
  const urlParams = new URLSearchParams(window.location.search);
  const lang = urlParams.get('lang');
  if (lang) {
    document.documentElement.setAttribute('lang', lang);
  }
})();

// ==== 全局配置与公共状态 ====
window.pages = {};
window.currentPageName = null;
window.cachedGap = 36;
window.cachedActiveY = window.innerHeight * 0.35;
window.resizeTimer = 0;
window.navManager = null;

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

// Read computed css font size of root element
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

// Clear active timers
function clearTimer(id) {
  if (id) window.clearTimeout(id);
  return 0;
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

// ==== 路由与导航管理机制 ====
class NavigationManager {
  constructor(pages) {
    this.pages = pages;
    this.currentPageName = null;
    this.pageCleanupTimer = 0;
    this.storageKey = "lyrics2screen.detailLayerVisible";

    // 瞬态（临时跳转）状态
    this.transientTimer = 0;
    this.transientOriginalPage = null;
    this.isTransientActive = false;
  }

  getStoredDetailLayerVisible() {
    try {
      return window.localStorage.getItem(this.storageKey) === "1";
    } catch {
      return false;
    }
  }

  storeDetailLayerVisible(isVisible) {
    try {
      window.localStorage.setItem(this.storageKey, isVisible ? "1" : "0");
    } catch {
      // 存储不可用时忽略
    }
  }

  navigateTo(newPageName, isTemporary = false) {
    if (this.currentPageName === newPageName) return;

    if (this.transientTimer && !isTemporary) {
      this.cancelTransient();
    }

    const root = document.getElementById("app-root");
    const oldPageDoms = Array.from(root.querySelectorAll(".page"));
    const oldPageName = this.currentPageName;

    const config = this.pages[newPageName];
    if (!config) return;

    if (this.pageCleanupTimer) {
      window.clearTimeout(this.pageCleanupTimer);
      this.pageCleanupTimer = 0;
    }

    if (oldPageName && this.pages[oldPageName] && typeof this.pages[oldPageName].unmount === "function") {
      this.pages[oldPageName].unmount();
    }

    const div = document.createElement("div");
    div.innerHTML = config.template;
    const newPageDom = div.firstElementChild;

    newPageDom.classList.add("page-enter");
    root.appendChild(newPageDom);

    this.currentPageName = newPageName;
    window.currentPageName = newPageName;
    config.mount(newPageDom);

    if (!isTemporary) {
      if (newPageName === "detail") {
        this.storeDetailLayerVisible(true);
      } else if (newPageName === "lyrics") {
        this.storeDetailLayerVisible(false);
      }
    }

    requestAnimationFrame(() => {
      newPageDom.getBoundingClientRect();
      for (const oldPageDom of oldPageDoms) {
        oldPageDom.classList.remove("page-active");
        oldPageDom.classList.add("page-leave");
      }
      newPageDom.classList.remove("page-enter");
      newPageDom.classList.add("page-active");
    });

    if (oldPageDoms.length > 0) {
      this.pageCleanupTimer = window.setTimeout(() => {
        this.pageCleanupTimer = 0;
        for (const oldPageDom of oldPageDoms) {
          oldPageDom.remove();
        }
      }, 220);
    }
  }

  toggle() {
    if (this.currentPageName === "lyrics") {
      this.navigateTo("detail");
    } else if (this.currentPageName === "detail") {
      this.navigateTo("lyrics");
    }
  }

  showTransient(targetPage, durationMs) {
    if (this.currentPageName !== targetPage) {
      if (!this.isTransientActive) {
        this.transientOriginalPage = this.currentPageName;
      }
      this.isTransientActive = true;
      this.navigateTo(targetPage, true);
    }

    this.clearTransientTimer();
    this.transientTimer = window.setTimeout(() => this.restoreTransient(), durationMs);
  }

  restoreTransient() {
    this.clearTransientTimer();
    if (this.transientOriginalPage && this.currentPageName !== this.transientOriginalPage) {
      this.navigateTo(this.transientOriginalPage, true);
    }
    this.resetTransientState();
  }

  cancelTransient() {
    this.clearTransientTimer();
    this.resetTransientState();
  }

  clearTransientTimer() {
    if (this.transientTimer) {
      window.clearTimeout(this.transientTimer);
      this.transientTimer = 0;
    }
  }

  resetTransientState() {
    this.transientOriginalPage = null;
    this.isTransientActive = false;
  }

  destroy() {
    if (this.pageCleanupTimer) {
      window.clearTimeout(this.pageCleanupTimer);
      this.pageCleanupTimer = 0;
    }
    this.cancelTransient();
  }
}

window.navManager = new NavigationManager(window.pages);

// ==== 全局事件分发 ====
function onTrackChange() {
  if (window.navManager) {
    window.navManager.showTransient("detail", 2000);
  }

  if (pages[currentPageName] && typeof pages[currentPageName].onTrackChange === "function") {
    pages[currentPageName].onTrackChange();
  }
}

function onProgressChange() {
  if (pages[currentPageName] && typeof pages[currentPageName].onProgressChange === "function") {
    pages[currentPageName].onProgressChange();
  }
}

// Handle resize event
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
  if (window.navManager) {
    window.navManager.toggle();
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
  if (window.navManager) {
    window.navManager.destroy();
  }
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

  // Sync language to the loaded iframe
  const urlParams = new URLSearchParams(window.location.search);
  const lang = urlParams.get('lang');
  if (lang) {
    const iframe = document.getElementById(`iframe-${pageName}`);
    if (iframe?.contentDocument?.documentElement) {
      iframe.contentDocument.documentElement.setAttribute('lang', lang);
    }
  }

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
    const initialPage = window.navManager.getStoredDetailLayerVisible() ? "detail" : "lyrics";
    window.navManager.navigateTo(initialPage);
  });
}
