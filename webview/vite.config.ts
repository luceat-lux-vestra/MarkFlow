// import {defineConfig} from 'vite';
// import {resolve} from 'path';
//
// export default defineConfig({
//     // 상대 경로로 리소스를 불러오도록 설정 (JCEF 환경 필수)
//     base: './',
//     build: {
//         // 빌드 결과물을 Kotlin 플러그인의 resources 폴더로 바로 꽂아 넣습니다.
//         outDir: resolve(__dirname, '../src/main/resources/webview'),
//         emptyOutDir: true, // 빌드할 때마다 이전 파일 삭제
//         rollupOptions: {
//             output: {
//                 // 파일명에 해시가 붙으면 Kotlin에서 불러오기 귀찮으므로 고정합니다.
//                 entryFileNames: `assets/[name].js`,
//                 chunkFileNames: `assets/[name].js`,
//                 assetFileNames: `assets/[name].[ext]`
//             }
//         }
//     }
// });

import {defineConfig} from 'vite';
import {resolve, dirname} from 'path';
import {fileURLToPath} from 'url';

// 최신 ESM 환경에서는 __dirname을 이렇게 직접 만들어줍니다.
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

export default defineConfig({
    base: './',
    build: {
        outDir: resolve(__dirname, '../src/main/resources/webview'),
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