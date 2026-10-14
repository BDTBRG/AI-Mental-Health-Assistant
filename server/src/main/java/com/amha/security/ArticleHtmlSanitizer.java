package com.amha.security;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/** Sanitizes rich-text article HTML before it is stored or returned to a browser. */
@Component
public class ArticleHtmlSanitizer {
    private static final PolicyFactory ARTICLE_POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "a", "blockquote", "br", "code", "del", "div", "em", "h1", "h2", "h3",
                    "h4", "h5", "h6", "hr", "i", "li", "ol", "p", "pre", "s", "span",
                    "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul")
            .allowWithoutAttributes("a", "span")
            .allowAttributes("href", "title").onElements("a")
            .allowUrlProtocols("http", "https", "mailto", "tel")
            .requireRelNofollowOnLinks()
            .allowStyling()
            .toFactory();

    public String sanitize(String html) {
        return ARTICLE_POLICY.sanitize(html == null ? "" : html);
    }
}
