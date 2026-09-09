import {defineConfig} from "vite";
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");

export default defineConfig({
    base: "./",
    build: {
        outDir: webviewOutputDir,
        emptyOutDir: false,
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
