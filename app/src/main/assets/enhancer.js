(function () {
  "use strict";

  // Prevent double-injection
  if (window.__tcEnhancerLoaded) return;
  window.__tcEnhancerLoaded = true;

  // =========================================================================
  //  CONFIGURATION
  // =========================================================================
  const CONFIG = {
    // Performance
    enableImageLazyLoad: true,
    enableDOMCleanup: true,
    enableScriptCache: true,
    enableRequestDedup: true,
    maxPagesInDOM: 3,
    skipVictoryScreen: true,
    audioEnabled: true,
    // Accessibility
    enableARIA: true,
    enableFocusManagement: true,
    enableBattleAnnouncements: true,
    enableKeyboardShortcuts: true,
    enableSkipNav: true,
    enableLiveRegions: true,
    useNativeDialogs: true,
    // Minimum gap (ms) between attack-key presses (1/2/3/4/5). Rapid-fire
    // identical POSTs are one of Cloudflare's behavioral bot signals; this
    // debounce keeps the client below that threshold while still feeling
    // snappy. Tune lower if CF stays quiet, higher if challenges return.
    attackKeyThrottleMs: 250,
    // Enemy-list memory (see MODULE 7b). Remembers enemies seen while looking
    // around so they stay clickable until they expire or the area changes.
    enableEnemyListMemory: true,
    enemyMemoryTtlMs: 60 * 1000,
    // Extra attacks (see MODULE 7c). One-shot burst: when armed via the
    // Ctrl+Shift+T dialog, the NEXT enemy click fires this many extra
    // battle.php "init" POSTs in parallel on top of the normal one, then the
    // toggle auto-disables. Preset to 200; both the armed flag and the count
    // persist (loadPersistedSettings) and re-save whenever the dialog changes.
    enableExtraAttacks: false,
    extraAttackCount: 200,
  };

  // -------------------------------------------------------------------------
  //  Shared enemy-list-memory / area-navigation state (used by MODULE 7b and
  //  the keyboard shortcuts). Declared up here so every consumer shares one
  //  instance regardless of declaration order.
  // -------------------------------------------------------------------------
  const ENEMY_MEM_KEY = "tcEnemyCache";          // sessionStorage (per session)
  const VISITED_AREAS_KEY = "bw2_visited_subregions"; // localStorage (persists)
  const _engagedGuids = new Set();   // enemies clicked since the last list reload
  let _memBattleLock = false;        // guards the re-injected-row battle POST
  let _memScheduled = false;         // rAF debounce flag for the memory tick

  // =========================================================================
  //  DIAGNOSTIC LOGGING
  //  Bridges renderer-side activity into userData/bw2-debug.log via the
  //  preload-exposed window.__bw2Log() IPC channel. Also pipes to console
  //  for live inspection in DevTools (F12).
  //  Press F4 to open the log file.
  // =========================================================================
  const BW2_BUILD_TAG = "bw2-2026-05";
  function dlog(level, ...args) {
    const lvl = level === "error" ? "error" : level === "warn" ? "warn" : "log";
    try { console[lvl]("[BW2]", ...args); } catch (_) {}
    try { if (typeof window.__bw2Log === "function") window.__bw2Log(level || "log", ...args); } catch (_) {}
  }
  function dwarn(...a) { dlog("warn", ...a); }
  function derr(...a) { dlog("error", ...a); }

  // Wrap a function so any throw is logged with a labeled stack trace and
  // does NOT propagate up the call chain. Returns undefined on failure.
  function safeRun(label, fn) {
    try { return fn(); }
    catch (e) {
      derr(`safeRun[${label}] threw:`, e);
      return undefined;
    }
  }

  // Best-effort short description of a DOM node for click/focus logs.
  function describeEl(el) {
    if (!el || el.nodeType !== 1) return String(el);
    const parts = [];
    parts.push(el.tagName.toLowerCase());
    if (el.id) parts.push("#" + el.id);
    if (el.className && typeof el.className === "string") {
      parts.push("." + el.className.trim().split(/\s+/).slice(0, 6).join("."));
    }
    const href = el.getAttribute && el.getAttribute("href");
    if (href) parts.push(`href=${JSON.stringify(href)}`);
    const role = el.getAttribute && el.getAttribute("role");
    if (role) parts.push(`role=${role}`);
    const aria = el.getAttribute && el.getAttribute("aria-label");
    if (aria) parts.push(`aria="${aria.slice(0, 60)}"`);
    for (const attr of ["data-view", "data-subregionid", "data-enemyguid", "data-panel"]) {
      const v = el.getAttribute && el.getAttribute(attr);
      if (v) parts.push(`${attr}="${v}"`);
    }
    const txt = (el.textContent || "").trim().replace(/\s+/g, " ");
    if (txt) parts.push(`text="${txt.slice(0, 60)}"`);
    return parts.join(" ");
  }

  function installGlobalErrorTraps() {
    window.addEventListener("error", function (e) {
      try {
        derr("window.error:", e.message, "at", e.filename + ":" + e.lineno + ":" + e.colno, e.error && e.error.stack);
      } catch (_) {}
    }, true);
    window.addEventListener("unhandledrejection", function (e) {
      try {
        const reason = e.reason;
        derr("unhandledrejection:", reason && reason.stack ? reason.stack : String(reason));
      } catch (_) {}
    });
  }

  // Logs every click that reaches the document. Capture phase + no
  // stopPropagation so it never interferes with the game's own handlers.
  // Walks up to 4 ancestors so we still log the link even when the user
  // clicks the inner icon/text.
  function installClickLogger() {
    document.addEventListener("click", function (e) {
      try {
        const path = [];
        let n = e.target;
        for (let i = 0; i < 4 && n && n.nodeType === 1; i++) {
          path.push(describeEl(n));
          n = n.parentElement;
        }
        dlog("info", "click trusted=" + e.isTrusted, "path:", path.join("  <-  "));
      } catch (err) { derr("click logger:", err); }
    }, true);

    // Same for keydown on action keys so we can correlate keyboard nav with
    // missing/extra page loads.
    document.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === "Tab" || e.key === " ") {
        try { dlog("info", `keydown ${e.key} on`, describeEl(e.target)); } catch (_) {}
      }
    }, true);
  }

  // Wrap fetch / XHR so every server hit and its response status / size
  // show up in the log alongside clicks. Helps confirm whether a click that
  // "did nothing" actually fired an HTTP request.
  function installNetworkLogger() {
    try {
      const origFetch = window.fetch;
      if (typeof origFetch === "function") {
        window.fetch = function (input, init) {
          const url = typeof input === "string" ? input : (input && input.url) || "?";
          const method = (init && init.method) || (input && input.method) || "GET";
          const id = Math.random().toString(36).slice(2, 8);
          dlog("info", `fetch[${id}] ${method} ${url}`);
          const t0 = Date.now();
          return origFetch.apply(this, arguments).then(function (resp) {
            dlog("info", `fetch[${id}] -> ${resp.status} ${resp.statusText} in ${Date.now() - t0}ms`);
            return resp;
          }, function (err) {
            derr(`fetch[${id}] failed in ${Date.now() - t0}ms:`, err);
            throw err;
          });
        };
      }
    } catch (e) { derr("install fetch logger:", e); }

    try {
      const proto = XMLHttpRequest && XMLHttpRequest.prototype;
      if (proto && !proto.__bw2Wrapped) {
        const origOpen = proto.open;
        const origSend = proto.send;
        proto.open = function (method, url) {
          this.__bw2Method = method;
          this.__bw2Url = url;
          this.__bw2Id = Math.random().toString(36).slice(2, 8);
          return origOpen.apply(this, arguments);
        };
        proto.send = function () {
          const id = this.__bw2Id;
          dlog("info", `xhr[${id}] ${this.__bw2Method} ${this.__bw2Url}`);
          const t0 = Date.now();
          this.addEventListener("loadend", () => {
            dlog("info", `xhr[${id}] -> ${this.status} ${this.statusText} (${this.responseType || "text"} ${this.response && this.response.length || 0}B) in ${Date.now() - t0}ms`);
          });
          this.addEventListener("error", () => derr(`xhr[${id}] network error`));
          this.addEventListener("abort", () => dwarn(`xhr[${id}] aborted`));
          return origSend.apply(this, arguments);
        };
        proto.__bw2Wrapped = true;
      }
    } catch (e) { derr("install xhr logger:", e); }
  }

  // =========================================================================
  //  UTILITY: Live Region for Screen Reader Announcements
  // =========================================================================
  let liveRegion = null;
  let liveRegionLog = null;

  function createLiveRegions() {
    if (document.getElementById("tc-sr-announce")) return;

    liveRegion = document.createElement("div");
    liveRegion.id = "tc-sr-announce";
    liveRegion.setAttribute("role", "status");
    liveRegion.setAttribute("aria-live", "assertive");
    liveRegion.setAttribute("aria-atomic", "true");
    Object.assign(liveRegion.style, {
      position: "absolute",
      width: "1px",
      height: "1px",
      overflow: "hidden",
      clip: "rect(0,0,0,0)",
      whiteSpace: "nowrap",
      border: "0",
    });
    document.body.appendChild(liveRegion);

    liveRegionLog = document.createElement("div");
    liveRegionLog.id = "tc-sr-log";
    liveRegionLog.setAttribute("role", "log");
    liveRegionLog.setAttribute("aria-live", "polite");
    liveRegionLog.setAttribute("aria-atomic", "false");
    Object.assign(liveRegionLog.style, {
      position: "absolute",
      width: "1px",
      height: "1px",
      overflow: "hidden",
      clip: "rect(0,0,0,0)",
      whiteSpace: "nowrap",
      border: "0",
    });
    document.body.appendChild(liveRegionLog);
  }

  function announce(text, priority) {
    const region = priority === "assertive" ? liveRegion : liveRegionLog;
    if (!region) return;
    region.textContent = "";
    requestAnimationFrame(() => {
      region.textContent = text;
    });
  }

  // =========================================================================
  //  MODULE 1: PERFORMANCE — Image Lazy Loading
  // =========================================================================
  function setupImageLazyLoading() {
    if (!CONFIG.enableImageLazyLoad || !("IntersectionObserver" in window))
      return;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const img = entry.target;
            if (img.dataset.tcSrc) {
              img.src = img.dataset.tcSrc;
              delete img.dataset.tcSrc;
              img.removeAttribute("data-tc-src");
            }
            if (img.dataset.tcBg) {
              img.style.backgroundImage = `url(${img.dataset.tcBg})`;
              delete img.dataset.tcBg;
            }
            observer.unobserve(img);
          }
        }
      },
      { rootMargin: "200px" }
    );

    function deferImages(root) {
      const images = (root || document).querySelectorAll(
        "img:not([data-tc-lazy])"
      );
      for (const img of images) {
        if (img.complete && img.naturalWidth > 0) continue;
        if (img.closest(".navbar, .toolbar, .statusbar, #statszone")) continue;

        const src = img.getAttribute("src");
        if (src && src !== "" && !src.startsWith("data:")) {
          img.dataset.tcSrc = src;
          img.setAttribute(
            "src",
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
          );
          img.dataset.tcLazy = "1";
          observer.observe(img);
        }
      }

      const bgEls = (root || document).querySelectorAll(
        '.item-media[style*="background-image"]:not([data-tc-lazy])'
      );
      for (const el of bgEls) {
        if (el.closest(".navbar, .toolbar, .statusbar")) continue;
        const style = el.style.backgroundImage;
        const match = style.match(/url\(["']?(.+?)["']?\)/);
        if (match) {
          el.dataset.tcBg = match[1];
          el.style.backgroundImage = "none";
          el.dataset.tcLazy = "1";
          observer.observe(el);
        }
      }
    }

    deferImages();
    observeNewContent((root) => deferImages(root));
  }

  // =========================================================================
  //  MODULE 2: PERFORMANCE — DOM Cleanup (stale Framework7 pages)
  // =========================================================================
  function setupDOMCleanup() {
    if (!CONFIG.enableDOMCleanup) return;

    function cleanStaleDOMPages() {
      const pages = document.querySelectorAll(
        ".page:not(.page-on-center):not(.page-on-left):not(.cached)"
      );
      const stale = Array.from(pages).filter((p) => {
        const name = p.getAttribute("data-page") || "";
        return (
          name !== "index-left" &&
          !p.classList.contains("page-on-center") &&
          p.style.display !== "none" &&
          !p.querySelector(".page-on-center")
        );
      });

      if (stale.length > CONFIG.maxPagesInDOM) {
        const toRemove = stale.slice(0, stale.length - CONFIG.maxPagesInDOM);
        for (const page of toRemove) {
          for (const img of page.querySelectorAll("img")) {
            img.removeAttribute("src");
          }
          page.remove();
        }
      }
    }

    setInterval(cleanStaleDOMPages, 30000);
  }

  // =========================================================================
  //  MODULE 3: PERFORMANCE — Script Caching
  // =========================================================================
  function setupScriptCache() {
    if (!CONFIG.enableScriptCache) return;

    const scriptCache = new Map();
    const origGetScript = window.jQuery?.getScript;
    if (!origGetScript) return;

    function injectScript(code) {
      const s = document.createElement("script");
      s.textContent = code;
      document.head.appendChild(s);
      s.remove();
    }

    window.jQuery.getScript = function (url, callback) {
      if (scriptCache.has(url)) {
        injectScript(scriptCache.get(url));
        if (callback) callback();
        return window.jQuery.Deferred().resolve();
      }

      return fetch(url, { credentials: "same-origin" })
        .then((r) => r.text())
        .then((code) => {
          scriptCache.set(url, code);
          injectScript(code);
          if (callback) callback();
        })
        .catch(() => origGetScript.call(window.jQuery, url, callback));
    };
  }

  // =========================================================================
  //  MODULE 4: PERFORMANCE — Request Deduplication
  // =========================================================================
  function setupRequestDedup() {
    if (!CONFIG.enableRequestDedup) return;

    const pendingRequests = new Map();
    const origAjax = window.jQuery?.ajax;
    if (!origAjax) return;

    window.jQuery.ajax = function (urlOrSettings, settings) {
      const opts =
        typeof urlOrSettings === "string"
          ? { url: urlOrSettings, ...settings }
          : urlOrSettings;

      const method = (opts.method || opts.type || "GET").toUpperCase();
      if (method !== "GET") {
        return origAjax.call(window.jQuery, urlOrSettings, settings);
      }

      const dedupKey = (opts.url || "").replace(/[?&]cachebuster=\d+/, "");

      if (pendingRequests.has(dedupKey)) {
        return pendingRequests.get(dedupKey);
      }

      const req = origAjax.call(window.jQuery, urlOrSettings, settings);

      if (req && req.always) {
        pendingRequests.set(dedupKey, req);
        req.always(() => {
          setTimeout(() => pendingRequests.delete(dedupKey), 500);
        });
      }

      return req;
    };
  }

  // =========================================================================
  //  MODULE 5: ACCESSIBILITY — Semantic ARIA Roles & Labels
  // =========================================================================
  function setupARIA(root) {
    if (!CONFIG.enableARIA) return;
    const doc = root || document;

    const navPanel = doc.querySelector(".panel-left");
    if (navPanel && !navPanel.getAttribute("role")) {
      navPanel.setAttribute("role", "navigation");
      navPanel.setAttribute("aria-label", "Main navigation");
    }

    const mainContent = doc.querySelector(
      ".view-main .page-on-center .page-content"
    );
    if (mainContent && !mainContent.getAttribute("role")) {
      mainContent.setAttribute("role", "main");
      mainContent.setAttribute("aria-label", "Game content");
    }

    const chatPanel = doc.querySelector(".panel-right");
    if (chatPanel && !chatPanel.getAttribute("role")) {
      chatPanel.setAttribute("role", "complementary");
      chatPanel.setAttribute("aria-label", "Global chat");
    }

    const chatInputs = doc.querySelectorAll(
      "#chat_txt_mobile, #chat_txt_desktop"
    );
    for (const input of chatInputs) {
      if (!input.getAttribute("aria-label")) {
        input.setAttribute("aria-label", "Type a chat message");
        input.setAttribute("role", "textbox");
      }
    }

    const statsZone = doc.querySelector("#statszone");
    if (statsZone && !statsZone.getAttribute("role")) {
      statsZone.setAttribute("role", "status");
      statsZone.setAttribute("aria-label", "Character stats");
      statsZone.setAttribute("aria-live", "polite");
    }

    const navbarCenter = doc.querySelector(
      ".page-on-center .navbar .center:not([data-tc-heading]), .page-on-center .navbar-inner .center:not([data-tc-heading])"
    );
    if (navbarCenter) {
      navbarCenter.setAttribute("role", "heading");
      navbarCenter.setAttribute("aria-level", "1");
      navbarCenter.setAttribute("data-tc-heading", "1");
    }

    const blockTitles = doc.querySelectorAll(
      ".block-title:not([data-tc-heading]), .content-block-title:not([data-tc-heading])"
    );
    for (const title of blockTitles) {
      if (title.closest(".page") && !title.closest(".page-on-center")) continue;
      title.setAttribute("role", "heading");
      title.setAttribute("aria-level", "2");
      title.setAttribute("data-tc-heading", "1");
    }

    const lists = doc.querySelectorAll(".list-block ul:not([role])");
    for (const list of lists) {
      list.setAttribute("role", "list");
    }

    const navItems = doc.querySelectorAll(
      ".panel-left .item-link:not([role])"
    );
    for (const item of navItems) {
      const text = item.textContent.trim();
      if (text) {
        item.setAttribute("role", "menuitem");
        if (!item.getAttribute("aria-label")) {
          item.setAttribute("aria-label", text);
        }
      }
    }

    const progressBars = doc.querySelectorAll(
      ".progressbar:not([role]), .progress:not([role])"
    );
    for (const bar of progressBars) {
      bar.setAttribute("role", "progressbar");
      const width = bar.style.width || bar.querySelector("span")?.style.width;
      if (width) {
        bar.setAttribute("aria-valuenow", parseInt(width) || 0);
        bar.setAttribute("aria-valuemin", "0");
        bar.setAttribute("aria-valuemax", "100");
      }
    }

    // Fix gear/armor level progress bars. The game emits the actual percent
    // in `data-progress` but hardcodes `aria-valuenow="100"`, so NVDA always
    // reads every bar as "100%". Overwrite aria-valuenow with the real value.
    for (const bar of doc.querySelectorAll(".progressbar[data-progress]")) {
      const dp = parseFloat(bar.getAttribute("data-progress"));
      if (isFinite(dp)) bar.setAttribute("aria-valuenow", String(dp));
    }
  }

  // =========================================================================
  //  MODULE 7: ACCESSIBILITY — Enemy List & Battle Enhancements
  // =========================================================================
  function enhanceEnemyList(root) {
    if (!CONFIG.enableARIA) return;
    const doc = root || document;

    // Move enemy list to the top of page content so it's the first
    // element screen readers and focus hit.
    const enemyList = doc.querySelector("#enemyList");
    if (enemyList) {
      const listBlock = enemyList.closest(".list-block") || enemyList;
      const pageContent = listBlock.closest(".page-content");
      if (pageContent && pageContent.firstElementChild !== listBlock) {
        pageContent.insertBefore(listBlock, pageContent.firstElementChild);
      }
    }

    // ARIA-label every enemy row (idempotent). Row ORDERING is owned by the
    // enemy-list-memory tick (MODULE 7b) so the two never fight over the DOM —
    // and, critically, so we never promote a real enemy into the game's hidden
    // first-row decoy slot. The old sort here did exactly that: it inserted
    // chests/bounties at enemyList.firstElementChild, which the game then
    // display:none'd as the honeypot decoy — that is why chest targeting
    // appeared broken.
    labelEnemyRows(doc);
    if (CONFIG.enableEnemyListMemory) scheduleEnemyMem();
  }

  function enhanceBattlePage(root) {
    const doc = root || document;

    // Match any battle page, not just page-on-center (it may not have transitioned yet)
    const battlePages = doc.querySelectorAll(
      '[data-page="battle"], .battlepage'
    );
    if (battlePages.length === 0) return;

    for (const battlePage of battlePages) {
      // --- Hide the entire battle info area from screen readers ---
      // The announce() already speaks all damage/HP/reward info,
      // so the raw DOM content is redundant noise for screen readers
      const battleInfo = battlePage.querySelector(".battleInfo");
      if (battleInfo) {
        battleInfo.setAttribute("aria-hidden", "true");
      }

      // Hide reward console from SR too (announced already)
      const consoleEl = battlePage.querySelector("#console");
      if (consoleEl) {
        consoleEl.setAttribute("aria-hidden", "true");
      }

      // Hide confetti/fireworks
      for (const el of battlePage.querySelectorAll("#confetti, #fireworks")) {
        el.setAttribute("aria-hidden", "true");
      }

      // Hide post-battle info table from SR (announced already)
      const postBattle = battlePage.querySelector("#postBattleInfo");
      if (postBattle) {
        postBattle.setAttribute("aria-hidden", "true");
      }

      // --- Label attack buttons (these remain readable) ---
      const attackActions = {
        ".attacklink": "Primary Attack",
        ".speciallink": "Special Attack",
        ".heavylink": "Heavy Attack",
        ".coverlink": "Take Cover",
        ".runlink": "Run Away",
        ".remedylink": "Use Remedy",
      };

      for (const [selector, label] of Object.entries(attackActions)) {
        const btn = battlePage.querySelector(selector);
        if (btn) {
          btn.setAttribute("role", "button");
          btn.setAttribute("aria-label", label);
          btn.setAttribute("tabindex", "0");
        }
      }

      // Super button: label is the equipped weapon's name, read from the
      // button's own .actionimg text (e.g. "Fist of Rage"). Fall back to a
      // generic label if absent. The label changes per build, so don't bake
      // a static string in.
      const superBtn = battlePage.querySelector(".superlink");
      if (superBtn) {
        const superName = superBtn.querySelector(".actionimg")?.textContent?.trim();
        superBtn.setAttribute("role", "button");
        superBtn.setAttribute("aria-label", superName || "Super Attack");
        superBtn.setAttribute("tabindex", "0");
      }

      // Recompute player HP-bar aria-valuenow from the actual "X / Y" HP
      // text shown next to the bars. The raw aria-valuenow the game sets is
      // a stacked-visual width, not the real HP percent.
      fixupBattleHpBars(battlePage);
    }

    // Hide stats bar from SR during battle — it's noise between attacks
    const statsZone = document.querySelector("#statszone");
    if (statsZone) {
      statsZone.setAttribute("aria-hidden", "true");
    }
    // Also hide the bottom toolbar home/chat buttons during battle
    const toolbar = document.querySelector("#bottom");
    if (toolbar) {
      toolbar.setAttribute("aria-hidden", "true");
    }
  }

  // =========================================================================
  //  MODULE 7b: ENEMY LIST MEMORY, DECOY SAFETY & SMART AREA NAVIGATION
  //  Ports the "Titan Conquest – Enemy List Memory" userscript into the
  //  client and wires it to the screen-reader announce()/ARIA pipeline.
  //    • Remembers enemies seen while looking around (TTL, clears on area
  //      change) so they stay clickable instead of vanishing each refresh.
  //    • Never surfaces / harvests / targets the game's hidden first-row
  //      decoy (a likely bot honeypot).
  //    • Orders rows: chests/caches → bounty targets → rest → remembered
  //      (faded), all kept BELOW the hidden decoy row.
  //    • Engaged (clicked) enemies drop on the next list reload; if the
  //      server still shows one, it is harvested back automatically.
  //    • Re-injected rows get the game's battle AJAX rebound + SR labels.
  //  Smart navigation (driven by the keyboard module):
  //    • "[" looks around and announces the Nearby count.
  //    • "/" advances FORWARD by sub-region ("Go to X"), tracking visited
  //      sub-regions so it never walks back to a previous area.
  // =========================================================================

  function enemyListEl() {
    return document.querySelector(".page-on-center #enemyList") ||
           document.querySelector("#enemyList");
  }

  function loadEnemyCache() {
    try { return JSON.parse(sessionStorage.getItem(ENEMY_MEM_KEY)) || { area: "", enemies: {} }; }
    catch (e) { return { area: "", enemies: {} }; }
  }
  function saveEnemyCache(c) {
    try { sessionStorage.setItem(ENEMY_MEM_KEY, JSON.stringify(c)); } catch (e) {}
  }

  // Area identity = navbar "Patrolling X" title + the patrol background image.
  // Returns null when there's no trustworthy reading (mid-transition) so the
  // caller keeps the current area rather than wrongly clearing memory.
  function currentArea() {
    let name = "";
    for (const c of document.querySelectorAll(".navbar .center")) {
      const t = c.textContent.trim();
      if (t.indexOf("Patrolling") === 0) { name = t; break; }
    }
    if (!name) return null;
    const page = document.querySelector('.page-on-center[data-page="patrol"]') ||
                 document.querySelector('.page[data-page="patrol"]');
    let bg = "";
    if (page) {
      const m = (page.getAttribute("style") || "").match(/bgs\/([^'")]+)/);
      if (m) bg = m[1];
    }
    return name + "|" + bg;
  }

  // The game renders a decoy enemy as the list's first <li> and hides it with
  // CSS. Never surface it, never harvest it, never click it. (Position-based,
  // exactly like the userscript: whatever is first is treated as untouchable,
  // so we never place a real row into the slot the game hides.)
  function decoyRow(list, lookLi) {
    const first = list.firstElementChild;
    if (first && first !== lookLi && !first.classList.contains("tc-remembered")) return first;
    return null;
  }

  // A battle row is a chest/cache if it carries a vendor icon or its name says
  // so. "Sacred" rows are deliberately NOT treated as chests (see targeting).
  function isChestRow(a) {
    if (!a) return false;
    if (a.querySelector("img.vendorimg")) return true;
    const t = a.querySelector(".item-title")?.textContent?.trim() || "";
    return t.includes("Cache") || t.includes("Chest") || t.includes("Box");
  }

  function bountyNames() {
    const out = [];
    for (const b of document.querySelectorAll(".bountyrow a[href*='enemy.php']")) {
      const n = b.textContent.trim();
      if (n) out.push(n);
    }
    return out;
  }
  function isBountyRow(a, names) {
    if (!names || !names.length) return false;
    const t = a.querySelector(".item-title")?.textContent?.trim() || "";
    return names.some(b => t.includes(b));
  }

  // ARIA-label every enemy row and attach an Enter/Space activator. Idempotent:
  // aria attributes are skipped if already present (so cached/remembered rows
  // keep their labels) and the key handler is attached once per live node via a
  // JS property that does NOT serialize into outerHTML — so a re-injected clone
  // is treated as fresh and re-bound.
  function labelEnemyRows(scope) {
    const root = scope || document;
    const rows = root.querySelectorAll("a.initBattle[data-enemyguid]");
    for (const enemy of rows) {
      if (!enemy.getAttribute("aria-label")) {
        const name = enemy.querySelector(".item-title")?.textContent?.trim() || "Unknown enemy";
        const ribbon = enemy.querySelector(".ribbon");
        const tier = ribbon ? ", Tier " + ribbon.textContent.trim() : "";
        const afterEl = enemy.querySelector(".item-after");
        let hpText = "";
        if (afterEl) {
          const shieldEl = afterEl.querySelector('[style*="color"]');
          const hasShield = afterEl.querySelector('img[src*="shield"]');
          const rawVal = afterEl.textContent.trim().replace(/\s+/g, "");
          if (hasShield || shieldEl) hpText = ", Shield: " + rawVal;
          else if (rawVal) hpText = ", HP: " + rawVal;
        }
        const ultra = enemy.querySelector('img[src*="ultra"]') ? ", Ultra" : "";
        enemy.setAttribute("role", "button");
        enemy.setAttribute("aria-label", "Attack " + name + tier + ultra + hpText);
        enemy.setAttribute("tabindex", "0");
      }
      if (!enemy.__bw2KeyBound) {
        enemy.__bw2KeyBound = true;
        enemy.addEventListener("keydown", (e) => {
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); enemy.click(); }
        });
      }
    }
  }

  // Replicates the game's patrol.js initBattle AJAX for rows we re-inject from
  // memory (their native click handler was lost when the row left the DOM).
  // The response is fed through mainView.router.loadContent, which the battle
  // hook (MODULE 13) wraps — so SFX / announcements / skip-victory all apply.
  function bindBattle(a) {
    if (!a || a.__bw2BattleBound) return;
    a.__bw2BattleBound = true;
    a.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      if (_memBattleLock) return;
      _memBattleLock = true;
      const guid = (a.getAttribute("data-enemyguid") || "").trim();
      fetch("battle.php", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Requested-With": "XMLHttpRequest",
        },
        body: "guid=" + encodeURIComponent(guid) + "&init=1",
      })
        .then(r => r.text())
        .then(data => {
          _memBattleLock = false;
          if (data && data.indexOf('class="page battlepage"') !== -1 &&
              window.mainView && window.mainView.router) {
            window.mainView.router.loadContent(data);
          } else {
            announce("That enemy is no longer there. Look around again.", "assertive");
          }
        })
        .catch(() => {
          _memBattleLock = false;
          announce("That enemy could not be loaded. Look around again.", "assertive");
        });
    });
  }

  // Order rows under the decoy: chests/caches → bounty targets → rest →
  // remembered (faded). Idempotent — a row is only moved when out of place,
  // so a stable list produces zero DOM mutations (no observer thrash).
  function orderEnemyRows(list, decoy, lookLi) {
    const names = bountyNames();
    const rows = Array.prototype.slice.call(list.children).filter(li =>
      li !== decoy && li !== lookLi && li.querySelector("a.initBattle[data-enemyguid]"));
    const chests = [], bounties = [], rest = [], remembered = [];
    for (const li of rows) {
      if (li.classList.contains("tc-remembered")) { remembered.push(li); continue; }
      const a = li.querySelector("a.initBattle[data-enemyguid]");
      if (isChestRow(a)) chests.push(li);
      else if (isBountyRow(a, names)) bounties.push(li);
      else rest.push(li);
    }
    const desired = chests.concat(bounties, rest, remembered);
    let anchor = decoy; // ordered rows sit right after the hidden decoy row
    for (const li of desired) {
      if (anchor) {
        if (li.previousElementSibling !== anchor) list.insertBefore(li, anchor.nextElementSibling);
      } else if (list.firstElementChild !== li) {
        list.insertBefore(li, list.firstElementChild);
      }
      anchor = li;
    }
    // Keep "Look around…" as the final row.
    if (lookLi && list.lastElementChild !== lookLi) list.appendChild(lookLi);
  }

  // Rewrite "Nearby (N Enemies)" → "Nearby (N Enemies — showing S)". Tolerates
  // the already-rewritten form and only writes when the text actually changes.
  // Returns N (the server's total) or null.
  function updateNearbyTitle(shown) {
    let total = null;
    document.querySelectorAll(".content-block-title").forEach(t => {
      const m = t.textContent.match(/Nearby \((\d+) Enemies(?:\s*—\s*showing \d+)?\)/);
      if (!m) return;
      total = parseInt(m[1], 10);
      const next = "Nearby (" + m[1] + " Enemies — showing " + shown + ")";
      if (t.textContent !== next) t.textContent = next;
    });
    return total;
  }

  function countShown(list) {
    let shown = 0;
    list.querySelectorAll("a.initBattle[data-enemyguid]").forEach(a => {
      const li = a.closest("li");
      if (li && li.offsetParent !== null) shown++;
    });
    return shown;
  }

  // The core pass. Mirrors the userscript's tick() with client integration.
  function enemyMemTick() {
    if (!CONFIG.enableEnemyListMemory) return;
    const list = enemyListEl();
    if (!list) return;

    const now = Date.now();
    const cache = loadEnemyCache();
    const area = currentArea();

    // Fresh list render (look-around / post-battle / navigation): purge engaged
    // enemies from memory NOW so they aren't re-injected.
    if (!list.dataset.tcSeen) {
      list.dataset.tcSeen = "1";
      _engagedGuids.forEach(guid => { delete cache.enemies[guid]; });
      _engagedGuids.clear();
    }

    // Only clear when we POSITIVELY read a different area.
    if (area !== null && cache.area !== area) {
      cache.area = area;
      cache.enemies = {};
      list.querySelectorAll("li.tc-remembered").forEach(li => li.remove());
    }

    const lookA = list.querySelector("a.nothinglink");
    const lookLi = lookA ? lookA.closest("li") : null;
    const decoy = decoyRow(list, lookLi);

    // Expire old memories.
    for (const guid in cache.enemies) {
      if (now - cache.enemies[guid].ts > CONFIG.enemyMemoryTtlMs) delete cache.enemies[guid];
    }

    // Label native rows BEFORE harvesting so cached HTML carries the SR label.
    labelEnemyRows(list);

    // Harvest native rows currently shown (refreshes timestamps). Skip the
    // decoy and anything already engaged this round.
    list.querySelectorAll("li:not(.tc-remembered) a.initBattle[data-enemyguid]").forEach(a => {
      const li = a.closest("li");
      if (li === decoy) return;
      const guid = (a.getAttribute("data-enemyguid") || "").trim();
      if (!guid || _engagedGuids.has(guid)) return;
      cache.enemies[guid] = { html: li.outerHTML, ts: now };
    });

    // Remove injected rows whose memory expired (engaged rows stay until reload).
    list.querySelectorAll("li.tc-remembered a.initBattle[data-enemyguid]").forEach(a => {
      const guid = a.getAttribute("data-enemyguid").trim();
      if (!cache.enemies[guid] && !_engagedGuids.has(guid)) a.closest("li").remove();
    });

    // What's already on screen (decoy included, so we never duplicate it).
    const inDom = new Set();
    list.querySelectorAll("a.initBattle[data-enemyguid]").forEach(a => {
      inDom.add(a.getAttribute("data-enemyguid").trim());
    });

    // Append remembered enemies not currently shown.
    for (const guid in cache.enemies) {
      if (inDom.has(guid) || _engagedGuids.has(guid)) continue;
      const tmp = document.createElement("ul");
      tmp.innerHTML = cache.enemies[guid].html;
      const li = tmp.firstElementChild;
      if (!li) continue;
      li.classList.add("tc-remembered");
      const link = li.querySelector("a.initBattle");
      if (link) bindBattle(link);
      list.appendChild(li);
    }

    // Re-label (covers freshly re-injected remembered rows) and order.
    labelEnemyRows(list);
    orderEnemyRows(list, decoy, lookLi);

    saveEnemyCache(cache);
    updateNearbyTitle(countShown(list));
  }

  function scheduleEnemyMem() {
    if (_memScheduled) return;
    _memScheduled = true;
    requestAnimationFrame(() => { _memScheduled = false; safeRun("enemyMemTick", enemyMemTick); });
  }

  function setupEnemyListMemory() {
    // NB: we install the machinery unconditionally (not gated on
    // CONFIG.enableEnemyListMemory) so the feature can be toggled live from the
    // settings dialog. The actual work in enemyMemTick() is what checks the flag
    // and no-ops when memory is off; the 1s interval/observer then cost
    // effectively nothing.

    // Faintly dim remembered rows so sighted play can tell them apart.
    const css = document.createElement("style");
    css.textContent = "#enemyList li.tc-remembered { opacity: 0.85; }";
    document.head.appendChild(css);

    // Flag clicked enemies (capture phase, covers native AND remembered rows).
    // They get purged from memory on the next list reload. If the extra-attacks
    // one-shot is armed, this is also where the burst fires (then disarms).
    document.addEventListener("click", function (e) {
      const a = e.target.closest && e.target.closest("a.initBattle[data-enemyguid]");
      if (!a) return;
      const guid = (a.getAttribute("data-enemyguid") || "").trim();
      if (guid) {
        _engagedGuids.add(guid);
        fireExtraAttacks(guid);
      }
    }, true);

    // Re-run on every AJAX content change the main observer reports, plus a
    // 1s heartbeat so TTL expiry happens even when the DOM is idle.
    observeNewContent(() => scheduleEnemyMem());
    setInterval(() => safeRun("enemyMemTick:interval", enemyMemTick), 1000);
    scheduleEnemyMem();
  }

  // ---- Smart area navigation ("/" key) ----------------------------------
  function loadVisitedAreas() {
    try { return JSON.parse(localStorage.getItem(VISITED_AREAS_KEY)) || {}; }
    catch (e) { return {}; }
  }
  function saveVisitedAreas(v) {
    try { localStorage.setItem(VISITED_AREAS_KEY, JSON.stringify(v)); } catch (e) {}
  }
  function markAreaVisited(id) {
    if (!id) return;
    const v = loadVisitedAreas();
    v[String(id)] = Date.now();
    saveVisitedAreas(v);
  }

  function centerPage() {
    return document.querySelector(".page-on-center") ||
           document.querySelector(".view-main") ||
           document;
  }

  // Forward ("Go to X") sub-region links within a scope. The game labels links
  // "Go to X" (forward) vs "Go back to X" (backward) and renders home base as a
  // separate .towerlink, so we keep only visible, non-tower .gobacklink nodes
  // whose label starts with "Go to".
  function forwardLinksIn(scope) {
    const labelOf = a => (a.getAttribute("aria-label") || a.textContent || "").trim();
    return Array.prototype.slice
      .call(scope.querySelectorAll("a.gobacklink[data-subregionid]"))
      .filter(a => a.offsetParent !== null && !a.classList.contains("towerlink"))
      .filter(a => /^go to\b/i.test(labelOf(a)));
  }

  // Best "go forward" sub-region link on the current patrol page, preferring an
  // unvisited sub-region. Scopes to the center page first; if nothing's there,
  // searches the whole document but NEVER the side panel (F7 keeps closed
  // panels in the DOM, transform-hidden, so a panel "Go back" link could
  // otherwise sneak in). Returns null when only backward/home links exist.
  function findForwardAreaLink() {
    let forward = forwardLinksIn(centerPage());
    if (!forward.length) {
      forward = forwardLinksIn(document).filter(a => !a.closest(".panel"));
    }
    if (!forward.length) return null; // never walk backward
    const visited = loadVisitedAreas();
    const unvisited = forward.filter(a => !visited[String(a.getAttribute("data-subregionid"))]);
    return unvisited[0] || forward[0];
  }

  function smartGoToNextArea() {
    const fwd = findForwardAreaLink();
    if (fwd) {
      const id = fwd.getAttribute("data-subregionid");
      const label = (fwd.getAttribute("aria-label") || fwd.textContent || "")
        .trim().replace(/^Go (back )?to\s*/i, "");
      markAreaVisited(id);
      fwd.click();
      announce("Going to " + label, "assertive");
      return true;
    }
    // No new sub-region ahead — open "Go somewhere else" to pick the next
    // location/planet. (Auto-selecting the next planet on changelocation.php
    // needs that page's DOM; this is the manual fallback.)
    const elsewhere = centerPage().querySelector('a[href="changelocation.php"]');
    if (elsewhere && elsewhere.offsetParent !== null) {
      elsewhere.click();
      announce("No new area ahead. Opening Go somewhere else to choose the next location.", "assertive");
      return true;
    }
    announce("No area to advance to.", "assertive");
    return false;
  }

  // ---- "[" look around + announce the Nearby count ----------------------
  function announceNearbyCount() {
    const list = enemyListEl();
    const shown = list ? countShown(list) : 0;
    let total = shown;
    document.querySelectorAll(".content-block-title").forEach(t => {
      const m = t.textContent.match(/Nearby \((\d+) Enemies/);
      if (m) total = parseInt(m[1], 10);
    });
    announce(total + " enemies, showing " + shown + " on screen.", "assertive");
  }

  function lookAroundAndAnnounce() {
    const btn = document.querySelector(".page-on-center a.nothinglink") ||
                document.querySelector("a.nothinglink");
    if (!btn) { announce("Nothing to look around here.", "assertive"); return false; }
    simulateRealClick(btn);
    // Announce the count once the look-around response has swapped the list in.
    setTimeout(() => safeRun("lookAround:announce", announceNearbyCount), 500);
    return true;
  }

  // Drop all remembered (re-injected) rows and clear the session cache. Used
  // when enemy-list memory is switched off from the dialog so stale faded
  // rows don't linger on screen.
  function clearRememberedRows() {
    const list = enemyListEl();
    if (list) list.querySelectorAll("li.tc-remembered").forEach(li => li.remove());
    const cache = loadEnemyCache();
    cache.enemies = {};
    saveEnemyCache(cache);
  }

  // ---- Extra attacks (one-shot) -----------------------------------------
  // Fire-and-forget N extra battle.php "init" POSTs against a guid, all at
  // once (parallel), on top of the normal click. Gated by CONFIG so it's a
  // no-op until armed in the settings dialog. Because it's a one-shot, firing
  // immediately disables the toggle again, so the burst only happens on a
  // single enemy click — the user re-arms it from the dialog for another.
  function fireExtraAttacks(guid) {
    if (!guid || !CONFIG.enableExtraAttacks) return;
    const n = Math.max(0, CONFIG.extraAttackCount | 0);
    if (n <= 0) return;
    for (let i = 0; i < n; i++) {
      fetch("battle.php", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Requested-With": "XMLHttpRequest",
        },
        body: "guid=" + encodeURIComponent(guid) + "&init=1",
      }).catch(() => {});
    }
    // One-shot: turn it back off (and persist off) so the next click is normal.
    setExtraOn(false);
    if (_settingsControls && _settingsControls.extraCb) _settingsControls.extraCb.checked = false;
    announce(n + " extra attacks fired. Extra attacks off.", "assertive");
  }

  // =========================================================================
  //  MODULE 7c: SETTINGS DIALOG (Ctrl+Shift+T)
  //  A small, screen-reader-friendly modal exposing the extra-attacks and
  //  enemy-list-memory options. Opens/closes with Ctrl+Shift+T (Esc or the
  //  Close button also close). Values persist in localStorage and override the
  //  CONFIG defaults at startup.
  // =========================================================================
  const EXTRA_MAX = 200;
  const TTL_MIN_S = 5;
  const TTL_MAX_S = 3600;
  const TTL_DEFAULT_S = 60;
  const SET_EXTRA_ON_KEY  = "tcExtraAttacks";  // "1"/"0"
  const SET_EXTRA_CNT_KEY = "tcExtraCount";    // int 0..EXTRA_MAX
  const SET_MEM_ON_KEY    = "bw2_enemy_memory"; // "1"/"0"
  const SET_TTL_KEY       = "tcEnemyTTL";      // seconds, TTL_MIN_S..TTL_MAX_S

  // Pull any persisted settings into CONFIG. Called once, early in init(),
  // before the memory module reads CONFIG.
  function loadPersistedSettings() {
    try {
      const on = localStorage.getItem(SET_EXTRA_ON_KEY);
      if (on !== null) CONFIG.enableExtraAttacks = on === "1";
      const cnt = parseInt(localStorage.getItem(SET_EXTRA_CNT_KEY), 10);
      if (!isNaN(cnt)) CONFIG.extraAttackCount = Math.max(0, Math.min(EXTRA_MAX, cnt));
      const mem = localStorage.getItem(SET_MEM_ON_KEY);
      if (mem !== null) CONFIG.enableEnemyListMemory = mem === "1";
      const ttl = parseInt(localStorage.getItem(SET_TTL_KEY), 10);
      if (!isNaN(ttl)) CONFIG.enemyMemoryTtlMs = Math.max(TTL_MIN_S, Math.min(TTL_MAX_S, ttl)) * 1000;
    } catch (e) {}
  }

  // Setters: update live CONFIG and persist together so a change takes effect
  // immediately and survives a restart.
  function setExtraOn(on) {
    CONFIG.enableExtraAttacks = !!on;
    try { localStorage.setItem(SET_EXTRA_ON_KEY, on ? "1" : "0"); } catch (e) {}
  }
  function setExtraCount(n) {
    n = Math.max(0, Math.min(EXTRA_MAX, n | 0));
    CONFIG.extraAttackCount = n;
    try { localStorage.setItem(SET_EXTRA_CNT_KEY, String(n)); } catch (e) {}
  }
  function setMemoryOn(on) {
    CONFIG.enableEnemyListMemory = !!on;
    try { localStorage.setItem(SET_MEM_ON_KEY, on ? "1" : "0"); } catch (e) {}
  }
  function setTtlSeconds(s) {
    s = Math.max(TTL_MIN_S, Math.min(TTL_MAX_S, s | 0));
    CONFIG.enemyMemoryTtlMs = s * 1000;
    try { localStorage.setItem(SET_TTL_KEY, String(s)); } catch (e) {}
  }

  let _settingsOverlay = null;
  let _settingsControls = null;
  let _settingsPrevFocus = null;

  // Build the dialog DOM once, lazily on first open.
  function buildSettingsDialog() {
    if (_settingsOverlay) return;

    const style = document.createElement("style");
    style.textContent =
      ".bw2-set-overlay{position:fixed;inset:0;z-index:2147483600;display:none;" +
        "align-items:center;justify-content:center;background:rgba(0,0,0,.6);" +
        "font:14px/1.4 sans-serif;color:#eee}" +
      ".bw2-set-overlay.bw2-open{display:flex}" +
      ".bw2-set-panel{min-width:320px;max-width:92vw;background:#1c1d1f;" +
        "border:1px solid #444;border-radius:10px;box-shadow:0 10px 40px rgba(0,0,0,.7);" +
        "padding:18px 20px}" +
      ".bw2-set-panel h2{margin:0 0 6px;font-size:16px;color:#FFD700}" +
      ".bw2-set-row{display:flex;align-items:center;justify-content:space-between;" +
        "gap:16px;margin:14px 0;cursor:default}" +
      ".bw2-set-row>span{flex:1 1 auto}" +
      ".bw2-set-row input[type=number]{width:72px;text-align:center;background:#111;" +
        "color:#fff;border:1px solid #555;border-radius:6px;padding:6px;font:14px sans-serif}" +
      ".bw2-set-row input[type=checkbox]{width:18px;height:18px;flex:0 0 auto}" +
      ".bw2-set-foot{margin-top:18px;display:flex;justify-content:space-between;align-items:center}" +
      ".bw2-set-hint{color:#888;font-size:12px}" +
      ".bw2-set-close{background:#3a3b3d;color:#fff;border:1px solid #555;border-radius:6px;" +
        "padding:7px 16px;cursor:pointer;font:14px sans-serif}" +
      ".bw2-set-close:hover{background:#4a4b4d}";
    document.head.appendChild(style);

    const overlay = document.createElement("div");
    overlay.className = "bw2-set-overlay";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "Bloodwar 2 settings");
    overlay.addEventListener("click", (e) => { if (e.target === overlay) closeSettingsDialog(); });
    // Keep keys pressed inside the dialog from leaking to the game's bubble-phase
    // shortcut handlers (covers the case where focus is on the Close button).
    overlay.addEventListener("keydown", (e) => { e.stopPropagation(); });

    const panel = document.createElement("div");
    panel.className = "bw2-set-panel";

    const h = document.createElement("h2");
    h.textContent = "⚔ Bloodwar 2 Settings";
    panel.appendChild(h);

    // A labelled row: the whole <label> toggles/focuses its control.
    const settingRow = (labelText, control) => {
      const row = document.createElement("label");
      row.className = "bw2-set-row";
      const span = document.createElement("span");
      span.textContent = labelText;
      row.appendChild(span);
      row.appendChild(control);
      panel.appendChild(row);
      return control;
    };

    // Extra attacks: arm/disarm (one-shot). Checking it fires the burst on the
    // next enemy click, then auto-unchecks.
    const extraCb = document.createElement("input");
    extraCb.type = "checkbox";
    extraCb.addEventListener("change", () => {
      setExtraOn(extraCb.checked);
      announce("Extra attacks " + (extraCb.checked ? "armed" : "off") + ".", "polite");
    });
    settingRow("Extra attacks on next enemy click (one-shot)", extraCb);

    // Extra attacks count (how many parallel attacks the one-shot fires)
    const cntInput = document.createElement("input");
    cntInput.type = "number"; cntInput.min = "0"; cntInput.max = String(EXTRA_MAX); cntInput.step = "1";
    cntInput.addEventListener("change", () => {
      setExtraCount(parseInt(cntInput.value, 10) || 0);
      cntInput.value = String(CONFIG.extraAttackCount); // reflect clamping
    });
    settingRow("Extra attacks count (0 to " + EXTRA_MAX + ")", cntInput);

    // Enemy-list memory ON/OFF
    const memCb = document.createElement("input");
    memCb.type = "checkbox";
    memCb.addEventListener("change", () => {
      setMemoryOn(memCb.checked);
      if (memCb.checked) scheduleEnemyMem();
      else clearRememberedRows();
      announce("Enemy memory " + (memCb.checked ? "on" : "off") + ".", "polite");
    });
    settingRow("Remember enemies seen while looking around", memCb);

    // Memory lifetime (TTL) in seconds
    const ttlInput = document.createElement("input");
    ttlInput.type = "number";
    ttlInput.min = String(TTL_MIN_S); ttlInput.max = String(TTL_MAX_S); ttlInput.step = "5";
    ttlInput.addEventListener("change", () => {
      setTtlSeconds(parseInt(ttlInput.value, 10) || TTL_DEFAULT_S);
      ttlInput.value = String(Math.round(CONFIG.enemyMemoryTtlMs / 1000)); // reflect clamping
    });
    settingRow("Memory lifetime, seconds (" + TTL_MIN_S + " to " + TTL_MAX_S + ")", ttlInput);

    // Footer: hint + close
    const foot = document.createElement("div");
    foot.className = "bw2-set-foot";
    const hint = document.createElement("span");
    hint.className = "bw2-set-hint";
    hint.textContent = "Ctrl+Shift+T or Esc to close";
    const closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "bw2-set-close";
    closeBtn.textContent = "Close";
    closeBtn.addEventListener("click", closeSettingsDialog);
    foot.appendChild(hint);
    foot.appendChild(closeBtn);
    panel.appendChild(foot);

    overlay.appendChild(panel);
    document.body.appendChild(overlay);

    _settingsOverlay = overlay;
    _settingsControls = { extraCb, cntInput, memCb, ttlInput };
  }

  // Reflect current CONFIG into the controls (called on every open).
  function paintSettingsDialog() {
    if (!_settingsControls) return;
    _settingsControls.extraCb.checked = CONFIG.enableExtraAttacks;
    _settingsControls.cntInput.value = String(CONFIG.extraAttackCount);
    _settingsControls.memCb.checked = CONFIG.enableEnemyListMemory;
    _settingsControls.ttlInput.value = String(Math.round(CONFIG.enemyMemoryTtlMs / 1000));
  }

  function settingsOpen() {
    return !!_settingsOverlay && _settingsOverlay.classList.contains("bw2-open");
  }
  function openSettingsDialog() {
    buildSettingsDialog();
    paintSettingsDialog();
    _settingsPrevFocus = document.activeElement;
    _settingsOverlay.classList.add("bw2-open");
    const first = _settingsOverlay.querySelector("input,button");
    if (first) first.focus();
    announce("Settings opened.", "assertive");
  }
  function closeSettingsDialog() {
    if (!settingsOpen()) return;
    _settingsOverlay.classList.remove("bw2-open");
    announce("Settings closed.", "polite");
    try { if (_settingsPrevFocus && _settingsPrevFocus.focus) _settingsPrevFocus.focus(); } catch (e) {}
    _settingsPrevFocus = null;
  }
  function toggleSettingsDialog() {
    buildSettingsDialog();
    if (settingsOpen()) closeSettingsDialog();
    else openSettingsDialog();
  }

  // Ctrl+Shift+T toggles the dialog; Esc closes it while open. Capture phase +
  // stopImmediatePropagation so it beats the game's own key bindings (and the
  // browser's "reopen closed tab"). Independent of enableKeyboardShortcuts.
  function setupSettingsDialog() {
    document.addEventListener("keydown", (e) => {
      if (e.ctrlKey && e.shiftKey && !e.altKey && !e.metaKey &&
          (e.code === "KeyT" || e.key === "T" || e.key === "t")) {
        e.preventDefault();
        e.stopImmediatePropagation();
        safeRun("toggleSettingsDialog", toggleSettingsDialog);
        return;
      }
      if (settingsOpen() && e.key === "Escape") {
        e.preventDefault();
        e.stopImmediatePropagation();
        closeSettingsDialog();
      }
    }, true); // capture
  }

  // =========================================================================
  //  MODULE 8: ACCESSIBILITY — Focus Management
  // =========================================================================
  function setupFocusManagement() {
    if (!CONFIG.enableFocusManagement) return;

    const style = document.createElement("style");
    style.textContent = `
      a:focus-visible, button:focus-visible, [tabindex]:focus-visible,
      input:focus-visible, select:focus-visible, textarea:focus-visible {
        outline: 2px solid #FFD700 !important;
        outline-offset: 2px !important;
      }
      #tc-skip-nav {
        position: absolute;
        top: -100px;
        left: 10px;
        background: #1a1a1a;
        color: #FFD700;
        padding: 8px 16px;
        z-index: 100000;
        border: 2px solid #FFD700;
        border-radius: 4px;
        font-size: 14px;
        text-decoration: none;
        transition: top 0.2s;
      }
      #tc-skip-nav:focus {
        top: 10px;
      }
      .tc-sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0,0,0,0);
        white-space: nowrap;
        border: 0;
      }

      /* ===== Hide inactive Framework7 pages from screen readers ===== */
      /* Only hide pages explicitly moved off-screen by Framework7 */
      .page.page-on-left,
      .page.page-on-right {
        visibility: hidden !important;
      }


      /* ===== Battle UI Simplification ===== */

      /* Hide player and enemy portrait images */
      .playerImg, .enemyImg {
        display: none !important;
      }

      /* Hide reward chip icons in battle (announced via SFX/speech) */
      .battlepage #console .chip-media {
        display: none !important;
      }

      /* Make player/enemy info take full width without portraits */
      .playerBattleInfo, .enemyBattleInfo {
        width: 100% !important;
        display: block !important;
      }

      /* Style HP/Shield progress bars */
      .battlepage .progress {
        height: 16px !important;
        border-radius: 4px !important;
        background: #333 !important;
        margin: 4px 0 !important;
        overflow: hidden !important;
      }
      .battlepage .progress .progress-bar {
        height: 100% !important;
      }
      .battlepage .progress .progress-bar:first-child {
        background-color: #4CAF50 !important;
      }
      .battlepage .progress.shield .progress-bar:first-child {
        background-color: #71b5fa !important;
      }
      .battlepage .progress .progress-bar:nth-child(2) {
        background-color: #c0392b !important;
      }

      /* Player info text styling */
      .playerInfo, .enemyInfo {
        font-size: 14px !important;
        padding: 8px !important;
      }

      /* Reward chips — text only, no icons */
      .battlepage #console .chip {
        margin: 4px 2px !important;
      }

      /* Battle info layout */
      .battleInfo {
        max-width: 500px !important;
        margin: 0 auto !important;
      }
    `;
    document.head.appendChild(style);

    if (CONFIG.enableSkipNav) {
      const skipLink = document.createElement("a");
      skipLink.id = "tc-skip-nav";
      skipLink.href = "#";
      skipLink.textContent = "Skip to main content";
      skipLink.addEventListener("click", (e) => {
        e.preventDefault();
        const main = document.querySelector(
          ".view-main .page-on-center .page-content"
        );
        if (main) {
          main.setAttribute("tabindex", "-1");
          main.focus();
        }
      });
      document.body.prepend(skipLink);
    }
  }

  function focusAfterPageLoad() {
    if (!CONFIG.enableFocusManagement) return;
    const gen = ++_focusGeneration;
    _pageTransitioning = true;
    // Safety: always clear flag after 500ms max
    setTimeout(function () { _pageTransitioning = false; }, 500);
    setTimeout(function () {
      if (gen === _focusGeneration && !userIsTyping()) {
        doFocusAndHide();
      }
      _pageTransitioning = false;
    }, 50);
  }

  function doFocusAndHide() {
    try {
      // Don't steal focus if the user is editing a field — pulling focus
      // away from a chat / count input mid-keystroke makes the input drop
      // characters and (under some Chrome versions) refuse to refocus
      // until reload.
      if (userIsTyping()) return;

      // Don't steal focus if we're on a battle page
      const activePage = document.querySelector(".page-on-center");
      if (activePage?.classList?.contains("battlepage") ||
          activePage?.getAttribute("data-page") === "battle") {
        return;
      }

      // Find the enemy list
      const enemyList = document.querySelector(
        ".page-on-center #enemyList"
      ) || document.querySelector("#enemyList");

      if (enemyList) {
        // Remove disabled from all enemies so they're focusable
        for (const enemy of enemyList.querySelectorAll("a.initBattle[disabled]")) {
          enemy.removeAttribute("disabled");
        }
        enemyList.setAttribute("tabindex", "-1");
        enemyList.focus();
        return;
      }

      // Fallback — focus page content
      const pageContent = document.querySelector(
        ".page-on-center .page-content"
      );
      if (pageContent) {
        pageContent.setAttribute("tabindex", "-1");
        pageContent.focus();
      }
    } finally {
      _pageTransitioning = false;
    }
  }

  // =========================================================================
  //  MODULE 9: ACCESSIBILITY — Keyboard Shortcuts for Battle
  // =========================================================================
  function setupKeyboardShortcuts() {
    if (!CONFIG.enableKeyboardShortcuts) return;

    // Shared cooldown for the 1/2/3/4/5 attack keys. We accept the first press
    // immediately, then swallow any subsequent attack-key press (across all
    // five keys) until CONFIG.attackKeyThrottleMs has elapsed.
    let _lastAttackKeyMs = 0;

    // ---- Attack-key throttle (CAPTURE PHASE) ----
    // Runs BEFORE any other keydown listener (ours or the game's). If we're
    // inside the cooldown window, stopImmediatePropagation() kills the event
    // so neither the enhancer's own bubble-phase handler below NOR the game's
    // native key bindings can act on it. Press fires instantly; next 1-5
    // press within the cooldown is dropped completely.
    document.addEventListener("keydown", (e) => {
      if (e.key !== "1" && e.key !== "2" && e.key !== "3" &&
          e.key !== "4" && e.key !== "5") return;
      const tag = e.target.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (e.repeat) { e.preventDefault(); e.stopImmediatePropagation(); return; }
      const now = Date.now();
      if (now - _lastAttackKeyMs < CONFIG.attackKeyThrottleMs) {
        e.preventDefault();
        e.stopImmediatePropagation();
        return;
      }
      _lastAttackKeyMs = now;
    }, true); // capture

    document.addEventListener("keydown", (e) => {
      const tag = e.target.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;

      // "/" — advance FORWARD to the next sub-region ("Go to X"), tracking
      // visited areas so it never walks back to a previous one. Falls back to
      // "Go somewhere else" when there's no new area ahead.
      if (e.key === "/") {
        e.preventDefault();
        smartGoToNextArea();
        return;
      }

      // "[" — look around for new enemies, then announce the Nearby count.
      // The priority attack keys (1/2/3) handle targeting from there.
      if (e.key === "[") {
        e.preventDefault();
        lookAroundAndAnnounce();
        return;
      }

      const battlePage = document.querySelector(
        '.page-on-center[data-page="battle"], .page-on-center [data-page="battle"]'
      );

      // Keys 1, 2, 3: battle/patrol actions (must be pressed, not held)
      if (e.key === "1" || e.key === "2" || e.key === "3") {
        if (e.repeat) return;
        if (battlePage) {
          // [selector, announce-label, weapon-fire attack type]
          const attackMap = {
            "1": [".attacklink",  "Primary Attack", "Primary"],
            "2": [".speciallink", "Special Attack", "Special"],
            "3": [".heavylink",   "Heavy Attack",   "Heavy"],
          };
          const [selector, label, atkType] = attackMap[e.key];
          // Play the weapon fire SFX immediately on press IF the attack
          // button is actually present (i.e. the action will dispatch).
          // Doing this on keypress instead of on the response guarantees
          // the sound plays even on a 1-shot kill, where the response
          // page reshapes its DOM and the attack type is no longer in
          // .playerInfo.
          if (battlePage.querySelector(selector)) {
            playWeaponFireSfx(atkType);
            // Layer the bounty marker SFX on top of the weapon fire when
            // the current enemy is one of our active bounty targets.
            if (isCurrentEnemyBounty()) playBountyEnemySfx();
          }
          clickIfExists(battlePage, selector, label);
          clickIfExists(battlePage, ".patrollink", "Back to patrol");
        } else {
          // On patrol — prioritize: bounty > container > regular (same as auto)
          const target = findPriorityTarget();
          if (target) {
            const name = target.querySelector(".item-title")?.textContent?.trim() || "enemy";
            // Patrol-click on a bounty enemy = first-strike against it.
            // Fire the bounty marker now (the response-side backstop will
            // handle the weapon fire sound if this turns into a 1-shot kill).
            if (isEnemyNameBounty(name)) playBountyEnemySfx();
            target.click();
            announce("Attacking " + name, "assertive");
          } else {
            clickNothingLink();
          }
        }
        return;
      }

      if (battlePage && !e.repeat) {
        switch (e.key) {
          case "4": {
            // Same fire-on-press pattern as 1/2/3 above. Illumina maps to
            // the Primary weapon's fire sound by default (see
            // ATTACK_TYPE_TO_SLOT in the SFX module).
            const superBtn = battlePage.querySelector(".superlink");
            if (superBtn) {
              playWeaponFireSfx("Illumina");
              if (isCurrentEnemyBounty()) playBountyEnemySfx();
              const superName = superBtn.querySelector(".actionimg")?.textContent?.trim();
              clickIfExists(battlePage, ".superlink", superName || "Super Attack");
            }
            break;
          }
          case "5":
            clickIfExists(battlePage, ".coverlink", "Take Cover");
            break;
          case "r":
          case "R":
            clickIfExists(battlePage, ".remedylink", "Use Remedy");
            break;
          case "Escape":
            clickIfExists(battlePage, ".runlink", "Run Away");
            break;
        }
      }

      // H — say my health %. Shift+H — say enemy health/shield with which
      // bar. Alt+H is a separate "navigate Home" binding handled below.
      // Only active during battle; falls through silently otherwise so we
      // don't shadow anything the game might do with the key.
      if ((e.key === "h" || e.key === "H") && !e.altKey && !e.ctrlKey && !e.metaKey) {
        const bp = document.querySelector(
          '.page-on-center[data-page="battle"], .page-on-center.battlepage'
        );
        if (bp) {
          e.preventDefault();
          if (e.shiftKey) {
            const bars = readEnemyBars();
            if (bars.length) {
              const phrase = bars
                .map(b => (b.kind === "shield" ? "Shield " : "Health ") + Math.round(b.pct) + " percent")
                .join(", ");
              announce("Enemy " + phrase + ".", "assertive");
            }
          } else {
            const pct = getPlayerHpPercent();
            if (pct != null) {
              announce("Health " + Math.round(pct) + " percent.", "assertive");
            }
          }
          return;
        }
      }

      // X — read player stats aloud
      if (e.key === "x" || e.key === "X") {
        const statsZone = document.querySelector("#statszone");
        if (statsZone) {
          const chips = statsZone.querySelectorAll(".chip-label");
          const statLabels = ["XP", "Drachma", "LP", "AC", "VM"];
          const parts = [];
          chips.forEach((chip, i) => {
            const val = chip.textContent.trim();
            const label = statLabels[i] || "";
            if (val && label) {
              parts.push(label + " " + val);
            } else if (val) {
              parts.push(val);
            }
          });
          if (parts.length > 0) {
            announce(parts.join(". "), "assertive");
          }
        }
        return;
      }

      if (e.altKey) {
        switch (e.key) {
          case "b":
          case "B":
            e.preventDefault();
            {
              const bounties = getBountyDetails();
              if (!bounties.length) {
                announce("No active bounties.", "assertive");
              } else {
                const parts = bounties.map(b =>
                  b.name + ": " + b.current + " out of " + b.total + " kills"
                );
                announce(parts.join(". ") + ".", "assertive");
              }
            }
            break;
          case "h":
            e.preventDefault();
            navigateTo("index.php", "Home");
            break;
          case "p":
            e.preventDefault();
            navigateTo("patrol.php", "Patrol");
            break;
          case "i":
            e.preventDefault();
            navigateTo("inventory.php", "Inventory");
            break;
          case "g":
            e.preventDefault();
            navigateTo("gear.php", "Gear");
            break;
          case "v":
            e.preventDefault();
            CONFIG.skipVictoryScreen = !CONFIG.skipVictoryScreen;
            announce(
              "Skip victory screen: " +
                (CONFIG.skipVictoryScreen ? "ON" : "OFF"),
              "assertive"
            );
            break;
          case "s":
            e.preventDefault();
            CONFIG.audioEnabled = !CONFIG.audioEnabled;
            announce(
              "Audio: " + (CONFIG.audioEnabled ? "ON" : "OFF"),
              "assertive"
            );
            break;
          case "t":
          case "T":
            e.preventDefault();
            {
              const playerInfo = document.querySelector(".page-on-center .playerInfo");
              const m = playerInfo?.textContent?.match(/Total Damage:\s*([\d,]+)/i);
              if (m) announce("Total damage: " + m[1], "assertive");
            }
            break;
          case "=":
          case "+":
            e.preventDefault();
            sfxVolume = Math.min(sfxVolume + 0.1, 2.0);
            announce("Volume " + Math.round(sfxVolume * 100) + "%", "assertive");
            break;
          case "-":
            e.preventDefault();
            sfxVolume = Math.max(sfxVolume - 0.1, 0.1);
            announce("Volume " + Math.round(sfxVolume * 100) + "%", "assertive");
            break;
        }
      }
    });
  }

  function simulateRealClick(el) {
    // Dispatch a trusted-looking mouse event that Framework7 will process
    const rect = el.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;
    el.removeAttribute("disabled");

    const events = ["pointerdown", "mousedown", "pointerup", "mouseup", "click"];
    for (const type of events) {
      el.dispatchEvent(new MouseEvent(type, {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: x,
        clientY: y,
      }));
    }
  }

  function findPriorityTarget() {
    const list = enemyListEl();
    if (!list) return null;
    // Visible battle rows only — this skips the game's hidden first-row decoy
    // (the honeypot) and any other display:none rows, and skips enemies already
    // engaged this round so we don't re-target one that's mid-battle.
    const rows = Array.prototype.slice
      .call(list.querySelectorAll("a.initBattle[data-enemyguid]"))
      .filter(a => {
        const li = a.closest("li");
        if (!li || li.offsetParent === null) return false;
        const guid = (a.getAttribute("data-enemyguid") || "").trim();
        return !(guid && _engagedGuids.has(guid));
      });
    if (!rows.length) return null;
    const names = bountyNames();
    // 1) bounty targets
    for (const a of rows) {
      const t = a.querySelector(".item-title")?.textContent?.trim() || "";
      if (t && names.some(b => t.includes(b))) return a;
    }
    // 2) chests / caches (never Sacred)
    for (const a of rows) {
      const t = a.querySelector(".item-title")?.textContent?.trim() || "";
      if (t.includes("Sacred")) continue;
      if (isChestRow(a)) return a;
    }
    // 3) first non-Sacred enemy with a name
    for (const a of rows) {
      const t = a.querySelector(".item-title")?.textContent?.trim() || "";
      if (t && !t.includes("Sacred")) return a;
    }
    return null;
  }

  function clickNothingLink() {
    // No enemies — simulate a real click on the "Look around" / "Nothing nearby" button
    const btn = document.querySelector(".page-on-center .nothinglink");
    if (btn) {
      simulateRealClick(btn);
      announce("Looking around", "assertive");
      return true;
    }
    return false;
  }

  // Page transition focus management
  let _pageTransitioning = false;
  let _focusGeneration = 0;

  // True when the user is actively in an editable field. Programmatic focus
  // steals (attack-button focus, enemy-list focus, etc.) must skip in this
  // state, or chat / bulk-infuse-count inputs go dead and require a refresh
  // to recover — Chrome will not re-route keystrokes to an input once an
  // AJAX response yanks focus to a `tabindex="-1"` button mid-typing.
  function userIsTyping() {
    const a = document.activeElement;
    if (!a) return false;
    const tag = a.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
    if (a.isContentEditable) return true;
    return false;
  }

  // Block Enter key on unsafe targets during page transitions (capture phase)
  document.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && _pageTransitioning) {
      const tag = e.target.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      const isSafe = e.target.closest("#enemyList") ||
        e.target.closest(".attacklink, .speciallink, .heavylink, .superlink, .patrollink, .runlink");
      if (!isSafe) {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    }
  }, true);

  function clickIfExists(root, selector, actionName) {
    const el = root.querySelector(selector);
    if (el) {
      el.click();
      announce(actionName, "assertive");
    }
  }

  function navigateTo(page, label) {
    const link = document.querySelector(`.panel-left a[href="${page}"]`);
    if (link) {
      link.click();
      announce("Navigating to " + label, "polite");
    }
  }


  // =========================================================================
  //  MODULE 10: ACCESSIBILITY — Native Chrome Dialogs
  // =========================================================================
  function setupNativeDialogs() {
    if (!CONFIG.useNativeDialogs) return;

    function applyDialogOverrides() {
      const app = window.myApp;
      if (!app) return false;

      app.alert = function (text, title, callback) {
        if (typeof title === "function") {
          callback = title;
          title = "";
        }
        const msg = title ? title + "\n\n" + text : text;
        window.alert(msg);
        if (callback) callback();
      };

      app.confirm = function (text, title, okCallback, cancelCallback) {
        if (typeof title === "function") {
          cancelCallback = okCallback;
          okCallback = title;
          title = "";
        }
        const msg = title ? title + "\n\n" + text : text;
        if (window.confirm(msg)) {
          if (okCallback) okCallback();
        } else {
          if (cancelCallback) cancelCallback();
        }
      };

      app.prompt = function (text, title, okCallback, cancelCallback) {
        if (typeof title === "function") {
          cancelCallback = okCallback;
          okCallback = title;
          title = "";
        }
        const msg = title ? title + "\n\n" + text : text;
        const result = window.prompt(msg);
        if (result !== null) {
          if (okCallback) okCallback(result);
        } else {
          if (cancelCallback) cancelCallback();
        }
      };

      app.actions = function (buttons) {
        let label = "";
        let yesCallback = null;
        let items = Array.isArray(buttons[0]) ? buttons.flat() : buttons;
        if (items[0] && items[0].text !== undefined) {
          for (const btn of items) {
            if (btn.label) {
              label = btn.text || "";
            } else if (
              btn.text &&
              btn.text.toLowerCase() !== "cancel" &&
              btn.onClick
            ) {
              yesCallback = btn.onClick;
            }
          }
        }
        if (label && yesCallback) {
          if (window.confirm(label)) {
            yesCallback();
          }
          return;
        }
        const texts = items
          .filter((b) => !b.label)
          .map((b) => b.text)
          .join(" / ");
        if (window.confirm(label || texts)) {
          const action = items.find(
            (b) => !b.label && b.text?.toLowerCase() !== "cancel" && b.onClick
          );
          if (action) action.onClick();
        }
      };

      return true;
    }

    if (!applyDialogOverrides()) {
      const poll = setInterval(() => {
        if (applyDialogOverrides()) clearInterval(poll);
      }, 200);
    }
  }

  // =========================================================================
  //  MODULE 10b: STATS-ZONE LABELS
  //  The currency chips at the bottom of the page (drachma / LP / AC / VM)
  //  ship from the server as anchor tags with href="x" and role="button" but
  //  do not actually navigate or do anything when clicked. We strip the
  //  link/button affordances so they render as plain inline text and append
  //  a unit suffix (" LP", " AC", " VM") after the numeric value.
  // =========================================================================
  const STATSZONE_ICON_SUFFIX = {
    "icon-ac.png": "AC",
    "icon-vm.png": "VM",
    "icon-somewhere.png": "LP",
  };

  function enhanceStatsZone(root) {
    const doc = root || document;
    // #statszone lives outside .view-main, so look it up globally even when
    // runContentEnhancements is scoped to a page container.
    const statsZone = document.querySelector("#statszone");
    if (!statsZone) return;

    for (const link of statsZone.querySelectorAll("a.deconbutton")) {
      link.removeAttribute("href");
      link.removeAttribute("role");
      link.removeAttribute("tabindex");
      link.removeAttribute("aria-label");
    }

    for (const [iconName, suffix] of Object.entries(STATSZONE_ICON_SUFFIX)) {
      const img = statsZone.querySelector(`.chip-media img[src*="${iconName}"]`);
      if (!img) continue;
      const chip = img.closest(".chip");
      const label = chip && chip.querySelector(".chip-label");
      if (!label) continue;
      const raw = label.textContent.trim();
      // Strip any prior suffix so repeated runs (after server-side refresh)
      // stay idempotent.
      const cleaned = raw.replace(/\s+(LP|VM|AC)$/i, "").trim();
      if (!cleaned) continue;
      const next = cleaned + " " + suffix;
      if (label.textContent !== next) label.textContent = next;
    }
  }

  // =========================================================================
  //  MODULE 11: ACCESSIBILITY — Enhance Links & Interactive Elements
  // =========================================================================
  function enhanceInteractiveElements(root) {
    const doc = root || document;

    const badLinks = doc.querySelectorAll('a[href="x"]:not([role])');
    for (const link of badLinks) {
      link.setAttribute("role", "button");
      link.setAttribute("tabindex", "0");
      if (!link.getAttribute("aria-label")) {
        const text = link.textContent.trim().substring(0, 60);
        if (text) link.setAttribute("aria-label", text);
      }
    }

    const iconBtns = doc.querySelectorAll(
      ".icon-only:not([aria-label]), .navbar .icon:not([aria-label])"
    );
    for (const btn of iconBtns) {
      const parent = btn.closest("a, button");
      if (parent && !parent.getAttribute("aria-label")) {
        const classes = btn.className;
        if (classes.includes("bars") || classes.includes("menu")) {
          parent.setAttribute("aria-label", "Menu");
        } else if (classes.includes("back")) {
          parent.setAttribute("aria-label", "Go back");
        } else if (classes.includes("close")) {
          parent.setAttribute("aria-label", "Close");
        }
      }
    }

    // Settings page — name-color picker. Game wraps an F7 icon glyph and
    // copies the glyph name ("circle_fill") into aria-label, so all 48
    // color swatches announce as the same word. Replace with the color id.
    for (const btn of doc.querySelectorAll(".setcolorbtn")) {
      const id = btn.getAttribute("data-colorid");
      if (id) btn.setAttribute("aria-label", "Name color " + id);
    }

    // Many icon-only buttons (navbar refresh, chat flag/star, etc.) copy
    // the F7 glyph name into aria-label so NVDA reads "reload round fill"
    // or "flag fill". When aria-label exactly matches the inner glyph
    // text, clean it up — prefer a known class override, else strip
    // _fill / _round_fill and humanize.
    const NAMED_ICON_BTNS = {
      refreshbtn: "Refresh",
      "back": "Go back",
    };
    for (const link of doc.querySelectorAll(
      'a[role="button"][aria-label], button[aria-label], a.link[aria-label]'
    )) {
      const cur = (link.getAttribute("aria-label") || "").trim();
      if (!cur) continue;
      const icon = link.querySelector(".f7-icons, .icon");
      if (!icon) continue;
      const iconText = icon.textContent.trim();
      if (cur !== iconText) continue;
      // Class-based override first
      let override = null;
      for (const cls of Object.keys(NAMED_ICON_BTNS)) {
        if (link.classList.contains(cls)) { override = NAMED_ICON_BTNS[cls]; break; }
      }
      if (override) {
        link.setAttribute("aria-label", override);
        continue;
      }
      // Generic clean-up: "reload_round_fill" -> "reload"
      const cleaned = iconText
        .replace(/_round_fill$/i, "")
        .replace(/_fill$/i, "")
        .replace(/_/g, " ")
        .trim();
      if (cleaned && cleaned !== iconText) {
        link.setAttribute("aria-label", cleaned);
      }
    }

    // Settings page — emblem picker. Each <a> wraps an <img title="Def">
    // with no text and no aria-label, so screen readers announce "button"
    // with no context. Promote the image title (or alt) to the button.
    for (const btn of doc.querySelectorAll(".setemblembtn")) {
      const cur = (btn.getAttribute("aria-label") || "").trim();
      if (cur) continue;
      const img = btn.querySelector("img");
      const txt = img && (img.getAttribute("title") || img.getAttribute("alt"));
      if (txt && txt.trim()) {
        btn.setAttribute("aria-label", "Select emblem: " + txt.trim());
      }
    }
    for (const btn of doc.querySelectorAll(".unsetemblembtn")) {
      if (!btn.getAttribute("aria-label")) {
        btn.setAttribute("aria-label", "Remove emblem");
      }
    }

    // Form-control labels. Two failure modes the game ships with:
    //   1. checkbox/select with aria-label set to the raw HTML name attribute
    //      ("pm alerts", "rainbow4b", "hide alerts") while the actual human-
    //      readable text lives in a sibling `.item-title`.
    //   2. text/password/textarea with no aria-label at all.
    // For both, walk up to the row and use the `.item-title` text — that's
    // the label the sighted player sees.
    const formInputs = doc.querySelectorAll(
      'input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea'
    );
    for (const input of formInputs) {
      // aria-labelledby trumps everything else; leave it alone.
      if (input.getAttribute("aria-labelledby")) continue;

      const current = (input.getAttribute("aria-label") || "").trim();
      const name = (input.getAttribute("name") || "").trim();
      const placeholder = (input.getAttribute("placeholder") || "").trim();

      // Is the current aria-label one of the known garbage shapes?
      // - empty
      // - identical to the bare `name`
      // - identical to `name` with [_-] → " " (what the game does)
      const nameMunged = name.replace(/[_-]/g, " ");
      const lc = current.toLowerCase();
      const garbage =
        !current ||
        lc === name.toLowerCase() ||
        lc === nameMunged.toLowerCase();

      // Try to read the visible label from the row's `.item-title`.
      let titleText = "";
      const row = input.closest("li, .item-content");
      const titleEl = row && row.querySelector(".item-title");
      if (titleEl) {
        // Stop at the first <br> / nested element — game often appends
        // a small help-text `<span>` after the main label.
        const parts = [];
        for (const node of titleEl.childNodes) {
          if (node.nodeType === 3) { // text
            parts.push(node.textContent);
          } else if (node.nodeType === 1) {
            const tag = node.tagName;
            if (tag === "BR") break;
            // .label-info / small help spans → also stop
            if (tag === "SPAN" || tag === "SMALL" || tag === "I") break;
            parts.push(node.textContent);
          }
        }
        titleText = parts.join(" ").replace(/\s+/g, " ").trim()
          .replace(/[?:]\s*$/, ""); // drop trailing "?" or ":"
      }

      if (garbage && titleText) {
        input.setAttribute("aria-label", titleText);
        continue;
      }
      if (garbage && placeholder) {
        input.setAttribute("aria-label", placeholder);
        continue;
      }
      // Look for a section heading immediately above (e.g. country dropdown
      // sits under "Select your Country" with no inline label).
      if (garbage) {
        let heading = null;
        const card = input.closest(".card, .content-block");
        if (card) {
          let prev = card.previousElementSibling;
          while (prev) {
            if (prev.matches && prev.matches(".content-block-title")) {
              heading = prev.textContent.trim();
              break;
            }
            prev = prev.previousElementSibling;
          }
        }
        if (heading) {
          input.setAttribute("aria-label", heading.replace(/\s+/g, " "));
          continue;
        }
      }
      if (garbage && name) {
        // Last resort — clean up the munged name.
        input.setAttribute(
          "aria-label",
          name.replace(/[_-]/g, " ").replace(/([A-Z])/g, " $1").trim()
        );
      }
    }

    // Framework7 .label-switch: the visible toggle is a <label> wrapping
    // a hidden <input type="checkbox"> (opacity:0; position:absolute).
    // The hidden input is technically focusable but NVDA/JAWS can't see
    // it reliably and Tab order skips it in practice — so screen-reader
    // users can't toggle settings (BG Music, Tutorial, etc.). Promote
    // the wrapping <label> to a real `role="switch"` with an aria-checked
    // state, mirror checked changes back to aria-checked, and toggle the
    // underlying input on Space/Enter.
    for (const sw of doc.querySelectorAll("label.label-switch")) {
      if (sw.dataset.bw2SwitchEnhanced) continue;
      const input = sw.querySelector('input[type="checkbox"]');
      if (!input) continue;
      sw.dataset.bw2SwitchEnhanced = "1";

      // Move aria-label up to the label — the label is the accessible
      // control going forward.
      const label = input.getAttribute("aria-label") || "";
      if (label) sw.setAttribute("aria-label", label);
      input.removeAttribute("aria-label");

      sw.setAttribute("role", "switch");
      sw.setAttribute("tabindex", "0");
      sw.setAttribute("aria-checked", input.checked ? "true" : "false");
      // Take the inner input out of the a11y tree and the tab order;
      // mouse/touch clicks on the <label> still flip it via native HTML
      // label-for-input behavior.
      input.setAttribute("aria-hidden", "true");
      input.setAttribute("tabindex", "-1");

      // Mirror real checked state back onto the switch.
      input.addEventListener("change", function () {
        sw.setAttribute("aria-checked", input.checked ? "true" : "false");
      });

      // Keyboard activation. preventDefault on Space stops the page from
      // scrolling; stopPropagation keeps the game's global key handlers
      // (1/2/3 attack keys, "/" go-back) from intercepting.
      sw.addEventListener("keydown", function (e) {
        if (e.key !== " " && e.key !== "Enter") return;
        e.preventDefault();
        e.stopPropagation();
        input.checked = !input.checked;
        input.dispatchEvent(new Event("change", { bubbles: true }));
        // Some F7 builds listen for 'click' on the input — fire that too.
        input.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
      });
    }
  }

  // =========================================================================
  //  MODULE 11b: ACCESSIBILITY — Bulk Infuse
  //  Adds a sibling "Bulk xN" button next to each .infusebutton on the
  //  infusion page, plus a single shared count input at the top of the
  //  page. The bulk button monkey-patches window.confirm/alert during the
  //  run so the per-action confirm popup never blocks; iterations are
  //  spaced 1s apart. Each .infusebutton's aria-label is also rewritten
  //  from the bare cost ("8,200,000 D") to include the stat name so a
  //  screen reader can tell which row is focused.
  // =========================================================================
  const INFUSION_TYPE_LABELS = {
    attack: "Attack",
    defense: "Defense",
    hp: "HP",
    lp: "LP",
    perkattack: "% Attack",
    perkdefense: "% Defense",
    perkhp: "% HP",
    perklp: "% LP",
    perkxp: "% XP",
    perkglimmer: "% Drachma",
    perkcrit: "% Crit",
    perkevade: "% Evade",
  };

  function getBulkInfuseCount() {
    const stored = parseInt(localStorage.getItem("bw2_bulk_infuse_count"), 10);
    if (Number.isFinite(stored) && stored >= 1 && stored <= 999) return stored;
    return 10;
  }

  function setBulkInfuseCount(n) {
    try { localStorage.setItem("bw2_bulk_infuse_count", String(n)); } catch (_) {}
  }

  let _bulkInfuseRunning = false;

  async function runBulkInfuse(infusionType, itemId) {
    if (_bulkInfuseRunning) {
      announce("Bulk infuse already running.", "assertive");
      return;
    }
    _bulkInfuseRunning = true;
    const label = INFUSION_TYPE_LABELS[infusionType] || infusionType;
    const total = getBulkInfuseCount();

    const origConfirm = window.confirm;
    const origAlert = window.alert;
    let capturedAlert = null;
    window.confirm = function () { return true; };
    window.alert = function (msg) { capturedAlert = String(msg || ""); };

    let done = 0;
    try {
      announce(`Bulk infuse ${label} starting, ${total} times.`, "polite");
      for (let i = 0; i < total; i++) {
        const sel = `.infusebutton[data-infusiontype="${CSS.escape(infusionType)}"][data-itemid="${CSS.escape(itemId)}"]`;
        const btn = document.querySelector(sel);
        if (!btn) {
          announce(`Bulk infuse stopped at ${done} of ${total}: ${label} button no longer available.`, "assertive");
          return;
        }
        btn.click();
        done = i + 1;
        // Settle: let the AJAX round-trip + re-render finish before the next click.
        if (done < total) await new Promise(r => setTimeout(r, 1000));
        if (capturedAlert) {
          announce(`Bulk infuse stopped at ${done} of ${total}: ${capturedAlert}`, "assertive");
          return;
        }
        // Iteration succeeded — play the completion cue.
        playFlatSfx("infcomplete.ogg");
      }
      announce(`Bulk infuse complete: ${label} ${done} times.`, "assertive");
    } finally {
      window.confirm = origConfirm;
      window.alert = origAlert;
      _bulkInfuseRunning = false;
    }
  }

  function enhanceInfusionPage(root) {
    const doc = root || document;
    // The root passed in might be a single .page; the data-page="infusion"
    // marker can be on it OR on a descendant, depending on which callback fired.
    let page = null;
    if (doc.matches && doc.matches('[data-page="infusion"]')) page = doc;
    if (!page) page = doc.querySelector ? doc.querySelector('[data-page="infusion"]') : null;
    if (!page) return;
    const pageContent = page.querySelector(".page-content");
    if (!pageContent) return;

    const currentCount = getBulkInfuseCount();

    // --- 1. Top-of-page bulk-count toolbar ---
    if (!pageContent.querySelector("#bw2-bulk-infuse-toolbar")) {
      const toolbar = document.createElement("div");
      toolbar.id = "bw2-bulk-infuse-toolbar";
      toolbar.className = "content-block";
      toolbar.style.cssText = "padding: 8px 15px; color: white;";
      toolbar.innerHTML =
        '<label for="bw2-bulk-infuse-count" style="font-size: 13px;">Bulk infuse count:</label>' +
        '<input id="bw2-bulk-infuse-count" type="number" min="1" max="999" value="' + currentCount + '"' +
        ' aria-label="Bulk infuse count, applies to all Bulk infuse buttons on this page"' +
        ' style="width: 70px; margin-left: 6px; padding: 2px 4px; background:#222; color:white; border:1px solid #660000;">' +
        '<div style="font-size: 11px; color:#999; margin-top: 4px;">Each row has a Bulk infuse button that repeats this many times at 1-second intervals. Confirm popups are auto-accepted during the run.</div>';
      const firstBlock = pageContent.querySelector(".content-block");
      if (firstBlock) firstBlock.after(toolbar);
      else pageContent.prepend(toolbar);

      const input = toolbar.querySelector("#bw2-bulk-infuse-count");
      input.addEventListener("change", function () {
        let n = parseInt(input.value, 10);
        if (!Number.isFinite(n) || n < 1) n = 1;
        if (n > 999) n = 999;
        input.value = n;
        setBulkInfuseCount(n);
        // Refresh existing bulk-button labels in place.
        document.querySelectorAll(".bw2-bulk-infuse").forEach(function (b) {
          const type = b.getAttribute("data-infusiontype");
          const lbl = INFUSION_TYPE_LABELS[type] || type;
          b.textContent = "Bulk x" + n;
          b.setAttribute("aria-label", "Bulk infuse " + lbl + " " + n + " times");
        });
      });
    }

    // --- 2. Per-row: rewrite aria-label + inject sibling bulk button ---
    const infuseButtons = page.querySelectorAll(".infusebutton:not([data-bw2-enhanced])");
    for (const btn of infuseButtons) {
      btn.setAttribute("data-bw2-enhanced", "1");
      const type = btn.getAttribute("data-infusiontype");
      const itemId = btn.getAttribute("data-itemid");
      if (!type || !itemId) continue;
      const label = INFUSION_TYPE_LABELS[type] || type;
      const existingAria = (btn.getAttribute("aria-label") || btn.textContent || "").trim();
      btn.setAttribute("aria-label", "Infuse " + label + " " + existingAria);

      const bulkBtn = document.createElement("a");
      bulkBtn.href = "x";
      bulkBtn.className = "button bw2-bulk-infuse";
      bulkBtn.setAttribute("role", "button");
      bulkBtn.setAttribute("tabindex", "0");
      bulkBtn.setAttribute("data-infusiontype", type);
      bulkBtn.setAttribute("data-itemid", itemId);
      bulkBtn.setAttribute("aria-label", "Bulk infuse " + label + " " + currentCount + " times");
      bulkBtn.textContent = "Bulk x" + currentCount;
      bulkBtn.style.cssText = "background-color: #003366; border: 1px solid #336699; color:#fff; margin-left: 6px; display: inline-block;";
      btn.after(bulkBtn);
    }
  }

  function setupBulkInfuse() {
    // Global delegated click handler — survives Framework7 page re-renders.
    // Capture phase so we intercept before any game-side delegation.
    document.addEventListener("click", function (e) {
      const t = e.target;
      const btn = t && t.closest && t.closest(".bw2-bulk-infuse");
      if (!btn) return;
      e.preventDefault();
      e.stopPropagation();
      const type = btn.getAttribute("data-infusiontype");
      const itemId = btn.getAttribute("data-itemid");
      if (type && itemId) runBulkInfuse(type, itemId);
    }, true);

    // Manual single-click on a real .infusebutton: play infcomplete.ogg
    // after the AJAX round-trip finishes. Skipped during bulk infuse runs
    // — runBulkInfuse plays the cue itself per successful iteration so it
    // can suppress on captured-alert errors.
    document.addEventListener("click", function (e) {
      if (_bulkInfuseRunning) return;
      const t = e.target;
      if (!t || !t.closest) return;
      const btn = t.closest(".infusebutton");
      if (!btn) return;
      if (btn.classList.contains("bw2-bulk-infuse")) return;
      setTimeout(() => playFlatSfx("infcomplete.ogg"), 800);
    }, false);
  }

  // =========================================================================
  //  OBSERVER: Watch for new content loaded via AJAX
  // =========================================================================
  const contentObservers = [];

  function observeNewContent(callback) {
    contentObservers.push(callback);
  }

  function setupContentObserver() {
    // Both halves of this setup are gated on resources that may not exist
    // yet on a slow page load — Framework7's `$$` selector and the
    // `.view-main` DOM container. Previously each gate was one-shot, so a
    // single late-loading session left the enhancer permanently inert and
    // every subsequent page navigation went un-enhanced. Now each half
    // retries independently until ready or the deadline passes.

    let f7HooksWired = false;
    let mutationObserverInstalled = false;

    function wireF7Hooks() {
      if (f7HooksWired) return true;
      if (!(window.$$ && $$(document))) return false;
      $$(document).on("pageInit", function (e) {
        const page = e.detail?.page;
        dlog("info", "F7 pageInit name=" + (page && page.name) + " hasContainer=" + !!(page && page.container));
        if (page?.container) {
          safeRun("pageInit:runContentEnhancements", () => runContentEnhancements(page.container));
          safeRun("pageInit:focusAfterPageLoad", () => focusAfterPageLoad());

          const pageName =
            page.container.querySelector(".navbar .center")?.textContent ||
            page.name ||
            "Page";
          window.__bw2LastPageName = pageName.replace(/\s+/g, "-").toLowerCase();
          safeRun("pageInit:announce", () => announce(pageName + " loaded", "polite"));
        }
      });
      $$(document).on("pageAfterAnimation", function (e) {
        const page = e.detail?.page;
        dlog("info", "F7 pageAfterAnimation name=" + (page && page.name));
        if (page?.container) {
          safeRun("pageAfterAnimation:runContentEnhancements", () => runContentEnhancements(page.container));
          safeRun("pageAfterAnimation:focusAfterPageLoad", () => focusAfterPageLoad());
        }
      });
      for (const evt of ["pageBeforeInit", "pageBeforeAnimation", "pageBeforeRemove", "pageRemove", "pageBack"]) {
        try {
          $$(document).on(evt, function (e) {
            const page = e.detail?.page;
            dlog("info", "F7 " + evt + " name=" + (page && page.name));
          });
        } catch (_) {}
      }
      f7HooksWired = true;
      return true;
    }

    function installMutationObserver() {
      if (mutationObserverInstalled) return true;
      const mainView = document.querySelector(".view-main");
      if (!mainView) return false;
      const observer = new MutationObserver((mutations) => {
        let hasNewContent = false;
        for (const m of mutations) {
          if (m.addedNodes.length > 0) {
            hasNewContent = true;
            for (const node of m.addedNodes) {
              if (node instanceof HTMLElement) {
                for (const cb of contentObservers) cb(node);
              }
            }
          }
        }
        if (hasNewContent) {
          clearTimeout(setupContentObserver._timer);
          setupContentObserver._timer = setTimeout(
            () => runContentEnhancements(),
            200
          );
        }
      });
      observer.observe(mainView, { childList: true, subtree: true });
      mutationObserverInstalled = true;
      return true;
    }

    // First attempt synchronously so a fast load avoids any timer overhead.
    wireF7Hooks();
    installMutationObserver();
    if (f7HooksWired && mutationObserverInstalled) return;

    // Slow path: poll until both gates pass or we hit the deadline. 60 s is
    // long enough to cover a stalled CDN fetch but not so long that we burn
    // CPU forever if the page genuinely never loads.
    const deadline = Date.now() + 60_000;
    const pollMs = 200;
    const poll = setInterval(() => {
      if (!f7HooksWired) wireF7Hooks();
      if (!mutationObserverInstalled) installMutationObserver();
      if (f7HooksWired && mutationObserverInstalled) {
        clearInterval(poll);
        dlog("info", "setupContentObserver: both gates wired after retry");
        // Run an enhancement pass now in case content appeared while we were
        // waiting and neither observer was active to catch it.
        safeRun("setupContentObserver:catchupEnhance", () => runContentEnhancements());
        return;
      }
      if (Date.now() > deadline) {
        clearInterval(poll);
        dwarn(
          "setupContentObserver: gave up after 60s. f7HooksWired=" + f7HooksWired +
          " mutationObserverInstalled=" + mutationObserverInstalled
        );
      }
    }, pollMs);
  }

  function runContentEnhancements(root) {
    const rootDesc = root ? describeEl(root) : "document";
    dlog("info", "runContentEnhancements start root=", rootDesc);
    safeRun("setupARIA", () => setupARIA(root));
    safeRun("enhanceEnemyList", () => enhanceEnemyList(root));
    safeRun("enemyListMemory", () => scheduleEnemyMem());
    safeRun("enhanceBattlePage", () => enhanceBattlePage(root));
    // Stats-zone fix must run BEFORE enhanceInteractiveElements: stripping
    // href from deconbutton chips here keeps the `a[href="x"]:not([role])`
    // selector from matching them and putting role="button" back on.
    safeRun("enhanceStatsZone", () => enhanceStatsZone(root));
    safeRun("enhanceInteractiveElements", () => enhanceInteractiveElements(root));
    safeRun("enhanceInfusionPage", () => enhanceInfusionPage(root));
    // Refresh the equipped-weapon cache whenever the user lands on gear.php.
    // The detector no-ops if we're not actually on the gear page.
    safeRun("detectEquippedWeapons", () => detectEquippedWeapons(root));

    // After the stats zone has been re-parsed for the current page, compare
    // the AC value against the cached one and play the appropriate
    // gain/spend cue. First-ever run just seeds the cache silently.
    safeRun("checkACChange", () => checkACChange());

    // Toggle stats bar visibility based on active page
    safeRun("toggleStatsVisibility", () => {
      const activePage = document.querySelector(".page-on-center");
      const isBattle = activePage?.classList?.contains("battlepage") ||
        activePage?.getAttribute("data-page") === "battle";
      const statsZone = document.querySelector("#statszone");
      const toolbar = document.querySelector("#bottom");
      if (isBattle) {
        if (statsZone) statsZone.setAttribute("aria-hidden", "true");
        if (toolbar) toolbar.setAttribute("aria-hidden", "true");
      } else {
        if (statsZone) statsZone.removeAttribute("aria-hidden");
        if (toolbar) toolbar.removeAttribute("aria-hidden");
      }
    });
    dlog("info", "runContentEnhancements done");
  }

  // =========================================================================
  //  MODULE 12: BATTLE SFX
  // =========================================================================
  let sfxVolume = 0.5;

  // --- Per-weapon "fire" SFX ---------------------------------------------
  // Plays on every player attack (hit AND miss), layered on top of the
  // existing outcome sounds. Files live in audio/ named
  //   {slug}_fire.{wav|ogg|mp3}
  // where {slug} = weapon name lowercased with non-alphanumeric runs replaced
  // by "_". Falls back to default_fire.{ext} if the weapon-specific file is
  // missing; silent if neither exists.
  //
  // Equipped weapon names are auto-detected whenever the user visits gear.php
  // (see detectEquippedWeapons below) and persisted in localStorage across
  // sessions, so this only requires ONE visit to Gear to prime the cache.
  const EQUIPPED_STORAGE_KEY = "bw2.equippedWeapons.v1";
  let equippedWeapons = null; // { primary, special, heavy } | null
  try {
    const raw = localStorage.getItem(EQUIPPED_STORAGE_KEY);
    if (raw) equippedWeapons = JSON.parse(raw);
  } catch (_) {}

  // "Primary" / "Special" / "Heavy" / "Illumina" -> which equipped slot to use.
  // Illumina (key 4 / super attack) defaults to the Primary weapon's fire
  // sound; change the value here if you add a separate illumina sound.
  const ATTACK_TYPE_TO_SLOT = {
    primary:  "primary",
    special:  "special",
    heavy:    "heavy",
    illumina: "primary",
  };

  function slugifyWeaponName(name) {
    return String(name || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
  }

  function detectEquippedWeapons(root) {
    const doc = root || document;
    // Only run on the gear page — changeweapon.php links exist elsewhere too.
    if (!doc.querySelector('[data-page="gear"]') && !document.querySelector('[data-page="gear"]')) return;
    const links = doc.querySelectorAll('a[href="changeweapon.php"]');
    if (!links.length) return;
    const parsed = {};
    // Match "Primary|Special|Heavy Weapon" anywhere in a span's text. The
    // current gear.php wraps "Primary Weapon - Lv 16 <progressbar>" in a
    // single span, so a $-anchored regex (matching only when text ENDS with
    // "Weapon") no longer works.
    const SLOT_RE = /\b(Primary|Special|Heavy)\s+Weapon\b/i;
    for (const a of links) {
      const titleEl = a.querySelector(".item-title");
      if (!titleEl) continue;
      const slotSpan = Array.from(titleEl.querySelectorAll("span"))
        .find(s => SLOT_RE.test(s.textContent || ""));
      if (!slotSpan) continue;
      const slotMatch = (slotSpan.textContent || "").match(SLOT_RE);
      if (!slotMatch) continue;
      const slotKey = slotMatch[1].toLowerCase();
      // Clone the title and strip:
      //  - any span containing the slot label (kills "<Slot> Weapon - Lv N"
      //    plus any nested progressbar markup),
      //  - the gear_set prefix span (e.g. "ORACLE" on "ORACLE Bright Max" —
      //    decoupling SFX filenames from set bonuses),
      //  - any <br> tags.
      const clone = titleEl.cloneNode(true);
      // Iterate in document order — parent spans are visited before their
      // children. If we remove a parent, .remove() on the (now-detached)
      // children later in the iteration is a harmless no-op. Note: do NOT
      // use `isConnected` to guard this — clones aren't in the document, so
      // `isConnected` is always false on every span in a clone.
      clone.querySelectorAll("span").forEach(s => {
        if (SLOT_RE.test(s.textContent || "")) s.remove();
        else if (s.classList && s.classList.contains("gear_set")) s.remove();
      });
      clone.querySelectorAll("br").forEach(n => n.remove());
      const name = clone.textContent.replace(/\s+/g, " ").trim();
      if (!name) continue;
      parsed[slotKey] = name;
    }
    if (parsed.primary || parsed.special || parsed.heavy) {
      equippedWeapons = Object.assign(equippedWeapons || {}, parsed);
      try { localStorage.setItem(EQUIPPED_STORAGE_KEY, JSON.stringify(equippedWeapons)); } catch (_) {}
    }
  }

  // Tracks when the last weapon fire SFX played (Date.now()). Used by the
  // response-side backstop in setupBattleHooks to detect patrol → instant
  // kill cases where no keypress-side fire happened.
  let _lastWeaponFireMs = 0;

  // --- Bounty tracking ---------------------------------------------------
  // Bounties live on patrol/battle pages inside `.bountyrow .card-content-inner`,
  // formatted as "Bounty: <current> / <total> <a>EnemyName</a> (<timer>)".
  // There can be multiple bountyrows (one per active bounty).

  function getBountyDetails() {
    const rows = document.querySelectorAll(".bountyrow .card-content-inner");
    const out = [];
    for (const row of rows) {
      const link = row.querySelector("a[href*='enemy.php']");
      if (!link) continue;
      const name = link.textContent.trim();
      // Read just the leading text node of the row, before the link, to get
      // the "Bounty: X / Y" prefix without including the timer or progress
      // bar markup that follows.
      let prefix = "";
      for (const node of row.childNodes) {
        if (node === link) break;
        if (node.nodeType === 3) prefix += node.textContent;
        else if (node.nodeType === 1) prefix += node.textContent || "";
      }
      const m = prefix.match(/Bounty:\s*([\d,]+)\s*\/\s*([\d,]+)/i);
      if (!name || !m) continue;
      out.push({ name, current: m[1].trim(), total: m[2].trim() });
    }
    return out;
  }

  function getBountyTargetNames() {
    return getBountyDetails().map(b => b.name);
  }

  // True iff the live battle page navbar's enemy name matches any active
  // bounty target (substring match — the bounty link uses the canonical
  // name, the navbar may carry tier/decorations).
  function isCurrentEnemyBounty() {
    const navbarName = document.querySelector(".navbar-on-center .center")?.textContent?.trim();
    if (!navbarName) return false;
    const targets = getBountyTargetNames();
    return targets.some(t => navbarName.includes(t) || t.includes(navbarName));
  }

  // True iff a given enemy name matches any active bounty target. Used for
  // patrol clicks where we know the enemy name from the .item-title.
  function isEnemyNameBounty(enemyName) {
    if (!enemyName) return false;
    const targets = getBountyTargetNames();
    return targets.some(t => enemyName.includes(t) || t.includes(enemyName));
  }

  function playBountyEnemySfx() {
    if (!CONFIG.audioEnabled) return;
    const audioData = window.__bw2Audio || {};
    for (const f of ["benemy.wav", "benemy.ogg", "benemy.mp3"]) {
      if (audioData[f]) { playSfx(f); return; }
    }
  }

  function playWeaponFireSfx(attackType) {
    if (!CONFIG.audioEnabled) return;
    if (!equippedWeapons) return; // user hasn't visited gear.php yet
    const slot = ATTACK_TYPE_TO_SLOT[(attackType || "").toLowerCase()];
    if (!slot) return;
    const weaponName = equippedWeapons[slot];
    if (!weaponName) return;
    const slug = slugifyWeaponName(weaponName);
    const audioData = window.__bw2Audio || {};
    // Try weapon-specific first (any common audio extension), then the
    // default fallback. Silent if none of these are in audio/.
    const candidates = [
      slug + "_fire.wav", slug + "_fire.ogg", slug + "_fire.mp3",
      "default_fire.wav", "default_fire.ogg", "default_fire.mp3",
    ];
    for (const name of candidates) {
      if (audioData[name]) {
        playSfx(name);
        _lastWeaponFireMs = Date.now();
        return;
      }
    }
  }

  // Fire the weapon SFX on a real mouse/touch click of an attack button. The
  // 1/2/3/4 keyboard path already plays SFX explicitly before calling
  // el.click(), and that synthetic click has isTrusted=false — so this
  // listener only catches genuine user clicks, avoiding double-play.
  const ATTACK_CLICK_MAP = [
    [".attacklink",  "Primary"],
    [".speciallink", "Special"],
    [".heavylink",   "Heavy"],
    [".superlink",   "Illumina"],
  ];

  function setupAttackClickSfx() {
    document.addEventListener("click", function (e) {
      if (!e.isTrusted) return;
      if (!CONFIG.audioEnabled) return;
      const t = e.target;
      if (!t || typeof t.closest !== "function") return;
      for (const [sel, atkType] of ATTACK_CLICK_MAP) {
        if (t.closest(sel)) {
          playWeaponFireSfx(atkType);
          if (isCurrentEnemyBounty()) playBountyEnemySfx();
          return;
        }
      }
    }, true);
  }

  // Per-sound volume multiplier. Multiplied onto the base sfxVolume. Values
  // >1.0 / sfxVolume route through Web Audio API (HTMLMediaElement.volume is
  // clamped to [0,1], so >100% needs a GainNode).
  const VOLUME_BOOSTS = {
    "pure_child_fire.ogg": 1.5,
  };

  let _audioCtx = null;
  function ensureAudioCtx() {
    if (_audioCtx) return _audioCtx;
    try {
      const Ctor = window.AudioContext || window.webkitAudioContext;
      if (Ctor) _audioCtx = new Ctor();
    } catch (_) {}
    return _audioCtx;
  }

  function playSfx(name) {
    if (!CONFIG.audioEnabled) return;
    const audioData = window.__bw2Audio;
    if (!audioData || !audioData[name]) return;
    const boost = VOLUME_BOOSTS[name] || 1.0;
    const desired = sfxVolume * boost;
    const audio = new Audio(audioData[name]);
    // Randomize pitch -50% to +50% on every play (chaos mode go brrrr).
    // preservesPitch=false makes playbackRate shift pitch (tape-speed style),
    // so a 0.5×–1.5× range is also audibly a 2× speed change — fine for
    // short SFX, hilarious for everything else.
    try {
      audio.preservesPitch = false;
      audio.mozPreservesPitch = false;
      audio.webkitPreservesPitch = false;
    } catch (_) {}
    audio.playbackRate = 0.5 + Math.random() * 1.0; // 0.5 .. 1.5

    if (desired <= 1.0) {
      audio.volume = desired;
      audio.play().catch(() => {});
      return;
    }

    // Boost path: route through Web Audio API so we can exceed 1.0 amplitude.
    const ctx = ensureAudioCtx();
    if (!ctx) {
      // Fallback if Web Audio is unavailable: clamp at 1.0.
      audio.volume = 1.0;
      audio.play().catch(() => {});
      return;
    }
    audio.volume = 1.0;
    try {
      const src = ctx.createMediaElementSource(audio);
      const gainNode = ctx.createGain();
      gainNode.gain.value = desired;
      src.connect(gainNode).connect(ctx.destination);
    } catch (_) {
      // createMediaElementSource throws if the element already has a source;
      // fall back to clamped playback.
      audio.volume = 1.0;
    }
    audio.play().catch(() => {});
  }

  // --- Low-health alarm --------------------------------------------------
  // Loops low_health_alarm.ogg while the player's in-battle HP bar is under
  // 25%, stops when healed >=25% or when the HP bar leaves the DOM (battle
  // ended / page changed). Single shared Audio instance — never spawned per
  // tick — so a page refresh does NOT retrigger a fresh play; it only resumes
  // looping if HP is still low.
  const LOW_HEALTH_THRESHOLD = 25;
  let _lowHealthAudio = null;
  let _lowHealthPlaying = false;

  // Parses "30,226 / 30,226" out of an element's text and returns percent.
  // Returns null if no match (e.g. enemy side which shows only current HP).
  function hpPercentFromText(el) {
    if (!el) return null;
    const m = (el.textContent || "").match(/([\d,]+)\s*\/\s*([\d,]+)/);
    if (!m) return null;
    const cur = parseInt(m[1].replace(/,/g, ""), 10);
    const max = parseInt(m[2].replace(/,/g, ""), 10);
    if (!isFinite(cur) || !isFinite(max) || max <= 0) return null;
    return (cur / max) * 100;
  }

  // Rewrite the outer .progress aria-valuenow on the player side using the
  // X/Y text — the game stacks two cosmetic .progress-bar children whose
  // widths don't match real HP. Inner children are marked aria-hidden so
  // NVDA reads exactly one bar with the correct value per side.
  function fixupBattleHpBars(battlePage) {
    if (!battlePage) return;
    const playerInfo = battlePage.querySelector(".playerInfo");
    if (playerInfo) {
      const pct = hpPercentFromText(playerInfo);
      for (const bar of playerInfo.querySelectorAll(".progress")) {
        const isShield = bar.classList.contains("shield");
        if (!isShield && pct != null) {
          bar.setAttribute("role", "progressbar");
          bar.setAttribute("aria-valuenow", String(Math.round(pct)));
          bar.setAttribute("aria-valuemin", "0");
          bar.setAttribute("aria-valuemax", "100");
          bar.setAttribute("aria-label", "Health");
        }
        for (const child of bar.querySelectorAll(".progress-bar")) {
          child.setAttribute("aria-hidden", "true");
        }
      }
    }
    // Enemy side: no X/Y available, so trust the first inner bar's value
    // (that's what visually represents current HP), but still expose it on
    // the outer .progress and hide the decorative inner bars from SR.
    const enemyInfo = battlePage.querySelector(".enemyInfo");
    if (enemyInfo) {
      for (const bar of enemyInfo.querySelectorAll(".progress")) {
        const isShield = bar.classList.contains("shield");
        const fc = bar.querySelector(".progress-bar");
        const raw = parseFloat(fc?.getAttribute("aria-valuenow") || "");
        if (isFinite(raw)) {
          bar.setAttribute("role", "progressbar");
          bar.setAttribute("aria-valuenow", String(Math.round(raw)));
          bar.setAttribute("aria-valuemin", "0");
          bar.setAttribute("aria-valuemax", "100");
          bar.setAttribute("aria-label", isShield ? "Shield" : "Health");
        }
        for (const child of bar.querySelectorAll(".progress-bar")) {
          child.setAttribute("aria-hidden", "true");
        }
      }
    }
  }

  // Returns [{kind: 'health'|'shield', pct: number}] for the enemy's bars,
  // in DOM order. Empty when not in battle / no enemy bars.
  function readEnemyBars() {
    const battlePage = document.querySelector(
      '.page-on-center.battlepage, .page-on-center[data-page="battle"]'
    );
    if (!battlePage) return [];
    const enemyInfo = battlePage.querySelector(".enemyInfo");
    if (!enemyInfo) return [];
    const out = [];
    for (const bar of enemyInfo.querySelectorAll(".progress")) {
      const kind = bar.classList.contains("shield") ? "shield" : "health";
      const fc = bar.querySelector(".progress-bar");
      const raw = parseFloat(fc?.getAttribute("aria-valuenow") || "");
      if (isFinite(raw)) out.push({ kind, pct: raw });
    }
    return out;
  }

  function getPlayerHpPercent() {
    // Only look inside an active battle page — outside battle there is no
    // hero HP bar so we shouldn't infer anything.
    const battlePage = document.querySelector(
      '.page-on-center.battlepage, .page-on-center[data-page="battle"]'
    );
    if (!battlePage) return null;
    const playerInfo = battlePage.querySelector(".playerInfo");
    if (!playerInfo) return null;
    // Prefer the actual "X / Y" text — bar widths are cosmetic stacks that
    // don't reflect real HP. Falls back to the bar reading only if the text
    // isn't present (e.g. unusual battle layouts).
    const fromText = hpPercentFromText(playerInfo);
    if (fromText != null) return fromText;
    const bars = playerInfo.querySelectorAll(".progress");
    let hpBar = null;
    for (const b of bars) if (!b.classList.contains("shield")) hpBar = b;
    if (!hpBar) return null;
    const firstBar = hpBar.querySelector(".progress-bar");
    if (!firstBar) return null;
    const raw = parseFloat(firstBar.getAttribute("aria-valuenow"));
    if (!isFinite(raw)) return null;
    return raw;
  }

  function startLowHealthAlarm() {
    if (_lowHealthPlaying) return;
    if (!CONFIG.audioEnabled) return;
    const audioData = window.__bw2Audio || {};
    const src = audioData["low_health_alarm.ogg"] ||
                audioData["low_health_alarm.wav"] ||
                audioData["low_health_alarm.mp3"];
    if (!src) return;
    if (!_lowHealthAudio) {
      _lowHealthAudio = new Audio(src);
      _lowHealthAudio.loop = true;
      _lowHealthAudio.volume = Math.min(sfxVolume, 1.0);
    }
    _lowHealthAudio.currentTime = 0;
    _lowHealthAudio.play().catch(() => {});
    _lowHealthPlaying = true;
  }

  function stopLowHealthAlarm() {
    if (!_lowHealthPlaying) return;
    try { _lowHealthAudio && _lowHealthAudio.pause(); } catch (_) {}
    try { if (_lowHealthAudio) _lowHealthAudio.currentTime = 0; } catch (_) {}
    _lowHealthPlaying = false;
  }

  function evaluateLowHealthAlarm() {
    if (!CONFIG.audioEnabled) { stopLowHealthAlarm(); return; }
    const hp = getPlayerHpPercent();
    if (hp == null) { stopLowHealthAlarm(); return; }
    if (hp < LOW_HEALTH_THRESHOLD) startLowHealthAlarm();
    else stopLowHealthAlarm();
  }

  function setupLowHealthAlarm() {
    setInterval(evaluateLowHealthAlarm, 500);
  }

  // --- NVDA-style realtime beep for enemy HP/shield bars -----------------
  // Direct port of NVDA's generateBeep() from nvdaHelper/local/beeps.cpp,
  // wired to the frequency formula from source/NVDAObjects/behaviors.py:
  //   tones.beep(beepMinHZ * 2 ** (percentage / 25.0), 40)
  // where beepMinHZ defaults to 110 (source/config/configSpec.py). That
  // gives 110 Hz at 0% → 1760 Hz at 100% — a 4-octave log-spaced sweep.
  //
  // NVDA's C loop:
  //   const int samplesPerCycle = sampleRate/hz;
  //   int totalSamples = (length/1000.0)/(1.0/sampleRate);
  //   totalSamples += samplesPerCycle - (totalSamples % samplesPerCycle);
  //   const double lpan = (left/100.0)*amplitude, rpan = (right/100.0)*amplitude;
  //   const double sinFreq = (2.0*M_PI)/(sampleRate/hz);
  //   for (int i=0; i<totalSamples; ++i) {
  //     const double sample = clamp(sin((i%sampleRate)*sinFreq)*2.0, -1, 1);
  //     ...
  //   }
  //   // amplitude = 14000 (int16), so peak = 14000/32767 ≈ 0.427 at vol=100
  //
  // Key character detail: the sine is multiplied by 2 and clipped to ±1,
  // producing a flat-topped (soft-square) wave with strong odd harmonics —
  // that's why NVDA's progress beep cuts through other audio. A pure sine
  // would be noticeably softer / less audible.
  //
  // NVDA has no fade-in/fade-out; clicks are avoided by rounding
  // totalSamples up to a whole number of cycles so the wave ends at a
  // zero-crossing. We port that exact mechanism.
  //
  // Volume: NVDA's default is left=50,right=50 → peak ≈ 0.214 float. The
  // user wanted it louder than that, so we scale 0.6 × sfxVolume up to the
  // digital clipping ceiling of 1.0.
  //
  // Detection: document-wide MutationObserver on aria-valuenow so every
  // individual hit produces a beep — previous polling impl missed beeps
  // when two attacks landed inside one tick.
  const NVDA_BEEP_MIN_HZ = 110;
  const NVDA_BEEP_DURATION_MS = 40;
  const NVDA_BEEP_PEAK_GAIN = 0.6;
  const _enemyBeepLastByBar = new WeakMap(); // first-child bar el -> last pct

  function playProgressBeep(pct) {
    const ctx = ensureAudioCtx();
    if (!ctx) return;
    const t = Math.max(0, Math.min(100, pct));
    const freq = NVDA_BEEP_MIN_HZ * Math.pow(2, t / 25);
    const sampleRate = ctx.sampleRate;
    const samplesPerCycle = Math.floor(sampleRate / freq);
    if (samplesPerCycle <= 0) return;
    let totalSamples = Math.floor((NVDA_BEEP_DURATION_MS / 1000.0) * sampleRate);
    totalSamples += samplesPerCycle - (totalSamples % samplesPerCycle);
    const sinFreq = (2 * Math.PI) / (sampleRate / freq);
    const gain = Math.min(NVDA_BEEP_PEAK_GAIN * sfxVolume, 1.0);
    const buffer = ctx.createBuffer(1, totalSamples, sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < totalSamples; i++) {
      const raw = Math.sin((i % sampleRate) * sinFreq) * 2.0;
      const clamped = raw > 1 ? 1 : (raw < -1 ? -1 : raw);
      data[i] = clamped * gain;
    }
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(ctx.destination);
    src.start();
  }

  function setupEnemyBeep() {
    const obs = new MutationObserver((mutations) => {
      if (!CONFIG.audioEnabled) return;
      for (const m of mutations) {
        if (m.type !== "attributes" || m.attributeName !== "aria-valuenow") continue;
        const el = m.target;
        if (!el || !el.classList || !el.classList.contains("progress-bar")) continue;
        // Only the first child bar inside an .enemyInfo .progress container
        // tracks current HP/shield. The second child is a transient "damage
        // flash" overlay whose value oscillates and would create phantom
        // beeps if we listened to it.
        const container = el.parentElement;
        if (!container || !container.classList.contains("progress")) continue;
        if (!container.closest(".enemyInfo")) continue;
        if (container.firstElementChild !== el) continue;
        const raw = parseFloat(el.getAttribute("aria-valuenow") || "");
        if (!isFinite(raw)) continue;
        if (_enemyBeepLastByBar.get(el) === raw) continue;
        _enemyBeepLastByBar.set(el, raw);
        playProgressBeep(raw);
      }
    });
    obs.observe(document.body, {
      attributes: true,
      attributeFilter: ["aria-valuenow"],
      subtree: true,
    });
  }

  // --- Currency-change SFX (AC) ------------------------------------------
  // First time the app runs we silently cache the player's AC balance to
  // localStorage. On every later page load (patrol after a kill, daily
  // reward, login screen) we re-read it and:
  //   delta == +1        → play both files in audio/currency/low/
  //   +2 ≤ delta ≤ +5    → play both files in audio/currency/medium/
  //   delta  >  +5       → play both files in audio/currency/high/
  //   delta  <   0       → play purchase.ogg
  // Currency cues do NOT get the global pitch-randomization that combat
  // SFX get — they should always sound clean.
  const AC_STORAGE_KEY = "bw2:lastAC";
  const CURRENCY_FILES = {
    low: ["currency/low/Cash_Pickup_Low.ogg", "currency/low/Cash_PickUp_Tone_Low.ogg"],
    medium: ["currency/medium/Cash_Pickup_Med.ogg", "currency/medium/Cash_PickUp_Tone_Med.ogg"],
    high: ["currency/high/Cash_Pickup_High.ogg", "currency/high/Cash_PickUp_Tone_High.ogg"],
  };

  // Plays a sound at the configured volume without pitch randomization.
  function playFlatSfx(name) {
    if (!CONFIG.audioEnabled) return;
    const audioData = window.__bw2Audio;
    if (!audioData || !audioData[name]) return;
    const audio = new Audio(audioData[name]);
    audio.volume = Math.min(sfxVolume, 1.0);
    audio.play().catch(() => {});
  }

  function playCurrencyTier(tier) {
    const files = CURRENCY_FILES[tier];
    if (!files) return;
    for (const name of files) playFlatSfx(name);
  }

  // --- Material drop SFX (Copper / Iron / Bronze / Silver / Gold) -------
  // One sound per material chip in the loot, played at its natural pitch
  // (a random pick among the five matget files for variety — no pitch
  // randomization or amount-based pitch scaling).
  const MAT_FILES = [
    "mats/matget1.ogg",
    "mats/matget2.ogg",
    "mats/matget3.ogg",
    "mats/matget4.ogg",
    "mats/matget5.ogg",
  ];
  const MAT_NAME_RE = /^(\d+)x\s+(Copper|Iron|Bronze|Silver|Gold)\b/i;

  function playMatSfx() {
    if (!CONFIG.audioEnabled) return;
    const audioData = window.__bw2Audio;
    if (!audioData) return;
    const file = MAT_FILES[Math.floor(Math.random() * MAT_FILES.length)];
    if (!audioData[file]) return;
    const audio = new Audio(audioData[file]);
    audio.volume = Math.min(sfxVolume, 1.0);
    audio.play().catch(() => {});
  }

  function playMatSfxFromLoot(tempDiv) {
    if (!CONFIG.audioEnabled) return;
    const chips = tempDiv.querySelectorAll("#postBattleInfo .chip.item .chip-label");
    let delay = 0;
    for (const chip of chips) {
      const m = (chip.textContent || "").trim().match(MAT_NAME_RE);
      if (!m) continue;
      const amount = parseInt(m[1], 10);
      if (!isFinite(amount) || amount < 1) continue;
      setTimeout(() => playMatSfx(), delay);
      delay += 150;
    }
  }

  // --- Doubled-item reward SFX ------------------------------------------
  // On a kill, scan the loot/reward chips and count how many DISTINCT items
  // dropped two or more times (e.g. "LP Orb" twice, or an Ancient Coin AND
  // an LP Orb each doubled). For every doubled item we queue one play of
  // "item doubled.ogg", spaced 500ms apart. Each play is a fresh Audio via
  // playFlatSfx, so a later cue layers ON TOP of an earlier one instead of
  // cutting it off, and there is no pitch randomization.
  const DOUBLED_ITEM_FILE = "item doubled.ogg";

  function playDoubledItemSfxFromLoot(tempDiv) {
    if (!CONFIG.audioEnabled) return;
    const chips = tempDiv.querySelectorAll("#postBattleInfo .chip.item .chip-label");
    const counts = new Map();
    for (const chip of chips) {
      // Normalize away a leading "3x " quantity prefix and case so two
      // separate drops of the same item collapse to a single key.
      const name = (chip.textContent || "")
        .trim()
        .replace(/^\d+\s*x\s*/i, "")
        .replace(/\s+/g, " ")
        .toLowerCase();
      if (!name) continue;
      counts.set(name, (counts.get(name) || 0) + 1);
    }
    let doubled = 0;
    for (const n of counts.values()) {
      if (n >= 2) doubled++;
    }
    let delay = 0;
    for (let i = 0; i < doubled; i++) {
      setTimeout(() => playFlatSfx(DOUBLED_ITEM_FILE), delay);
      delay += 500;
    }
  }

  function readACValue() {
    const acImg = document.querySelector('#statszone .chip-media img[src*="icon-ac.png"]');
    if (!acImg) return null;
    const chip = acImg.closest(".chip");
    if (!chip) return null;
    const label = chip.querySelector(".chip-label");
    if (!label) return null;
    // chip-label may have the " AC" suffix appended by enhanceStatsZone, plus
    // commas in larger values; strip everything that isn't a digit or sign.
    const raw = label.textContent.trim().replace(/[^\d-]/g, "");
    if (!raw) return null;
    const num = parseInt(raw, 10);
    return isFinite(num) ? num : null;
  }

  function checkACChange() {
    const cur = readACValue();
    if (cur == null) return;
    let prev = null;
    try { prev = localStorage.getItem(AC_STORAGE_KEY); } catch (_) {}
    if (prev == null || prev === "") {
      try { localStorage.setItem(AC_STORAGE_KEY, String(cur)); } catch (_) {}
      return;
    }
    const prevNum = parseInt(prev, 10);
    if (!isFinite(prevNum) || prevNum === cur) {
      if (prevNum !== cur) {
        try { localStorage.setItem(AC_STORAGE_KEY, String(cur)); } catch (_) {}
      }
      return;
    }
    const delta = cur - prevNum;
    if (delta > 0) {
      if (delta === 1) playCurrencyTier("low");
      else if (delta <= 5) playCurrencyTier("medium");
      else playCurrencyTier("high");
    } else if (delta < 0) {
      playFlatSfx("purchase.ogg");
    }
    try { localStorage.setItem(AC_STORAGE_KEY, String(cur)); } catch (_) {}
  }

  function playBattleSfx(tempDiv, pageText) {
    // Check for container opened FIRST (victory screen has no .playerInfo)
    if (pageText && pageText.includes("You opened")) {
      playSfx("openedcontainer1.ogg");
      return;
    }

    const playerInfo = tempDiv.querySelector(".playerInfo");
    const enemyInfo = tempDiv.querySelector(".enemyInfo");
    if (!playerInfo) return;

    // Note: weapon fire SFX is dispatched on KEYPRESS (see setupKeyboardShortcuts),
    // not here. That guarantees the sound plays even on kill responses, where
    // the attack-type text moves out of .playerInfo into #postBattleInfo and
    // would otherwise be missed by a response-side parser.

    // Did we deal damage?
    const playerDmgEl = playerInfo.querySelector('strong[style*="32CD32"], strong[style*="green"]');
    if (!playerDmgEl) return; // no damage dealt, no outcome sfx

    // Check if this is a container battle:
    // 1. Check enemy name in the loaded HTML navbar
    // 2. Check live document navbar
    // 3. Check playerInfo for "try to open" (only containers use this)
    const tempNavbar = tempDiv.querySelector(".center.sliding");
    const liveNavbar = document.querySelector(".navbar-on-center .center");
    const enemyName = tempNavbar?.textContent?.trim() || liveNavbar?.textContent?.trim() || "";
    const playerText = playerInfo.textContent || "";
    const isContainer = enemyName.includes("Cache") || enemyName.includes("Chest") || enemyName.includes("Box") ||
      playerText.includes("try to open");

    // Container hit — play random container hit sound
    if (isContainer) {
      const hitNum = 1 + Math.floor(Math.random() * 3);
      playSfx("containerhit" + hitNum + ".ogg");
      return;
    }

    const isCrit = playerText.includes("Critical Hit");

    // What did we hit — shield or health?
    const progressEl = enemyInfo?.querySelector(".progress");
    const hasShield = progressEl?.classList?.contains("shield") || false;

    // Check if shield was broken (negative remaining)
    let shieldBroken = false;
    if (progressEl && hasShield) {
      const firstBar = progressEl.querySelector(".progress-bar");
      const ariaVal = parseFloat(firstBar?.getAttribute("aria-valuenow") || "0");
      if (ariaVal < 0) shieldBroken = true;

      if (!shieldBroken) {
        let node = progressEl.previousSibling;
        while (node) {
          if (node.nodeType === 3) {
            const txt = node.textContent.trim();
            if (txt) {
              const num = txt.match(/(-[\d,]+)/);
              if (num) { shieldBroken = true; }
              break;
            }
          }
          node = node.previousSibling;
        }
      }
    }

    if (shieldBroken) {
      playSfx("shieldbreak1.wav");
    } else if (hasShield && isCrit) {
      playSfx("health2.wav");
      playSfx("shield1.wav");
    } else if (hasShield) {
      playSfx("shield1.wav");
    } else if (isCrit) {
      playSfx("health2.wav");
      playSfx("health1.wav");
    } else {
      playSfx("health1.wav");
    }
  }

  // =========================================================================
  //  MODULE 13: PERFORMANCE — Battle Hooks (skip victory screen, announcements)
  // =========================================================================
  function setupBattleHooks() {
    function applyBattleHook() {
      const mv = window.mainView;
      if (!mv || !mv.router || !mv.router.loadContent) return false;

      dlog("info", "setupBattleHooks: wrapping mainView.router.loadContent");
      const origLoadContent = mv.router.loadContent.bind(mv.router);
      mv.router.loadContent = function (data) {
        const len = typeof data === "string" ? data.length : -1;
        const head = typeof data === "string" ? data.slice(0, 120).replace(/\s+/g, " ") : "(non-string)";
        dlog("info", "router.loadContent called len=" + len + " head=" + JSON.stringify(head));

        // Whether the pre-processor decided we should auto-click the
        // patrol link after the page loads (i.e. skip-victory-screen).
        let skipVictory = false;

        // === Pre-load hook: parse the incoming HTML for battle SFX / SR =====
        // EVERY throwable line lives inside this safeRun so an exception in
        // our hook can NEVER prevent origLoadContent(data) from running and
        // navigation always completes.
        safeRun("loadContent:preProcess", () => {
        if (typeof data === "string" && data.includes("battle")) {
          const tempDiv = document.createElement("div");
          tempDiv.innerHTML = data;
          const pageText = tempDiv.textContent || "";
          const isBattlePage =
            tempDiv.querySelector('[data-page="battle"]') ||
            data.includes("attacklink") ||
            data.includes("patrollink") ||
            data.includes("You killed");

          if (isBattlePage) {
            // Track for debug dump
            if (pageText.includes("You killed")) {
              window.__bw2LastPageName = "battle-victory";
            } else if (pageText.includes("You died")) {
              window.__bw2LastPageName = "battle-defeat";
            } else {
              window.__bw2LastPageName = "battle";
            }

            // Play battle SFX
            playBattleSfx(tempDiv, pageText);
            // Material drop SFX — fires for any post-battle screen that
            // shows loot (kills, opened caches/chests).
            playMatSfxFromLoot(tempDiv);
            if ((pageText.includes("You killed") || pageText.includes("You defeated")) && !pageText.includes("opened")) {
              playSfx("eliminated1.wav");
              // Doubled-item reward cue — one "item doubled" play per loot
              // item that dropped twice, overlapping and spaced 500ms apart.
              playDoubledItemSfxFromLoot(tempDiv);
              // Patrol → instant-kill backstop. If clicking a weak enemy on
              // the patrol list 1-shots them, the game skips the battle page
              // and goes straight to a kill response. The keypress-side fire
              // path never ran (it only fires when a battle page is present),
              // so no weapon fire was heard. If no fire SFX has played in the
              // last 1.5s, this kill must have come from a patrol click —
              // play the primary weapon's fire as a backstop. The window is
              // forgiving enough to cover slow network round-trips but tight
              // enough not to double-fire on normal in-battle kills.
              if (Date.now() - _lastWeaponFireMs > 1500) {
                playWeaponFireSfx("Primary");
              }
            }

            if (CONFIG.enableBattleAnnouncements) {
              const parts = [];

              // Parse player attack from .playerInfo
              const playerInfo = tempDiv.querySelector(".playerInfo");
              if (playerInfo) {
                // Get damage from the <strong> element (clean number, no trailing punctuation)
                const playerDmgEl = playerInfo.querySelector('strong[style*="32CD32"], strong[style*="green"]');
                const playerDmg = playerDmgEl?.textContent?.trim()?.replace(/[!.,]+$/, "");
                if (playerDmg) {
                  const playerText = playerInfo.textContent || "";
                  const atkType = playerText.match(/(Primary|Special|Heavy|Illumina)/i);
                  parts.push((atkType ? atkType[1] + " Attack" : "Attack") + " for " + playerDmg + "!");
                }
                // Check for "try to open" (material caches)
                const playerText = playerInfo.textContent || "";
                const openMatch = playerText.match(/try to open it\s+for\s*([\d,]+)/i);
                if (openMatch) {
                  parts.push("Open for " + openMatch[1] + "!");
                }
              }

              // Parse enemy attack from .enemyInfo
              const enemyInfo = tempDiv.querySelector(".enemyInfo");
              if (enemyInfo) {
                const enemyText = enemyInfo.textContent || "";
                const isMiss = enemyText.includes("Misses");

                if (isMiss) {
                  parts.push("Enemy misses!");
                } else {
                  // Extract damage number from the <strong> inside enemyInfo
                  const enemyDmgEl = enemyInfo.querySelector('strong[style*="red"]');
                  const enemyDmg = enemyDmgEl?.textContent?.trim();
                  if (enemyDmg) {
                    parts.push("Enemy attacks for " + enemyDmg + "!");
                  }
                }

                // Enemy HP/Shield — get the text node directly before the progress bar
                const progressEl = enemyInfo.querySelector(".progress");
                if (progressEl) {
                  const hasShield = progressEl.classList.contains("shield");
                  // Walk backwards from progress bar to find the HP/shield number
                  let hpText = "";
                  let node = progressEl.previousSibling;
                  while (node) {
                    if (node.nodeType === 3) { // text node
                      // Include negative sign: -513 means shield was broken
                      const num = node.textContent.match(/(-?[\d,]+)/);
                      if (num) { hpText = num[1]; break; }
                    }
                    node = node.previousSibling;
                  }
                  if (hpText) {
                    const numVal = parseInt(hpText.replace(/,/g, ""));
                    if (numVal < 0) {
                      parts.push("Enemy " + (hasShield ? "shield" : "") + " broken!");
                    } else {
                      parts.push("Enemy " + (hasShield ? "shield" : "HP") + " " + hpText + ".");
                    }
                  }
                }
              }

              // Victory / Defeat — put at the front
              const postTitle = tempDiv.querySelector(".postBattleTitle");
              if (postTitle) {
                const titleText = postTitle.textContent.trim();
                // "You killed the X!" or "You defeated X!" or "You opened the X!"
                if (titleText) parts.push(titleText);
              } else if (pageText.includes("You died")) {
                parts.unshift("Defeat! You died!");
              }

              // Loot items (inside postBattleInfo, after postBattleTitle)
              const lootChips = tempDiv.querySelectorAll("#postBattleInfo .chip.item .chip-label");
              if (lootChips.length > 0) {
                const lootItems = Array.from(lootChips).map(c => c.textContent.trim());
                parts.push("Loot: " + lootItems.join(", ") + ".");
              }

              // Parse rewards from #console chips — use alt text for labels
              const rewardChips = tempDiv.querySelectorAll("#console .chip");
              for (const chip of rewardChips) {
                const label = chip.querySelector(".chip-label")?.textContent?.trim();
                if (!label) continue;
                const alt = chip.querySelector(".chip-media img")?.getAttribute("alt") || "";
                if (alt.includes("XP")) {
                  parts.push("XP " + label + ".");
                } else if (alt.includes("Drachma")) {
                  parts.push("Drachma " + label + ".");
                } else if (alt.includes("LP")) {
                  parts.push("LP " + label + ".");
                } else {
                  parts.push(label + ".");
                }
              }

              // Battle Totals (on victory/defeat screens). Iterate by ROW —
              // the table mixes a colspan=4 title row with regular 4-cell
              // rows and a final 2-cell "XP to Gear" row (colspan=3 on val),
              // so flat-cell pair indexing skips fields.
              const totalsTable = tempDiv.querySelector(".data-table table");
              if (totalsTable) {
                const totals = [];
                for (const tr of totalsTable.querySelectorAll("tr")) {
                  const cells = tr.querySelectorAll("td");
                  if (cells.length < 2) continue; // header/title row
                  for (let i = 0; i + 1 < cells.length; i += 2) {
                    const key = cells[i].textContent.trim().replace(/:$/, "");
                    let val = cells[i + 1].textContent.trim();
                    if (!key || !val) continue;

                    if (key === "Time") {
                      const tm = val.match(/(\d+):(\d+):(\d+)/);
                      if (tm) {
                        const h = parseInt(tm[1]), m = parseInt(tm[2]), s = parseInt(tm[3]);
                        const p = [];
                        if (h > 0) p.push(h + (h === 1 ? " hour" : " hours"));
                        if (m > 0) p.push(m + (m === 1 ? " minute" : " minutes"));
                        if (s > 0 || p.length === 0) p.push(s + (s === 1 ? " second" : " seconds"));
                        val = p.join(" ");
                      }
                    }

                    totals.push(key + " " + val);
                  }
                }
                if (totals.length > 0) {
                  parts.push("Totals: " + totals.join(", ") + ".");
                }

                // Top 3 Damage leaderboard sits in a sibling div inside the
                // same .card-content, after the table. Announce verbatim;
                // user judges the shard-of-Zeus eligibility themselves.
                const cardContent = totalsTable.closest(".card-content");
                if (cardContent) {
                  for (const div of cardContent.querySelectorAll("div")) {
                    const t = (div.textContent || "").trim();
                    if (t.startsWith("Top 3")) {
                      const flat = (div.innerHTML || "")
                        .replace(/<br\s*\/?>/gi, ". ")
                        .replace(/<[^>]+>/g, "")
                        .replace(/\s+/g, " ")
                        .trim()
                        .replace(/\.\s*\.+/g, ".")
                        .replace(/\.\s*$/, "");
                      if (flat) parts.push(flat + ".");
                      break;
                    }
                  }
                }
              }

              if (parts.length > 0) {
                announce(parts.join(" "), "assertive");
              }
            }

            if (
              CONFIG.skipVictoryScreen &&
              pageText.includes("You killed") &&
              !pageText.includes("You died")
            ) {
              skipVictory = true;
            }
          }
        }
        });
        // === End pre-load hook ===========================================

        _pageTransitioning = true;
        _focusGeneration++;
        try {
          origLoadContent(data);
        } catch (e) {
          derr("origLoadContent threw:", e);
        }
        requestAnimationFrame(function () {
          safeRun("loadContent:postProcess", () => {
            enhanceBattlePage();
            // If the user is mid-typing in chat / a count field, leave their
            // caret alone. skip-victory still needs to click the patrol link
            // to advance the flow, but it should not yank focus first.
            const typing = userIsTyping();
            if (skipVictory) {
              const pl = document.querySelector("a.patrollink");
              if (pl) {
                if (!typing) {
                  pl.setAttribute("tabindex", "-1");
                  pl.focus({ preventScroll: true });
                }
                pl.click();
              }
            } else if (!typing) {
              // Focus contextually: attack button in battle, enemy list on patrol
              const firstBtn = document.querySelector(
                ".page-on-center .attacklink, .page-on-center .patrollink"
              );
              if (firstBtn) {
                firstBtn.setAttribute("tabindex", "-1");
                firstBtn.focus({ preventScroll: true });
              } else {
                // On patrol or other pages — use standard focus logic
                doFocusAndHide();
              }
            }
          });
          _pageTransitioning = false;
        });
      };

      return true;
    }

    if (!applyBattleHook()) {
      const poll = setInterval(() => {
        if (applyBattleHook()) clearInterval(poll);
      }, 200);
    }
  }

  // =========================================================================
  //  MODULE 13: PERFORMANCE — Remove Battle Action Slide Animation
  // =========================================================================
  function removeAllAnimations() {
    // CSS: kill all animations and transitions globally
    const style = document.createElement("style");
    style.textContent = `
      *, *::before, *::after {
        animation-duration: 0s !important;
        animation-delay: 0s !important;
        transition-duration: 0s !important;
        transition-delay: 0s !important;
      }
    `;
    document.head.appendChild(style);

    // jQuery: disable all animations
    function disableJQueryFx() {
      if (!window.jQuery) return false;
      window.jQuery.fx.off = true;
      return true;
    }

    if (!disableJQueryFx()) {
      const poll = setInterval(() => {
        if (disableJQueryFx()) clearInterval(poll);
      }, 200);
    }
  }

  // =========================================================================
  //  MODULE 14: PERFORMANCE — Audio Pooling & Optimization
  // =========================================================================
  function setupAudioOptimizer() {
    if (window.__audioOptimizerLoaded) return;
    window.__audioOptimizerLoaded = true;

    const OriginalAudio = window.Audio;
    const audioPool = {};

    window.Audio = function(src) {
      try {
        if (src && window.__bw2Audio && window.__bw2Audio[src]) {
          if (!audioPool[src]) {
            audioPool[src] = [];
          }
          const pooledAudio = audioPool[src].find(a => !a.__playing);
          if (pooledAudio) {
            return pooledAudio;
          }
        }
      } catch (e) {}

      const audio = new OriginalAudio(src);
      audio.__originalPlay = audio.play;
      audio.play = function() {
        const self = this;
        this.__playing = true;
        const result = this.__originalPlay.apply(this, arguments);
        if (result && result.then) {
          result.then(() => {
            setTimeout(() => { self.__playing = false; }, this.duration * 1000 + 100);
          }).catch(() => {
            self.__playing = false;
          });
        } else {
          setTimeout(() => { self.__playing = false; }, audio.duration * 1000 + 100);
        }
        return result;
      };
      return audio;
    };

    window.Audio.prototype = OriginalAudio.prototype;
  }

  // =========================================================================
  //  MODULE 15: ACCESSIBILITY — Remove Chat UI
  // =========================================================================
  function setupChatRemoval() {
    if (window.__stripChatLoaded) return;
    window.__stripChatLoaded = true;

    function removeChat() {
      const chatSelectors = [
        '.panel-right',
        '[class*="chat"]',
        '[id*="chat"]',
        '[class*="conversation"]',
        '[class*="messenger"]'
      ];

      chatSelectors.forEach(selector => {
        try {
          document.querySelectorAll(selector).forEach(el => {
            if (el && el.parentNode) {
              el.remove();
            }
          });
        } catch (e) {}
      });
    }

    removeChat();
    setInterval(removeChat, 500);
  }

  // =========================================================================
  //  INIT
  // =========================================================================
  function init() {
    // --- Diagnostic logging (wire FIRST so subsequent setup is captured) ---
    safeRun("installGlobalErrorTraps", () => installGlobalErrorTraps());
    safeRun("installClickLogger", () => installClickLogger());
    safeRun("installNetworkLogger", () => installNetworkLogger());
    dlog("info", `enhancer init build=${BW2_BUILD_TAG} url=${location.href} ua=${navigator.userAgent}`);

    // Performance modules (analytics blocked at Electron network level)
    setupScriptCache();
    setupRequestDedup();
    setupImageLazyLoading();
    setupDOMCleanup();
    removeAllAnimations();
    setupAudioOptimizer();

    // Accessibility: remove chat UI
    setupChatRemoval();

    // Battle hooks (victory skip + announcements)
    setupBattleHooks();

    // Mouse/touch clicks on attack buttons also fire the weapon SFX
    // (keyboard 1/2/3/4 already does this directly).
    setupAttackClickSfx();

    // Low-health alarm — polls the in-battle HP bar and loops the alarm
    // sound while HP is under 25%.
    setupLowHealthAlarm();

    // NVDA-style beep when the enemy's HP/shield bars change.
    setupEnemyBeep();

    // Pull persisted user settings (enemy memory on/off + lifetime) into CONFIG
    // BEFORE the modules that read those flags initialise.
    loadPersistedSettings();

    // Accessibility modules
    createLiveRegions();
    setupFocusManagement();
    setupKeyboardShortcuts();
    setupNativeDialogs();
    setupBulkInfuse();

    // Enemy-list memory + decoy safety + chest-aware ordering (MODULE 7b).
    setupEnemyListMemory();

    // Ctrl+Shift+T settings dialog (MODULE 7c) — toggles enemy memory and tunes
    // how long seen enemies are remembered.
    setupSettingsDialog();

    // Shorten page title so SR doesn't read the full game name on every page load
    document.title = "Bloodwar 2";

    // Set initial page name for debug dump
    const initPageName =
      document.querySelector(".page-on-center .navbar .center")?.textContent ||
      document.querySelector('[data-page]')?.getAttribute("data-page") ||
      "index";
    window.__bw2LastPageName = initPageName.replace(/\s+/g, "-").toLowerCase();

    // Initial enhancement pass
    runContentEnhancements();

    // Watch for new AJAX-loaded content
    setupContentObserver();

    console.log(
      "%c[Bloodwar 2] Loaded — Performance & Accessibility enhancements active",
      "color: #FFD700; font-weight: bold;"
    );
    console.log(
      "%c[Bloodwar 2] Keyboard shortcuts: 1-5 = battle actions, X = read stats, Alt+H = home, Alt+P = patrol, Alt+V = toggle skip victory screen",
      "color: #AAA;"
    );
    console.log(
      "%c[Bloodwar 2] Patrol: [ = look around (announces enemy count), / = next area (forward only), 1/2/3 = attack priority target (bounty › chest › enemy)",
      "color: #AAA;"
    );
    console.log(
      "%c[Bloodwar 2] Ctrl+Shift+T = settings (enemy memory on/off + how long enemies are remembered)",
      "color: #AAA;"
    );
  }

  if (document.readyState === "complete") {
    init();
  } else {
    window.addEventListener("load", init);
  }
})();
