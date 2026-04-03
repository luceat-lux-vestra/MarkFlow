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
            output: {
                entryFileNames: `assets/[name].js`,
                chunkFileNames: `assets/[name].js`,
                assetFileNames: `assets/[name].[ext]`
            }
        }
    }
});