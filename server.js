import express from "express";
import { chromium } from "playwright";
import crypto from "crypto";

const app = express();

// --- Configuration ---
const PORT = parseInt(process.env.PORT || "3000", 10);

// Sécurité optionnelle: si CRITICALCSS_TOKEN est défini, il faut fournir ?token=...
const API_TOKEN = process.env.CRITICALCSS_TOKEN || "";

// Cache mémoire simple
// Clé: sha256(url|w|h|ua)
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

// Healthcheck (inclut SHA de build si injecté via Dockerfile)
app.get("/health", (req, res) => {
		res.status(200).json({ ok: true, sha: process.env.APP_GIT_SHA || "unknown" });
});

// GET /critical-css?url=...&w=1366&h=768&token=...
app.get("/critical-css", async (req, res) => {
		const url = (req.query.url || "").toString();
		const w = parseInt((req.query.w || "1366").toString(), 10);
		const h = parseInt((req.query.h || "768").toString(), 10);
		const token = (req.query.token || "").toString();

		if (!url || !/^https?:\/\//i.test(url)) {
				return res.status(400).type("text/plain").send("Missing or invalid url");
		}

		if (API_TOKEN && token !== API_TOKEN) {
				return res.status(401).type("text/plain").send("Unauthorized");
		}

		const width = Number.isFinite(w) ? Math.min(Math.max(w, 320), 2560) : 1366;
		const height = Number.isFinite(h) ? Math.min(Math.max(h, 480), 2000) : 768;

		const ua = (req.query.ua || "").toString();
		const cacheKey = sha256(`${url}|${width}|${height}|${ua}`);

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

				page.setDefaultTimeout(60000);

				// Charge la page
				await page.goto(url, { waitUntil: "networkidle" });

				// CDP session
				const cdp = await page.context().newCDPSession(page);

				// IMPORTANT: DOM doit être activé avant CSS.enable sur certains Chromiums
				await cdp.send("DOM.enable");
				console.log("CDP: DOM.enable OK");
				await cdp.send("CSS.enable");
				console.log("CDP: CSS.enable OK");

				// Démarre le tracking d'usage des règles
				await cdp.send("CSS.startRuleUsageTracking");

				// Laisse le temps aux scripts de finaliser le rendu
				await page.waitForTimeout(800);

				const usage = await cdp.send("CSS.stopRuleUsageTracking");
				const sheets = await cdp.send("CSS.getAllStyleSheets");

				// Récupère le texte de toutes les stylesheets
				const sheetIdToText = new Map();
				for (const s of sheets.headers || []) {
						try {
								const t = await cdp.send("CSS.getStyleSheetText", {
										styleSheetId: s.styleSheetId
								});
								sheetIdToText.set(s.styleSheetId, t.text || "");
						} catch {
								// ignore
						}
				}

				// Ranges utilisés
				const bySheet = new Map();
				for (const u of usage.ruleUsage || []) {
						if (!u.used) continue;
						if (!bySheet.has(u.styleSheetId)) bySheet.set(u.styleSheetId, []);
						bySheet.get(u.styleSheetId).push([u.startOffset, u.endOffset]);
				}

				// Reconstruit le CSS critique
				let critical = "";
				for (const [sheetId, ranges] of bySheet.entries()) {
						const cssText = sheetIdToText.get(sheetId);
						if (!cssText) continue;
						for (const [a, b] of mergeRanges(ranges)) {
								critical += cssText.substring(a, b) + "\n";
						}
				}

				critical = minifyCss(critical);
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
