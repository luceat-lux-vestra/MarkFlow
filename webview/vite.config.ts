import {defineConfig} from "vite";
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

// Recreate __dirname in ESM mode.
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");

/** Legacy Crepe build only. The source-native target is built by vite.source-native.config.ts. */
export default defineConfig({
    base: "./",
    build: {
        outDir: webviewOutputDir,
        emptyOutDir: true,
        rollupOptions: {
            input: {
                main: resolve(__dirname, "index.html")
            },
            output: {
                entryFileNames: `assets/[name].js`,
                chunkFileNames: `assets/[name].js`,
                assetFileNames: `assets/[name].[ext]`
            }
        }
    }
});
