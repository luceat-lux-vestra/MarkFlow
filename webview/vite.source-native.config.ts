import {defineConfig} from "vite";
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");

/**
 * Build the target source-native realm separately from the legacy Crepe realm.
 *
 * A dedicated output namespace is a trust boundary: the production JCEF request policy can grant
 * the target only its own executable/static graph without implicitly authorizing legacy assets.
 */
export default defineConfig({
    base: "./",
    build: {
        outDir: webviewOutputDir,
        emptyOutDir: false,
        assetsInlineLimit: 0,
        modulePreload: {
            // Chromium/JCEF supports modulepreload natively. Do not inject Vite's fetch()-based
            // compatibility polyfill into a realm whose browser network surface is intentionally nil.
            polyfill: false
        },
        rollupOptions: {
            input: {
                sourceNative: resolve(__dirname, "source-native.html")
            },
            output: {
                entryFileNames: "source-native-assets/[name].js",
                chunkFileNames: "source-native-assets/[name].js",
                assetFileNames: "source-native-assets/[name].[ext]"
            }
        }
    }
});
