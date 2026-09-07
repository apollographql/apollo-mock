// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
	site: 'https://apollographql.github.io',
	base: '/apollo-mock',
	integrations: [
		starlight({
			title: 'Apollo Mock',
			editLink: {
				baseUrl: 'https://github.com/apollographql/apollo-mock/edit/main/docs/',
			},
			social: {
				github: 'https://github.com/apollographql/apollo-mock',
			},
			sidebar: [
				{ label: 'Quickstart', link: '/', },
				{ label: 'KDoc↗', link: 'https://apollographql.github.io/apollo-mock/kdoc/apollo-mock/index.html' },
				{ label: 'GitHub↗', link: 'https://github.com/apollographql/apollo-mock' },
			],
		}),
	],
});
