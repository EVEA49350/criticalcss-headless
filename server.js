import express from "express";
import { chromium } from "playwright";
import crypto from "crypto";

const app = express();

// --- Configuration ---
const PORT = parseInt(process.env.PORT || "3000", 10);

// Sécurité optionnelle: si CRITICALCSS_TOKEN est défini, il faut fournir ?token=...
const API_TOKEN = process.env.CRITICALCSS_TOKEN || "";

// Cache mémoire simple
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
				if (!out.length || r[0] > out[out.length - 1][1]) out.push(r);
				else out[out.length - 1][1] = Math.max(out[out.length - 1][1], r[1]);
		}
		return out;
}

// Healthcheck
app.get("/health", (req, res) => {
		res.status(200).json({ ok: true, sha: process.env.APP_GIT_SHA || "unknown" });
});

/**
 * GET /critical-css
 * Paramètres:
 *   url      (obligatoire)  : page cible
 *   w,h      (optionnel)    : viewport
 *   ua       (optionnel)    : user-agent
 *   token    (optionnel)    : sécurité
 *   wait     (optionnel)    : sélecteurs CSS à attendre (CSV), ex: "header,#header,.elementor"
 *   settle   (optionnel)    : ms additionnels après stabilisation (défaut 10000)
 *   csswait  (optionnel)    : ms max pour attendre CSS appliqué (défaut 15000)
 *
 * Stratégie:
 * - startRuleUsageTracking AVANT goto
 * - goto domcontentloaded (plus tôt)
 * - attendre load/networkidle (best-effort)
 * - attendre que les stylesheets soient chargés ET appliqués (rel=stylesheet + media print hack)
 * - attendre selectors business (header/hero/etc)
 * - attendre fonts + 2 RAF
 * - attendre settle (par défaut 10s)
 * - stopRuleUsageTracking et extraction
 */
app.get("/critical-css", async (req, res) => {
		const url = (req.query.url || "").toString();
		const w = parseInt((req.query.w || "1366").toString(), 10);
		const h = parseInt((req.query.h || "768").toString(), 10);
		const token = (req.query.token || "").toString();
		const ua = (req.query.ua || "").toString();

		const waitRaw = (req.query.wait || "").toString().trim();
		const waitSelectors = waitRaw
				? waitRaw.split(",").map(s => s.trim()).filter(Boolean)
				: [];

		// settle par défaut = 10 secondes (votre demande)
		const settleMs = Math.min(Math.max(parseInt((req.query.settle || "10000").toString(), 10) || 10000, 0), 60000);

		// temps max pour attendre CSS "appliqué"
		const cssWaitMs = Math.min(Math.max(parseInt((req.query.csswait || "15000").toString(), 10) || 15000, 1000), 60000);

		if (!url || !/^https?:\/\//i.test(url)) {
				return res.status(400).type("text/plain").send("Missing or invalid url");
		}

		if (API_TOKEN && token !== API_TOKEN) {
				return res.status(401).type("text/plain").send("Unauthorized");
		}

		const width = Number.isFinite(w) ? Math.min(Math.max(w, 320), 2560) : 1366;
		const height = Number.isFinite(h) ? Math.min(Math.max(h, 480), 2000) : 768;

		const cacheKey = sha256(`${url}|${width}|${height}|${ua}|${waitSelectors.join("|")}|${settleMs}|${cssWaitMs}`);

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

				// IMPORTANT: tracking AVANT navigation
				await cdp.send("CSS.startRuleUsageTracking");

				await page.goto(url, { waitUntil: "domcontentloaded" });

				// Best-effort : load puis networkidle
				await page.waitForLoadState("load").catch(() => {});
				await page.waitForLoadState("networkidle").catch(() => {});

				// 1) Attendre que les CSS soient chargés ET appliqués
				// - tous les <link rel="stylesheet"> doivent avoir un .sheet
				// - et plus aucun rel=stylesheet media="print" (votre hack preload/apply)
				await page.waitForFunction(() => {
						const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
						// stylesheets chargés (sheet non null)
						const allHaveSheet = links.every(l => !!l.sheet);

						// hack media=print -> all
						// tant qu'un stylesheet est encore en media=print, il n'est pas appliqué.
						const anyPrint = links.some(l => (l.media || "").toLowerCase() === "print");

						return allHaveSheet && !anyPrint;
				}, { timeout: cssWaitMs }).catch(() => {});

				// 2) Attentes explicites (header/hero/CE...) si fourni
				for (const sel of waitSelectors) {
						await page.waitForSelector(sel, { state: "attached", timeout: 12000 }).catch(() => {});
				}

				// 3) Attendre polices
				await page.evaluate(async () => {
						try {
								if (document.fonts && document.fonts.ready) {
										await document.fonts.ready;
								}
						} catch (e) {}
				}).catch(() => {});

				// 4) 2 frames pour stabiliser layout
				await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))).catch(() => {});

				// 5) Votre buffer demandé : 10s par défaut
				if (settleMs > 0) {
						await page.waitForTimeout(settleMs);
				}

				const usage = await cdp.send("CSS.stopRuleUsageTracking");

				const bySheet = new Map();
				for (const u of usage.ruleUsage || []) {
						if (!u.used) continue;
						if (!bySheet.has(u.styleSheetId)) bySheet.set(u.styleSheetId, []);
						bySheet.get(u.styleSheetId).push([u.startOffset, u.endOffset]);
				}

				const sheetIdToText = new Map();
				for (const sheetId of bySheet.keys()) {
						try {
								const t = await cdp.send("CSS.getStyleSheetText", { styleSheetId: sheetId });
								sheetIdToText.set(sheetId, (t && t.text) ? t.text : "");
						} catch (e) {
								sheetIdToText.set(sheetId, "");
						}
				}

				let critical = "";
				for (const [sheetId, ranges] of bySheet.entries()) {
						const cssText = sheetIdToText.get(sheetId);
						if (!cssText) continue;
						for (const [a, b] of mergeRanges(ranges)) {
								critical += cssText.substring(a, b) + "\n";
						}
				}

				critical = minifyCss(critical);

				if (!critical) {
						res.setHeader("Cache-Control", "no-store");
						return res.status(204).send("");
				}

				cacheSet(cacheKey, critical);

				res.setHeader("Cache-Control", "public, max-age=600");
				return res.type("text/css").send(critical);

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
