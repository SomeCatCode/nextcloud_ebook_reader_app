import { defineConfig } from 'vite'
import { resolve } from 'node:path'

// Output goes straight into the Android assets (loaded via WebViewAssetLoader from
// https://appassets.androidplatform.net/reader/index.html). `base: './'` keeps all URLs relative.
export default defineConfig({
	base: './',
	resolve: {
		// reader-core lives in the submodule (outside of this package): resolve its bare imports here
		alias: { 'libarchive.js': resolve(import.meta.dirname, 'node_modules/libarchive.js') },
	},
	build: {
		outDir: resolve(import.meta.dirname, '../app/src/main/assets/reader'),
		emptyOutDir: true,
		target: 'es2022',
		assetsInlineLimit: 0,
		sourcemap: false,
	},
	server: {
		fs: { allow: [resolve(import.meta.dirname, '..')] },
	},
})
