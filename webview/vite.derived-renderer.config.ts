import {defineConfig, type Plugin} from "vite";
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");
const normalizeModulePath = (value: string): string => value.replaceAll("\\", "/");
const expectedKatexRoot = normalizeModulePath(resolve(__dirname, "node_modules/katex"));

const singleKatexEngine: Plugin = {
    name: "markflow-single-katex-engine",
    generateBundle(_options, bundle) {
        const roots = new Set<string>();
        for (const output of Object.values(bundle)) {
            if (output.type !== "chunk") continue;
            for (const moduleId of Object.keys(output.modules)) {
                const normalizedId = normalizeModulePath(moduleId.replace(/\?.*$/, ""));
                const marker = "/node_modules/katex/";
                const markerIndex = normalizedId.indexOf(marker);
                if (markerIndex >= 0) {
                    roots.add(normalizedId.slice(0, markerIndex + marker.length - 1));
                }
            }
        }

        if (roots.size !== 1 || !roots.has(expectedKatexRoot)) {
            this.error(
                `MarkFlow requires one bundled KaTeX engine at ${expectedKatexRoot}; found ${[...roots].sort().join(", ") || "none"}`
            );
        }
    }
};

export default defineConfig({
    base: "./",
    resolve: {
        dedupe: ["katex"]
    },
    plugins: [singleKatexEngine],
    build: {
        outDir: webviewOutputDir,
        emptyOutDir: true,
        assetsInlineLimit: 0,
        modulePreload: {
            polyfill: false
        },
        rollupOptions: {
            input: {
                derivedRenderer: resolve(__dirname, "derived-renderer.html")
            },
            output: {
                entryFileNames: "derived-renderer-assets/[name].js",
                chunkFileNames: "derived-renderer-assets/[name]-[hash].js",
                assetFileNames: "derived-renderer-assets/[name]-[hash].[ext]"
            }
        }
    }
});
