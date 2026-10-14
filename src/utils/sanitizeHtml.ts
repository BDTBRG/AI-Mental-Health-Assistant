import DOMPurify from 'dompurify'

const ALLOWED_TAGS = [
	'a',
	'b',
	'blockquote',
	'br',
	'code',
	'del',
	'div',
	'em',
	'h1',
	'h2',
	'h3',
	'h4',
	'h5',
	'h6',
	'hr',
	'i',
	'li',
	'ol',
	'p',
	'pre',
	's',
	'span',
	'strong',
	'sub',
	'sup',
	'table',
	'tbody',
	'td',
	'th',
	'thead',
	'tr',
	'u',
	'ul',
]

const ALLOWED_ATTRIBUTES = ['class', 'href', 'style', 'title']
const SAFE_COLOR_NAMES = new Set([
	'black',
	'blue',
	'gray',
	'green',
	'grey',
	'orange',
	'purple',
	'red',
	'transparent',
	'white',
	'yellow',
])
const SAFE_FONT_FAMILIES = new Set([
	'arial',
	'tahoma',
	'verdana',
	'times new roman',
	'courier new',
	'microsoft yahei',
	'微软雅黑',
	'simsun',
	'宋体',
	'simhei',
	'黑体',
	'kaiti',
	'楷体',
	'serif',
	'sans-serif',
	'monospace',
])

function isSafeColor(value: string): boolean {
	const normalized = value.trim().toLowerCase()
	return (
		/^#[\da-f]{3,4}(?:[\da-f]{2}){0,2}$/i.test(normalized) ||
		SAFE_COLOR_NAMES.has(normalized) ||
		/^rgba?\(\s*[\d.%]+\s*,\s*[\d.%]+\s*,\s*[\d.%]+(?:\s*,\s*[\d.%]+)?\s*\)$/i.test(
			normalized,
		)
	)
}

function isSafeFontSize(value: string): boolean {
	const match = value.trim().match(/^(\d+(?:\.\d+)?)(px|pt|em|rem|%)$/i)
	if (!match) return false
	const size = Number(match[1])
	return Number.isFinite(size) && size >= 6 && size <= 72
}

function isSafeFontFamily(value: string): boolean {
	const families = value.match(/"[^"]*"|'[^']*'|[^,]+/g)
	return Boolean(
		families?.length &&
		families.every((family) =>
			SAFE_FONT_FAMILIES.has(family.trim().replace(/^['"]|['"]$/g, '').toLowerCase()),
		),
	)
}

function sanitizeInlineStyles(element: HTMLElement): void {
	const source = element.getAttribute('style')
	if (!source) return

	const parsed = document.createElement('span')
	parsed.setAttribute('style', source)
	const safeDeclarations: string[] = []

	for (let index = 0; index < parsed.style.length; index += 1) {
		const property = parsed.style.item(index).toLowerCase()
		const value = parsed.style.getPropertyValue(property).trim()
		let safe = false

		switch (property) {
			case 'color':
			case 'background-color':
				safe = isSafeColor(value)
				break
			case 'font-size':
				safe = isSafeFontSize(value)
				break
			case 'font-family':
				safe = isSafeFontFamily(value)
				break
			case 'text-align':
				safe = ['left', 'right', 'center', 'justify'].includes(value.toLowerCase())
				break
			case 'line-height':
				safe = /^(?:[1-3](?:\.\d+)?|\d+(?:\.\d+)?(?:px|%))$/i.test(value)
				break
		}

		if (safe) safeDeclarations.push(`${property}: ${value}`)
	}

	if (safeDeclarations.length) {
		element.setAttribute('style', safeDeclarations.join('; '))
	} else {
		element.removeAttribute('style')
	}
}

function isSafeLink(href: string): boolean {
	if (/[\u0000-\u001f\u007f]/.test(href)) return false

	try {
		const url = new URL(href.trim(), window.location.href)
		return ['http:', 'https:', 'mailto:', 'tel:'].includes(url.protocol)
	} catch {
		return false
	}
}

export function sanitizeRichHtml(html: string | null | undefined): string {
	if (!html) return ''

	const fragment = DOMPurify.sanitize(html, {
		ALLOWED_TAGS,
		ALLOWED_ATTR: ALLOWED_ATTRIBUTES,
		ALLOW_DATA_ATTR: false,
		FORBID_ATTR: ['id', 'name', 'src', 'srcset', 'target'],
		FORBID_TAGS: ['embed', 'form', 'iframe', 'input', 'math', 'object', 'script', 'style', 'svg', 'textarea'],
		RETURN_DOM_FRAGMENT: true,
		SANITIZE_NAMED_PROPS: true,
	}) as DocumentFragment

	const container = document.createElement('div')
	container.append(fragment)

	container.querySelectorAll<HTMLElement>('[style]').forEach(sanitizeInlineStyles)
	container.querySelectorAll<HTMLAnchorElement>('a[href]').forEach((link) => {
		const href = link.getAttribute('href')
		if (!href || !isSafeLink(href)) link.removeAttribute('href')
	})

	return container.innerHTML
}
