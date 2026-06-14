import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import Icons from 'unplugin-icons/vite'
import IconsResolver from 'unplugin-icons/resolver'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
	resolve: {
		alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
	},
	plugins: [
		vue(),
		AutoImport({
			imports: ['vue', 'vue-router', 'pinia'],
			resolvers: [
				ElementPlusResolver(),
				IconsResolver({ prefix: 'Icon', enabledCollections: ['ep'] }),
			],
		}),
		Components({
			resolvers: [
				ElementPlusResolver(),
				IconsResolver({ enabledCollections: ['ep'] }),
			],
		}),
		Icons({ autoInstall: true }),
	],
	server: {
		proxy: {
			'/api': {
				target: 'http://159.75.169.224:1235',
				changeOrigin: true,
			},
		},
	},
})
