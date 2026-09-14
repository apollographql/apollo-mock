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
				{ label: 'Welcome', link: '/', },
				{ label: 'CLI', link: '/cli/' },
				{
					label: 'Kotlin',
					items: [
						{ label: 'Gradle plugin', link: '/gradle-plugin/' },
						{ label: 'Building an executable schema', link: '/executable-schema/' },
						{ label: 'Serving mock data', link: '/server/' },
						{ label: 'Apollo Kotlin integration', link: '/network-transport/' },
					],
				},
				{ label: 'KDoc↗', link: 'https://apollographql.github.io/apollo-mock/kdoc/apollo-mock/index.html' },
				{ label: 'GitHub↗', link: 'https://github.com/apollographql/apollo-mock' },
			],
		}),
	],
});
