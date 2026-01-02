import express from "express";
import { chromium } from "playwright";
import crypto from "crypto";

const app = express();

/*
	========================================================
	CRITICAL CSS HEADLESS API (Playwright + CDP Coverage)
	- Offline generation (cron)
	- No runtime fetch from PrestaShop
	- Output: raw CSS (text/plain or text/css)
	========================================================
*/

/* =========================
	CONFIG
========================= */

const PORT = parseInt(process.env.PORT || "3000", 10);

// If set, requires ?token=...
const API_TOKEN = process.env.CRITICALCSS_TOKEN || "";

// Memory cache (optional but helpful for repeated runs)
const CACHE = new Map();
const CACHE_MAX_ITEMS = 200;
const CACHE_TTL_MS = 1000 * 60 * 30; // 30 minutes

function nowMs() {
		return Date.now();
}

function sha256(s) {
		return crypto.createHash("sha256").update(s).digest("hex");
}

function cacheGet(key) {
		const item = CACHE.get(key);
		if (!item) return null;
		if (item.expiresAt <= nowMs()) {
				CACHE.delete(key);
				return null;
		}
		return item.value;
}

function cacheSet(key, value) {
		if (CACHE.size >= CACHE_MAX_ITEMS) {
				const firstKey = CACHE.keys().next().value;
				if (firstKey) CACHE.delete(firstKey);
		}
		CACHE.set(key, { value, expiresAt: nowMs() + CACHE_TTL_MS });
}

function minifyCss(css) {
		return css
				.replace(/\/\*[\s\S]*?\*\//g, "")
				.replace(/\s+/g, " ")
				.replace(/\s*([{}:;,])\s*/g, "$1")
				.replace(/;}/g, "}")
				.trim();
}

function mergeRanges(ranges) {
		ranges.sort((a, b) => a[0] - b[0]);
		const out = [];
		for (const r of ranges) {
				if (!out.length || r[0] > out[out.length - 1][1]) {
						out.push([r[0], r[1]]);
				} else {
						out[out.length - 1][1] = Math.max(out[out.length - 1][1], r[1]);
				}
		}
		return out;
}

/*
	Extract a minimal “global base” that often makes the difference for header rendering:
	- @font-face
	- @keyframes
	- :root variables
	- html/body/* basics (heuristic)
	This is intentionally conservative: it improves correctness, and you can tune later.
*/
function extractGlobalBase(cssText) {
		const blocks = [];

		// @font-face
		const fontFaceRe = /@font-face\s*{[^}]*}/g;
		cssText.replace(fontFaceRe, (m) => {
				blocks.push(m);
				return m;
		});

		// @keyframes (simple heuristic)
		const keyframesRe = /@keyframes\s+[^{]+\{[\s\S]*?\}\s*\}/g;
		cssText.replace(keyframesRe, (m) => {
				blocks.push(m);
				return m;
		});

		// :root
		const rootRe = /:root\s*{[^}]*}/g;
		cssText.replace(rootRe, (m) => {
				blocks.push(m);
				return m;
		});

		// html/body/* basics (heuristic)
		const simpleRuleRe = /([^{]+)\{([^}]*)\}/g;
		let match;
		while ((match = simpleRuleRe.exec(cssText)) !== null) {
				const sel = (match[1] || "").trim();
				if (
						sel.includes("html") ||
						sel.includes("body") ||
						sel.includes("*") ||
						sel.includes("::before") ||
						sel.includes("::after")
				) {
						blocks.push(`${sel}{${match[2]}}`);
				}
		}

		return Array.from(new Set(blocks)).join("\n");
}

/* =========================
	ROUTES
========================= */

app.get("/health", (req, res) => {
		res.status(200).json({ ok: true, sha: process.env.APP_GIT_SHA || "unknown" });
});

/*
	GET /critical-css

	Required:
	- url
	Optional:
	- w, h          viewport
	- ua            user agent
	- token         auth (if CRITICALCSS_TOKEN is set)
	- settle        ms after “stable” (default 10000)
	- csswait       max ms to wait for CSS applied (default 20000)
	- wait          CSV selectors to wait (best-effort)
	- scope         "" | "header"   (header-only capture after stabilization)
	- base          0|1 include global base (default 1)

	Strategy (robust):
	1) startRuleUsageTracking BEFORE goto (covers early usage if you keep global mode)
	2) goto domcontentloaded
	3) wait load + networkidle (best-effort)
	4) wait for “CSS applied” (rel=stylesheet -> sheet present, and no media=print remaining)
	5) wait for selectors (best-effort)
	6) wait fonts, 2 RAF
	7) wait settle (default 10s)
	8) if scope=header: restart tracking and hide everything except header, then stop
	9) extract coverage CSS, prepend global base (optional), minify, return
*/
app.get("/critical-css", async (req, res) => {
		const url = (req.query.url || "").toString().trim();
		const token = (req.query.token || "").toString();

		if (!url || !/^https?:\/\//i.test(url)) {
				return res.status(400).type("text/plain").send("Missing or invalid url");
		}

		if (API_TOKEN && token !== API_TOKEN) {
				return res.status(401).type("text/plain").send("Unauthorized");
		}

		const w = parseInt((req.query.w || "1366").toString(), 10);
		const h = parseInt((req.query.h || "768").toString(), 10);
		const width = Number.isFinite(w) ? Math.min(Math.max(w, 320), 2560) : 1366;
		const height = Number.isFinite(h) ? Math.min(Math.max(h, 480), 2000) : 768;

		const ua = (req.query.ua || "").toString().trim();

		const settleMs = Math.min(
				Math.max(parseInt((req.query.settle || "10000").toString(), 10) || 10000, 0),
				60000
		);

		const cssWaitMs = Math.min(
				Math.max(parseInt((req.query.csswait || "20000").toString(), 10) || 20000, 1000),
				60000
		);

		const waitRaw = (req.query.wait || "").toString().trim();
		const waitSelectors = waitRaw
				? waitRaw.split(",").map(s => s.trim()).filter(Boolean)
				: [];

		const scope = (req.query.scope || "").toString().trim().toLowerCase(); // "" | "header"
		const includeBase = ((req.query.base || "1").toString().trim() !== "0"); // default 1

		const cacheKey = sha256(`${url}|${width}|${height}|${ua}|${settleMs}|${cssWaitMs}|${waitSelectors.join("|")}|${scope}|${includeBase ? 1 : 0}`);

		const cached = cacheGet(cacheKey);
		if (cached) {
				res.setHeader("X-CriticalCSS-Cache", "HIT");
				return res.type("text/css").send(cached);
		}
		res.setHeader("X-CriticalCSS-Cache", "MISS");

		let browser;
		try {
				browser = await chromium.launch({ headless: true });

				const page = await browser.newPage({
						viewport: { width: width, height: height },
						userAgent: ua || undefined
				});

				await page.emulateMedia({ media: "screen" });
				page.setDefaultTimeout(60000);

				const cdp = await page.context().newCDPSession(page);
				await cdp.send("DOM.enable");
				await cdp.send("CSS.enable");

				// Start tracking early (safe even if we later restart for header-only)
				await cdp.send("CSS.startRuleUsageTracking");

				await page.goto(url, { waitUntil: "domcontentloaded" });

				// Best-effort: let the page settle naturally
				await page.waitForLoadState("load").catch(() => {});
				await page.waitForLoadState("networkidle").catch(() => {});

				// Wait for CSS to be effectively applied:
				// - all link[rel=stylesheet] have sheet
				// - none remain media="print" (your non-blocking apply pattern)
				await page.waitForFunction(() => {
						const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
						if (!links.length) return true;

						const allHaveSheet = links.every(l => !!l.sheet);
						const anyPrint = links.some(l => (l.media || "").toLowerCase() === "print");

						return allHaveSheet && !anyPrint;
				}, { timeout: cssWaitMs }).catch(() => {});

				// Wait for business selectors (best-effort)
				for (const sel of waitSelectors) {
						await page.waitForSelector(sel, { state: "attached", timeout: 12000 }).catch(() => {});
				}

				// Wait for header existence (for scope=header and generally helpful)
				await page.waitForSelector("header", { state: "attached", timeout: 12000 }).catch(() => {});

				// Fonts ready
				await page.evaluate(async () => {
						try {
								if (document.fonts && document.fonts.ready) {
										await document.fonts.ready;
								}
						} catch (e) {}
				}).catch(() => {});

				// 2 frames
				await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))).catch(() => {});

				// Your required stable time
				if (settleMs > 0) {
						await page.waitForTimeout(settleMs);
				}

				// If we only want header: restart tracking here, then hide everything except header
				let usage;
				if (scope === "header") {
						// Stop any previous tracking to avoid mixing windows
						await cdp.send("CSS.stopRuleUsageTracking").catch(() => {});

						// Start a clean tracking window focused on header
						await cdp.send("CSS.startRuleUsageTracking");

						// Hide everything except header (no scroll)
						await page.addStyleTag({
								content: `
										body > *:not(header) { display:none !important; }
										html, body { overflow:hidden !important; }
								`
						});

						// Force layout/paint
						await page.evaluate(() => {
								const h = document.querySelector("header");
								if (h) h.getBoundingClientRect();
								void document.body.offsetHeight;
						});

						// Small delay so CDP marks the rules as used
						await page.waitForTimeout(500);

						usage = await cdp.send("CSS.stopRuleUsageTracking");
				} else {
						// Global mode: stop tracking now
						usage = await cdp.send("CSS.stopRuleUsageTracking");
				}

				// Build ranges by stylesheetId
				const bySheet = new Map();
				for (const u of (usage.ruleUsage || [])) {
						if (!u.used) continue;
						if (!bySheet.has(u.styleSheetId)) bySheet.set(u.styleSheetId, []);
						bySheet.get(u.styleSheetId).push([u.startOffset, u.endOffset]);
				}

				// Fetch sheet text for those sheets
				const sheetIdToText = new Map();
				for (const sheetId of bySheet.keys()) {
						try {
								const t = await cdp.send("CSS.getStyleSheetText", { styleSheetId: sheetId });
								sheetIdToText.set(sheetId, (t && t.text) ? t.text : "");
						} catch (e) {
								sheetIdToText.set(sheetId, "");
						}
				}

				// Reconstruct coverage CSS
				let critical = "";
				for (const [sheetId, ranges] of bySheet.entries()) {
						const cssText = sheetIdToText.get(sheetId);
						if (!cssText) continue;

						for (const [a, b] of mergeRanges(ranges)) {
								critical += cssText.substring(a, b) + "\n";
						}
				}

				// Optionally prepend a minimal “global base”
				let globalBase = "";
				if (includeBase) {
						// Heuristic: use the largest 2 sheets among those touched
						const sheets = Array.from(sheetIdToText.values()).filter(Boolean);
						sheets.sort((a, b) => b.length - a.length);

						for (const cssText of sheets.slice(0, 2)) {
								globalBase += extractGlobalBase(cssText) + "\n";
						}
				}

				const outCss = minifyCss(`${globalBase}\n${critical}`);

				if (!outCss) {
						res.setHeader("Cache-Control", "no-store");
						return res.status(204).send("");
				}

				cacheSet(cacheKey, outCss);

				res.setHeader("Cache-Control", "public, max-age=600");
				return res.type("text/css").send(outCss);

		} catch (e) {
				const msg = (e && e.message) ? e.message : String(e);
				return res.status(500).type("text/plain").send(`Error: ${msg}`);
		} finally {
				if (browser) {
						try { await browser.close(); } catch {}
				}
		}
});

app.listen(PORT, "0.0.0.0", () => {
		console.log(`criticalcss-headless listening on port ${PORT}`);
});
