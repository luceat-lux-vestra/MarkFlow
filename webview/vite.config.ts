import {defineConfig} from "vite";
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

// Recreate __dirname in ESM mode.
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");

export default defineConfig({
    base: "./",
    build: {
        outDir: webviewOutputDir,
        emptyOutDir: true,
        rollupOptions: {
            input: {
                main: resolve(__dirname, "index.html"),
                // Target #105/#81 per-surface runtime entry, parallel to the legacy Crepe `main`
                // entry above. It has no build/runtime dependency on `main`'s bootstrap/bridge.
                sourceNative: resolve(__dirname, "source-native.html"),
                // Diagnostic-only real-JCEF bridge page for #108. This page is never selected by
                // normal editor/runtime code and is exercised only when the dedicated probe mode runs.
                jcefEnvelopeProbe: resolve(__dirname, "jcef-envelope-probe.html")
            },
            output: {
                entryFileNames: `assets/[name].js`,
                chunkFileNames: `assets/[name].js`,
                assetFileNames: `assets/[name].[ext]`
            }
        }
    }
});
